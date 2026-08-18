package re.keti.a3004bridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The transports. Each one runs on its own thread and hands finished results to
 * a callback; nothing here touches views.
 *
 * The reason this app exists rather than just a browser tab: an app can speak
 * UDP. The web dashboard has to send one HTTP request per command, and that path
 * already ran into a browser's parallel-connection cap at 20 Hz. Here the
 * control channel is a datagram, so there is no connection to run out of.
 */

// ---------------------------------------------------------------- MJPEG

/**
 * Reads multipart/x-mixed-replace and decodes each part.
 *
 * Deliberately hand-rolled rather than using a library: the parts are JPEGs
 * delimited by their own SOI/EOI markers, so scanning for those is more robust
 * than trusting the boundary string, which some streamers get subtly wrong.
 */
class MjpegReader(
    private val url: String,
    private val onFrame: (Bitmap) -> Unit,
    private val onState: (String) -> Unit,
) : Thread("mjpeg") {

    private val stop = AtomicBoolean(false)
    fun halt() { stop.set(true); interrupt() }

    override fun run() {
        while (!stop.get()) {
            var conn: HttpURLConnection? = null
            try {
                onState("connecting")
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 5000
                    doInput = true
                }
                val ins = BufferedInputStream(conn.inputStream, 1 shl 16)
                onState("live")
                readParts(ins)
            } catch (e: Exception) {
                if (!stop.get()) onState("no stream")
            } finally {
                runCatching { conn?.disconnect() }
            }
            if (!stop.get()) sleepQuietly(700)
        }
    }

    /*
     * Two decode targets, used alternately.
     *
     * A fresh 1280x720 ARGB bitmap per frame is 3.7 MB: 74 MB/s of allocation
     * and collection at 20 fps, 220 MB/s at 60. inBitmap reuses the buffer
     * instead, but the view still holds the frame it is drawing, so decoding
     * into that one would tear the image. Hence two: decode into the buffer the
     * UI is not showing.
     */
    private val slot = arrayOfNulls<Bitmap>(2)
    private var next = 0

    private fun decode(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = slot[next]
        }
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        } catch (e: IllegalArgumentException) {
            // The candidate was the wrong size - the stream's resolution
            // changed. Drop it and let the next attempt allocate.
            slot[next] = null
            o.inBitmap = null
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            }.getOrNull()
        }
        if (bmp != null) {
            slot[next] = bmp
            next = 1 - next
        }
        return bmp
    }

    /*
     * The newest frame, waiting to be decoded, and nothing older.
     *
     * Reading and decoding used to be the same thread: parse a frame, decode it,
     * hand it to the UI, then read again. That works only while decoding keeps up.
     * A 1280x720 JPEG at 20 fps is 20 decodes a second on a tablet that is also
     * drawing a map and a lidar plot, and the moment decoding falls behind the
     * socket stops being drained, the receive window closes, and the router's
     * frames queue in the network instead. Latency then grows without a limit -
     * the picture is late by however long the app has been behind, not by however
     * long the link takes - and it never recovers, because there is no mechanism
     * that discards the backlog.
     *
     * So the reader never decodes. It drains the socket at full speed and leaves
     * the latest complete frame here; the decoder takes whatever is here now.
     * If decoding is slower than arrival the frames in between are dropped
     * instead of queued, which is the correct trade for a camera somebody is
     * driving by: a frame that is two revolutions old has no value.
     */
    private val pending = AtomicReference<ByteArray?>(null)
    private var arrived = 0L
    private var dropped = 0L
    private var decoded = 0L
    private var decodeNanos = 0L
    private var reported = 0L

    private inner class Decoder : Thread("mjpeg-decode") {
        override fun run() {
            while (!stop.get()) {
                val bytes = pending.getAndSet(null)
                if (bytes == null) {
                    // Nothing new. Park briefly rather than spin: at 20 fps the
                    // next frame is at most 50 ms away.
                    try { sleep(3) } catch (e: InterruptedException) { return }
                    continue
                }
                val t0 = System.nanoTime()
                val bmp = decode(bytes)
                decodeNanos += System.nanoTime() - t0
                decoded++
                if (bmp != null) onFrame(bmp)
                report()
            }
        }
    }

    /** Say how the pipeline is keeping up, so falling behind is visible. */
    private fun report() {
        val now = System.currentTimeMillis()
        if (reported == 0L) { reported = now; return }
        if (now - reported < 3000) return
        val secs = (now - reported) / 1000.0
        val ms = if (decoded > 0) decodeNanos / decoded / 1_000_000.0 else 0.0
        Log.i("mjpeg",
            "%.1f fps in, %.1f fps drawn, %d dropped as stale, decode %.1f ms"
                .format(arrived / secs, decoded / secs, dropped, ms))
        arrived = 0; decoded = 0; dropped = 0; decodeNanos = 0
        reported = now
    }

    private fun readParts(ins: InputStream) {
        val buf = ByteArray(1 shl 15)
        val acc = ByteArrayOutputStream(1 shl 18)
        var inFrame = false
        var prev = -1
        val dec = Decoder().also { it.start() }

        try {
            while (!stop.get()) {
                val n = ins.read(buf)
                if (n < 0) return
                var i = 0
                while (i < n) {
                    if (!inFrame) {
                        // Find the SOI. Nothing is copied while searching, so the
                        // headers between parts cost only the scan.
                        var j = i
                        var at = -1
                        while (j < n) {
                            val b = buf[j].toInt() and 0xff
                            if (prev == 0xFF && b == 0xD8) { prev = b; at = j; j++; break }
                            prev = b; j++
                        }
                        if (at < 0) { i = n; continue }
                        acc.reset()
                        acc.write(0xFF); acc.write(0xD8)
                        inFrame = true
                        i = j
                    } else {
                        // Find the EOI, then copy the run in one call. Copying
                        // byte by byte through ByteArrayOutputStream.write(int)
                        // meant 150 000 calls per frame, three million a second,
                        // to move bytes that were already contiguous.
                        var j = i
                        var end = -1
                        while (j < n) {
                            val b = buf[j].toInt() and 0xff
                            if (prev == 0xFF && b == 0xD9) { prev = b; end = j; j++; break }
                            prev = b; j++
                        }
                        val upto = if (end >= 0) end + 1 else n
                        acc.write(buf, i, upto - i)
                        i = upto
                        if (end >= 0) {
                            inFrame = false
                            arrived++
                            // Replacing an undecoded frame is a drop, and it is
                            // counted: a number that is normally zero and rises
                            // under load is how this stays honest about what the
                            // person is actually seeing.
                            if (pending.getAndSet(acc.toByteArray()) != null) dropped++
                        } else if (acc.size() > 4 shl 20) {
                            // Runaway: no EOI in 4 MB means the stream is not what
                            // we think it is. Resynchronise rather than grow.
                            inFrame = false
                            acc.reset()
                        }
                    }
                }
            }
        } finally {
            dec.interrupt()
        }
    }
}

