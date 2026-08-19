package re.keti.a3004bridge

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * One screen: camera, lidar ring, microphone, RC and CAN telemetry, and the
 * controls that can drive something.
 *
 * Compose for the tree, threads for the wire. The protocol code in Net.kt is
 * blocking socket I/O verified against real hardware, and a thread is the right
 * shape for that. What needed replacing was the layer above it, where every
 * status string was pushed into a view by hand and a weight inside a
 * wrap-content parent silently gave the camera panel zero height.
 *
 * The layout assumes a tablet held in two hands: the camera is largest and where
 * the eyes go, translate and rotate sit in the bottom corners under the thumbs,
 * and arming is between them so the steering hand cannot reach it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        /*
         * Full screen, and the bars come back on a swipe rather than a tap.
         *
         * This is a console somebody drives from: a status bar with the time and
         * a notification dot is another application's interface sitting on top of
         * one where the top row matters, and the navigation bar sits exactly where
         * a thumb rests. BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE rather than hiding
         * them for good, because a deliberate swipe is recoverable and a
         * permanently hidden home gesture is not.
         *
         * setDecorFitsSystemWindows(false) as well, so the layout uses the whole
         * panel instead of leaving the inset behind as a grey band.
         */
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent { Bridge() }
    }
}

private const val PREFS = "cfg"
private const val KEY_HOST = "host"
private const val DEFAULT_HOST = "192.168.1.1"

