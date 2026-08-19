package re.keti.a3004bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * The lidar console: the same vehicle, no camera, and the cloud drawn in 3D.
 *
 * Why a second app rather than a mode in the first. The camera is a USB device and
 * that port is wanted for the CAN adapter, so the machine has a camera and a
 * microphone or it has neither - unplugging the StreamCam takes the sound card with
 * it, which was measured rather than assumed (/dev/snd empties). A console built
 * around a picture and a waveform has two dead panels in that configuration, and a
 * console built around the lidar has none. Two consoles, one shared source tree.
 *
 * The 3D view is the range image reprojected, not a second stream: the raw sensor
 * output is 64 Mbit/s and does not survive wifi, while the range image is 23 kB and
 * carries the same geometry at a coarser sampling.
 */
class LidarActivity : ComponentActivity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        // The same reasoning as the other console: a status bar belongs to another
        // application and the navigation bar sits where a thumb rests.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // No theme wrapper: the colours are a plain object in Theme.kt rather
        // than a MaterialTheme, so there is nothing to provide.
        setContent { LidarConsole() }
    }
}

@Composable
private fun LidarConsole() {
    val ctx = LocalContext.current
    val view = LocalView.current
    val prefs = remember {
        ctx.getSharedPreferences("a3004lidar", android.content.Context.MODE_PRIVATE)
    }
    // Its own default, because the camera console's is private to that file and
    // sharing a constant across two apps by widening its visibility would be a
    // change to the other app for this one's convenience.
    val defaultHost = "192.168.1.1"
    var host by remember {
        mutableStateOf(prefs.getString("host", defaultHost) ?: defaultHost)
    }

    var ring by remember { mutableStateOf<Ring?>(null) }
    var ringLive by remember { mutableStateOf(false) }
    var ringState by remember { mutableStateOf("" to T.textDim) }
    var rangeFrame by remember { mutableStateOf<RangeFrame?>(null) }
    var rangeState by remember { mutableStateOf("" to T.textDim) }
    var geometry by remember { mutableStateOf<BeamGeometry?>(null) }
    var geometryState by remember { mutableStateOf("" to T.textDim) }
    var map by remember { mutableStateOf<MapFrame?>(null) }
    var slamState by remember { mutableStateOf("" to T.textDim) }
    var navState by remember { mutableStateOf("no nav" to T.bad) }
    var canState by remember { mutableStateOf("no can") }
    // Roll and pitch from the lidar's own IMU, and whether they are current. The
    // router derives the angles so two readers cannot disagree about the convention.
    var roll by remember { mutableStateOf(0f) }
    var pitch by remember { mutableStateOf(0f) }
    var imuLive by remember { mutableStateOf(false) }
    var rcState by remember { mutableStateOf("no rc" to T.textDim) }
    var rcLive by remember { mutableStateOf(false) }
    val channels = remember { IntArray(14) }
    var tlState by remember { mutableStateOf("no teleop" to T.bad) }
    var teleopLive by remember { mutableStateOf(false) }
    var navLive by remember { mutableStateOf(false) }
    var linkState by remember { mutableStateOf("connecting" to T.warn) }
    var armed by remember { mutableStateOf(false) }
    var navDriving by remember { mutableStateOf(false) }
    var goal by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pending by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val waypoints = remember { mutableStateListOf<Pair<Int, Int>>() }
    var floor by remember { mutableStateOf(1) }
    var confirmReset by remember { mutableStateOf(false) }
    var wifiNote by remember { mutableStateOf(0) }

    val tele = remember { arrayOfNulls<TeleopSender>(1) }
    val goals = remember { arrayOfNulls<GoalSender>(1) }

    // Awake only while armed, for the same reason as the other console: driving
    // while the screen sleeps must not be possible, and a permanent wake lock on a
    // tablet that lives on a vehicle is a flat battery.
    androidx.compose.runtime.SideEffect { view.keepScreenOn = armed }

    LifecycleResumeEffect(host) {
        val ep = Endpoints(host)

        val ringRx = RingReader(Wire.RING_PORT,
            onRing = { r ->
                /*
                 * The ring does not prove the link.
                 *
                 * It is a UDP broadcast, so it arrives on any network that carries
                 * it - and this app's HTTP sockets need the process bound to the
                 * router's access point, which is a separate thing. Setting the link
                 * state from here painted "connected" over a console whose depth
                 * image, map and status were all failing, and put ARM where JOIN AP
                 * belonged. Only something that completes an HTTP request may say
                 * the link is up.
                 */
                ring = r
                ringLive = true
                // Just the frame in the heading. The sector count belongs in the
                // card, under the plot: it is fixed configuration rather than
                // state, and putting both in a narrow heading ellipsised the one
                // that changes.
                ringState = ("frame ${r.frameId}" +
                        (if (r.alarm) "  ZONE" else "")) to T.textDim
            },
            onState = { s -> ringState = s to T.bad; ringLive = false }
        ).also { it.start() }

        val rangeRx = RangeReader(ep.sensors, 330,
            onFrame = { f ->
                rangeFrame = f
                linkState = host to T.good      // an HTTP body arrived
                rangeState = "${f.rows}x${f.cols} · frame ${f.frameId}" to T.good
            },
            onState = { s ->
                rangeState = s to T.bad
                if (s.startsWith("no link")) linkState = "connecting" to T.warn
            }
        ).also { it.start() }

        val beam = GeometryFetcher(ep.sensors,
            onGeometry = { g -> geometry = g },
            onState = { s -> geometryState = s to
                    (if (s.endsWith("beams")) T.textDim else T.bad) }
        ).also { it.start() }

        val mapRx = MapReader(ep.sensors, 500,
            onMap = { m -> map = m; linkState = host to T.good },
            onState = { }
        ).also { it.start() }

        val sender = TeleopSender(host, Wire.TELE_PORT, Wire.TELE_HZ_ARMED)
            .also { it.start() }
        tele[0] = sender
        goals[0] = GoalSender(host)

        val status = StatusPoller(ep.sensors, 400) { name, j ->
            when (name) {
                "slam2d" -> slamState = if (j == null) "" to T.textDim else {
                    val pct = j.optInt("score_frac_pct")
                    val name2 = (j.optJSONObject("map")?.optString("loaded") ?: "")
                        .substringAfterLast('/').removeSuffix(".s2mp")
                    ("match $pct%" + (if (name2.isEmpty()) " · unsaved"
                                      else " · $name2")) to
                            (if (pct >= 60) T.good else T.bad)
                }
                "navigate" -> navState = if (j == null) {
                    navLive = false; "no nav" to T.bad
                } else {
                    navLive = true
                    navDriving = j.optString("state") == "driving"
                    val fault = j.optString("fault")
                        .takeIf { it.isNotEmpty() && it != "null" }
                    (j.optString("state") + (fault?.let { " · $it" } ?: "")) to
                            (if (fault != null) T.bad else T.textDim)
                }
                "teleop" -> if (j == null) {
                    tlState = "no teleop" to T.bad
                    teleopLive = false
                    if (armed) { armed = false; sender.armed = false }
                } else {
                    teleopLive = true
                    val a = j.optBoolean("armed")
                    val fwd = j.optBoolean("forwarding")
                    if (!a && armed &&
                        j.optLong("age_ms") >= j.optLong("timeout_ms", 300)) {
                        armed = false
                    }
                    tlState = ((if (a) "ARMED" else "disarmed") +
                            (if (fwd) "" else " · not forwarding")) to
                            (if (a && fwd) T.good else if (!fwd) T.warn else T.textDim)
                }
                "ouster" -> {
                    val imu = j?.optJSONObject("imu")
                    // Live means arriving, not merely present: the field stays in
                    // the file with its last values if the IMU stops, and a
                    // horizon frozen at the angle it was is worse than none.
                    imuLive = imu != null && imu.optLong("age_ms") < 1000
                    if (imuLive) {
                        roll = imu!!.optDouble("roll_deg", 0.0).toFloat()
                        pitch = imu.optDouble("pitch_deg", 0.0).toFloat()
                    }
                }
                "can" -> canState = if (j == null) "no can" else
                    "${j.optString("interface")} · rx ${j.optLong("rx")}"
                "rc" -> if (j == null) {
                    rcState = "no rc" to T.textDim; rcLive = false
                } else {
                    rcLive = j.optBoolean("live")
                    rcState = (if (rcLive) "live" else "idle") to
                            (if (rcLive) T.good else T.textDim)
                    j.optJSONArray("channels")?.let { arr ->
                        for (i in 0 until minOf(arr.length(), channels.size))
                            channels[i] = arr.optInt(i)
                    }
                }
            }
        }.also { it.start() }

        onPauseOrDispose {
            armed = false
            sender.armed = false
            ringRx.halt(); rangeRx.halt(); beam.halt(); mapRx.halt()
            status.halt(); sender.halt()
            tele[0] = null; goals[0] = null
        }
    }

    androidx.compose.runtime.LaunchedEffect(armed) {
        tele[0]?.armed = armed
        tele[0]?.hz = if (armed) Wire.TELE_HZ_ARMED else 5
    }

    val joinAp = {
        wifiNote = when (Wifi.bindToAp(ctx) { ok ->
            wifiNote = if (ok) 2 else 3
            if (ok) host = host
        }) {
            Wifi.Route.BOUND -> 2
            Wifi.Route.REQUESTING -> 1
            Wifi.Route.SYSTEM_DIALOG, Wifi.Route.SUGGESTION, Wifi.Route.PICKER -> 4
            Wifi.Route.UNSUPPORTED -> 5
        }
    }
    val linked = linkState.second == T.good

    Column(Modifier.fillMaxSize().padding(T.s3)) {
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(T.s3)
        ) {
            /*
             * Narrower than the camera console's, because the cloud beside it says
             * the same thing better.
             *
             * The two views are the same returns: the polar plot is a plan and the
             * cloud is the room. Keeping the plan is worth it - it is read at a
             * glance and needs no orbiting - but it does not need the width of a
             * panel that has to be interpreted in three dimensions.
             */
            Panel("LIDAR", Modifier.weight(0.72f).fillMaxHeight(),
                  status = { Status(ringState.first, ringState.second) }) { body ->
                Column(body) {
                    RingPlot(ring, 0f,
                             Modifier.fillMaxWidth().weight(1f).padding(T.s2),
                             live = ringLive)
                    // The dead space under a circle in a tall card, used for the one
                    // thing about the ring that never changes.
                    Status(ring?.let { "${it.sectors} sectors" } ?: "",
                           T.textFaint,
                           Modifier.fillMaxWidth().padding(start = T.s4,
                                                           bottom = T.s3))
                }
            }

            /*
             * The cloud takes the slot the camera had, and the whole of it.
             *
             * No heading, the same as the picture it replaces: a room drawn in
             * three dimensions does not need to be told it is a lidar, and the
             * state has the corner. The panel is not 16:9 - the cloud has no
             * native ratio, and a projection has no reason to be letterboxed.
             */
            Panel("", Modifier.weight(2.38f).fillMaxHeight(), overlayTitle = true,
                  status = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Status(geometryState.first, geometryState.second)
                    Spacer(Modifier.width(T.s2))
                    Badge(rangeState.first.ifEmpty { "no depth" }, rangeState.second)
                }
            }) { body ->
                if (geometry != null && rangeFrame != null) {
                    CloudPanel(rangeFrame, geometry, body.fillMaxSize())
                } else {
                    // Not a black rectangle. Until the geometry arrives the range
                    // image can still be shown flat, which is the same data without
                    // the directions - and it makes the difference between "no
                    // sensor" and "no calibration yet" visible.
                    RangeView(rangeFrame, body.fillMaxSize())
                }
            }

            /*
             * The same height as its neighbours, said explicitly.
             *
             * A Row gives each child its own height unless told otherwise, and this
             * card's content is a few short lines - so it ended a hundred pixels
             * short of the two beside it and the row read as broken. The other two
             * only looked right because their content happened to fill.
             */
            Panel("TELEMETRY", Modifier.weight(0.85f).fillMaxHeight(),
                  status = { Status(rcState.first, rcState.second) }) { _ ->
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
                                    ChannelBar(channels[i], rcLive,
                                               Modifier.weight(1f).height(6.dp))
                                }
                            }
                        }
                    } else {
                        Status("no receiver", T.textFaint)
                    }
                    Spacer(Modifier.height(T.s4))
                    Label("CAN")
                    Spacer(Modifier.height(T.s1))
                    Status(canState)
                    Spacer(Modifier.height(T.s4))
                    Label("ATTITUDE")
                    Spacer(Modifier.height(T.s1))
                    /*
                     * The horizon takes the space the empty channel bars used to.
                     *
                     * It belongs in telemetry because it is the vehicle's state
                     * rather than the sensor's, and it belongs on the screen at all
                     * because the map cannot show it: a 2D grid assumes the scan
                     * was taken level, and a ramp makes that quietly untrue.
                     */
                    TiltView(roll, pitch, imuLive,
                             Modifier.fillMaxWidth().height(118.dp))
                }
            }
        }

        Spacer(Modifier.height(T.s3))

        /*
         * The heading over the content, so the map has the whole card.
         *
         * A titled row costs the map a line of height across the full width, and
         * "CONTROL" only occupies the left of it - above the joystick, where there
         * was nothing anyway. Overlaid, the map extends up to where the title sits
         * and the card gives back what it was spending on a label.
         */
        Panel("CONTROL", Modifier.weight(1f), overlayTitle = true,
              status = { Badge(linkState.first, linkState.second) }) { _ ->
            Row(
                Modifier.fillMaxSize().padding(T.s3),
                horizontalArrangement = Arrangement.spacedBy(T.s3),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(0.85f),
                       horizontalAlignment = Alignment.CenterHorizontally) {
                    Joystick(armed, Modifier.fillMaxWidth(0.9f).aspectRatio(1f)) { x, y ->
                        tele[0]?.x = x; tele[0]?.y = y
                    }
                    Spacer(Modifier.height(T.s2))
                    Label("TRANSLATE")
                }
                Column(Modifier.weight(2f)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = T.s1),
                        verticalAlignment = Alignment.CenterVertically) {
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
                }
                Column(Modifier.weight(0.85f),
                       horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("FLOOR")
                        Spacer(Modifier.size(T.s2))
                        for (n in 1..3) {
                            Chip("$n",
                                 emph = if (n == floor) Emph.Tinted else Emph.Quiet,
                                 colour = if (n == floor) T.accent else T.textDim) {
                                floor = n; confirmReset = false
                            }
                            if (n < 3) Spacer(Modifier.size(T.s1))
                        }
                    }
                    Spacer(Modifier.height(T.s2))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Chip("SAVE", emph = Emph.Quiet) {
                            goals[0]?.saveMap("floor$floor"); confirmReset = false
                        }
                        Spacer(Modifier.size(T.s2))
                        Chip("LOAD", emph = Emph.Quiet) {
                            goals[0]?.loadMap("floor$floor"); confirmReset = false
                        }
                        Spacer(Modifier.size(T.s2))
                        Chip(if (confirmReset) "SURE?" else "RESET",
                             emph = if (confirmReset) Emph.Filled else Emph.Quiet,
                             colour = T.bad) {
                            if (confirmReset) {
                                goals[0]?.resetMap(); confirmReset = false
                            } else confirmReset = true
                        }
                    }
                    Spacer(Modifier.height(T.s3))
                    if (waypoints.size > 1) {
                        Chip("ROUTE ${waypoints.size}", emph = Emph.Filled,
                             enabled = navLive) {
                            if (goals[0]?.route(waypoints.toList()) == true) {
                                goal = waypoints.last(); pending = null
                                waypoints.clear()
                            }
                        }
                        Spacer(Modifier.height(T.s2))
                    } else pending?.let { (px, py) ->
                        Chip("GO HERE", emph = Emph.Filled, enabled = navLive) {
                            if (goals[0]?.goal(px, py) == true) {
                                goal = px to py; pending = null; waypoints.clear()
                            }
                        }
                        Spacer(Modifier.height(T.s2))
                    }
                    // The same one control, saying whatever has to happen next.
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
                        enabled = !linked || teleopLive || armed
                    ) {
                        if (!linked) joinAp() else {
                            if (armed) {
                                tele[0]?.let { it.x = 0f; it.y = 0f; it.r = 0f }
                                goals[0]?.stop(); goal = null
                                pending = null; waypoints.clear()
                            }
                            armed = !armed
                        }
                    }
                    Spacer(Modifier.height(T.s2))
                    Status(tlState.first, tlState.second)
                    Spacer(Modifier.height(T.s3))
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