// ---------------------------------------------------------------- PCM audio

/**
 * Raw S16 PCM over HTTP into an AudioTrack.
 *
 * The point of no codec is latency, so the buffer is deliberately small - two
 * ALSA periods' worth. AudioTrack underruns produce a click; a large buffer
 * would hide those at the cost of the thing we came for.
 */
class PcmPlayer(
    private val url: String,
    private val onLevel: (Float) -> Unit,
    private val onState: (String) -> Unit,
) : Thread("pcm") {

    private val stop = AtomicBoolean(false)
    private var track: AudioTrack? = null
    fun halt() { stop.set(true); interrupt(); runCatching { track?.stop() } }

    override fun run() {
        /*
         * Wait for the microphone rather than giving up on it.
         *
         * This asked once and returned on failure. With the panel defaulting to on,
         * the thread started before the WiFi association finished, got nothing, and
         * died - so the card sat there reading "no mic" with a flat trace while the
         * router was streaming perfectly. A reader that starts before its link is
         * up is normal; one that never looks again is a bug.
         */
        var rate = 16000
        var channels = 1
        while (!stop.get()) {
            try {
                val info = JSONObject(httpText("$url/info"))
                rate = info.optInt("rate", 16000)
                channels = info.optInt("channels", 1)
                break
            } catch (e: Exception) {
                onState("waiting for mic")
                try { sleep(1500) } catch (i: InterruptedException) { return }
            }
        }
        if (stop.get()) return

        val cfg = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO
                  else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(
            rate, cfg, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, rate * channels * 2 / 25)   // ~40 ms

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(cfg)
                    .build())
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        // Mono is unremarkable and is left unsaid: the heading row holds the card
        // title, this state and the toggle, and "16 kHz mono" was wide enough to
        // close the gap between MICROPHONE and itself. Stereo would be a surprise
        // worth naming, so that case still says so.
        onState("${rate / 1000} kHz${if (channels == 1) "" else " stereo"}")

        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$url/pcm").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 5000
            }
            val ins = BufferedInputStream(conn.inputStream, 1 shl 14)
            val buf = ByteArray(1024 * channels)
            while (!stop.get()) {
                val n = ins.read(buf)
                if (n <= 0) break
                t.write(buf, 0, n)
                onLevel(peak(buf, n))
            }
        } catch (e: Exception) {
            if (!stop.get()) onState("mic dropped")
        } finally {
            runCatching { conn?.disconnect() }
            runCatching { t.stop(); t.release() }
        }
    }

    private fun peak(b: ByteArray, n: Int): Float {
        var p = 0
        var i = 0
        while (i + 1 < n) {
            val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toInt()
            val a = if (v < 0) -v else v
            if (a > p) p = a
            i += 2
        }
        return p / 32768f
    }
}