@Composable
private fun Bridge() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // The address in use, and the text being edited. Separate, because typing
    // must not tear down a working stream on every keystroke.
    var host by remember {
        mutableStateOf(prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST)
    }
    var hostEdit by remember { mutableStateOf(host) }


    /*
     * neverEqualPolicy, because the decoder reuses two bitmaps in rotation:
     * frame N and frame N+2 are the same object, and a structural comparison
     * would call that "no change" and drop half the frames.
     */
    var frame by remember { mutableStateOf<ImageBitmap?>(null, neverEqualPolicy()) }
    var ring by remember { mutableStateOf<Ring?>(null, neverEqualPolicy()) }

    var camState by remember { mutableStateOf("idle" to T.textFaint) }
    var linkState by remember { mutableStateOf("connecting" to T.warn) }
    var ringState by remember { mutableStateOf("") }
    // Set by the ring itself, cleared by the reader's own timeout, so the plot can
    // show the difference between "these are the walls" and "these were".
    var ringLive by remember { mutableStateOf(false) }
    /*
     * The sensor's own range image, for the panel the camera used to have.
     *
     * The camera is a USB device and that port is wanted for the CAN adapter, so
     * the big panel needs something when there is no camera - and the lidar is
     * already producing the thing to put there. It is the same 3D data the ring is
     * a slice of: 64 channels by 1024 columns of range, which is a picture of the
     * room rather than a plan of it.
     *
     * ouster-edge builds it only when range_image is on, because it costs a fifth
     * of a core - affordable exactly when the camera is not running, which is the
     * case this exists for.
     */
    var rangeFrame by remember { mutableStateOf<RangeFrame?>(null) }
    var rangeState by remember { mutableStateOf("" to T.textDim) }
    val camLive = camState.first == "live"
    var rcState by remember { mutableStateOf("no rc" to T.textDim) }
    var canState by remember { mutableStateOf("idle") }
    var tlState by remember { mutableStateOf("no teleop" to T.textDim) }
    var micState by remember { mutableStateOf("") }
    var mapState by remember { mutableStateOf("no map") }
    /*
     * How well the mapper is tracking, which the map picture alone does not
     * show. A map that has stopped changing looks the same whether the vehicle
     * is standing still or the matcher has lost the room, and those need
     * different reactions. The score is the mapper's own confidence; being at
     * the edge of its search window means the next scan may not be found at
     * all, which is the moment before it loses tracking rather than after.
     */
    var slamState by remember { mutableStateOf("" to T.textDim) }
    /*
     * A ring buffer of microphone peaks, and a counter to redraw on.
     *
     * The array is written from the audio thread thirty times a second and never
     * reallocated; `micHead` is what Compose watches. Storing the levels in
     * state instead would allocate a new list per buffer for a canvas that is
     * redrawn either way.
     */
    val micLevels = remember { FloatArray(160) }
    var micHead by remember { mutableStateOf(0, neverEqualPolicy()) }
    var navState by remember { mutableStateOf("no nav" to T.textDim) }

    var armed by remember { mutableStateOf(false) }
    // The floor the operator is working with, and the survey the mapper says it
    // has. Separate on purpose: see where mapLoaded is parsed.
    var floor by remember { mutableStateOf(1) }
    var mapLoaded by remember { mutableStateOf("") }
    /*
     * Whether anything is listening to the steering, and to the waypoints.
     *
     * teleop and navigate are off by default on the router, and their status files
     * outlive them, so both of these used to read as fine while the frames went
     * nowhere. StatusPoller now nulls a status that has stopped changing, and
     * these follow it: a control with nothing behind it is faded rather than
     * inviting.
     */
    var teleopLive by remember { mutableStateOf(false) }
    var navLive by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var micOn by remember { mutableStateOf(true) }
    /*
     * Which of the two gets the big slot.
     *
     * The centre of the control card is the only place on this screen big enough
     * to read detail from, and the camera is not always what you want there - a
     * goal three rooms away is a map question. So the two swap rather than one of
     * them being permanently second.
     */
    var rcLive by remember { mutableStateOf(false) }
    var wifiNote by remember { mutableIntStateOf(0) }
    val channels = remember { mutableStateListOf<Int>().also { l -> repeat(14) { l.add(1500) } } }

    // The map is replaced wholesale on every fetch, and two consecutive frames
    // of an unchanging room are equal by value. neverEqualPolicy so the canvas
    // still redraws when only the pose inside it moved.
    var map by remember { mutableStateOf<MapFrame?>(null, neverEqualPolicy()) }
    var goal by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Tapped but not sent. The gap between meaning a destination and the
    // vehicle driving to it should contain a decision, not a fingertip.
    var pending by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /*
     * Waypoints collected before anything is sent.
     *
     * Tapping adds to this list rather than committing each point, because a
     * route is a decision about the whole path - and because the alternative,
     * sending each tap as it lands, would have the vehicle set off towards the
     * first one while the rest of the route is still being drawn.
     */
    val waypoints = remember { mutableStateListOf<Pair<Int, Int>>() }
    var navDriving by remember { mutableStateOf(false) }

    // Single-slot holders for things the effect owns and the controls have to
    // reach. Created in the effect and cleared on dispose, so there is never a
    // live sender the current composition does not know about.
    val tele = remember { arrayOfNulls<TeleopSender>(1) }
    val goals = remember { arrayOfNulls<GoalSender>(1) }
    val pcm = remember { arrayOfNulls<PcmPlayer>(1) }

    // Awake only while armed. Driving while the screen sleeps must not be
    // possible; holding the backlight on through an idle shift is a flat battery
    // when it is finally needed.
    val view = LocalView.current
    SideEffect { view.keepScreenOn = armed }

    /*
     * The streams live exactly as long as the screen shows them.
     *
     * This is the reason for the migration. Under views the readers were started
     * in onCreate and stopped in onDestroy, so a backgrounded app kept pulling
     * 720p, decoding every frame, playing audio and sending 50 datagrams a
     * second - invisibly, out of the battery. Here it is not something to
     * remember: resume starts them, pause disposes them.
     */
    LifecycleResumeEffect(host) {
        val ep = Endpoints(host)

        val mjpeg = MjpegReader(ep.cameraStream,
            onFrame = { bmp: Bitmap ->
                frame = bmp.asImageBitmap()
                camState = "live" to T.good
                linkState = host to T.good
            },
            onState = { s -> camState = s to T.bad }).also { it.start() }

        /*
         * Polled at 3 Hz, not the sensor's 10.
         *
         * 23 kB an image over wifi, of a room that changes slowly, and the panel it
         * fills is a picture to glance at rather than a control loop. Faster costs
         * bandwidth the camera may want back.
         */
        val rangeRx = RangeReader(ep.sensors, 330,
            onFrame = { f ->
                rangeFrame = f
                rangeState = "${f.rows}x${f.cols} · frame ${f.frameId}" to T.good
            },
            onState = { st -> rangeState = st to T.textDim }).also { it.start() }

        val ringRx = RingReader(Wire.RING_PORT,
            onRing = { r ->
                ring = r
                ringLive = true
                ringState = "${r.sectors} sectors · frame ${r.frameId}" +
                        if (r.alarm) "  ZONE" else ""
            },
            onState = { s -> ringState = s; ringLive = false }).also { it.start() }

        val sender = TeleopSender(ep.host, Wire.TELE_PORT, Wire.TELE_HZ_ARMED)
            .also { tele[0] = it; it.start() }


        val mapRx = MapReader(ep.sensors, 400,
            onMap = { m ->
                map = m
                mapState = "${m.w}x${m.h} · ${m.resCm}cm"
            },
            onState = { s -> mapState = s }).also { it.start() }
        goals[0] = GoalSender(ep.host, Wire.GOAL_PORT)

        val status = StatusPoller(ep.sensors, 400) { name, j ->
            when (name) {
                "rc" -> if (j == null) {
                    rcState = "no rc" to T.textDim; rcLive = false
                } else {
                    val link = j.optBoolean("link") && j.optLong("age_ms") < 1000
                    rcLive = link
                    rcState = ((if (link) "link ok" else "LINK LOST") +
                            " · ${j.optLong("frames")}") to
                            (if (link) T.textDim else T.bad)
                    j.optJSONArray("channels")?.let { a ->
                        for (i in 0 until minOf(a.length(), channels.size))
                            channels[i] = a.optInt(i, 1500)
                    }
                }
                "can" -> canState = if (j == null) "no can" else {
                    // The generation belongs on this line because it decides
                    // whether the numbers beside it mean anything: the two
                    // protocols reuse ids for unrelated things, and agx-cmd
                    // sends nothing at all while it is undecided. Frames
                    // arriving with no generation is the state that otherwise
                    // reads as a broken commander.
                    val gen = when (j.optString("agilex_protocol")) {
                        "v1" -> "v1"
                        "v2" -> "v2"
                        else -> "generation unknown"
                    }
                    "${j.optString("interface")} · rx ${j.optLong("rx")}" +
                            " · ${j.optJSONObject("frames")?.length() ?: 0} ids" +
                            " · $gen" +
                            if (j.optBoolean("inject_allowed")) " · INJECT"
                            else " · read-only"
                }
                "slam2d" -> slamState = if (j == null) "" to T.textDim else {
                    val pct = j.optInt("score_frac_pct")
                    val rings = j.optLong("rings")
                    val matched = j.optLong("matched")
                    val edge = j.optBoolean("at_search_edge")
                    val thin = j.optLong("skipped_too_few_returns")
                    // Reported separately from the score: a scan that was never
                    // matched because it had too few returns is a sensor or
                    // window problem, not a matching one, and the score says
                    // nothing about it.
                    // Which survey is on the grid, which is not the same as
                    // which floor the operator last tapped. The tap is intent;
                    // this is what the mapper is actually matching against, and
                    // a load that was refused leaves the two disagreeing.
                    mapLoaded = j.optJSONObject("map")?.optString("loaded") ?: ""
                    val floorName = mapLoaded.substringAfterLast('/')
                        .removeSuffix(".s2mp")
                    val text = "match $pct% · $matched/$rings" +
                            (if (floorName.isEmpty()) " · unsaved"
                             else " · $floorName") +
                            (if (edge) " · at search edge" else "") +
                            (if (thin > 0) " · $thin thin scans" else "")
                    text to when {
                        edge || pct < 40 -> T.bad
                        pct < 60 -> T.textDim
                        else -> T.good
                    }
                }
                "navigate" -> navState = if (j == null) {
                    navLive = false
                    "no nav" to T.bad
                } else {
                    navLive = true
                    val st = j.optString("state")
                    navDriving = st == "driving"
                    val fault = j.optString("fault").takeIf { it.isNotEmpty() && it != "null" }
                    val rem = j.optInt("remaining_cm")
                    when (st) {
                        "driving" -> "driving · ${rem} cm to go" to T.good
                        "arrived" -> "arrived" to T.good
                        "idle" -> "idle · score ${j.optInt("match_score_pct")}%" to T.textDim
                        else -> (fault ?: "STOP") to T.bad
                    }
                }
                "teleop" -> if (j == null) {
                    tlState = "no teleop" to T.bad
                    teleopLive = false
                    // Nothing is carrying the intent any more, so do not sit here
                    // showing an armed vehicle.
                    if (armed) { armed = false; sender.armed = false }
                } else {
                    teleopLive = true
                    val a = j.optBoolean("armed")
                    // If the daemon disarmed underneath us, reflect it.
                    if (!a && armed &&
                        j.optLong("age_ms") >= j.optLong("timeout_ms", 300)) {
                        armed = false
                    }
                    /*
                     * Whether teleop is passing anything on, not just whether it
                     * is alive.
                     *
                     * teleop forwards to agx-cmd, and agx-cmd is off by default.
                     * So the ordinary state is teleop running, accepting frames,
                     * and dropping them - and the panel used to report that as
                     * "disarmed · 20 Hz · udp 1406", which reads like a healthy
                     * link. Arming from there does nothing and says nothing about
                     * why. The web console names the same condition.
                     */
                    val fwd = j.optBoolean("forwarding")
                    tlState = ((if (a) "ARMED" else "disarmed") +
                            " · ${j.optInt("rate_hz")} Hz" +
                            (if (fwd) "" else " · not forwarding") +
                            " · udp ${j.optLong("udp_commands")}") to
                            (if (a && fwd) T.good
                             else if (!fwd) T.warn else T.textDim)
                }
                "ouster" -> if (j != null && ring == null)
                    ringState = "${j.optInt("channels")}ch · " + j.optString("profile")
            }
        }.also { it.start() }

        /*
         * If the microphone starts on, start it.
         *
         * micOn defaulted to true while the player was only ever created inside the
         * toggle, so the button read "MIC OFF" - as though it were running - next
         * to a flat trace and a peak of 0.000. State that claims something is
         * happening has to be the state that makes it happen.
         */
        if (micOn && pcm[0] == null) {
            pcm[0] = PcmPlayer(ep.audioBase,
                onLevel = { v ->
                    micLevels[micHead % micLevels.size] = v
                    micHead = micHead + 1
                },
                onState = { st -> micState = st }).also { it.start() }
        }

        onPauseOrDispose {
            // Leaving must not leave something armed. The deadman would catch it;
            // not relying on the deadman is the point.
            armed = false
            sender.armed = false
            mjpeg.halt(); rangeRx.halt(); ringRx.halt(); status.halt(); sender.halt()
            mapRx.halt()
            pcm[0]?.halt(); pcm[0] = null
            tele[0] = null
            goals[0] = null
            micOn = false; micState = ""
            frame = null
            camState = "idle" to T.textFaint
            linkState = "connecting" to T.warn
        }
    }

    // 50 Hz is for steering. Disarmed there is nothing to steer, and the daemon
    // only needs enough traffic to know the app is still there.
    SideEffect {
        tele[0]?.armed = armed
        tele[0]?.hz = if (armed) 50 else 5
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(T.bg)
            .padding(start = T.s4, end = T.s4, top = T.s2, bottom = T.s3)
    ) {
        /*
         * No application name.
         *
         * On a device that runs one thing, the name is the one piece of text on
         * screen that never tells you anything - and it was taking a 22 sp line
         * across the top of a console where every panel wants the height. What
         * belongs in this row is state and the controls that change it.
         */
        /*
         * There is no header while the link is up.
         *
         * Everything it held - the address, the two connect controls, the link
         * badge - answers a question you only ask when something is wrong. The
         * badge moved to the control card, where the rest of the state already is,
         * and the row itself now costs nothing when it has nothing to say.
         */
        /*
         * One implementation of joining, two places that offer it.
         *
         * The big control in CONTROL is the one somebody will find; the header chip
         * stays because that row is also where the address is edited, and a second
         * copy of this logic there would be a second thing to keep correct.
         */
        val joinAp = {
            // Binds only this app's sockets to the router's AP. The system keeps
            // its own default network, which is what stops Android wandering off an
            // access point that has no internet - and makes the router's
            // 192.168.1.0/24 unambiguous even where the building network overlaps.
            wifiNote = when (Wifi.bindToAp(ctx) { ok ->
                wifiNote = if (ok) 2 else 3
                if (ok) host = host       // re-key: reconnect over the AP
            }) {
                Wifi.Route.BOUND -> 2
                Wifi.Route.REQUESTING -> 1
                Wifi.Route.SYSTEM_DIALOG, Wifi.Route.SUGGESTION,
                Wifi.Route.PICKER -> 4
                Wifi.Route.UNSUPPORTED -> 5
            }
        }

        if (linkState.second != T.good) Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(linkState.first, linkState.second)
            Spacer(Modifier.weight(1f))
            /*
             * The address and the two connect controls appear only when there is
             * nothing to connect to.
             *
             * Once the link is up they answer no question anyone is asking, and
             * they were taking the width of a card across the top of a console
             * whose panels want it. When the link drops they are the first thing
             * needed, and they come back on their own.
             */
            HostField(hostEdit) { hostEdit = it }
            Spacer(Modifier.width(T.s2))
            Chip(
                when (wifiNote) {
                    1 -> "joining AP"
                    2 -> "AP joined"
                    3 -> "AP dropped"
                    4 -> "use wifi settings"
                    5 -> "AP failed"
                    else -> "JOIN AP"
                }
                , emph = Emph.Tinted
            ) { joinAp() }
            Spacer(Modifier.width(T.s2))
            Chip("CONNECT") {
                val h = hostEdit.trim().ifEmpty { DEFAULT_HOST }
                prefs.edit().putString(KEY_HOST, h).apply()
                host = h            // re-keys the effect, so the streams restart
            }
        }

        Spacer(Modifier.height(T.s3))

        /*
         * spacedBy, not hand-placed spacers.
         *
         * There was a 12 dp Spacer between the first two cards and nothing at all
         * between the second and third - so two gaps that should have matched did
         * not, and no amount of looking at the numbers in the file showed it,
         * because the missing one was an absence. Letting the row own the spacing
         * makes every gap the same by construction.
         */
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(T.s3)
        ) {
            /*
             * The camera is not here any more - it sits between the two thumbs,
             * in the control panel below. On a 16:9 tablet held in both hands the
             * centre of the control row is where the eyes already are, and a
             * picture in a corner means looking away from the sticks to use it.
             */
            Panel(
                "LIDAR", Modifier.weight(0.85f),
                status = { Status(ringState, if (ringLive) T.textDim else T.bad) }
            ) { body ->
                // 0 means scale to the data: a sensor indoors puts everything inside
                // the first ring of a fixed 30 m plot.
                RingPlot(ring, 0f, body.fillMaxWidth().padding(T.s2),
                         live = ringLive)
                /*
                 * The depth strip used to sit under the ring. Measured rather than
                 * argued about, and the numbers decided it: sixteen points of one
                 * router core, 11520 rects a frame here, 46 kB/s - against a driver
                 * who is reading the ring and the camera. The router still knows how
                 * to produce it and is documented for when something wants it
                 * (doc/RING-FORMAT.md); it is off by default now, and the space it
                 * took belongs to the camera.
                 */
            }
            Panel(
                /*
                 * No heading. A picture of what is in front of the vehicle does
                 * not need to be told it is a camera, and the line it was using
                 * is now part of the picture. The state still has somewhere to
                 * go: it is drawn over the top corner.
                 */
                "",
                Modifier.weight(2f),
                overlayTitle = true,
                status = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Whichever picture the panel is showing says so. With the
                        // camera unplugged its badge would otherwise be the only
                        // label on a panel full of lidar.
                        if (camLive) {
                            Badge(camState.first, camState.second)
                        } else {
                            Status("lidar depth", T.textDim)
                            Spacer(Modifier.width(T.s2))
                            Badge(rangeState.first.ifEmpty { camState.first },
                                  if (rangeFrame != null) T.good else camState.second)
                        }
                    }
                }
            ) { body ->
                /*
                 * Height first, then 16:9. Fit inside a box of some other ratio
                 * puts black bars beside the picture; a box that is exactly 16:9
                 * has none, and the space left over is card rather than void.
                 */
                /*
                 * No inset and no outline. A picture framed inside a card that is
                 * itself framed reads as two boxes; the card's own rounded corners
                 * are the frame, so the image takes all of it.
                 */
                /*
                 * The camera when there is one, the lidar's range image when there
                 * is not.
                 *
                 * The USB port the camera used is wanted for the CAN adapter, so
                 * "no camera" stops being a fault and becomes a configuration - and
                 * the biggest panel on the screen should not be a black rectangle
                 * because of it. The sensor is already producing a picture: 64
                 * channels by 1024 columns of range, which is the room rather than
                 * a plan of it.
                 *
                 * The depth image takes the whole panel rather than 16:9. It is
                 * 1024 columns of a 360-degree sweep by 64 rows of 45 degrees, so
                 * its own ratio is nothing like a camera's and letterboxing it to
                 * one would throw away the width that makes it readable.
                 */
                if (camLive) {
                    Box(body.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CameraBox(frame, Modifier.fillMaxHeight().aspectRatio(16f / 9f),
                                  framed = false, live = true)
                    }
                } else if (rangeFrame != null) {
                    RangeView(rangeFrame, body.fillMaxSize())
                } else {
                    Box(body.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CameraBox(frame, Modifier.fillMaxHeight().aspectRatio(16f / 9f),
                                  framed = false, live = false)
                    }
                }
            }

            /*
             * Widths by how much of the screen each thing earns.
             *
             * The lidar column is the widest: the ring plus the rows the ring
             * throws away is the live picture of what surrounds the vehicle. The
             * map answers "where" and changes slowly. The microphone is a check
             * rather than an instrument - narrow is the point, not a compromise.
             */
            /*
             * Equal thirds. A regular grid reads as deliberate where 1.5 / 1.2 /
             * 0.8 reads as whatever fitted, and the lidar plot loses nothing by
             * being square-ish: it is a polar plot, so width past its own diameter
             * was empty margin.
             */
            /*
             * The camera is the biggest thing up here and the map is the biggest
             * thing below: "what is in front of me" and "where am I". The lidar
             * plot and the two readouts are references, so they are narrow - the
             * polar plot loses nothing, since width past its own diameter is
             * margin.
             */
            Column(
                Modifier.weight(0.85f),
                verticalArrangement = Arrangement.spacedBy(T.s3)
            ) {
                Panel(
                    "MICROPHONE", Modifier.height(188.dp),
                    // Its own state. This read camState for a while - left behind
                    // when the camera moved out of this panel - so the badge said
                    // "live" about the video while the microphone was off.
                    /*
                     * The toggle sits in the heading row.
                     *
                     * It was a chip on its own line inside the card, which cost a
                     * row of height to hold one control while the heading row beside
                     * it was half empty. A card with one action puts it where the
                     * card is named.
                     */
                    status = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // The text takes the squeeze, not the button.
                            //
                            // Without the weight here, a long state - "waiting for
                            // mic" - ate the chip's width inside the heading row's
                            // bounded slot and the chip wrapped to one letter per
                            // line. A control has an intrinsic size and a status
                            // string does not; the flexible one has to be the text.
                            Status(micState, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(T.s3))
                            // Just the action. "MIC OFF" beside a card headed MICROPHONE said
                            // the word twice and pushed that heading onto two lines.
                            Chip(if (micOn) "OFF" else "ON",
                                 emph = Emph.Tinted) {
                                if (micOn) {
                                    pcm[0]?.halt(); pcm[0] = null
                                    micOn = false; micState = ""
                                    micLevels.fill(0f)
                                    micHead = micHead + 1
                                } else {
                                    pcm[0] = PcmPlayer(Endpoints(host).audioBase,
                                        onLevel = { v ->
                                            micLevels[micHead % micLevels.size] = v
                                            micHead = micHead + 1
                                        },
                                        onState = { s -> micState = s })
                                        .also { it.start() }
                                    micOn = true
                                }
                            }
                        }
                    }
                ) { body ->
                    Column(body) {
                        Spacer(Modifier.weight(1f))
                        MicWave(
                            micLevels, micHead, micOn,
                            Modifier.fillMaxWidth().height(96.dp))
                        Spacer(Modifier.height(T.s4))
                    }
                }
                Panel(
                    "TELEMETRY",
                    status = { Status(rcState.first, rcState.second) }
                ) { _ ->
                    /*
                     * Bars only when there are channels behind them.
                     *
                     * Fourteen empty bars and the word "no can" is a card saying
                     * nothing, twice, in the space of the largest thing on that side
                     * of the screen - and it is the normal state, because the
                     * receiver and the CAN bridge are both off by default. Worse,
                     * fourteen grey bars look like fourteen channels sitting at
                     * zero, which is a different fact from no receiver at all.
                     *
                     * So the bars appear when the link does, and their presence is
                     * itself the signal. Absence gets one line each, which is all
                     * "nothing here" needs.
                     */
                    Column(Modifier.padding(start = T.s4, end = T.s4, bottom = T.s3)) {
                        Label("RC · i-BUS")
                        Spacer(Modifier.height(T.s2))
                        if (rcLive) {
                            for (row in 0 until 2) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = T.s1),
                                    horizontalArrangement = Arrangement.spacedBy(T.s1)
                                ) {
                                    for (i in row * 7 until row * 7 + 7) {
                                        ChannelBar(
                                            channels[i], rcLive,
                                            Modifier.weight(1f).height(6.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Short enough to fit. The first version explained what
                            // would appear later and was ellipsised into
                            // "no receiver - 14 channels when one appea...", which
                            // is a worse thing to read than the two words that
                            // matter.
                            Status("no receiver", T.textFaint)
                        }
                        Spacer(Modifier.height(T.s4))
                        Label("CAN")
                        Spacer(Modifier.height(T.s1))
                        Status(canState.ifEmpty { "no bus" },
                               if (canState.startsWith("no")) T.textFaint else T.textDim)
                    }
                }
            }
        }

        Spacer(Modifier.height(T.s3))

        /*
         * Equal halves, top and bottom.
         *
         * With the top row taking whatever the cards wanted and the control card
         * wrapping a fixed-height map, neither half was a deliberate size. Half
         * each also gives the camera enough height to be a 16:9 rectangle rather
         * than a letterboxed one.
         */
        Panel("CONTROL", Modifier.weight(1f), overlayTitle = true, status = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                /*
                 * The swap belongs here, not in the map's status row.
                 *
                 * It decides what fills this card's big slot, so this is where its
                 * effect is. In the map's row it was also the thing breaking the
                 * top row's alignment: a chip is taller than a line of text, so
                 * that one panel's heading sat lower than its neighbours' and the
                 * three cards stopped lining up.
                 */
                Spacer(Modifier.width(T.s3))
                // The camera lives in this panel now, so its state is reported here.
                Badge(linkState.first, linkState.second)
                Spacer(Modifier.width(T.s3))
            }
        }) { _ ->
            /*
             * The same column widths as the row above, so the map lands directly
             * under the camera.
             *
             * They were 230 dp fixed either side of a weighted middle, which put
             * the map's edges a few dozen pixels off the picture's - close enough
             * to read as a mistake. Sharing the weights makes the two halves one
             * grid instead of two that nearly agree.
             */
            Row(
                Modifier.fillMaxSize().padding(T.s3),
                horizontalArrangement = Arrangement.spacedBy(T.s3),
                verticalAlignment = Alignment.CenterVertically
            ) {
                /*
                 * Both sides the same width.
                 *
                 * They were 132 dp and 190 dp, so the picture between them sat 54 px
                 * left of the card's centre - close enough to look like a mistake
                 * and far enough to see. Equal columns put the centre where the eye
                 * expects it without measuring anything.
                 */
                Column(
                    // The same width as the column on the far side, so the picture
                    // above and the map below are both centred. They were 0.7 and
                    // 0.85: enough to see, not enough to look deliberate.
                    Modifier.weight(0.85f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    /*
                     * As big as its column allows.
                     *
                     * It was a fixed 190 dp in a column about 290 dp wide and 350 dp
                     * tall, so nearly half of the panel's left third was white
                     * space - and this is the control somebody steers with, where
                     * size is precision. Derived from the column rather than typed
                     * as a number, so it stays right if the weights either side
                     * change again.
                     */
                    Joystick(armed, Modifier.fillMaxWidth(0.9f).aspectRatio(1f)) { x, y ->
                        tele[0]?.x = x; tele[0]?.y = y
                    }
                    Spacer(Modifier.height(T.s2))
                    Label("TRANSLATE")
                }
                Column(
                    Modifier.weight(2f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    /*
                     * 16:9, declared rather than inherited.
                     *
                     * The stream is 1280x720 and ContentScale.Fit would letterbox
                     * it inside whatever box the layout happened to give - so the
                     * picture would change size as the panels around it changed,
                     * and the two sticks either side would shift with it. Fixing
                     * the aspect ratio makes the frame the thing that decides, and
                     * the controls stay where the hands left them.
                     */
                    /*
                     * The camera is 16:9 because its source is; the map is not.
                     * Fitting a square grid into a 16:9 box left a third of the
                     * slot empty either side, so the map takes the whole width and
                     * the picture keeps its ratio.
                     */
                    /*
                     * The map, always. The camera has the top row to itself, so a
                     * swap between them would only ever move one of them somewhere
                     * worse - and the control that did it was another thing to
                     * learn for no gain.
                     */
                    /*
                     * The mapper's and the navigator's state, over the map.
                     *
                     * They were right-aligned in the card's heading, which spans the
                     * whole card - so they came out above the FLOOR buttons rather
                     * than above the thing they describe, and read as belonging to
                     * the controls. A caption inside the map's own column costs one
                     * line of the map's height and puts each number over its
                     * subject, which is the same reason the teleop line moved under
                     * ARM.
                     */
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = T.s1),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Status(navState.first, navState.second)
                        if (slamState.first.isNotEmpty()) {
                            Spacer(Modifier.weight(1f))
                            Status(slamState.first, slamState.second)
                        }
                    }
                    MapPlot(
                        map, goal, pending,
                        vehicleColour = when {
                            navState.second == T.bad -> T.bad
                            navDriving -> T.good
                            else -> T.textDim
                        },
                        ring = ring,
                        routeCm = waypoints.toList(),
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(),
                    ) { x, y -> pending = x to y; waypoints.add(x to y) }
                    /*
                     * The deadman sentence used to sit here, under the picture.
                     * It is instruction rather than state: read once, then noise
                     * between the camera and the control that arms a vehicle. What
                     * matters while driving is whether the deadman is live, and the
                     * ARM control's own colour already says that.
                     */
                }
                /*
                 * ARM sits above the yaw slider rather than under the camera.
                 *
                 * Arming is a right-thumb action and so is yaw, so they belong on
                 * the same side and in reach of the same hand. Under the picture it
                 * was in the middle of the screen, which is where the eyes are and
                 * where no thumb rests - the one control that must be deliberate
                 * was the one placed furthest from a finger.
                 */
                Column(
                    Modifier.weight(0.85f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    /*
                     * Route controls above ARM, in reach of the same thumb that
                     * arms and steers. They were in a status row, which is for
                     * state - four chips there overflowed the line and broke the
                     * heading beside them into one letter per row.
                     *
                     * They appear only when there is something to do with them: a
                     * point tapped, a route drawn, or a goal running. A row of
                     * greyed-out buttons is a row of questions.
                     */
                    // Keeping the map is a mapper action rather than a driving
                    // one - it goes to slam2d, which owns the map, not to navigate
                    // which only reads it - so it sits with the route controls and
                    // not beside ARM.
                    /*
                     * A floor is a map.
                     *
                     * One grid cannot hold two of them: the corridor upstairs and
                     * the corridor below it occupy the same coordinates, so they
                     * cannot share a survey. That makes the map a thing you pick
                     * rather than a thing you have, and one SAVE button was the
                     * wrong shape for it - it could only ever keep one building.
                     *
                     * The row is the operator's choice; what the mapper actually
                     * has is in the status line beside the map, because a LOAD the
                     * daemon refused would otherwise leave this row claiming a
                     * floor that is not on the grid.
                     */
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("FLOOR")
                        Spacer(Modifier.size(T.s2))
                        for (n in 1..3) {
                            Chip(
                                "$n",
                                emph = if (n == floor) Emph.Tinted else Emph.Quiet,
                                colour = if (n == floor) T.accent else T.textDim
                            ) { floor = n; confirmReset = false }
                            if (n < 3) Spacer(Modifier.size(T.s1))
                        }
                    }
                    Spacer(Modifier.height(T.s2))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Chip("SAVE", emph = Emph.Quiet) {
                            navState = if (goals[0]?.saveMap("floor$floor") == true)
                                "floor $floor saved" to T.good
                            else "save failed" to T.bad
                            confirmReset = false
                        }
                        Spacer(Modifier.size(T.s2))
                        Chip("LOAD", emph = Emph.Quiet) {
                            // The datagram leaving is not the map loading. Only the
                            // status line can say whether the daemon took it, so
                            // this says what was asked for and nothing more.
                            navState = if (goals[0]?.loadMap("floor$floor") == true)
                                "floor $floor requested" to T.textDim
                            else "load not sent" to T.bad
                            confirmReset = false
                        }
                        Spacer(Modifier.size(T.s2))
                        /*
                         * Two taps, because this one cannot be undone.
                         *
                         * RESET throws away a survey that may have taken a walk
                         * around a building to build, and there is no other button
                         * here whose accidental press costs anything. So the first
                         * tap only arms it and says so in red.
                         */
                        Chip(
                            if (confirmReset) "SURE?" else "RESET",
                            emph = if (confirmReset) Emph.Filled else Emph.Quiet,
                            colour = T.bad
                        ) {
                            if (confirmReset) {
                                navState = if (goals[0]?.resetMap() == true)
                                    "map cleared" to T.textDim
                                else "reset not sent" to T.bad
                                confirmReset = false
                            } else {
                                confirmReset = true
                            }
                        }
                    }
                    Spacer(Modifier.height(T.s2))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    if (waypoints.size > 1) {
                        Chip("ROUTE ${waypoints.size}", emph = Emph.Filled,
                             enabled = navLive) {
                            if (goals[0]?.route(waypoints.toList()) == true) {
                                goal = waypoints.last()
                                pending = null
                                waypoints.clear()
                            } else {
                                navState = "route not sent" to T.bad
                            }
                        }
                        Spacer(Modifier.size(T.s2))
                        Chip("UNDO", emph = Emph.Quiet) {
                            waypoints.removeAt(waypoints.size - 1)
                            pending = waypoints.lastOrNull()
                        }
                        Spacer(Modifier.size(T.s2))
                    }
                    pending?.let { (px, py) ->
                        Chip("GO HERE", emph = Emph.Filled, enabled = navLive) {
                            if (goals[0]?.goal(px, py) == true) {
                                goal = px to py
                                pending = null
                                waypoints.clear()
                            } else {
                                navState = "goal not sent" to T.bad
                            }
                        }
                        Spacer(Modifier.size(T.s2))
                        Chip("CANCEL", emph = Emph.Quiet) { pending = null; waypoints.clear() }
                    }
                    if (pending == null && (goal != null || navDriving))
                        Chip("STOP", emph = Emph.Tinted, colour = T.bad) {
                            goals[0]?.stop(); goal = null
                        }
                    }
                    Spacer(Modifier.height(T.s3))
                    /*
                     * One control, and when it matters it says STOP.
                     *
                     * There is no separate emergency stop, because there is
                     * already nothing to stop: teleop runs a 300 ms deadman, so
                     * letting go of the stick, losing wifi, or backgrounding the
                     * app all bring the output to neutral and drop the armed flag
                     * on their own. A second red button for the case that already
                     * self-stops would be theatre.
                     *
                     * What a person in a hurry does need is one obvious target,
                     * so the armed state of this button is it: full width, red,
                     * and labelled for what it does rather than for the flag it
                     * clears. It said DISARM, which is the name of the mechanism
                     * and not the name of the intention.
                     */
                    /*
                     * One big button, and it is whatever has to happen next.
                     *
                     * JOIN AP used to live in a header that only appeared when the
                     * link was bad, which is the moment somebody is least inclined
                     * to hunt for a small chip - and it read as missing the rest of
                     * the time. Meanwhile ARM in that same moment is a button that
                     * cannot do anything: with no route to the router there is no
                     * teleop to arm, so it was sitting there faded.
                     *
                     * So the slot carries the one action that is useful in each
                     * state. No link: join the access point. Linked and disarmed:
                     * arm. Armed: stop. The position never moves, which is what
                     * makes a big button worth having.
                     */
                    val linked = linkState.second == T.good
                    Chip(
                        when {
                            !linked -> if (wifiNote == 1) "JOINING AP" else "JOIN AP"
                            armed -> "STOP"
                            else -> "ARM"
                        },
                        Modifier.width(190.dp),
                        emph = Emph.Filled,
                        colour = if (armed && linked) T.bad else T.accent,
                        big = true,
                        // Arming with nothing listening is the one thing this
                        // button must never look like it did. Joining is always
                        // available, because it is what fixes that.
                        enabled = !linked || teleopLive || armed
                    ) {
                        if (!linked) {
                            joinAp()
                        } else {
                            if (armed) {
                                // Stop everything it can, not just the flag: a route
                                // being driven is the navigator's, and disarming alone
                                // would leave it planning against a vehicle that has
                                // gone quiet.
                                tele[0]?.let { it.x = 0f; it.y = 0f; it.r = 0f }
                                goals[0]?.stop(); goal = null
                                pending = null; waypoints.clear()
                            }
                            armed = !armed
                        }
                    }
                    /*
                     * The router's own view of the arming, directly under the
                     * control that does it.
                     *
                     * It used to sit at the far end of a row above the map with the
                     * navigator's state and the mapper's - four subsystems, three
                     * colours, one line, and the densest text on the screen. Only
                     * this part described a button, and it is the part that says
                     * whether pressing that button can do anything: "not forwarding"
                     * means the intent reaches teleop and stops there.
                     */
                    Spacer(Modifier.height(T.s2))
                    Status(tlState.first, tlState.second)
                    Spacer(Modifier.height(T.s4))
                    YawSlider(armed, Modifier.width(200.dp).height(72.dp)) { r ->
                        tele[0]?.r = r
                    }
                    Spacer(Modifier.height(T.s2))
                    Label("ROTATE")
                }
            }
        }
    }
}

