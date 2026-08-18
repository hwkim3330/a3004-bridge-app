package re.keti.a3004bridge

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The visual language, in one place.
 *
 * One spacing scale, three radii, one reserved accent, four type steps. Panels
 * are built from these rather than from per-widget guesses, which is what makes
 * the screen look decided rather than assembled.
 *
 * Two choices are about a vehicle rather than taste:
 *
 *  - The accent is reserved. It marks live data and the one control that matters
 *    (arming). If everything is accented, nothing is.
 *  - Telemetry is monospaced, so a value going from 9 to 10 does not shift the
 *    text beside it. Jitter reads as instability, and you should not have to
 *    wonder whether it is the display or the machine that is unsteady.
 */
object T {

    /*
     * A light palette, following the iOS light system colours rather than
     * inventing one: a grey page with white cards on it, separation carried by
     * that contrast instead of by outlines, one blue accent, and separators thin
     * enough to read as a hairline rather than a border.
     *
     * Light is the right choice here for a reason, not just taste. This tablet is
     * an SM-T736N and its panel is an LCD, so the backlight is on regardless of
     * what the pixels say and a dark theme saves no power at all. What it does
     * cost is daylight legibility: a dark LCD outdoors is mostly reflection, and
     * this is a tablet somebody drives a robot with.
     */

    /** The page. Grey, so a white card on it needs no border to be a card. */
    val bg = Color(0xFFF2F2F7)
    val surface = Color(0xFFFFFFFF)
    val surfaceHi = Color(0xFFE9E9EF)

    /** Separator, not a border: present at a glance only if you look for it. */
    val hairline = Color(0x1F3C3C43)

    val text = Color(0xFF1C1C1E)
    val textDim = Color(0xFF6C6C70)
    val textFaint = Color(0xFFA0A0A6)

    val accent = Color(0xFF007AFF)
    val good = Color(0xFF248A3D)
    val warn = Color(0xFFC77700)
    val bad = Color(0xFFD70015)

    /**
     * Video keeps a dark ground. A frame letterboxed against white glares beside
     * the picture and changes how the picture itself reads - which is why photo
     * and video viewers are dark even in a light interface.
     */
    val videoBg = Color(0xFF1C1C1E)

    /*
     * Ink for drawn surfaces, as its own tokens rather than alpha on the text
     * colour. The dark theme's 5-7% white worked because it sat on near-black;
     * the same alpha of black on white is almost nothing, and the range rings
     * disappeared. These are the iOS system greys, which are chosen to hold at
     * these weights on a light ground.
     */
    val gridStrong = Color(0xFFD1D1D6)   /* range rings, panel divider */
    val gridWeak = Color(0xFFE5E5EA)     /* axes, crosshairs */
    val track = Color(0xFFE5E5EA)        /* the unfilled part of a bar */
    val trackIdle = Color(0xFFBCBCC2)    /* a bar with no live signal */

    /** 4pt grid. Every gap on screen is one of these. */
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 28.dp

    // Bigger surfaces get more curvature, which is what keeps a large card from
    // reading as a rectangle with the corners filed off.
    val rSm = RoundedCornerShape(12.dp)
    val rMd = RoundedCornerShape(22.dp)
    val rPill = RoundedCornerShape(percent = 50)

    /*
     * "tnum" on everything that carries a number.
     *
     * Proportional digits have different widths - a 1 is narrower than a 0 - so a
     * value updating in place makes the text after it shift left and right. On a
     * screen where the match score, the ranges and the packet counts all change
     * several times a second, that shimmer is the difference between an instrument
     * and a web page. Tabular figures fix the advance width and cost nothing.
     */

