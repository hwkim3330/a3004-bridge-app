package re.keti.a3004bridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Polar plot of the lidar range ring. */
class RingView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var ring: Ring? = null
        set(v) { field = v; postInvalidateOnAnimation() }
    var maxRange = 30f
        set(v) { field = v; postInvalidateOnAnimation() }

    private val grid = Paint().apply {
        color = Color.parseColor("#1d2534"); style = Paint.Style.STROKE
        strokeWidth = 2f; isAntiAlias = true
    }
    private val label = Paint().apply {
        color = Color.parseColor("#3d4859"); textSize = 26f; isAntiAlias = true
    }
    private val dot = Paint().apply { isAntiAlias = true }
    private val centre = Paint().apply {
        color = Color.parseColor("#e6ecf5"); isAntiAlias = true
    }
    private val hint = Paint().apply {
        color = Color.parseColor("#8695ab"); textSize = 30f; isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.46f

        val step = when {
            maxRange <= 10f -> 2
            maxRange <= 30f -> 5
            maxRange <= 60f -> 10
            else -> 20
        }
        var m = step
        while (m <= maxRange) {
            val rr = r * m / maxRange
            c.drawCircle(cx, cy, rr, grid)
            c.drawText("${m}m", cx + 6f, cy - rr - 6f, label)
            m += step
        }
        c.drawLine(cx - r, cy, cx + r, cy, grid)
        c.drawLine(cx, cy - r, cx, cy + r, grid)

        val g = ring
        if (g == null) {
            c.drawText("lidar 데이터 없음", cx, cy + r + 42f, hint)
            return
        }

        val n = g.sectors
        val dotR = maxOf(3f, (r * 2f * Math.PI.toFloat() / n) * 0.42f)
        for (i in 0 until n) {
            val v = g.cm[i]
            if (v < 0) continue
            val metres = v / 100f
            if (metres > maxRange) continue
            // sector 0 points up, azimuth increases clockwise
            val a = (i.toFloat() / n) * 2f * Math.PI.toFloat() - Math.PI.toFloat() / 2f
            val rr = r * metres / maxRange
            // near = red, far = blue, so something approaching is obvious
            val t = (metres / maxRange).coerceIn(0f, 1f)
            dot.color = Color.HSVToColor(floatArrayOf(8f + t * 210f, 0.85f, 0.95f))
            c.drawCircle(cx + cos(a) * rr, cy + sin(a) * rr, dotR, dot)
        }
        c.drawCircle(cx, cy, 6f, centre)
    }
}

/**
 * Touch joystick. Reports normalised x/y with up as positive y.
 *
 * Releasing always re-centres. That is not a convenience: a stick that keeps its
 * deflection after your finger leaves is a stuck throttle.
 */
class JoystickView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var onMove: ((Float, Float) -> Unit)? = null
    var armed = false
        set(v) { field = v; invalidate() }

    private var kx = 0f
    private var ky = 0f

    private val base = Paint().apply {
        color = Color.parseColor("#10151d"); isAntiAlias = true
    }
    private val rim = Paint().apply {
        color = Color.parseColor("#232b38"); style = Paint.Style.STROKE
        strokeWidth = 3f; isAntiAlias = true
    }
    private val cross = Paint().apply {
        color = Color.parseColor("#1d2534"); strokeWidth = 2f; isAntiAlias = true
    }
    private val knob = Paint().apply { isAntiAlias = true }

    private fun radius() = min(width, height) / 2f * 0.92f
    private fun knobR() = radius() * 0.31f
    private fun limit() = radius() - knobR()

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = radius()

        c.drawCircle(cx, cy, r, base)
        c.drawCircle(cx, cy, r, rim)
        c.drawLine(cx - r * 0.8f, cy, cx + r * 0.8f, cy, cross)
        c.drawLine(cx, cy - r * 0.8f, cx, cy + r * 0.8f, cross)

        knob.color = if (armed) Color.parseColor("#34d399")
                     else Color.parseColor("#2b3547")
        c.drawCircle(cx + kx, cy + ky, knobR(), knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var dx = e.x - width / 2f
                var dy = e.y - height / 2f
                val lim = limit()
                val d = hypot(dx, dy)
                if (d > lim) { dx *= lim / d; dy *= lim / d }
                kx = dx; ky = dy
                onMove?.invoke(dx / lim, -dy / lim)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                kx = 0f; ky = 0f
                onMove?.invoke(0f, 0f)
                invalidate()
            }
        }
        return true
    }
}

/**
 * Horizontal-only stick for yaw.
 *
 * The vehicle is a SCOUT MINI Omni: mecanum wheels, so translation and rotation
 * are independent. Putting yaw on the same two-axis stick as strafe would throw
 * away a degree of freedom the machine actually has.
 */
class YawView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var onMove: ((Float) -> Unit)? = null
    var armed = false
        set(v) { field = v; invalidate() }

    private var kx = 0f

    private val base = Paint().apply {
        color = Color.parseColor("#10151d"); isAntiAlias = true
    }
    private val rim = Paint().apply {
        color = Color.parseColor("#232b38"); style = Paint.Style.STROKE
        strokeWidth = 3f; isAntiAlias = true
    }
    private val tick = Paint().apply { color = Color.parseColor("#3d4859") }
    private val knob = Paint().apply { isAntiAlias = true }

    private fun knobR() = height / 2f * 0.86f
    private fun limit() = width / 2f - knobR()

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val r = height / 2f

        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, base)
        c.drawRoundRect(1.5f, 1.5f, width - 1.5f, height - 1.5f, r, r, rim)
        c.drawRect(width / 2f - 1f, height * 0.15f,
                   width / 2f + 1f, height * 0.85f, tick)

        knob.color = if (armed) Color.parseColor("#34d399")
                     else Color.parseColor("#2b3547")
        c.drawCircle(width / 2f + kx, cy, knobR(), knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val lim = limit()
                kx = (e.x - width / 2f).coerceIn(-lim, lim)
                // right on screen is clockwise, which is negative yaw in REP-103
                onMove?.invoke(-kx / lim)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                kx = 0f
                onMove?.invoke(0f)
                invalidate()
            }
        }
        return true
    }
}

/** Horizontal bar for an RC channel, 1000..2000 microseconds. */
class BarView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var value = 1500
        set(v) { field = v; invalidate() }
    var live = true
        set(v) { field = v; invalidate() }

    private val bg = Paint().apply { color = Color.parseColor("#1d2534") }
    private val fill = Paint().apply { isAntiAlias = true }
    private val tick = Paint().apply { color = Color.parseColor("#3d4859") }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        c.drawRect(0f, 0f, width.toFloat(), h, bg)
        val frac = ((value - 1000) / 1000f).coerceIn(0f, 1f)
        fill.color = if (live) Color.parseColor("#60a5fa")
                     else Color.parseColor("#8695ab")
        c.drawRect(0f, 0f, width * frac, h, fill)
        c.drawRect(width / 2f - 1f, 0f, width / 2f + 1f, h, tick)
    }
}
