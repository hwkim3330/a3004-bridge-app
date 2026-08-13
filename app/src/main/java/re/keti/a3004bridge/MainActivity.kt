package re.keti.a3004bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * One screen: camera, lidar ring, microphone, RC and CAN telemetry, and a
 * joystick that can drive something.
 *
 * Built with plain views rather than a UI framework because every panel here is
 * either a bitmap or a custom canvas, and neither benefits from one.
 *
 * The camera, lidar and control paths deliberately differ from the web
 * dashboard's:
 *   - the ring arrives as the binary UDP datagram, not polled JSON
 *   - audio goes through AudioTrack with a ~40 ms buffer instead of a browser's
 *     jitter buffer
 *   - control is UDP at 50 Hz, so there is no connection to exhaust
 */
class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var cam: ImageView
    private lateinit var camState: TextView
    private lateinit var ringView: RingView
    private lateinit var ringState: TextView
    private lateinit var stick: JoystickView
    private lateinit var yaw: YawView
    private lateinit var armBtn: Button
    private lateinit var tlState: TextView
    private lateinit var micBtn: Button
    private lateinit var micState: TextView
    private lateinit var rcState: TextView
    private lateinit var canState: TextView
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun panel(title: String): Pair<LinearLayout, TextView> {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val t = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#8695ab"))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val s = TextView(this).apply {
            setTextColor(Color.parseColor("#8695ab"))
            textSize = 11f
            gravity = Gravity.END
        }
        head.addView(t); head.addView(s)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141922"))
            addView(head)
        }
        return box to s
    }

    @SuppressLint("SetTextI18n")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0b0e13"))
        }

        // --- header with the router address ---
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.parseColor("#141922"))
        }
        bar.addView(TextView(this).apply {
            text = "A3004 Bridge"
            setTextColor(Color.parseColor("#e6ecf5"))
            textSize = 15f
        })
        hostEdit = EditText(this).apply {
            setText(host)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.parseColor("#e6ecf5"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(12) }
        }
        bar.addView(hostEdit)
        bar.addView(Button(this).apply {
            text = "연결"
            setOnClickListener {
                host = hostEdit.text.toString().trim().ifEmpty { "192.168.1.1" }
                getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                    .putString("host", host).apply()
                connect()
            }
        })
        root.addView(bar)

        // --- camera + lidar side by side ---
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 3f)
        }

        val (camBox, camS) = panel("USB CAMERA")
        camState = camS
        cam = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        camBox.addView(cam)
        val camCtl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(6))
        }
        micBtn = Button(this).apply {
            text = "🎤 마이크"
            setOnClickListener { toggleMic() }
        }
        micState = TextView(this).apply {
            setTextColor(Color.parseColor("#8695ab")); textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(8) }
        }
        camCtl.addView(micBtn); camCtl.addView(micState)
        camBox.addView(camCtl)
        camBox.layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            .apply { rightMargin = dp(6) }
        top.addView(camBox)

        val (ringBox, ringS) = panel("LIDAR RANGE RING")
        ringState = ringS
        ringView = RingView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        ringBox.addView(ringView)
        ringBox.layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
        top.addView(ringBox)
        root.addView(top)

        // --- teleop ---
        val (tlBox, tlS) = panel("TELEOP")
        tlState = tlS
        val tlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(10))
        }
        stick = JoystickView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(150))
            onMove = { x, y ->
                teleop?.x = x
                teleop?.y = y
            }
        }
        tlRow.addView(stick)

        // A separate rotation control, because the vehicle is holonomic: mecanum
        // wheels make translation and yaw independent, and a single two-axis
        // stick cannot express both.
        yaw = YawView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(56))
                .apply { leftMargin = dp(12) }
            onMove = { r -> teleop?.r = r }
        }
        tlRow.addView(yaw)
        val tlSide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        armBtn = Button(this).apply {
            text = "ARM"
            // Wide enough to hit without looking, not so wide it reads as a
            // banner: the default weight made it span the whole panel.
            layoutParams = LinearLayout.LayoutParams(dp(150), WRAP_CONTENT)
            setOnClickListener { setArmed(teleop?.armed != true) }
        }
        tlSide.addView(armBtn)
        tlSide.addView(TextView(this).apply {
            text = "왼쪽: 전후·좌우 이동 (메카넘). 오른쪽: 회전.\n" +
                   "손을 떼면 중립, 300 ms 끊기면 데드맨이 해제합니다."
            setTextColor(Color.parseColor("#8695ab")); textSize = 11f
            setPadding(0, dp(6), 0, 0)
        })
        tlRow.addView(tlSide)
        tlBox.addView(tlRow)
        tlBox.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { topMargin = dp(6) }
        root.addView(tlBox)

        // --- RC channels ---
        val (rcBox, rcS) = panel("RC (i-BUS)")
        rcState = rcS
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        var row: LinearLayout? = null
        for (i in 0 until 14) {
            if (i % 7 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                grid.addView(row)
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    .apply { rightMargin = dp(6) }
            }
            cell.addView(TextView(this).apply {
                text = "${i + 1}"
                setTextColor(Color.parseColor("#8695ab")); textSize = 10f
                width = dp(18)
            })
            val b = BarView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(8), 1f)
            }
            bars.add(b)
            cell.addView(b)
            row!!.addView(cell)
        }
        rcBox.addView(grid)
        rcBox.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { topMargin = dp(6) }
        root.addView(rcBox)

        // --- CAN ---
        val (canBox, canS) = panel("CAN")
        canState = canS
        canBox.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { topMargin = dp(6) }
        root.addView(canBox)

        return root
    }

    // ----------------------------------------------------------------- wiring

    private fun connect() {
        disconnect()

        mjpeg = MjpegReader("http://$host:8080/stream",
            onFrame = { bmp: Bitmap -> ui.post { cam.setImageBitmap(bmp) } },
            onState = { s -> ui.post { camState.text = s } }).also { it.start() }

        ringRx = RingReader(7602,
            onRing = { r -> ui.post { ringView.ring = r
                ringState.text = "${r.sectors} sectors · frame ${r.frameId}" +
                        if (r.alarm) " · ZONE" else "" } },
            onState = { s -> ui.post { ringState.text = s } }).also { it.start() }

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

    private fun setArmed(on: Boolean) {
        teleop?.armed = on
        stick.armed = on
        yaw.armed = on
        armBtn.text = if (on) "DISARM" else "ARM"
    }

    private fun toggleMic() {
        val p = pcm
        if (p != null) {
            p.halt(); pcm = null
            micBtn.text = "🎤 마이크"
            micState.text = ""
            return
        }
        pcm = PcmPlayer("http://$host:8082",
            onLevel = { /* level is drawn by the button tint only */ },
            onState = { s -> ui.post { micState.text = s } }).also { it.start() }
        micBtn.text = "🎤 끄기"
    }

    @SuppressLint("SetTextI18n")
    private fun onStatus(name: String, j: JSONObject?) {
        when (name) {
            "rc" -> {
                if (j == null) { rcState.text = "없음"; return }
                val link = j.optBoolean("link") && j.optLong("age_ms") < 1000
                rcState.text = (if (link) "link ok" else "LINK LOST") +
                        " · ${j.optLong("frames")} frames"
                val ch = j.optJSONArray("channels") ?: return
                for (i in bars.indices) {
                    if (i < ch.length()) {
                        bars[i].value = ch.optInt(i, 1500)
                        bars[i].live = link
                    }
                }
            }
            "can" -> {
                if (j == null) { canState.text = "없음"; return }
                val frames = j.optJSONObject("frames")
                val n = frames?.length() ?: 0
                canState.text = "${j.optString("interface")} · rx ${j.optLong("rx")}" +
                        " · $n ids" +
                        if (j.optBoolean("inject_allowed")) " · INJECT" else " · read-only"
            }
            "teleop" -> {
                if (j == null) { tlState.text = "teleop 없음"; return }
                val armed = j.optBoolean("armed")
                // If the daemon disarmed underneath us, reflect it. Unlike the
                // web page this cannot race a stale snapshot, because arming
                // here is continuous rather than a one-shot request.
                if (!armed && teleop?.armed == true &&
                    j.optLong("age_ms") >= j.optLong("timeout_ms", 300)) {
                    setArmed(false)
                }
                tlState.text = (if (armed) "ARMED" else "disarmed") +
                        " · ${j.optInt("rate_hz")} Hz" +
                        (if (j.optBoolean("forwarding")) " · forwarding"
                         else " · not forwarding") +
                        " · udp ${j.optLong("udp_commands")}"
            }
            "ouster" -> {
                // The ring itself comes over UDP; this is only for the header.
                if (j != null && ringView.ring == null) {
                    ringState.text = "${j.optInt("channels")}ch · " +
                            j.optString("profile")
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the app must not leave something armed. The deadman would
        // catch it, but not relying on the deadman is the point.
        setArmed(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}
