package re.keti.a3004bridge

import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The map the router built, and the destination sent back to it.
 *
 * The router does the mapping and the driving; this end draws the result and
 * says where to go. That split is deliberate and is the reason the vehicle
 * keeps going when this tablet is put in a pocket - see doc/COMPUTE.md in the
 * firmware tree. Nothing here is in a control loop, so a slow or dropped fetch
 * costs a stale picture and nothing else.
 */

/** One fetch of `/sensors/map.s2mp`: cells, the transform, and the pose. */
class MapFrame(
    val level: Int,
    val w: Int,
    val h: Int,
    val resCm: Int,
    val originXCm: Int,
    val originYCm: Int,
    val poseXCm: Int,
    val poseYCm: Int,
    val poseA: Int,
    val cells: ByteArray,
) {
    /** Occupancy at a cell, 0 free .. 128 unknown .. 255 occupied. */
    fun at(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= w || y >= h) S2_UNKNOWN
        else cells[y * w + x].toInt() and 0xFF

    fun cellXOf(xCm: Int): Int = Math.floorDiv(xCm - originXCm, resCm)
    fun cellYOf(yCm: Int): Int = Math.floorDiv(yCm - originYCm, resCm)
    fun xCmOf(cx: Int): Int = originXCm + cx * resCm + resCm / 2
    fun yCmOf(cy: Int): Int = originYCm + cy * resCm + resCm / 2

    /** Heading in degrees, from the router's 1/4096-of-a-turn integer. */
    val headingDeg: Float get() = poseA * 360f / 4096f

    companion object {
        const val S2_UNKNOWN = 128

        /**
         * Parse the format slam2d writes. Returns null rather than throwing on
         * anything unexpected: this runs on a polling thread and a truncated
         * body should show as a stale map, not a crash.
         */
        fun parse(b: ByteArray): MapFrame? {
            if (b.size < 32) return null
            if (b[0] != 'S'.code.toByte() || b[1] != '2'.code.toByte() ||
                b[2] != 'M'.code.toByte() || b[3] != 'P'.code.toByte()) return null
            if (b[4].toInt() != 1) return null
            fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            fun i32(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                    ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
            val w = u16(6); val h = u16(8)
            if (w <= 0 || h <= 0 || b.size < 32 + w * h) return null
            return MapFrame(
                level = b[5].toInt(), w = w, h = h, resCm = u16(10),
                originXCm = i32(12), originYCm = i32(16),
                poseXCm = i32(20), poseYCm = i32(24), poseA = i32(28),
                cells = b.copyOfRange(32, 32 + w * h))
        }
    }
}

/**
 * Poll the map.
 *
 * Slower than the sensor by design. The map is 40 kB at level 2 and changes
 * slowly; pulling it at the ring's 10 Hz would spend WiFi on redrawing the same
 * room. Two or three times a second looks live to a person and costs a tenth as
 * much.
 */
class MapReader(
    private val base: String,
    private val periodMs: Long = 400,
    private val onMap: (MapFrame) -> Unit,
    private val onState: (String) -> Unit,
) : Thread("map") {
    private val stop = AtomicBoolean(false)

    fun halt() { stop.set(true); interrupt() }

    override fun run() {
        var said = ""
        while (!stop.get()) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("$base/map.s2mp").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 2500
                    useCaches = false
                }
                if (conn.responseCode == 200) {
                    val len = conn.contentLength
                    val body = if (len in 1..(4 shl 20)) {
                        ByteArray(len).also { DataInputStream(conn.inputStream).readFully(it) }
                    } else {
                        conn.inputStream.readBytes()
                    }
                    val m = MapFrame.parse(body)
                    if (m != null) {
                        onMap(m)
                        if (said != "ok") { said = "ok"; onState("") }
                    } else if (said != "bad") {
                        said = "bad"; onState("지도 형식이 아님")
                    }
                } else if (said != "none") {
                    // 404 is the ordinary case when slam2d is not enabled, so
                    // it is worth saying which of the two it is.
                    said = "none"
                    onState(if (conn.responseCode == 404) "지도 없음" else "HTTP ${conn.responseCode}")
                }
            } catch (e: Exception) {
                if (said != "err") { said = "err"; onState("연결 없음") }
            } finally {
                runCatching { conn?.disconnect() }
            }
            try { sleep(periodMs) } catch (e: InterruptedException) { return }
        }
    }
}

/**
 * Send a destination, or ask it to stop.
 *
 * A single datagram, because that is what `navigate` listens for, and because
 * the reply that matters is not an acknowledgement but the state in
 * `navigate.json` a moment later - which is what the screen shows. If the
 * datagram is lost the state simply does not change, and tapping again is the
 * obvious and correct response.
 */
class GoalSender(private val host: String, private val port: Int = 7604) {
    fun goal(xCm: Int, yCm: Int) = send("GOAL $xCm $yCm")
    fun stop() = send("STOP")

    private fun send(msg: String): Boolean = runCatching {
        DatagramSocket().use { s ->
            val b = msg.toByteArray()
            s.send(DatagramPacket(b, b.size, InetAddress.getByName(host), port))
        }
        true
    }.getOrDefault(false)
}
