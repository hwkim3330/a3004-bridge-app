package re.keti.a3004bridge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The drawn surfaces.
 *
 * Each is a continuous quantity - a range, a deflection, a pulse width - which is
 * why they are drawn rather than composed out of widgets. They take their colours
 * from T so a change to the palette reaches them too.
 */

private const val TAU = (Math.PI * 2).toFloat()

/** Polar plot of the lidar range ring. */
@Composable
fun RingPlot(ring: Ring?, maxRange: Float = 30f, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(size.width, size.height) * 0.44f

        // Never more than about six rings: past that they stop being reference
        // marks and become texture.
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
            drawCircle(T.gridStrong, rr, Offset(cx, cy),
                style = Stroke(1f))
            // Labels on a diagonal, every other ring. Stacked up the vertical
            // axis they read as a list rather than as marks on their own rings.
            if (idx % 2 == 1 || m + step > maxRange) {
                val a = -TAU / 8f
                label(tm, "${m}m",
                    Offset(cx + cos(a) * rr + 3f, cy + sin(a) * rr - 12f))
            }
            m += step; idx++
        }
        drawLine(T.gridWeak, Offset(cx - r, cy), Offset(cx + r, cy))
        drawLine(T.gridWeak, Offset(cx, cy - r), Offset(cx, cy + r))

        if (ring == null) {
            val t = tm.measure("lidar 데이터 없음",
                TextStyle(color = T.textFaint, fontSize = 12.sp))
            // Clamped inside: r comes from the shorter side, so on a wide, short
            // panel cy + r already sits past the bottom edge.
            val y = min(cy + r + 20f, size.height - t.size.height - 2f)
            drawText(t, topLeft = Offset(cx - t.size.width / 2f, y))
            return@Canvas
        }

        val n = ring.sectors
        val dotR = maxOf(2f, (r * TAU / n) * 0.40f)
        for (i in 0 until n) {
            val v = ring.cm[i]
            if (v < 0) continue
            val metres = v / 100f
            if (metres > maxRange) continue
            // sector 0 points up, azimuth increases clockwise
            val a = (i.toFloat() / n) * TAU - TAU / 4f
            val rr = r * metres / maxRange
            // Near is warm, far is cool: something approaching reads before it
            // is consciously measured.
            val f = (metres / maxRange).coerceIn(0f, 1f)
            drawCircle(hsv(10f + f * 200f, 0.72f, 1f), dotR,
                Offset(cx + cos(a) * rr, cy + sin(a) * rr))
        }
        drawCircle(T.accent.copy(alpha = 0.18f), 7f, Offset(cx, cy))
        drawCircle(T.accent, 2.5f, Offset(cx, cy))
    }
}

private fun DrawScope.label(tm: TextMeasurer, s: String, at: Offset) {
    drawText(tm.measure(s, TextStyle(color = T.textFaint, fontSize = 9.sp)),
        topLeft = at)
}

/** Cheap HSV, so the ring can colour by distance without a bitmap lookup. */
private fun hsv(h: Float, s: Float, v: Float): Color {
    val c = v * s
    val hp = (h % 360f) / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val (r, g, b) = when (hp.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = v - c
    return Color(r + m, g + m, b + m)
}


/**
 * A knob that looks like a physical thing.
 *
 * Flat grey circles were the weakest part of this screen. What sells a control as
 * touchable is not colour but the pair of cues a real one has: a well that looks
 * recessed, and a cap that looks to be sitting above it. Both are faked here with
 * radial gradients, which is cheap and enough - a shadow is only a gradient
 * anyway.
 */
private fun DrawScope.knob(at: Offset, r: Float, armed: Boolean, held: Boolean) {
    // Cast shadow: offset down, soft, and stronger while held so pressing reads
    // as the cap moving toward the surface.
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.Black.copy(alpha = if (held) 0.10f else 0.16f),
            1f to Color.Transparent,
            center = Offset(at.x, at.y + r * 0.18f),
            radius = r * 1.45f
        ),
        radius = r * 1.45f,
        center = Offset(at.x, at.y + r * 0.18f)
    )
    // The cap. White when idle so it reads as a control rather than a smudge;
    // accent-green when armed, because that is the one state worth colouring.
    val top = if (armed) Color(0xFF3FD06A) else Color.White
    val bottom = if (armed) Color(0xFF1E9E4A) else Color(0xFFEDEDF2)
    drawCircle(
        brush = Brush.verticalGradient(
            0f to top, 1f to bottom,
            startY = at.y - r, endY = at.y + r
        ),
        radius = r, center = at
    )
    // A hairline so the white cap has an edge against a light well.
    drawCircle(Color.Black.copy(alpha = 0.10f), r, at, style = Stroke(1f))
}

