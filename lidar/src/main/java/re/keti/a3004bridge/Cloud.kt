package re.keti.a3004bridge

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin

/**
 * The point cloud, from the range image the router already serves.
 *
 * The full sensor stream is 64 Mbit/s and does not survive wifi - that was measured
 * on this bench, and it is why the raw relay goes to a wired PC. What does fit is
 * the range image: 32 rows by 360 columns of centimetres, 23 kB, which is the same
 * geometry at a coarser sampling and 69 kB/s at three frames a second. So the 3D
 * view here is the sensor's own picture reprojected, not a second copy of the
 * stream.
 */

/**
 * Where each measurement was pointing.
 *
 * A range image is distances and nothing else; the directions are calibration data,
 * per unit, and they live in the sensor's metadata. The tablet cannot ask the sensor
 * for them - it is on the router's access point and the sensor is on the LAN with no
 * route between - so the router fetches them once and serves them beside the image.
 */
class BeamGeometry(val altitudeDeg: FloatArray, val azimuthDeg: FloatArray) {
    companion object {
        fun fetch(sensorsBase: String): BeamGeometry? = runCatching {
            val text = httpText("$sensorsBase/beam.json")
            val alt = numbersAfter(text, "\"beam_altitude_angles\"")
            val az = numbersAfter(text, "\"beam_azimuth_angles\"")
            // Both or neither. A file with altitudes and no azimuths would draw a
            // cloud that looks right and is sheared by a few degrees, which is the
            // kind of wrong that gets believed.
            if (alt.isEmpty() || az.size != alt.size) null
            else BeamGeometry(alt, az)
        }.getOrNull()

        /**
         * The numbers in the array following `key`.
         *
         * Hand-parsed rather than pulled through JSONObject: this is one array of
         * doubles out of a file the router wrote from the sensor, and the whole
         * body is 2.4 kB. A parser would be more code for the same answer.
         */
        private fun numbersAfter(text: String, key: String): FloatArray {
            val k = text.indexOf(key)
            if (k < 0) return FloatArray(0)
            val open = text.indexOf('[', k)
            val close = text.indexOf(']', open + 1)
            if (open < 0 || close < 0) return FloatArray(0)
            return text.substring(open + 1, close)
                .split(',')
                .mapNotNull { it.trim().toFloatOrNull() }
                .toFloatArray()
        }
    }
}

/**
 * Range image plus geometry, out as x, y, z and range in metres.
 *
 * Four floats a point, interleaved, because that is what the vertex shader reads
 * and building a second array to hold the colour would double the traffic across
 * the GL boundary for a number already present.
 *
 * The frame is the sensor's: z up, and column zero pointing the way the ring's
 * sector zero points, with azimuth increasing clockwise. Keeping that agreement
 * means the cloud and the polar plot beside it describe the same room in the same
 * orientation, which is the only reason to have both on one screen.
 */
class Cloud(val buf: FloatBuffer, val count: Int, val reachM: Float)

