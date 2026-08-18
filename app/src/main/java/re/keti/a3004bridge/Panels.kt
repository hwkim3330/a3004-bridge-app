package re.keti.a3004bridge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.ceil
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
fun RingPlot(ring: Ring?, maxRange: Float = 0f, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        //
        // Scale to what came back, not to what the sensor could see.
        //
        // Fixed at 30 m, a sensor on a desk put every return inside the innermost
        // ring - a plot of a room five metres across, drawn at one sixth scale,
        // reading as a speck. The rings stop being reference marks when nothing
        // reaches them.
        //
        // Rounded up to the ring step so the outermost ring is a round number,
        // and floored at 2 m so a sensor facing a wall does not zoom to absurdity.
        val dataMax = ((ring?.maxCm ?: 0) / 100f)
        val range = when {
            maxRange > 0f -> maxRange
            dataMax <= 0f -> 10f
            else -> maxOf(2f, ceil(dataMax * 1.15f))
        }
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(size.width, size.height) * 0.44f

        // Never more than about six rings: past that they stop being reference
        // marks and become texture.
        val step = when {
            range <= 6f -> 1
            range <= 10f -> 2
            range <= 30f -> 5
            range <= 60f -> 10
            else -> 20
        }
        var m = step
        var idx = 0
        while (m <= range) {
            val rr = r * m / range
            drawCircle(T.gridStrong, rr, Offset(cx, cy),
                style = Stroke(1f))
            // Labels on a diagonal, every other ring. Stacked up the vertical
            // axis they read as a list rather than as marks on their own rings.
            if (idx % 2 == 1 || m + step > range) {
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
            if (metres > range) continue
            // sector 0 points up, azimuth increases clockwise
            val a = (i.toFloat() / n) * TAU - TAU / 4f
            val rr = r * metres / range
            //
            // Coloured by reflectivity, not by distance.
            //
            // Distance is already the radius. Colouring by it again spent the
            // only free dimension on information the position had already given,
            // and left the ring's own reflectivity byte unread. Dark carpet,
            // painted wall and retroreflective tape are wildly different here at
            // the same distance, and that difference is what tells you what you
            // are looking at.
            val f = (ring.refl[i] / 200f).coerceIn(0f, 1f)
            drawCircle(hsv(215f - f * 200f, 0.62f - f * 0.30f, 0.55f + f * 0.45f),
                       dotR, Offset(cx + cos(a) * rr, cy + sin(a) * rr))
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
 * The map, the vehicle on it, and where you are about to send it.
 *
 * Drawn from one `MapFrame`, so the cells, the transform and the pose are
 * always the same instant - a map and a pose fetched separately drift apart and
 * put the robot through a wall.
 *
 * Four things here are about a person tapping a destination for a machine that
 * will then drive there on its own:
 *
 *  - a metre grid and a scale bar. Judging "about two metres past the doorway"
 *    off an unlabelled grey shape is guesswork, and the tap commits a vehicle.
 *  - pinch to zoom and drag to pan. A forty metre map fitted to a panel puts
 *    twenty centimetres inside one finger width, so fine placement is
 *    impossible without it.
 *  - a tap proposes; it does not send. The destination is drawn hollow until
 *    it is confirmed, because the distance between "I meant there" and "it is
 *    now driving there" should contain a decision.
 *  - the vehicle is coloured by what the navigator says it is doing, so a
 *    stopped robot does not look like a driving one.
 */
@Composable
fun MapPlot(
    map: MapFrame?,
    goalCm: Pair<Int, Int>?,
    pendingCm: Pair<Int, Int>?,
    vehicleColour: Color,
    /* The ring as it is right now, drawn over the map it is being matched
       against. Two things become visible that neither shows alone: whether the
       sensor still agrees with the map - live returns sitting off the mapped
       walls mean the pose is drifting - and what has appeared since, which is
       everything the map does not have and a person needs to see. */
    ring: Ring? = null,
    modifier: Modifier = Modifier,
    onTap: (Int, Int) -> Unit,
) {
    if (map == null) {
        EmptyState("지도 없음", modifier = modifier)
        return
    }

    // Only the part that has been seen is worth fitting to. Fitting the whole
    // 40 m grid puts a 12 m room in the middle sixth of the screen.
    val bounds = remember(map.w, map.h, map.cells.size) {
        var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
        var bo = Int.MAX_VALUE; var bi = Int.MIN_VALUE
        for (y in 0 until map.h) for (x in 0 until map.w) {
            if (map.at(x, y) != MapFrame.S2_UNKNOWN) {
                if (x < lo) lo = x; if (x > hi) hi = x
                if (y < bo) bo = y; if (y > bi) bi = y
            }
        }
        if (lo > hi) null else intArrayOf(lo - 2, bo - 2, hi + 2, bi + 2)
    }

    // User zoom and pan, on top of the automatic fit. Reset when the map's
    // geometry changes underneath, which is the only time the fit is wrong in a
    // way the user did not ask for.
    var zoom by remember(map.resCm, map.w) { mutableStateOf(1f) }
    var panX by remember(map.resCm, map.w) { mutableStateOf(0f) }
    var panY by remember(map.resCm, map.w) { mutableStateOf(0f) }

    Box(modifier.background(T.surfaceHi)) {
        var scale by remember { mutableStateOf(1f) }
        var offX by remember { mutableStateOf(0f) }
        var offY by remember { mutableStateOf(0f) }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, z, _ ->
                        zoom = (zoom * z).coerceIn(0.5f, 12f)
                        panX += pan.x
                        panY += pan.y
                    }
                }
                .pointerInput(map.w, map.h) {
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
            scale = minOf(size.width / bw, size.height / bh) * zoom
            offX = (size.width - bw * scale) / 2f - b[0] * scale + panX
            offY = (size.height - bh * scale) / 2f - (map.h - 1 - b[3]) * scale + panY

            fun sx(cx: Int) = offX + cx * scale
            fun sy(cy: Int) = offY + (map.h - 1 - cy) * scale

            // Cells first, then the grid over them, so the grid stays visible
            // on filled ground.
            for (cy in 0 until map.h) {
                val py = sy(cy)
                if (py < -scale || py > size.height) continue
                for (cx in 0 until map.w) {
                    val px = sx(cx)
                    if (px < -scale || px > size.width) continue
                    val v = map.at(cx, cy)
                    //
                    // A ramp, not two colours.
                    //
                    // Occupancy is a confidence and it was being thresholded away
                    // into black or white, so a wall seen once looked exactly like
                    // one seen two hundred times. The ramp costs nothing and shows
                    // which parts of the map to trust.
                    val c = when {
                        v > MapFrame.S2_UNKNOWN + 8 -> {
                            val f = ((v - MapFrame.S2_UNKNOWN) / 127f).coerceIn(0f, 1f)
                            lerp(T.gridStrong, T.text, 0.25f + 0.75f * f)
                        }
                        v < MapFrame.S2_UNKNOWN - 8 -> {
                            val f = ((MapFrame.S2_UNKNOWN - v) / 127f).coerceIn(0f, 1f)
                            lerp(T.surfaceHi, T.surface, 0.3f + 0.7f * f)
                        }
                        else -> continue
                    }
                    drawRect(c, Offset(px, py), Size(scale + 0.6f, scale + 0.6f))
                }
            }

            // A metre grid, thinned out as it gets dense so it never becomes a
            // solid wash at low zoom.
            val cellsPerMetre = 100f / map.resCm
            var stepM = 1
            while (cellsPerMetre * scale * stepM < 28f) stepM *= 5
            val step = (cellsPerMetre * stepM).toInt().coerceAtLeast(1)
            val originCx = map.cellXOf(0)
            val originCy = map.cellYOf(0)
            var gx = originCx % step
            while (gx < map.w) {
                val x = sx(gx)
                if (x >= 0 && x <= size.width)
                    drawLine(T.gridWeak, Offset(x, 0f), Offset(x, size.height), 1f)
                gx += step
            }
            var gy = originCy % step
            while (gy < map.h) {
                val y = sy(gy)
                if (y >= 0 && y <= size.height)
                    drawLine(T.gridWeak, Offset(0f, y), Offset(size.width, y), 1f)
                gy += step
            }
            // The origin, which is where the vehicle started and what every
            // coordinate is relative to.
            drawLine(T.gridStrong, Offset(sx(originCx), 0f),
                     Offset(sx(originCx), size.height), 1.6f)
            drawLine(T.gridStrong, Offset(0f, sy(originCy)),
                     Offset(size.width, sy(originCy)), 1.6f)

            // Scale bar: the grid spacing, stated.
            val barPx = cellsPerMetre * scale * stepM
            if (barPx in 20f..size.width * 0.6f) {
                val y = size.height - 14f
                val x0 = 14f
                drawLine(T.textDim, Offset(x0, y), Offset(x0 + barPx, y), 2.5f)
                drawLine(T.textDim, Offset(x0, y - 5f), Offset(x0, y + 5f), 2.5f)
                drawLine(T.textDim, Offset(x0 + barPx, y - 5f),
                         Offset(x0 + barPx, y + 5f), 2.5f)
            }

            fun cross(p: Offset, c: Color, filled: Boolean) {
                if (filled) drawCircle(c, 7f, p)
                else drawCircle(c, 8f, p, style = Stroke(width = 2.5f))
                drawLine(c, Offset(p.x - 14f, p.y), Offset(p.x + 14f, p.y), 2f)
                drawLine(c, Offset(p.x, p.y - 14f), Offset(p.x, p.y + 14f), 2f)
            }

            // Drawn where it was *sent*, not where the planner rounded it to, so
            // a tap that did not land where it looked is visible.
            goalCm?.let { (x, y) ->
                cross(Offset(sx(map.cellXOf(x)) + scale / 2,
                             sy(map.cellYOf(y)) + scale / 2), T.accent, true)
            }
            pendingCm?.let { (x, y) ->
                cross(Offset(sx(map.cellXOf(x)) + scale / 2,
                             sy(map.cellYOf(y)) + scale / 2), T.warn, false)
            }

            // The live ring, placed at the pose the map was matched to.
            ring?.let { rg ->
                val hx = sx(map.cellXOf(map.poseXCm)) + scale / 2
                val hy = sy(map.cellYOf(map.poseYCm)) + scale / 2
                val head = -map.headingDeg * PI.toFloat() / 180f - PI.toFloat() / 2f
                val perCm = scale / map.resCm
                for (i in 0 until rg.sectors) {
                    val d = rg.cm[i]
                    if (d < 0) continue
                    val a = head + (i.toFloat() / rg.sectors) * 2f * PI.toFloat()
                    val px2 = hx + cos(a) * d * perCm
                    val py2 = hy + sin(a) * d * perCm
                    if (px2 < 0 || py2 < 0 || px2 > size.width || py2 > size.height)
                        continue
                    drawCircle(T.warn.copy(alpha = 0.75f), 1.8f, Offset(px2, py2))
                }
            }

            val vx = sx(map.cellXOf(map.poseXCm)) + scale / 2
            val vy = sy(map.cellYOf(map.poseYCm)) + scale / 2
            val a = -map.headingDeg * PI.toFloat() / 180f
            val r = 12f
            drawPath(Path().apply {
                moveTo(vx + r * cos(a), vy + r * sin(a))
                lineTo(vx + r * cos(a + 2.5f), vy + r * sin(a + 2.5f))
                lineTo(vx + r * 0.35f * cos(a + PI.toFloat()),
                       vy + r * 0.35f * sin(a + PI.toFloat()))
                lineTo(vx + r * cos(a - 2.5f), vy + r * sin(a - 2.5f))
                close()
            }, vehicleColour)
        }
    }
}

/**
 * A rolling picture of what the microphone is hearing.
 *
 * `PcmPlayer` already computes a peak per buffer - 512 samples at 16 kHz, so
 * about thirty values a second - and that callback was being thrown away. Thirty
 * a second is too coarse to be a waveform in the oscilloscope sense and too fast
 * to read as numbers, so it is drawn as an envelope: each value is one column,
 * mirrored about the centre line, newest on the right.
 *
 * That makes the two things worth seeing obvious without reading anything. A
 * dead microphone is a flat line rather than a missing panel, and clipping is a
 * column that reaches the frame - which a single level number cannot show
 * because it has already been averaged away by the time you look.
 *
 * `head` is the write position in a ring buffer the caller owns. Passing the
 * array rather than a list avoids allocating thirty times a second for something
 * that only gets drawn.
 */
@Composable
fun MicWave(
    levels: FloatArray,
    head: Int,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val n = levels.size
        if (n == 0) return@Canvas
        val mid = size.height / 2f
        val step = size.width / n

        // The centre line is drawn whether or not anything is arriving, so
        // silence and "not connected" do not look like the same empty box.
        drawLine(T.gridStrong, Offset(0f, mid), Offset(size.width, mid), 1f)

        if (!live) return@Canvas

        var peak = 0f
        for (v in levels) if (v > peak) peak = v
        /*
         * Scaled to what was heard, not to full scale.
         *
         * Drawn against 1.0 this was a single pixel either side of the centre in
         * a quiet room - a peak of 0.013 is what a room with nobody talking
         * measures - so the panel looked broken while the microphone was
         * working. Found by building the same view on the desktop, where the
         * two sat side by side and the fault was obvious.
         *
         * The floor stops a silent room amplifying its own noise floor into a
         * waveform, and the gain is printed rather than hidden, because an
         * envelope with an invisible scale invites reading loudness off the
         * height.
         */
        val span = maxOf(peak, 0.02f)
        for (i in 0 until n) {
            // Oldest at the left: read forward from just after the head.
            val v = levels[(head + i) % n]
            if (v <= 0f) continue
            val h = ((v / span).coerceAtMost(1f) * (mid - 2f))
            val x = i * step
            // Full scale reads as clipping, which is worth its own colour: the
            // recording is already damaged by the time it looks like this.
            val c = if (v >= 0.98f) T.bad else T.good
            drawLine(c, Offset(x, mid - h), Offset(x, mid + h),
                     strokeWidth = maxOf(1f, step * 0.8f))
        }

        drawText(tm, "피크 %.3f · 스케일 %.3f".format(peak, span),
                 topLeft = Offset(4f, 2f),
                 style = TextStyle(color = T.textDim, fontSize = 9.sp))
    }
}