/** A recessed well for a knob to sit in. */
private fun DrawScope.well(centre: Offset, r: Float) {
    drawCircle(T.surfaceHi, r, centre)
    // Darker at the top inner edge, which is what "recessed" looks like.
    drawCircle(
        brush = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.07f),
            0.45f to Color.Transparent,
            startY = centre.y - r, endY = centre.y + r
        ),
        radius = r, center = centre
    )
}

/**
 * Two-axis stick. Reports normalised x/y with up as positive y.
 *
 * Releasing always re-centres, and that is not a convenience: a stick that keeps
 * its deflection after your finger leaves is a stuck throttle. The gesture is
 * handled with awaitEachGesture rather than detectDragGestures because a press
 * with no movement still has to command - a driver holding a steady offset is not
 * dragging.
 */
@Composable
fun Joystick(
    armed: Boolean,
    modifier: Modifier = Modifier,
    onMove: (Float, Float) -> Unit,
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var held by remember { mutableStateOf(false) }

    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                held = true
                fun report(p: Offset) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = min(size.width, size.height) / 2f * 0.96f
                    val lim = r - r * 0.30f
                    var d = p - c
                    val len = hypot(d.x, d.y)
                    if (len > lim) d *= lim / len
                    knob = d
                    onMove(d.x / lim, -d.y / lim)
                }
                report(down.position)
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    report(ch.position)
                    ch.consume()
                }
                held = false
                knob = Offset.Zero
                onMove(0f, 0f)
            }
        }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(size.width, size.height) / 2f * 0.96f
        val knobR = r * 0.30f

        well(Offset(cx, cy), r)
        // The travel limit, so the dead area outside it is visible not just felt.
        drawCircle(T.gridStrong, r - knobR, Offset(cx, cy), style = Stroke(1f))
        drawLine(T.gridWeak,
            Offset(cx - r * 0.60f, cy), Offset(cx + r * 0.60f, cy))
        drawLine(T.gridWeak,
            Offset(cx, cy - r * 0.60f), Offset(cx, cy + r * 0.60f))

        knob(Offset(cx + knob.x, cy + knob.y), knobR, armed, held)
    }
}

/**
 * Horizontal-only stick for yaw.
 *
 * The vehicle is a SCOUT MINI Omni: mecanum wheels, so translation and rotation
 * are independent, and putting yaw on the same two-axis stick as strafe would
 * throw away a degree of freedom the machine has.
 */
@Composable
fun YawSlider(
    armed: Boolean,
    modifier: Modifier = Modifier,
    onMove: (Float) -> Unit,
) {
    var kx by remember { mutableStateOf(0f) }
    var held by remember { mutableStateOf(false) }

    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                held = true
                fun report(p: Offset) {
                    val knobR = size.height / 2f * 0.82f
                    val lim = size.width / 2f - knobR - 2f
                    kx = (p.x - size.width / 2f).coerceIn(-lim, lim)
                    // right on screen is clockwise, negative yaw in REP-103
                    onMove(-kx / lim)
                }
                report(down.position)
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    report(ch.position)
                    ch.consume()
                }
                held = false
                kx = 0f
                onMove(0f)
            }
        }
    ) {
        val cy = size.height / 2f
        val rad = size.height / 2f
        val knobR = rad * 0.82f

        val cr = androidx.compose.ui.geometry.CornerRadius(rad, rad)
        drawRoundRect(T.surfaceHi, size = size, cornerRadius = cr)
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.07f),
                0.45f to Color.Transparent,
                startY = 0f, endY = size.height
            ),
            size = size, cornerRadius = cr
        )
        // Centre detent: the only position meaning "not turning", so it is marked
        // rather than inferred from where the knob happens to be.
        drawRect(T.trackIdle,
            topLeft = Offset(size.width / 2f - 0.5f, size.height * 0.28f),
            size = Size(1f, size.height * 0.44f))

        knob(Offset(size.width / 2f + kx, cy), knobR, armed, held)
    }
}

/** One RC channel, 1000..2000 microseconds. */
@Composable
fun ChannelBar(value: Int, live: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val h = size.height
        val rad = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f)

        drawRoundRect(T.track, size = size, cornerRadius = rad)
        val frac = ((value - 1000) / 1000f).coerceIn(0f, 1f)
        if (frac > 0f) {
            drawRoundRect(if (live) T.accent else T.trackIdle,
                size = Size(size.width * frac, h), cornerRadius = rad)
        }
        // Centre mark: an RC channel at rest sits here, and the eye needs a
        // reference for neutral that does not require reading a number.
        drawRect(T.gridStrong,
            topLeft = Offset(size.width / 2f - 0.5f, 0f), size = Size(1f, h))
    }
}