fun cloudFrom(f: RangeFrame, g: BeamGeometry): Cloud {
    val step = if (f.step > 0) f.step else 1
    val buf = ByteBuffer.allocateDirect(f.rows * f.cols * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    var n = 0
    for (r in 0 until f.rows) {
        val ch = (r * step).coerceAtMost(g.altitudeDeg.size - 1)
        val phi = Math.toRadians(g.altitudeDeg[ch].toDouble())
        val cphi = cos(phi).toFloat()
        val sphi = sin(phi).toFloat()
        // The per-beam azimuth offset is a few degrees either way and is applied
        // here for completeness. Its sign is taken to match the altitude table's
        // order; at this sampling it moves a point by less than a column.
        val azOff = Math.toRadians(g.azimuthDeg[ch].toDouble()).toFloat()
        for (c in 0 until f.cols) {
            val cm = f.cm[r * f.cols + c]
            if (cm <= 0) continue
            val d = cm / 100f
            val theta = (Math.PI / 2.0 - 2.0 * Math.PI * c / f.cols).toFloat() + azOff
            buf.put(d * cphi * cos(theta))
            buf.put(d * cphi * sin(theta))
            buf.put(d * sphi)
            buf.put(d)
            n++
        }
    }
    buf.position(0)
    /*
     * How far the points actually reach, at the 90th percentile of range.
     *
     * The view is framed from this rather than from a fixed distance. A fixed
     * 14 m put a five-metre room in the middle of the panel as a small clump with
     * most of the box empty - the panel is the largest thing on the screen and the
     * data was using a fifth of it. The percentile rather than the maximum because
     * one return down a corridor should not zoom the room out to nothing.
     */
    val reach = if (n == 0) 6f else {
        val r = FloatArray(n)
        for (i in 0 until n) r[i] = buf.get(i * 4 + 3)
        r.sort()
        maxOf(1.5f, r[(n * 90) / 100])
    }
    buf.position(0)
    return Cloud(buf, n, reach)
}

private const val VERT = """#version 300 es
layout(location = 0) in vec4 aPosRange;
uniform mat4 uMvp;
uniform float uScale;      // metres at which the colour ramp saturates
uniform float uPointPx;
out float vT;
void main() {
    gl_Position = uMvp * vec4(aPosRange.xyz, 1.0);
    vT = clamp(aPosRange.w / uScale, 0.0, 1.0);
    // Nearer points draw larger, which is what makes a flat splat of dots read as
    // depth at all. Clamped so a point against the sensor does not become a disc.
    gl_PointSize = clamp(uPointPx / max(gl_Position.w, 0.4), 1.5, 9.0);
}
"""

private const val FRAG = """#version 300 es
precision mediump float;
in float vT;
out vec4 fragColor;
void main() {
    // Round points. A square dot at this size reads as a pixel artefact rather
    // than a measurement.
    vec2 d = gl_PointCoord - vec2(0.5);
    if (dot(d, d) > 0.25) discard;
    // Near warm, far cool - the same ramp the polar plot uses, so the two views
    // agree about which returns are close.
    vec3 near = vec3(1.00, 0.42, 0.21);
    vec3 far  = vec3(0.16, 0.47, 0.95);
    fragColor = vec4(mix(near, far, vT), 1.0);
}
"""

class CloudRenderer : GLSurfaceView.Renderer {

    /** Set from the network thread, consumed on the GL thread. */
    @Volatile private var pending: Cloud? = null
    /*
     * Framed from the data until somebody pinches.
     *
     * Auto-fit is right while nobody has an opinion and wrong the moment they do:
     * a view that keeps snapping back to its own idea of the right distance cannot
     * be examined. So the first pinch takes it over for good.
     */
    @Volatile var userZoomed = false
    @Volatile var scaleM = 10f
    @Volatile var yaw = 0.6f
    @Volatile var pitch = 0.35f
    @Volatile var dist = 14f
    @Volatile var points = 0
        private set

    private var prog = 0
    private var vbo = 0
    private var vao = 0
    private var count = 0
    private var uMvp = 0
    private var uScale = 0
    private var uPointPx = 0
    private val mvp = FloatArray(16)
    private val view = FloatArray(16)
    private val proj = FloatArray(16)

    fun submit(c: Cloud) {
        pending = c
        if (!userZoomed && c.count > 0) {
            // Far enough back that the reach fits the shorter side of a 42-degree
            // field, with a little air. Eased rather than jumped: a view that
            // lurches every time a doorway opens is unreadable.
            val want = (c.reachM * 2.1f).coerceIn(2.5f, 90f)
            dist += (want - dist) * 0.25f
            scaleM = c.reachM
        }
    }

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?,
                                  cfg: javax.microedition.khronos.egl.EGLConfig?) {
        GLES30.glClearColor(0.055f, 0.055f, 0.07f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        prog = link(VERT, FRAG)
        uMvp = GLES30.glGetUniformLocation(prog, "uMvp")
        uScale = GLES30.glGetUniformLocation(prog, "uScale")
        uPointPx = GLES30.glGetUniformLocation(prog, "uPointPx")
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?,
                                  w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        val a = if (h > 0) w.toFloat() / h else 1f
        Matrix.perspectiveM(proj, 0, 42f, a, 0.15f, 220f)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        pending?.let { c ->
            val buf = c.buf
            val n = c.count
            pending = null
            count = n
            points = n
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            // Reuploaded whole rather than mapped and patched: a frame is at most
            // 11 520 points, 184 kB, and arrives three times a second. The
            // bookkeeping to do better would cost more than it saves.
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 16, buf,
                                GLES30.GL_DYNAMIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0)
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (count == 0) return

        // Orbit around the sensor, which sits at the origin because that is where
        // the sensor's own frame puts it.
        val cx = dist * cos(pitch) * cos(yaw)
        val cy = dist * cos(pitch) * sin(yaw)
        val cz = dist * sin(pitch)
        Matrix.setLookAtM(view, 0, cx, cy, cz, 0f, 0f, 0f, 0f, 0f, 1f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(uScale, scaleM)
        GLES30.glUniform1f(uPointPx, 26f)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val id = GLES30.glCreateShader(type)
            GLES30.glShaderSource(id, src)
            GLES30.glCompileShader(id)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, ok, 0)
            // Thrown rather than logged. A shader that did not compile draws
            // nothing, and an empty black panel with no message is the hardest
            // possible version of this to diagnose later.
            if (ok[0] == 0) throw RuntimeException(
                "shader: " + GLES30.glGetShaderInfoLog(id))
            return id
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException(
            "link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }
}

/** A GLSurfaceView that orbits under one finger and zooms under two. */
class CloudView(ctx: Context) : GLSurfaceView(ctx) {
    val renderer = CloudRenderer()
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        // Only when something changed. Continuous rendering would hold a core of
        // the tablet awake for a cloud that arrives three times a second.
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun submit(c: Cloud) {
        renderer.submit(c)
        requestRender()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_POINTER_DOWN -> lastSpan = span(e)
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val s = span(e)
                    if (lastSpan > 1f && s > 1f) {
                        renderer.userZoomed = true
                        renderer.dist = (renderer.dist * lastSpan / s)
                            .coerceIn(1.5f, 90f)
                    }
                    lastSpan = s
                } else {
                    renderer.yaw -= (e.x - lastX) * 0.006f
                    renderer.pitch = (renderer.pitch + (e.y - lastY) * 0.005f)
                        // Short of straight down, so the view never flips through
                        // the pole and leaves the room upside down.
                        .coerceIn(-1.45f, 1.45f)
                    lastX = e.x; lastY = e.y
                }
                requestRender()
            }
        }
        return true
    }

    private fun span(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return kotlin.math.hypot(dx, dy)
    }
}