    /** The one big thing on screen. */
    val title = TextStyle(color = text, fontSize = 22.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum")

    /**
     * A card's name. Sentence case at a readable size, not a tracked-out
     * micro-caption: hierarchy comes from weight and size, and shrinking a
     * heading until it needs letter-spacing to be legible is the opposite of
     * hierarchy.
     */
    val cardTitle = TextStyle(color = text, fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
        fontFeatureSettings = "tnum")

    /** A quieter heading inside a card. */
    val section = TextStyle(color = textDim, fontSize = 12.sp,
        fontWeight = FontWeight.Medium)

    val body = TextStyle(color = textDim, fontSize = 12.sp, lineHeight = 17.sp,
        fontFeatureSettings = "tnum")

    /** Telemetry. Fixed-width digits, so a changing value does not shift its neighbour. */
    val mono = TextStyle(color = textDim, fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum")
}

/** A quiet heading inside a card. Sentence case: it is a label, not a stencil. */
@Composable
fun Label(s: String, modifier: Modifier = Modifier) =
    Text(s, style = T.section, modifier = modifier)

/**
 * A status line.
 *
 * Proportional, not monospaced. Mono is for a column of digits that must not
 * shift; using it for prose - and for Korean prose especially - makes an
 * interface read as log output, which is the wrong register for something you
 * hold in your hands.
 */
@Composable
fun Status(s: String, colour: Color = T.textDim, modifier: Modifier = Modifier) {
    /*
     * The colour eases rather than jumps.
     *
     * A status that snaps from grey to red is read as a redraw; the same change
     * over a quarter of a second is read as something happening. That is most of
     * where "considered" comes from in an interface, and it costs one wrapper.
     * Short enough that nobody waits for it - 220 ms is under the threshold where
     * a transition starts feeling like latency.
     */
    val c by animateColorAsState(colour, tween(220), label = "status")
    Text(s, style = T.body.copy(color = c), modifier = modifier)
}

/** For digits that change in place. */
@Composable
fun Mono(s: String, colour: Color = T.textDim, modifier: Modifier = Modifier) =
    Text(s, style = T.mono.copy(color = colour), modifier = modifier)

/**
 * A card: name on the left, a status slot on the right, content below.
 *
 * `content` is handed a Modifier that already carries the weight, so a camera
 * frame or a plot stretches without the caller knowing how. Under views this was
 * the trap that gave the camera zero height - a weight inside a wrap-content
 * parent - and expressing it once here is why it cannot recur.
 */
@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    status: @Composable () -> Unit = {},
    /**
     * Give the title row less room, for a card whose content is the point.
     *
     * The control card holds the camera, and every pixel the heading takes is a
     * pixel off the picture somebody is driving by. Overlaying the title on the
     * video would be the other way to win the space and is worse: it costs the
     * legibility of both. So the heading keeps its own line and simply sits
     * closer to the top edge.
     */
    tight: Boolean = false,
    /**
     * Draw the heading over the content instead of above it.
     *
     * For the control card, whose content is a camera somebody drives by. The
     * heading only occupies the left of that line and the picture is centred, so
     * the two do not meet - the row of space the heading reserved was empty
     * either side of it, and giving it back is free height for the picture.
     */
    overlayTitle: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    if (overlayTitle) {
        Box(
            modifier
                .shadow(3.dp, T.rMd, clip = false)
                .clip(T.rMd)
                .background(T.surface)
        ) {
            content(Modifier.fillMaxWidth())
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(start = T.s5, end = T.s5, top = T.s3),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = T.cardTitle, modifier = Modifier.weight(1f))
                status()
            }
        }
        return
    }
    Column(
        modifier
            // Shadow, not a border. On a grey page a white card is already
            // separated; an outline as well is the belt-and-braces look that
            // makes a light interface feel drawn rather than lit.
            .shadow(3.dp, T.rMd, clip = false)
            .clip(T.rMd)
            .background(T.surface)
    ) {
        Row(
            Modifier.padding(
                start = T.s5, end = T.s5,
                top = if (tight) T.s2 else T.s4,
                bottom = if (tight) T.s1 else T.s3
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = T.cardTitle, modifier = Modifier.weight(1f))
            status()
        }
        content(Modifier.weight(1f, fill = true))
    }
}

/**
 * A status pill.
 *
 * Tinted rather than grey-with-a-dot: the same colour at low alpha behind text of
 * that colour, which is how iOS marks state. It reads at a glance without adding
 * a second shape to the row.
 */
@Composable
fun Badge(text: String, colour: Color) {
    val c by animateColorAsState(colour, tween(220), label = "badge")
    Text(
        text,
        style = T.body.copy(color = c, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .clip(T.rPill)
            .background(c.copy(alpha = 0.13f))
            .padding(horizontal = T.s3, vertical = 5.dp)
    )
}

/** How much a control wants to be noticed. */
enum class Emph { Quiet, Tinted, Filled }

/**
 * A control that looks like one.
 *
 * Three weights, because a screen where every button is equally loud has no
 * primary action - and here the primary action is the one that can move a
 * vehicle.
 */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    emph: Emph = Emph.Quiet,
    colour: Color = T.accent,
    big: Boolean = false,
    onTap: () -> Unit,
) {
    val shape = if (big) T.rPill else T.rSm
    val fill = when (emph) {
        Emph.Quiet -> T.surfaceHi
        Emph.Tinted -> colour.copy(alpha = 0.13f)
        Emph.Filled -> colour
    }
    val fg = when (emph) {
        Emph.Quiet -> T.text
        Emph.Tinted -> colour
        Emph.Filled -> Color.White
    }
    /*
     * Pressed state by scale and a haptic, not a ripple.
     *
     * A ripple is Android's idiom - ink spreading from the touch point - and it
     * belongs to a different design language from the rest of this screen. What a
     * physical control does is give slightly and be felt, so the chip shrinks by
     * three percent and taps back. The spring rather than a tween because a
     * button that returns linearly feels like an animation and one that overshoots
     * slightly feels like a button.
     *
     * The haptic matters most on the controls that arm or move something: the
     * confirmation arrives through the finger before the eye has read the state,
     * which is the point of having it.
     */
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 900f), label = "press")

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (emph == Emph.Filled) Modifier.shadow(6.dp, shape, clip = false)
                  else Modifier)
            .clip(shape)
            .background(fill)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onTap()
            }
            .padding(
                horizontal = if (big) T.s6 else T.s4,
                vertical = if (big) T.s4 else 9.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = TextStyle(
                color = fg,
                fontSize = if (big) 17.sp else 13.sp,
                fontWeight = if (big) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = if (big) 0.2.sp else 0.sp,
                textAlign = TextAlign.Center
            )
        )
    }
}

/**
 * What a panel shows when it has nothing to show.
 *
 * A blank rectangle is indistinguishable from a broken one. A mark and a line of
 * text say "nothing here yet" rather than leaving the user to work out which.
 */
@Composable
fun EmptyState(text: String, onDark: Boolean = false, modifier: Modifier = Modifier) {
    val ink = if (onDark) Color.White.copy(alpha = 0.30f) else T.textFaint
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(T.rSm)
                .background(ink.copy(alpha = if (onDark) 0.12f else 0.18f))
        )
        Spacer(Modifier.size(T.s3))
        Text(text, style = T.body.copy(color = ink))
    }
}