// ---------------------------------------------------------------- ring over UDP

/** Decoded lidar range ring. Ranges are centimetres; -1 means no return. */
class Ring(val sectors: Int) {
    val cm = IntArray(sectors) { -1 }

    /* Reflectivity of the nearest return per sector, 0..255.
     *
     * The format has carried this since the beginning and the reader threw it
     * away - the length check even accounted for the bytes. It is the difference
     * between a plot of where things are and a plot that also shows what they
     * are: retroreflective tape, painted walls and dark carpet are wildly
     * different here at the same distance. */
    val refl = IntArray(sectors)

    var frameId = 0
    var alarm = false

    /** Farthest return, in centimetres, or 0 if nothing came back. */
    val maxCm: Int get() = cm.max().coerceAtLeast(0)
}

/**
 * The binary ring straight off ouster-edge, rather than the JSON the web page
 * polls. Same data, a tenth of the bytes, and no parse cost worth measuring.
 */
class RingReader(
    private val port: Int,
    private val onRing: (Ring) -> Unit,
    private val onState: (String) -> Unit,
) : Thread("ring") {

    private val stop = AtomicBoolean(false)
    private var sock: DatagramSocket? = null
    fun halt() { stop.set(true); runCatching { sock?.close() } }

    override fun run() {
        try {
            val s = DatagramSocket(port)
            s.soTimeout = 1500
            sock = s
            val buf = ByteArray(16384)
            var seen = false
            while (!stop.get()) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (e: Exception) {
                    if (!stop.get() && seen) onState("no lidar")
                    continue
                }
                decode(buf, p.length)?.let {
                    seen = true
                    onRing(it)
                }
            }
        } catch (e: Exception) {
            onState("ring port busy")
        }
    }

    private fun decode(b: ByteArray, len: Int): Ring? {
        if (len < 20) return null
        if (b[0] != 'O'.code.toByte() || b[1] != 'S'.code.toByte() ||
            b[2] != 'E'.code.toByte() || b[3] != 'D'.code.toByte()) return null
        if (b[4].toInt() != 1) return null

        val sectors = u16(b, 6)
        if (sectors < 1 || sectors > 4096 || len != 20 + 3 * sectors) return null

        val r = Ring(sectors)
        r.frameId = u16(b, 8)
        r.alarm = b[10].toInt() != 0
        for (i in 0 until sectors) {
            val v = u16(b, 20 + i * 2)
            r.cm[i] = if (v == 0xFFFF) -1 else v
            r.refl[i] = b[20 + sectors * 2 + i].toInt() and 0xFF
        }
        return r
    }

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
}

// ---------------------------------------------------------------- teleop

/**
 * Control intent as UDP, at a fixed cadence, armed or not.
 *
 * Keeping the cadence when disarmed is the same rule the daemon follows: the
 * receiver cannot tell "no packet" from "packet lost", so disarmed has to arrive
 * as data. Stopping the thread sends a final disarmed frame for the same reason.
 */