/**
 * The camera picture, wherever it happens to be.
 *
 * Two slots can hold it - the big centre of the control card, or the top row when
 * the map has been promoted - so it exists once and takes a modifier. Written as
 * a function rather than duplicated because the two copies drifted apart the
 * moment one of them was touched.
 */
@Composable
private fun CameraBox(frame: androidx.compose.ui.graphics.ImageBitmap?,
                      modifier: Modifier,
                      framed: Boolean = true,
                      /**
                       * Whether the picture is still arriving.
                       *
                       * The last frame is kept when the stream drops, which is
                       * right - a picture from a second ago beats a black
                       * rectangle. But it was kept at full brightness beside a
                       * small red "no stream" tag, so the panel showed a vivid
                       * live-looking room and a label nobody reads first. Stale
                       * data has to look stale, or the label is decoration.
                       */
                      live: Boolean = true) {
    Box(
        modifier
            .then(if (framed) Modifier.clip(T.rSm) else Modifier)
            .background(T.videoBg)
            .then(if (framed) Modifier.border(1.dp, T.hairline, T.rSm) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    // Dimmed and drained of colour when it is no longer arriving.
                    // Not hidden: the shapes in the last frame are still worth
                    // having, they are just not now.
                    .alpha(if (live) 1f else 0.45f),
                colorFilter = if (live) null
                              else ColorFilter.colorMatrix(ColorMatrix().apply {
                                  setToSaturation(0.15f)
                              }),
                // Fit, not Crop: the whole frame, because what is cut off
                // by a crop is the edges - which is where an obstacle appears
                // first.
                contentScale = ContentScale.Fit
            )
        } else {
            EmptyState("waiting for camera", onDark = true,
                       modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun HostField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(
            color = T.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace
        ),
        cursorBrush = SolidColor(T.accent),
        modifier = Modifier
            .width(150.dp)
            .clip(T.rSm)
            .background(T.surfaceHi)
            .border(1.dp, T.hairline, T.rSm)
            .padding(horizontal = T.s3, vertical = T.s2)
    )
}