/**
 * The map, the vehicle on it, and the destination you tapped.
 *
 * Drawn from one `MapFrame`, so the cells, the transform and the pose are
 * always the same instant - a map and a pose fetched separately drift apart and
 * put the robot through a wall.
 *
 * Three deliberate choices about what a person sees:
 *
 *  - unknown ground is the page colour, not a shade of grey. A map that starts
 *    as a grey rectangle looks broken; one that starts empty and fills in looks
 *    like it is working, which is also the truth.
 *  - the view follows the map, not the vehicle. A rotating, recentring display
 *    is disorienting to tap on, and tapping is what this screen is for.
 *  - the goal is drawn where it was *sent*, not where the planner rounded it
 *    to. If those differ the tap did not do what it looked like it did, and
 *    that is worth being able to see.
 */
@Composable
fun MapPlot(
    map: MapFrame?,
    goalCm: Pair<Int, Int>?,
    modifier: Modifier = Modifier,
    onTap: (Int, Int) -> Unit,
) {
    if (map == null) {
        EmptyState("지도 없음", modifier = modifier)
        return
    }

    // Only the part that has been seen is worth showing. Fitting the whole
    // 40 m grid puts a 12 m room in the middle sixth of the screen.
    val bounds = remember(map) {
        var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
        var bo = Int.MAX_VALUE; var bi = Int.MIN_VALUE
        for (y in 0 until map.h) for (x in 0 until map.w) {
            val v = map.at(x, y)
            if (v != MapFrame.S2_UNKNOWN) {
                if (x < lo) lo = x; if (x > hi) hi = x
                if (y < bo) bo = y; if (y > bi) bi = y
            }
        }
        if (lo > hi) null else intArrayOf(lo - 2, bo - 2, hi + 2, bi + 2)
    }

    Box(modifier.background(T.surfaceHi)) {
        var scale by remember { mutableStateOf(1f) }
        var offX by remember { mutableStateOf(0f) }
        var offY by remember { mutableStateOf(0f) }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(map.w, map.h, bounds) {
                    detectTapGestures { p ->
                        if (scale <= 0f) return@detectTapGestures
                        val cx = ((p.x - offX) / scale).toInt()
                        val cy = map.h - 1 - ((p.y - offY) / scale).toInt()
                        if (cx in 0 until map.w && cy in 0 until map.h)
                            onTap(map.xCmOf(cx), map.yCmOf(cy))
                    }
                }
        ) {
            val b = bounds ?: intArrayOf(0, 0, map.w - 1, map.h - 1)
            val bw = (b[2] - b[0] + 1).coerceAtLeast(1)
            val bh = (b[3] - b[1] + 1).coerceAtLeast(1)
            scale = minOf(size.width / bw, size.height / bh)
            offX = (size.width - bw * scale) / 2f - b[0] * scale
            offY = (size.height - bh * scale) / 2f - (map.h - 1 - b[3]) * scale

            fun sx(cx: Int) = offX + cx * scale
            fun sy(cy: Int) = offY + (map.h - 1 - cy) * scale

            for (cy in b[1]..b[3]) {
                for (cx in b[0]..b[2]) {
                    val v = map.at(cx, cy)
                    val c = when {
                        v > MapFrame.S2_UNKNOWN + 8 -> T.text
                        v < MapFrame.S2_UNKNOWN - 8 -> T.surface
                        else -> continue          // unknown: leave the page
                    }
                    drawRect(c, Offset(sx(cx), sy(cy)), Size(scale + 0.6f, scale + 0.6f))
                }
            }

            goalCm?.let { (gx, gy) ->
                val p = Offset(sx(map.cellXOf(gx)) + scale / 2,
                               sy(map.cellYOf(gy)) + scale / 2)
                drawCircle(T.accent, 9f, p, style = Stroke(width = 2.5f))
                drawLine(T.accent, Offset(p.x - 13f, p.y), Offset(p.x + 13f, p.y), 2f)
                drawLine(T.accent, Offset(p.x, p.y - 13f), Offset(p.x, p.y + 13f), 2f)
            }

            // The vehicle: a wedge, so heading is readable without a label.
            val vx = sx(map.cellXOf(map.poseXCm)) + scale / 2
            val vy = sy(map.cellYOf(map.poseYCm)) + scale / 2
            val a = -map.headingDeg * PI.toFloat() / 180f
            val r = 11f
            val path = Path().apply {
                moveTo(vx + r * cos(a), vy + r * sin(a))
                lineTo(vx + r * cos(a + 2.5f), vy + r * sin(a + 2.5f))
                lineTo(vx + r * 0.35f * cos(a + PI.toFloat()),
                       vy + r * 0.35f * sin(a + PI.toFloat()))
                lineTo(vx + r * cos(a - 2.5f), vy + r * sin(a - 2.5f))
                close()
            }
            drawPath(path, T.good)
        }
    }
}
