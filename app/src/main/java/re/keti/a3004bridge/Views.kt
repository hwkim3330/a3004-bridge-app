package re.keti.a3004bridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The custom canvases.
 *
 * All four share the palette in Design.kt rather than carrying their own
 * colours, so a change to the theme reaches them too. They are drawn rather than
 * composed because each is a continuous quantity - a range, a deflection, a
 * pulse width - and a widget tree is the wrong tool for that.
 */

private fun View.px(v: Float) = v * resources.displayMetrics.density

/** Polar plot of the lidar range ring. */
class RingView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var ring: Ring? = null
        set(v) { field = v; postInvalidateOnAnimation() }
    var maxRange = 30f
        set(v) { field = v; postInvalidateOnAnimation() }

    private val grid = Paint().apply {
        color = D.withAlpha(D.text, 0.07f); style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val axis = Paint().apply {
        color = D.withAlpha(D.text, 0.05f); style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val label = Paint().apply {
        color = D.textFaint; isAntiAlias = true
    }
    private val dot = Paint().apply { isAntiAlias = true }
    private val centre = Paint().apply { color = D.accent; isAntiAlias = true }
    private val centreGlow = Paint().apply {
        color = D.withAlpha(D.accent, 0.18f); isAntiAlias = true
    }
    private val hint = Paint().apply {
        color = D.textFaint; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        grid.strokeWidth = px(1f)
        axis.strokeWidth = px(1f)
        label.textSize = px(9f)
        hint.textSize = px(12f)

        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.44f

        // Range rings. The step is chosen so there are never more than about six,
        // because past that they stop being reference marks and become texture.
        val step = when {
            maxRange <= 10f -> 2
            maxRange <= 30f -> 5
            maxRange <= 60f -> 10
            else -> 20
        }
        var m = step
        var idx = 0
        while (m <= maxRange) {
            val rr = r * m / maxRange
            c.drawCircle(cx, cy, rr, grid)
            // Along a diagonal, not up the vertical axis: stacked on one line the
            // labels read as a list rather than as marks on their own rings.
            // Every other ring only, so the inner ones stay uncluttered.
            if (idx % 2 == 1 || m + step > maxRange) {
                val a = -Math.PI.toFloat() / 4f
                c.drawText("${m}m", cx + cos(a) * rr + px(3f),
                           cy + sin(a) * rr - px(2f), label)
            }
            m += step; idx++
        }
        c.drawLine(cx - r, cy, cx + r, cy, axis)
        c.drawLine(cx, cy - r, cx, cy + r, axis)

        val g = ring
        if (g == null) {
            // Clamped into the view: r is derived from the shorter side, so on a
            // wide, short panel cy + r already sits past the bottom edge and the
            // message was drawn where nothing is visible.
            val y = min(cy + r + px(20f), height - px(6f))
            c.drawText("lidar 데이터 없음", cx, y, hint)
            return
        }

        val n = g.sectors
        val dotR = maxOf(px(1.6f), (r * 2f * Math.PI.toFloat() / n) * 0.40f)
        for (i in 0 until n) {
            val v = g.cm[i]
            if (v < 0) continue
            val metres = v / 100f
            if (metres > maxRange) continue
            // sector 0 points up, azimuth increases clockwise
            val a = (i.toFloat() / n) * 2f * Math.PI.toFloat() - Math.PI.toFloat() / 2f
            val rr = r * metres / maxRange
            // Near is warm, far is cool: something approaching reads before it is
            // consciously measured.
            val t = (metres / maxRange).coerceIn(0f, 1f)
            dot.color = Color.HSVToColor(floatArrayOf(10f + t * 200f, 0.72f, 1f))
            c.drawCircle(cx + cos(a) * rr, cy + sin(a) * rr, dotR, dot)
        }

        c.drawCircle(cx, cy, px(7f), centreGlow)
        c.drawCircle(cx, cy, px(2.5f), centre)
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
    private var held = false

    private val base = Paint().apply { color = D.surfaceHi; isAntiAlias = true }
    private val rim = Paint().apply {
        color = D.hairline; style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val guide = Paint().apply {
        color = D.withAlpha(D.text, 0.06f); style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val cross = Paint().apply {
        color = D.withAlpha(D.text, 0.05f); isAntiAlias = true
    }
    private val knob = Paint().apply { isAntiAlias = true }
    private val knobRim = Paint().apply {
        style = Paint.Style.STROKE; isAntiAlias = true
    }

    private fun radius() = min(width, height) / 2f * 0.96f
    private fun knobR() = radius() * 0.30f
    private fun limit() = radius() - knobR()

    override fun onDraw(c: Canvas) {
        rim.strokeWidth = px(1f)
        guide.strokeWidth = px(1f)
        cross.strokeWidth = px(1f)
        knobRim.strokeWidth = px(1f)

        val cx = width / 2f
        val cy = height / 2f
        val r = radius()

        c.drawCircle(cx, cy, r, base)
        c.drawCircle(cx, cy, r, rim)
        // The travel limit, so the dead area outside it is visible rather than felt.
        c.drawCircle(cx, cy, limit(), guide)
        c.drawLine(cx - r * 0.66f, cy, cx + r * 0.66f, cy, cross)
        c.drawLine(cx, cy - r * 0.66f, cx, cy + r * 0.66f, cross)

        val fill = if (armed) D.good else D.textFaint
        knob.color = if (held) D.withAlpha(fill, 0.95f) else D.withAlpha(fill, 0.75f)
        knobRim.color = D.withAlpha(Color.WHITE, if (held) 0.30f else 0.14f)
        c.drawCircle(cx + kx, cy + ky, knobR(), knob)
        c.drawCircle(cx + kx, cy + ky, knobR(), knobRim)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                held = true
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
                held = false
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
    private var held = false

    private val base = Paint().apply { color = D.surfaceHi; isAntiAlias = true }
    private val rim = Paint().apply {
        color = D.hairline; style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val detent = Paint().apply {
        color = D.withAlpha(D.text, 0.12f); isAntiAlias = true
    }
    private val knob = Paint().apply { isAntiAlias = true }
    private val knobRim = Paint().apply {
        style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val r = RectF()

    private fun knobR() = height / 2f * 0.82f
    private fun limit() = width / 2f - knobR() - px(2f)

    override fun onDraw(c: Canvas) {
        rim.strokeWidth = px(1f)
        knobRim.strokeWidth = px(1f)

        val cy = height / 2f
        val rad = height / 2f
        r.set(px(0.5f), px(0.5f), width - px(0.5f), height - px(0.5f))
        c.drawRoundRect(r, rad, rad, base)
        c.drawRoundRect(r, rad, rad, rim)

        // Centre detent: the only position that means "not turning", so it is
        // marked rather than left to be inferred from the knob's position.
        c.drawRect(width / 2f - px(0.5f), height * 0.28f,
                   width / 2f + px(0.5f), height * 0.72f, detent)

        val fill = if (armed) D.good else D.textFaint
        knob.color = if (held) D.withAlpha(fill, 0.95f) else D.withAlpha(fill, 0.75f)
        knobRim.color = D.withAlpha(Color.WHITE, if (held) 0.30f else 0.14f)
        c.drawCircle(width / 2f + kx, cy, knobR(), knob)
        c.drawCircle(width / 2f + kx, cy, knobR(), knobRim)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                held = true
                val lim = limit()
                kx = (e.x - width / 2f).coerceIn(-lim, lim)
                // right on screen is clockwise, which is negative yaw in REP-103
                onMove?.invoke(-kx / lim)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                held = false
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
    /* Defaults to false: with no receiver attached the bars would otherwise be
       drawn in the live accent colour, which says "signal present" when nothing
       is connected at all. */
    var live = false
        set(v) { field = v; invalidate() }

    private val bg = Paint().apply { color = D.withAlpha(D.text, 0.07f); isAntiAlias = true }
    private val fill = Paint().apply { isAntiAlias = true }
    private val tick = Paint().apply { color = D.withAlpha(D.text, 0.16f); isAntiAlias = true }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val rad = h / 2f

        r.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(r, rad, rad, bg)

        val frac = ((value - 1000) / 1000f).coerceIn(0f, 1f)
        if (frac > 0f) {
            fill.color = if (live) D.accent else D.textFaint
            r.set(0f, 0f, width * frac, h)
            c.drawRoundRect(r, rad, rad, fill)
        }
        // Centre mark: an RC channel at rest sits here, so the eye needs a
        // reference for "neutral" that does not depend on reading a number.
        c.drawRect(width / 2f - px(0.5f), 0f, width / 2f + px(0.5f), h, tick)
    }
}
