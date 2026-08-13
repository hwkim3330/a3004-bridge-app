package re.keti.a3004bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import re.keti.a3004bridge.D.badge
import re.keti.a3004bridge.D.caption
import re.keti.a3004bridge.D.dp
import re.keti.a3004bridge.D.label
import re.keti.a3004bridge.D.mono
import re.keti.a3004bridge.D.panel
import re.keti.a3004bridge.D.pill
import re.keti.a3004bridge.D.roundRect
import re.keti.a3004bridge.D.tappable
import re.keti.a3004bridge.D.value
import re.keti.a3004bridge.D.withAlpha

/**
 * One screen: camera, lidar ring, microphone, RC and CAN telemetry, and the
 * controls that can drive something.
 *
 * Plain views rather than a UI framework, because every panel here is either a
 * bitmap or a custom canvas and neither benefits from one. The visual language
 * lives in Design.kt so the decisions are made once.
 *
 * The layout assumes a tablet held in two hands: the camera is the largest thing
 * and sits where the eyes go, and the two controls are in the bottom corners
 * where thumbs already are, with arming between them so it cannot be hit by the
 * hand that is steering.
 *
 * The camera, lidar and control paths deliberately differ from the web
 * dashboard's:
 *   - the ring arrives as the binary UDP datagram, not polled JSON
 *   - audio goes through AudioTrack with a ~40 ms buffer, not a browser's
 *   - control is UDP at 50 Hz, so there is no connection to exhaust
 */
