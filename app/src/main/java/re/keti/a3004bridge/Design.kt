package re.keti.a3004bridge

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The visual language, in one place.
 *
 * Everything here exists so the screen has one set of decisions applied
 * consistently rather than per-widget guesses: a single spacing scale, three
 * corner radii, one accent colour, and a type ramp with four steps. Panels are
 * built from these rather than from raw setBackgroundColor calls, which is what
 * makes the layout look deliberate instead of assembled.
 *
 * Two choices are worth stating because they are about a vehicle, not taste:
 *
 *  - The accent is reserved. It marks the one control that matters (arming) and
 *    live data. If everything is accented, nothing is.
 *  - Telemetry numbers are tabular: fixed-width digits so a value changing from
 *    9 to 10 does not shift the text beside it. Jitter reads as instability, and
 *    you should not have to wonder whether the display or the vehicle is
 *    unsteady.
 */
object D {

    // ---------------------------------------------------------------- colour

    /** Page. Not pure black: a near-black lets the panels above it read as raised. */
    val bg = Color.parseColor("#0A0C10")

    /** Panel. One step up from the page. */
    val surface = Color.parseColor("#12161D")

    /** A control sitting on a panel. One step up again. */
    val surfaceHi = Color.parseColor("#1A202A")

    /** Hairline. Low-alpha white rather than a grey, so it works over any surface. */
    val hairline = Color.parseColor("#1FFFFFFF")

    val text = Color.parseColor("#EDF1F7")
    val textDim = Color.parseColor("#94A1B4")
    val textFaint = Color.parseColor("#5D6879")

    /** Reserved for live data and the armed state. */
    val accent = Color.parseColor("#4C9AFF")
    val good = Color.parseColor("#31C56D")
    val warn = Color.parseColor("#F0A93B")
    val bad = Color.parseColor("#F0553B")

    // --------------------------------------------------------------- metrics

    /** 4pt grid. Every gap on screen is one of these. */
    const val s1 = 4
    const val s2 = 8
    const val s3 = 12
    const val s4 = 16
    const val s5 = 24
    const val s6 = 32

    const val rSm = 10f
    const val rMd = 16f
    const val rLg = 22f

    // ------------------------------------------------------------ primitives

    fun Context.dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            resources.displayMetrics).toInt()

    fun Context.dpf(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
            resources.displayMetrics)

    /** A filled, optionally outlined, rounded rectangle. */
    fun Context.roundRect(
        fill: Int,
        radius: Float = rMd,
        stroke: Int = 0,
        strokeWidth: Int = 1,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpf(radius)
        setColor(fill)
        if (stroke != 0) setStroke(dp(strokeWidth), stroke)
    }

    /** A pill, for status badges. Radius is deliberately larger than any height. */
    fun Context.pill(fill: Int, stroke: Int = 0): GradientDrawable =
        roundRect(fill, 999f, stroke)

    /**
     * Press feedback. A control with no pressed state feels dead on a
     * touchscreen, and on a tablet held in one hand the touch target is the only
     * confirmation you get that the tap landed.
     */
    fun Context.tappable(base: GradientDrawable, ripple: Int = accent): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(withAlpha(ripple, 0.22f)), base, null)

    fun withAlpha(c: Int, a: Float): Int =
        Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    // ---------------------------------------------------------------- typography

    /** Section label: small, tracked out, muted. Reads as a heading without shouting. */
    fun Context.label(s: String): TextView = TextView(this).apply {
        text = s.uppercase()
        setTextColor(textFaint)
        textSize = 10f
        letterSpacing = 0.14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** A value the user reads at a glance. */
    fun Context.value(s: String = ""): TextView = TextView(this).apply {
        text = s
        // D.text, not `text`: inside apply on a TextView the bare name resolves to
        // the view's own CharSequence property, which the compiler rightly refuses.
        setTextColor(D.text)
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Telemetry. Monospaced so digits do not shift as values change. */
    fun Context.mono(s: String = "", size: Float = 12f, colour: Int = textDim): TextView =
        TextView(this).apply {
            text = s
            setTextColor(colour)
            textSize = size
            typeface = Typeface.MONOSPACE
        }

    fun Context.caption(s: String = ""): TextView = TextView(this).apply {
        text = s
        setTextColor(textDim)
        textSize = 11f
        setLineSpacing(dpf(3f), 1f)
    }

    // ----------------------------------------------------------- composition

    /**
     * A panel: label on the left, a status slot on the right, content below.
     * Returns the panel and the status TextView so callers can update it without
     * reaching back into the hierarchy.
     */
    fun Context.panel(title: String): Panel {
        val status = mono(size = 11f, colour = textDim).apply { gravity = Gravity.END }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(s4), dp(s3), dp(s4), dp(s2))
            addView(label(title), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(surface, rMd, hairline)
            clipToOutline = true
            addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        return Panel(box, body, status)
    }

    class Panel(val root: LinearLayout, val body: LinearLayout, val status: TextView) {
        /**
         * Let the body take the panel's leftover height.
         *
         * Needed by any panel whose content should stretch - a camera frame, a
         * plot. Without it the body is WRAP_CONTENT, so a child asking for
         * weight inside it resolves to zero height and simply vanishes, leaving
         * a panel that looks correctly sized and is empty.
         */
        fun fillBody() {
            (body.layoutParams as LinearLayout.LayoutParams).also {
                it.height = 0
                it.weight = 1f
            }
            body.requestLayout()
        }
    }

    /** A dot-and-text status badge. The dot carries the colour so the text stays readable. */
    fun Context.badge(): Badge {
        val dot = View(this).apply { background = pill(textFaint) }
        val txt = mono(size = 11f, colour = textDim)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(surfaceHi, hairline)
            setPadding(dp(s2), dp(s1), dp(s3), dp(s1))
            addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(s2) })
            addView(txt)
        }
        return Badge(row, dot, txt)
    }

    class Badge(val root: LinearLayout, private val dot: View, private val txt: TextView) {
        fun set(text: String, colour: Int) {
            txt.text = text
            (dot.background as? GradientDrawable)?.setColor(colour)
        }
    }
}
