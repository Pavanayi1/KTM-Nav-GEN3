package com.mensa.riderintercom.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The palette is sampled straight from the design mockup rather than approximated, so
 * the built screens and the reference render agree.
 *
 * Dark only. This screen is read in daylight, through a visor, at a traffic light, so
 * the rules are: one accent, heavy contrast against near-black, and nothing thin.
 */
object Brand {
    val Background = Color(0xFF0C1116)
    val Surface = Color(0xFF12151A)
    val SurfaceElevated = Color(0xFF171B21)
    /** Warm-tinted fill behind a selected card, as in the mockup's "Connect Rider". */
    val SurfaceSelected = Color(0xFF1F1615)
    val Border = Color(0xFF23272E)
    val BottomBar = Color(0xFF080A0D)

    val Accent = Color(0xFFF97A12)
    val AccentBright = Color(0xFFFF8A2B)
    val AccentDeep = Color(0xFFE25C02)

    /** The joining rider's colour, so the two phones never look alike at a glance. */
    val Blue = Color(0xFF2F9BFF)
    val BlueBright = Color(0xFF5BB0FF)
    val BlueDeep = Color(0xFF1877D6)

    val TextPrimary = Color(0xFFF5F5F5)
    val TextDim = Color(0xFF8E9095)

    /** Floor states. Green is this rider, amber is the buddy, grey is a clear channel. */
    val Talking = Color(0xFF34D058)
    val Buddy = Color(0xFFFBBF24)
    val Idle = Color(0xFF6B7280)
    val Danger = Color(0xFFEF4444)

    val AccentGradient = Brush.verticalGradient(listOf(AccentBright, AccentDeep))
}

/**
 * Which rider this phone is.
 *
 * The host's screen is orange and the joiner's is blue, so two riders comparing phones at
 * a stop can tell instantly which one is hosting - and so a screenshot from a bug report
 * says which end it came from.
 */
data class Accents(
    val base: Color,
    val bright: Color,
    val deep: Color,
) {
    /** Built once per role, not per draw: a Brush rebuilt inside a DrawScope recreates
     *  its shader on every frame, which is one of the things that made the dial stutter. */
    val gradient: Brush = Brush.verticalGradient(listOf(bright, deep))
    val soft: Color get() = base.copy(alpha = 0.18f)

    /** Fill behind a selected card: the role colour laid faintly over the card surface. */
    val selectedFill: Color = base.copy(alpha = 0.10f).compositeOver(Brand.Surface)
}

val HostAccents = Accents(Brand.Accent, Brand.AccentBright, Brand.AccentDeep)
val JoinAccents = Accents(Brand.Blue, Brand.BlueBright, Brand.BlueDeep)

/** Read by every component, so role colour never has to be threaded through by hand. */
val LocalAccents = staticCompositionLocalOf { HostAccents }

@Composable
fun WithAccents(host: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAccents provides if (host) HostAccents else JoinAccents,
        content = content,
    )
}

/** Kept for the older call sites until every screen has moved over. */
internal object IntercomColors {
    val accent = Brand.Accent
    val talking = Brand.Talking
    val muted = Brand.Buddy
    val danger = Brand.Danger
    val dim = Brand.TextDim
}

private val Scheme = darkColorScheme(
    primary = Brand.Accent,
    onPrimary = Color(0xFF1A0D02),
    secondary = Brand.Talking,
    background = Brand.Background,
    onBackground = Brand.TextPrimary,
    surface = Brand.Surface,
    onSurface = Brand.TextPrimary,
    surfaceVariant = Brand.SurfaceElevated,
    onSurfaceVariant = Brand.TextDim,
    outline = Brand.Border,
    error = Brand.Danger,
)

private val IntercomTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
)

@Composable
fun RiderIntercomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = IntercomTypography,
        content = content,
    )
}