class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var cam: ImageView
    private lateinit var camBadge: D.Badge
    private lateinit var linkBadge: D.Badge
    private lateinit var ringView: RingView
    private lateinit var ringStatus: TextView
    private lateinit var stick: JoystickView
    private lateinit var yaw: YawView
    private lateinit var armBtn: TextView
    private lateinit var armHint: TextView
    private lateinit var tlStatus: TextView
    private lateinit var micBtn: TextView
    private lateinit var micStatus: TextView
    private lateinit var rcStatus: TextView
    private lateinit var canStatus: TextView
    private lateinit var hostEdit: EditText
    private val bars = ArrayList<BarView>()

    private var mjpeg: MjpegReader? = null
    private var pcm: PcmPlayer? = null
    private var ringRx: RingReader? = null
    private var teleop: TeleopSender? = null
    private var status: StatusPoller? = null

    private var host = "192.168.1.1"

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        // Driving something while the screen sleeps is not a thing we want to
        // make possible.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        host = getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getString("host", "192.168.1.1") ?: "192.168.1.1"

        setContentView(buildUi())
        connect()
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(D.bg)
            setPadding(dp(D.s4), dp(D.s3), dp(D.s4), dp(D.s4))
        }

        root.addView(topBar(), lp(MATCH_PARENT, WRAP_CONTENT))

        // Camera left, sensors right. The camera gets the larger share because it
        // is the thing being looked at; the ring is glanced at.
        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mid.addView(cameraPanel(), lp(0, MATCH_PARENT, 1.55f).apply { rightMargin = dp(D.s3) })
        mid.addView(sensorColumn(), lp(0, MATCH_PARENT, 1f))
        root.addView(mid, lp(MATCH_PARENT, 0, 1f).apply { topMargin = dp(D.s3) })

        root.addView(controlBar(), lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(D.s3) })
        return root
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    /** Title, live-link badge, and the router address. */
    private fun topBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = "A3004 Bridge"
            setTextColor(D.text)
            textSize = 19f
            letterSpacing = -0.01f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        linkBadge = badge()
        linkBadge.set("연결 중", D.textFaint)
        row.addView(linkBadge.root, lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(D.s3) })

        row.addView(View(this), lp(0, 1, 1f))     // spacer

        hostEdit = EditText(this).apply {
            setText(host)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(D.text)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            background = roundRect(D.surfaceHi, D.rSm, D.hairline)
            setPadding(dp(D.s3), dp(D.s2), dp(D.s3), dp(D.s2))
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(hostEdit, lp(dp(150), WRAP_CONTENT))

        row.addView(button("연결") {
            host = hostEdit.text.toString().trim().ifEmpty { "192.168.1.1" }
            getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putString("host", host).apply()
            connect()
        }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(D.s2) })
        return row
    }

    /** A text button that looks like a control rather than a system default. */
    private fun button(text: String, onTap: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(D.text)
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(D.s4), dp(D.s2), dp(D.s4), dp(D.s2))
        background = tappable(roundRect(D.surfaceHi, D.rSm, D.hairline))
        isClickable = true
        setOnClickListener { onTap() }
    }

    private fun cameraPanel(): View {
        val p = panel("USB CAMERA")
        p.fillBody()
        camBadge = badge()
        camBadge.set("대기", D.textFaint)
        // The badge belongs beside the label, so it replaces the plain status slot.
        (p.status.parent as LinearLayout).addView(
            camBadge.root, lp(WRAP_CONTENT, WRAP_CONTENT))

        cam = ImageView(this).apply {
            setBackgroundColor(D.bg)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val frame = LinearLayout(this).apply {
            background = roundRect(D.bg, D.rSm, D.hairline)
            clipToOutline = true
            addView(cam, lp(MATCH_PARENT, MATCH_PARENT))
        }
        p.body.addView(frame, lp(MATCH_PARENT, 0, 1f).apply {
            leftMargin = dp(D.s3); rightMargin = dp(D.s3)
        })

        micStatus = mono(size = 11f)
        micBtn = button("마이크 켜기") { toggleMic() }
        val ctl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(D.s3), dp(D.s3), dp(D.s3), dp(D.s3))
            addView(micBtn, lp(WRAP_CONTENT, WRAP_CONTENT))
            addView(micStatus, lp(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(D.s3) })
        }
        p.body.addView(ctl, lp(MATCH_PARENT, WRAP_CONTENT))
        return p.root
    }

    private fun sensorColumn(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val lidar = panel("LIDAR RANGE RING")
        lidar.fillBody()
        ringStatus = lidar.status
        ringView = RingView(this)
        lidar.body.addView(ringView, lp(MATCH_PARENT, 0, 1f).apply {
            leftMargin = dp(D.s2); rightMargin = dp(D.s2); bottomMargin = dp(D.s2)
        })
        col.addView(lidar.root, lp(MATCH_PARENT, 0, 1f))

        // RC and CAN are read-only telemetry, so they share one panel and stay
        // visually quieter than anything interactive.
        val tele = panel("TELEMETRY")
        rcStatus = tele.status
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(D.s4), 0, dp(D.s4), dp(D.s3))
        }
        body.addView(label("RC · i-BUS"), lp(MATCH_PARENT, WRAP_CONTENT))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        for (i in 0 until 14) {
            if (i % 7 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(D.s1) })
            }
            val b = BarView(this)
            bars.add(b)
            row!!.addView(b, lp(0, dp(6), 1f).apply { rightMargin = dp(D.s1) })
        }
        body.addView(grid, lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(D.s2) })

        body.addView(label("CAN"), lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(D.s4) })
        canStatus = mono("대기", size = 11f)
        body.addView(canStatus, lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(D.s1) })

        tele.body.addView(body, lp(MATCH_PARENT, WRAP_CONTENT))
        col.addView(tele.root, lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(D.s3) })
        return col
    }

    /**
     * Translate on the left, rotate on the right, arm in the middle.
     *
     * The vehicle is a SCOUT MINI Omni: mecanum wheels, so translation and
     * rotation are independent and one two-axis stick cannot express both.
     */
    private fun controlBar(): View {
        val p = panel("TELEOP")
        tlStatus = p.status

        stick = JoystickView(this).apply {
            onMove = { x, y -> teleop?.x = x; teleop?.y = y }
        }
        yaw = YawView(this).apply {
            onMove = { r -> teleop?.r = r }
        }

        armBtn = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.08f
            isClickable = true
            setPadding(dp(D.s5), dp(D.s4), dp(D.s5), dp(D.s4))
            setOnClickListener { setArmed(teleop?.armed != true) }
        }
        armHint = caption(
            "손을 떼면 중립. 300 ms 끊기면 데드맨이 해제합니다."
        ).apply { gravity = Gravity.CENTER }

        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(armBtn, lp(dp(190), WRAP_CONTENT))
            addView(armHint, lp(dp(320), WRAP_CONTENT).apply { topMargin = dp(D.s2) })
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(D.s4), dp(D.s2), dp(D.s4), dp(D.s4))
            addView(stickCell("이동", stick, dp(132), dp(132)), lp(WRAP_CONTENT, WRAP_CONTENT))
            addView(centre, lp(0, WRAP_CONTENT, 1f))
            addView(stickCell("회전", yaw, dp(150), dp(56)), lp(WRAP_CONTENT, WRAP_CONTENT))
        }
        p.body.addView(row, lp(MATCH_PARENT, WRAP_CONTENT))
        return p.root
    }

    private fun stickCell(name: String, v: View, w: Int, h: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(v, lp(w, h))
            addView(label(name), lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(D.s2) })
        }

    // ----------------------------------------------------------------- wiring

    private fun connect() {
        disconnect()
        linkBadge.set("연결 중", D.warn)

        mjpeg = MjpegReader("http://$host:8080/stream",
            onFrame = { bmp: Bitmap ->
                ui.post {
                    cam.setImageBitmap(bmp)
                    camBadge.set("live", D.good)
                    linkBadge.set(host, D.good)
                }
            },
            onState = { s -> ui.post { camBadge.set(s, D.bad) } }).also { it.start() }

        ringRx = RingReader(7602,
            onRing = { r ->
                ui.post {
                    ringView.ring = r
                    ringStatus.text = "${r.sectors} sectors · frame ${r.frameId}" +
                            if (r.alarm) "  ZONE" else ""
                    ringStatus.setTextColor(if (r.alarm) D.bad else D.textDim)
                }
            },
            onState = { s -> ui.post { ringStatus.text = s } }).also { it.start() }

        teleop = TeleopSender(host, 7721, 50).also { it.start() }
        setArmed(false)

        status = StatusPoller("http://$host/sensors", 400) { name, j ->
            ui.post { onStatus(name, j) }
        }.also { it.start() }
    }

    private fun disconnect() {
        mjpeg?.halt(); mjpeg = null
        ringRx?.halt(); ringRx = null
        pcm?.halt(); pcm = null
        teleop?.halt(); teleop = null
        status?.halt(); status = null
    }

    /**
     * Arming is the one place the interface changes colour wholesale, because it
     * is the one place where a mistaken tap moves a vehicle.
     */
    @SuppressLint("SetTextI18n")
    private fun setArmed(on: Boolean) {
        teleop?.armed = on
        stick.armed = on
        yaw.armed = on
        armBtn.text = if (on) "DISARM" else "ARM"
        armBtn.setTextColor(if (on) D.bg else D.text)
        armBtn.background = tappable(
            roundRect(if (on) D.good else D.surfaceHi, D.rLg,
                if (on) 0 else D.hairline),
            if (on) D.bad else D.accent)
        armHint.setTextColor(if (on) D.good else D.textDim)
    }

    @SuppressLint("SetTextI18n")
    private fun toggleMic() {
        val p = pcm
        if (p != null) {
            p.halt(); pcm = null
            micBtn.text = "마이크 켜기"
            micStatus.text = ""
            return
        }
        pcm = PcmPlayer("http://$host:8082",
            onLevel = { },
            onState = { s -> ui.post { micStatus.text = s } }).also { it.start() }
        micBtn.text = "마이크 끄기"
    }

    @SuppressLint("SetTextI18n")
    private fun onStatus(name: String, j: JSONObject?) {
        when (name) {
            "rc" -> {
                if (j == null) { rcStatus.text = "rc 없음"; return }
                val link = j.optBoolean("link") && j.optLong("age_ms") < 1000
                rcStatus.text = (if (link) "link ok" else "LINK LOST") +
                        " · ${j.optLong("frames")}"
                rcStatus.setTextColor(if (link) D.textDim else D.bad)
                val ch = j.optJSONArray("channels") ?: return
                for (i in bars.indices) {
                    if (i < ch.length()) {
                        bars[i].value = ch.optInt(i, 1500)
                        bars[i].live = link
                    }
                }
            }
            "can" -> {
                if (j == null) { canStatus.text = "can 없음"; return }
                val frames = j.optJSONObject("frames")
                canStatus.text = "${j.optString("interface")} · rx ${j.optLong("rx")}" +
                        " · ${frames?.length() ?: 0} ids" +
                        if (j.optBoolean("inject_allowed")) " · INJECT" else " · read-only"
            }
            "teleop" -> {
                if (j == null) { tlStatus.text = "teleop 없음"; return }
                val armed = j.optBoolean("armed")
                // If the daemon disarmed underneath us, reflect it. Unlike the web
                // page this cannot race a stale snapshot, because arming here is
                // continuous rather than a one-shot request.
                if (!armed && teleop?.armed == true &&
                    j.optLong("age_ms") >= j.optLong("timeout_ms", 300)) {
                    setArmed(false)
                }
                tlStatus.text = (if (armed) "ARMED" else "disarmed") +
                        " · ${j.optInt("rate_hz")} Hz · udp ${j.optLong("udp_commands")}"
                tlStatus.setTextColor(if (armed) D.good else D.textDim)
            }
            "ouster" -> {
                if (j != null && ringView.ring == null) {
                    ringStatus.text = "${j.optInt("channels")}ch · " + j.optString("profile")
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the app must not leave something armed. The deadman would catch
        // it, but not relying on the deadman is the point.
        setArmed(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}