@Composable
fun CloudPanel(frame: RangeFrame?, geometry: BeamGeometry?, modifier: Modifier = Modifier) {
    val holder = remember { arrayOfNulls<CloudView>(1) }
    /*
     * Convert once per frame, not once per recomposition.
     *
     * AndroidView's update block runs on every recomposition, and this screen
     * recomposes whenever any status string changes - which is several times a
     * second from four pollers. So a 64 x 512 reprojection, 32 768 cells of
     * trigonometry, was running far more often than the three frames a second that
     * actually arrive. Measured before: the app held a whole core of eight.
     *
     * The frame object is the identity to compare: RangeReader hands over a new one
     * per poll, so "same reference" means "already drawn".
     */
    val last = remember { arrayOfNulls<Any>(1) }
    DisposableEffect(Unit) { onDispose { holder[0] = null; last[0] = null } }
    AndroidView(
        factory = { ctx -> CloudView(ctx).also { holder[0] = it } },
        modifier = modifier,
        update = { v ->
            val f = frame
            val g = geometry
            if (f != null && g != null && last[0] !== f) {
                last[0] = f
                v.submit(cloudFrom(f, g))
            }
        }
    )
}

/** Fetch the geometry once, in the background, and hand it over when it arrives. */
class GeometryFetcher(
    private val sensorsBase: String,
    private val onGeometry: (BeamGeometry) -> Unit,
    private val onState: (String) -> Unit,
) : Thread("beam") {
    private val stop = AtomicBoolean(false)
    fun halt() { stop.set(true); interrupt() }

    override fun run() {
        var tries = 0
        while (!stop.get()) {
            val g = BeamGeometry.fetch(sensorsBase)
            if (g != null) {
                onState("${g.altitudeDeg.size} beams")
                onGeometry(g)
                return
            }
            tries++
            // Said once, not every attempt: the router fetches this from the
            // sensor itself and may still be retrying while the sensor boots.
            if (tries == 1) onState("no beam geometry")
            sleepQuietly(3000)
        }
    }
}