class TeleopSender(
    private val host: String,
    private val port: Int,
    /* Mutable and volatile: the activity drops this to a keepalive rate when
       disarmed, so it has to be read on every pass rather than captured once. */
    @Volatile var hz: Int = 50,
    /* Optional, so the existing call site needs no change: reports the first
       failure of a run and the recovery, not every dropped frame. */
    private val onState: (String) -> Unit = {},
) : Thread("teleop") {

    private val stop = AtomicBoolean(false)
    /* Axis map, shared with doc/TELEOP.md and the reference receiver:
       a0 strafe (+right), a1 forward (+forward), a2 yaw (+anticlockwise). */
    @Volatile var armed = false
    @Volatile var x = 0f
    @Volatile var y = 0f
    @Volatile var r = 0f
    @Volatile var sent = 0L
    @Volatile var failures = 0
    private var seq = 0

    fun halt() { stop.set(true); interrupt() }

    override fun run() {
        val sock = DatagramSocket()
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return
        try {
            while (!stop.get()) {
                //
                // A send that fails must not take the app with it.
                //
                // This threw IOException straight out of run(), so the thread
                // died and Android killed the process. ENETUNREACH is not an
                // exceptional condition here: it happens every time the app
                // binds itself to the router's access point, because for a
                // moment there is no route - and it happens again whenever the
                // tablet walks out of range, which is the exact situation the
                // deadman exists for. An operator's screen dying at the moment
                // the link goes is the worst possible behaviour.
                //
                // Losing frames is fine and already handled: agx-cmd ramps down
                // and stops on its own when they stop arriving.
                try {
                    send(sock, addr, armed)
                    if (failures > 0) { failures = 0; onState("") }
                } catch (e: Exception) {
                    failures++
                    if (failures == 1) onState("send failed: ${e.message ?: "unknown"}")
                }
                sleepQuietly((1000L / hz.coerceAtLeast(1)).coerceAtLeast(5L))
            }
        } finally {
            runCatching { send(sock, addr, false) }   // final disarmed frame
            sock.close()
        }
    }

    private fun send(sock: DatagramSocket, addr: InetAddress, arm: Boolean) {
        val p = ByteArray(24)
        p[0] = 'T'.code.toByte(); p[1] = 'C'.code.toByte()
        p[2] = 'M'.code.toByte(); p[3] = 'D'.code.toByte()
        p[4] = 1
        p[5] = if (arm) 1 else 0
        seq++
        p[8] = (seq and 0xff).toByte()
        p[9] = ((seq shr 8) and 0xff).toByte()
        p[10] = ((seq shr 16) and 0xff).toByte()
        p[11] = ((seq shr 24) and 0xff).toByte()
        // Axes are integers in 1/10000 so nothing here depends on float
        // formatting agreeing across three languages.
        putAxis(p, 12, if (arm) x else 0f)
        putAxis(p, 14, if (arm) y else 0f)
        putAxis(p, 16, if (arm) r else 0f)
        sock.send(DatagramPacket(p, p.size, addr, port))
        sent++
    }

    private fun putAxis(p: ByteArray, off: Int, v: Float) {
        val q = (v.coerceIn(-1f, 1f) * 10000f).toInt()
        p[off] = (q and 0xff).toByte()
        p[off + 1] = ((q shr 8) and 0xff).toByte()
    }
}

// ---------------------------------------------------------------- status poll

/** Polls the small JSON status files for anything that is not a live stream. */
class StatusPoller(
    private val base: String,
    private val everyMs: Long,
    /**
     * How long a status file may go unchanged before it is treated as gone.
     *
     * Several polls' worth, so an unlucky pair of identical fetches cannot
     * declare a live daemon dead.
     */
    private val staleMs: Long = 3000,
    private val onStatus: (String, JSONObject?) -> Unit,
) : Thread("status") {

    private val stop = AtomicBoolean(false)
    fun halt() { stop.set(true); interrupt() }

    /*
     * A status file outlives the daemon that wrote it.
     *
     * The status files under /var/run are written by each daemon on the router,
     * and none of them is removed when its daemon stops.
     *
     * (Written without a wildcard on purpose: Kotlin nests block comments, so the
     * two characters of a path like that open a second comment inside this one and
     * the closing marker then only shuts the inner half. It cost a build.) A fetch of a stopped teleop's file therefore succeeds and
     * parses, so every panel that reads it kept showing the last state that
     * daemon ever published, indefinitely, as if it were current. teleop,
     * navigate, agx-cmd and can-bridge are all off by default, so this is the
     * ordinary case rather than an edge one - and the panel that lies about it is
     * the panel next to the button that arms a vehicle.
     *
     * What separates a live daemon from a stopped one is that a live one rewrites
     * its file constantly: measured on the router, six polls over 3.6 s return six
     * different bodies for every daemon here, even with nothing happening, because
     * each carries a counter or an age. So a body that has not changed in a few
     * seconds is a daemon that is gone, and gone reads the same as missing.
     */
    private val lastText = HashMap<String, String>()
    private val lastChange = HashMap<String, Long>()

    override fun run() {
        val names = Wire.STATUS_NAMES
        while (!stop.get()) {
            for (n in names) {
                if (stop.get()) return
                val text = runCatching { httpText("$base/$n.json") }.getOrNull()
                val now = System.currentTimeMillis()
                if (text == null) {
                    lastText.remove(n)
                    lastChange.remove(n)
                    onStatus(n, null)
                    continue
                }
                if (lastText[n] != text) {
                    lastText[n] = text
                    lastChange[n] = now
                }
                val fresh = now - (lastChange[n] ?: now) < staleMs
                onStatus(n, if (fresh) runCatching { JSONObject(text) }.getOrNull()
                            else null)
            }
            sleepQuietly(everyMs)
        }
    }
}

// ---------------------------------------------------------------- helpers

internal fun httpText(url: String): String {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 1500
        readTimeout = 1500
    }
    try {
        return c.inputStream.readBytes().toString(Charsets.UTF_8)
    } finally {
        c.disconnect()
    }
}

internal fun sleepQuietly(ms: Long) {
    try { Thread.sleep(ms) } catch (e: InterruptedException) { /* expected */ }
}
