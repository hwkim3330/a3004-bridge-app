package re.keti.a3004bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val s5 = 24.dp

    val rSm = RoundedCornerShape(12.dp)
    val rMd = RoundedCornerShape(18.dp)
    val rLg = RoundedCornerShape(22.dp)

    val title = TextStyle(color = text, fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)

    /** Section heading: small, tracked out, muted. A label, not a shout. */
    val label = TextStyle(color = textFaint, fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

    val body = TextStyle(color = textDim, fontSize = 11.sp, lineHeight = 15.sp)

    /** Telemetry. Fixed-width digits, so a value changing does not shift its neighbour. */
    val mono = TextStyle(color = textDim, fontSize = 11.sp,
        fontFamily = FontFamily.Monospace)
}

/** A section label. */
@Composable
fun Label(s: String, modifier: Modifier = Modifier) =
    Text(s.uppercase(), style = T.label, modifier = modifier)

/** Telemetry text. */
@Composable
fun Mono(s: String, colour: Color = T.textDim, modifier: Modifier = Modifier) =
    Text(s, style = T.mono.copy(color = colour), modifier = modifier)

/**
 * A panel: label on the left, a status slot on the right, content below.
 *
 * `content` is given a Modifier already carrying the panel's weight, so a camera
 * frame or a plot stretches without the caller having to know how. Under views
 * this was the trap that gave the camera zero height - a weight inside a
 * wrap-content parent - and expressing it once here is why it cannot recur.
 */
@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    status: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Column(
        modifier
            // Shadow, not a border. On a grey page a white card is already
            // separated; an outline as well is the belt-and-braces look that
            // makes a light interface feel drawn rather than lit. Applied before
            // the clip so it renders outside the shape.
            .shadow(2.dp, T.rMd, clip = false)
            .clip(T.rMd)
            .background(T.surface)
    ) {
        Row(
            Modifier.padding(start = T.s4, end = T.s4, top = T.s3, bottom = T.s2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Label(title, Modifier.weight(1f))
            status()
        }
        content(Modifier.weight(1f, fill = true))
    }
}

/** A dot-and-text badge. The dot carries the colour so the text stays readable. */
@Composable
fun Badge(text: String, colour: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(T.surfaceHi)
            .padding(start = T.s2, end = T.s3, top = T.s1, bottom = T.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(T.s2)
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(colour))
        Mono(text)
    }
}

/** A tappable control that looks like one, rather than a system default. */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    fill: Color = T.surfaceHi,
    fg: Color = T.text,
    big: Boolean = false,
    onTap: () -> Unit,
) {
    val shape: Shape = if (big) T.rLg else T.rSm
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .then(
                // Outline only the quiet variant: a filled, coloured control
                // already reads as raised and a border on it just muddies the edge.
                if (fill == T.surfaceHi) Modifier.border(1.dp, T.hairline, shape)
                else Modifier
            )
            .clickable(onClick = onTap)
            .padding(
                horizontal = if (big) T.s5 else T.s4,
                vertical = if (big) T.s4 else T.s2
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = TextStyle(
                color = fg,
                fontSize = if (big) 17.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = if (big) 1.4.sp else 0.sp,
                textAlign = TextAlign.Center
            )
        )
    }
}
