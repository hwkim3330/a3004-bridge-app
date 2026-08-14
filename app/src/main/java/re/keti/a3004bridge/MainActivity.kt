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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
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

    var camState by remember { mutableStateOf("대기" to T.textFaint) }
    var linkState by remember { mutableStateOf("연결 중" to T.warn) }
    var ringState by remember { mutableStateOf("") }
    var rcState by remember { mutableStateOf("rc 없음" to T.textDim) }
    var canState by remember { mutableStateOf("대기") }
    var tlState by remember { mutableStateOf("teleop 없음" to T.textDim) }
    var micState by remember { mutableStateOf("") }
    var mapState by remember { mutableStateOf("지도 없음") }
    var navState by remember { mutableStateOf("항법 없음" to T.textDim) }

    var armed by remember { mutableStateOf(false) }
    var micOn by remember { mutableStateOf(false) }
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

        val ringRx = RingReader(Wire.RING_PORT,
            onRing = { r ->
                ring = r
                ringState = "${r.sectors} sectors · frame ${r.frameId}" +
                        if (r.alarm) "  ZONE" else ""
            },
            onState = { s -> ringState = s }).also { it.start() }

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
                    rcState = "rc 없음" to T.textDim; rcLive = false
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
                "can" -> canState = if (j == null) "can 없음" else
                    "${j.optString("interface")} · rx ${j.optLong("rx")}" +
                            " · ${j.optJSONObject("frames")?.length() ?: 0} ids" +
                            if (j.optBoolean("inject_allowed")) " · INJECT"
                            else " · read-only"
                "navigate" -> navState = if (j == null) "항법 없음" to T.textDim else {
                    val st = j.optString("state")
                    navDriving = st == "driving"
                    val fault = j.optString("fault").takeIf { it.isNotEmpty() && it != "null" }
                    val rem = j.optInt("remaining_cm")
                    when (st) {
                        "driving" -> "주행 중 · ${rem} cm 남음" to T.good
                        "arrived" -> "도착" to T.good
                        "idle" -> "대기 · 점수 ${j.optInt("match_score_pct")}%" to T.textDim
                        else -> (fault ?: "정지") to T.bad
                    }
                }
                "teleop" -> if (j == null) {
                    tlState = "teleop 없음" to T.textDim
                } else {
                    val a = j.optBoolean("armed")
                    // If the daemon disarmed underneath us, reflect it.
                    if (!a && armed &&
                        j.optLong("age_ms") >= j.optLong("timeout_ms", 300)) {
                        armed = false
                    }
                    tlState = ((if (a) "ARMED" else "disarmed") +
                            " · ${j.optInt("rate_hz")} Hz" +
                            " · udp ${j.optLong("udp_commands")}") to
                            (if (a) T.good else T.textDim)
                }
                "ouster" -> if (j != null && ring == null)
                    ringState = "${j.optInt("channels")}ch · " + j.optString("profile")
            }
        }.also { it.start() }

        onPauseOrDispose {
            // Leaving must not leave something armed. The deadman would catch it;
            // not relying on the deadman is the point.
            armed = false
            sender.armed = false
            mjpeg.halt(); ringRx.halt(); status.halt(); sender.halt()
            mapRx.halt()
            pcm[0]?.halt(); pcm[0] = null
            tele[0] = null
            goals[0] = null
            micOn = false; micState = ""
            frame = null
            camState = "대기" to T.textFaint
            linkState = "연결 중" to T.warn
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
            .padding(start = T.s4, end = T.s4, top = T.s3, bottom = T.s4)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("A3004 Bridge", style = T.title)
            Spacer(Modifier.width(T.s3))
            Badge(linkState.first, linkState.second)
            Spacer(Modifier.weight(1f))
            HostField(hostEdit) { hostEdit = it }
            Spacer(Modifier.width(T.s2))
            Chip(
                when (wifiNote) {
                    1 -> "AP 요청 중"
                    2 -> "AP 연결됨"
                    3 -> "AP 끊김"
                    4 -> "wifi 설정에서"
                    5 -> "AP 안 됨"
                    else -> "AP 접속"
                }
                , emph = Emph.Tinted
            ) {
                // Binds only this app's sockets to the router's AP. The system
                // keeps its own default network, which is what stops Android
                // wandering off an access point that has no internet - and makes
                // the router's 192.168.1.0/24 unambiguous even where the building
                // network overlaps it.
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
            Spacer(Modifier.width(T.s2))
            Chip("연결") {
                val h = hostEdit.trim().ifEmpty { DEFAULT_HOST }
                prefs.edit().putString(KEY_HOST, h).apply()
                host = h            // re-keys the effect, so the streams restart
            }
        }

        Spacer(Modifier.height(T.s3))

        Row(Modifier.weight(1f)) {
            Panel(
                "카메라", Modifier.weight(1.2f),
                status = { Badge(camState.first, camState.second) }
            ) { body ->
                Column(body) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = T.s3)
                            .clip(T.rSm)
                            .background(T.videoBg)
                            .border(1.dp, T.hairline, T.rSm),
                        contentAlignment = Alignment.Center
                    ) {
                        if (frame != null) {
                            Image(
                                bitmap = frame!!,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            EmptyState("카메라 대기 중", onDark = true,
                                modifier = Modifier.fillMaxSize())
                        }
                    }
                    Row(
                        Modifier.padding(T.s3),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Chip(if (micOn) "마이크 끄기" else "마이크 켜기") {
                            if (micOn) {
                                pcm[0]?.halt(); pcm[0] = null
                                micOn = false; micState = ""
                            } else {
                                pcm[0] = PcmPlayer(Endpoints(host).audioBase,
                                    onLevel = {},
                                    onState = { s -> micState = s })
                                    .also { it.start() }
                                micOn = true
                            }
                        }
                        Spacer(Modifier.width(T.s3))
                        Status(micState)
                    }
                }
            }

            Spacer(Modifier.width(T.s3))

            Panel(
                "지도",
                Modifier.weight(1.4f),
                status = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Status(navState.first, navState.second)
                        Spacer(Modifier.size(T.s2))
                        pending?.let { (px, py) ->
                            Chip("여기로", emph = Emph.Filled) {
                                if (goals[0]?.goal(px, py) == true) {
                                    goal = px to py
                                    pending = null
                                } else {
                                    navState = "목적지 전송 실패" to T.bad
                                }
                            }
                            Spacer(Modifier.size(T.s2))
                            Chip("취소", emph = Emph.Quiet) { pending = null }
                        }
                        if (pending == null && (goal != null || navDriving))
                            Chip("정지", emph = Emph.Tinted, colour = T.bad) {
                                goals[0]?.stop(); goal = null
                            }
                    }
                }
            ) { body ->
                Column(body) {
                    // A tap proposes; the chip above sends. Nothing here can
                    // start a vehicle on its own either way: navigate is off by
                    // default, has to be pointed at agx-cmd, and can-bridge
                    // still needs allow_inject.
                    MapPlot(
                        map, goal, pending,
                        vehicleColour = when {
                            navState.second == T.bad -> T.bad
                            navDriving -> T.good
                            else -> T.textDim
                        },
                        ring = ring,
                        modifier = Modifier.weight(1f),
                    ) { x, y -> pending = x to y }
                    Status(
                        when {
                            pending != null -> "%s · 여기로 보낼까요? %.1f, %.1f m"
                                .format(mapState, pending!!.first / 100f,
                                        pending!!.second / 100f)
                            goal != null -> "%s · 목적지 %.1f, %.1f m"
                                .format(mapState, goal!!.first / 100f,
                                        goal!!.second / 100f)
                            else -> "$mapState · 탭해서 목적지 지정"
                        },
                        modifier = Modifier.padding(
                            start = T.s5, end = T.s5, top = T.s2, bottom = T.s3)
                    )
                }
            }

            Column(Modifier.weight(0.95f)) {
                Panel(
                    "라이다", Modifier.weight(1f),
                    status = { Status(ringState) }
                ) { body ->
                    // 0 means scale to the data: a sensor indoors puts everything inside
                    // the first ring of a fixed 30 m plot.
                    RingPlot(ring, 0f, body.fillMaxWidth().padding(T.s2))
                }
                Spacer(Modifier.height(T.s3))
                Panel(
                    "텔레메트리",
                    status = { Status(rcState.first, rcState.second) }
                ) { _ ->
                    Column(Modifier.padding(start = T.s4, end = T.s4, bottom = T.s3)) {
                        Label("RC · i-BUS")
                        Spacer(Modifier.height(T.s2))
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
                        Spacer(Modifier.height(T.s4))
                        Label("CAN")
                        Spacer(Modifier.height(T.s1))
                        Status(canState)
                    }
                }
            }
        }

        Spacer(Modifier.height(T.s3))

        Panel("조작", status = { Status(tlState.first, tlState.second) }) { _ ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = T.s4, end = T.s4, top = T.s2, bottom = T.s4),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Joystick(armed, Modifier.size(132.dp)) { x, y ->
                        tele[0]?.x = x; tele[0]?.y = y
                    }
                    Spacer(Modifier.height(T.s2))
                    Label("이동")
                }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Chip(
                        if (armed) "DISARM" else "ARM",
                        Modifier.width(200.dp),
                        emph = Emph.Filled,
                        colour = if (armed) T.bad else T.accent,
                        big = true
                    ) { armed = !armed }
                    Spacer(Modifier.height(T.s2))
                    Text(
                        "손을 떼면 중립. 300 ms 끊기면 데드맨이 해제합니다.",
                        style = T.body.copy(color = if (armed) T.good else T.textDim),
                        modifier = Modifier.width(320.dp)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    YawSlider(armed, Modifier.width(150.dp).height(56.dp)) { r ->
                        tele[0]?.r = r
                    }
                    Spacer(Modifier.height(T.s2))
                    Label("회전")
                }
            }
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
