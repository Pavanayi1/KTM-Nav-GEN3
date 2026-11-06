package com.navigator.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * Multi-brand theme tokens. The app is one binary that re-skins per motorcycle
 * brand (KTM / Husqvarna / … — all Pierer Mobility, all the same BCCU dash), so
 * every colour is a [BrandTheme] token rather than a fixed value.
 *
 * The [Ktm] accessor object is kept (all existing `Ktm.Xxx` call sites still
 * compile) but each colour is now a getter over a reactive [current] theme:
 * reading `Ktm.Orange` inside a composable subscribes to `current`, so setting
 * a new brand ([applyBrand]) recomposes the whole app live. Radii are constant
 * across brands and stay plain vals.
 *
 * Semantic colours (Green/Danger) ARE themed for legibility (a light theme
 * needs a darker green/red) but keep their meaning — never repurposed for brand
 * identity. Brand identity is [BrandTheme.accent] only.
 */

enum class Brand(val id: String) {
    KTM("ktm"),
    HUSQVARNA("husqvarna");

    companion object {
        fun fromId(id: String?): Brand = entries.firstOrNull { it.id == id } ?: KTM
    }
}

/** One brand's full token set + identity metadata. */
data class BrandTheme(
    val brand: Brand,
    val isDark: Boolean,
    // Identity
    val displayName: String,
    val wordmark: String,
    val tagline: String,
    val wordmarkItalic: Boolean,
    val wordmarkTracking: TextUnit,
    // Brand accent
    val accent: Color,
    val accentDeep: Color,
    val onAccent: Color, // text/icon colour that sits ON an accent fill
    // Backgrounds
    val board: Color,
    val screen: Color,
    val black: Color,
    val screenDeep: Color,
    // Surfaces
    val surface: Color,
    val surfaceAlt: Color,
    val surfaceAlt2: Color,
    val notifCard: Color,
    val surfaceDisabled: Color,
    val connBanner: Color,
    val monoChip: Color,
    val dashBanner: Color,
    // Borders
    val border: Color,
    val bezel: Color,
    val rowDivider: Color,
    val notifBorder: Color,
    val connBorder: Color,
    val monoChipBorder: Color,
    val borderSoft: Color,
    // Text
    val head: Color, // strongest heading text (was "White")
    val textPrimary: Color,
    val textSecondary: Color,
    val muted: Color,
    val muted2: Color,
    val dim: Color,
    val dim2: Color,
    val dim3: Color,
    val connSub: Color,
    // Semantic (themed for contrast, meaning fixed)
    val green: Color,
    val danger: Color,
)

/** KTM — dark, "READY TO RACE". Values are the original Navigator KTM handoff, 1:1. */
val KtmTheme = BrandTheme(
    brand = Brand.KTM,
    isDark = true,
    displayName = "KTM",
    wordmark = "KTM",
    tagline = "READY TO RACE",
    wordmarkItalic = true,
    wordmarkTracking = (-0.01).em,
    accent = Color(0xFFFF6600),
    accentDeep = Color(0xFFE05500),
    onAccent = Color(0xFF0B0C0E),
    board = Color(0xFF08090A),
    screen = Color(0xFF0B0C0E),
    black = Color(0xFF000000),
    screenDeep = Color(0xFF050505),
    surface = Color(0xFF141618),
    surfaceAlt = Color(0xFF101214),
    surfaceAlt2 = Color(0xFF101215),
    notifCard = Color(0xFF0F0F10),
    surfaceDisabled = Color(0xFF1A1C1F),
    connBanner = Color(0xFF0F1416),
    monoChip = Color(0xFF191B1F),
    dashBanner = Color(0xFF0C0C0C),
    border = Color(0xFF24272D),
    bezel = Color(0xFF2A2D33),
    rowDivider = Color(0xFF202329),
    notifBorder = Color(0xFF1E1E20),
    connBorder = Color(0xFF1D3A2B),
    monoChipBorder = Color(0xFF26282E),
    borderSoft = Color(0xFF3A3D44),
    head = Color(0xFFFFFFFF),
    textPrimary = Color(0xFFE6E7EA),
    textSecondary = Color(0xFFC9CDD3),
    muted = Color(0xFF9AA0A8),
    muted2 = Color(0xFF8A8F98),
    dim = Color(0xFF6C727B),
    dim2 = Color(0xFF5C626B),
    dim3 = Color(0xFF4C525B),
    connSub = Color(0xFF7B8188),
    green = Color(0xFF34D07F),
    danger = Color(0xFFFF4438),
)

/** Husqvarna — light, "Pioneering since 1903". Blue accent on white. */
val HusqvarnaTheme = BrandTheme(
    brand = Brand.HUSQVARNA,
    isDark = false,
    displayName = "Husqvarna",
    wordmark = "HUSQVARNA",
    tagline = "PIONEERING SINCE 1903",
    wordmarkItalic = false,
    wordmarkTracking = 0.22.em,
    accent = Color(0xFF2C5CB0),
    accentDeep = Color(0xFF1E4488),
    onAccent = Color(0xFFFFFFFF),
    board = Color(0xFFE4E9F1),
    screen = Color(0xFFEEF1F6),
    black = Color(0xFFFFFFFF), // "max-contrast bg" → white in a light theme
    screenDeep = Color(0xFFE1E7F0),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF5F7FA),
    surfaceAlt2 = Color(0xFFF2F5F9),
    notifCard = Color(0xFFFFFFFF),
    surfaceDisabled = Color(0xFFDDE3EB),
    connBanner = Color(0xFFECF6F0), // faint green card
    monoChip = Color(0xFFEEF1F6),
    dashBanner = Color(0xFFF0F2F5),
    border = Color(0xFFD6DCE4),
    bezel = Color(0xFFC6CFDA),
    rowDivider = Color(0xFFE7EBF0),
    notifBorder = Color(0xFFE1E6EC),
    connBorder = Color(0xFFB9DEC9),
    monoChipBorder = Color(0xFFD6DCE4),
    borderSoft = Color(0xFFBFC8D3),
    head = Color(0xFF132542),
    textPrimary = Color(0xFF2A3852),
    textSecondary = Color(0xFF48566E),
    muted = Color(0xFF6C778A),
    muted2 = Color(0xFF7A8397),
    dim = Color(0xFF98A2B2),
    dim2 = Color(0xFFA9B1BF),
    dim3 = Color(0xFFBBC2CC),
    connSub = Color(0xFF6C778A),
    green = Color(0xFF1B9A56),
    danger = Color(0xFFE5362B),
)

fun themeFor(brand: Brand): BrandTheme = when (brand) {
    Brand.KTM -> KtmTheme
    Brand.HUSQVARNA -> HusqvarnaTheme
}

/**
 * Reactive theme accessor. Historically named `Ktm`; kept so the ~40 files
 * that read `Ktm.Orange`, `Ktm.Screen`, … keep working unchanged — but every
 * colour now resolves against the live [current] brand and is Compose-reactive.
 */
object Ktm {
    /** The active brand's tokens. Set via [applyBrand]; drives live re-theme. */
    var current by mutableStateOf(KtmTheme)
        private set

    fun applyBrand(brand: Brand) {
        current = themeFor(brand)
    }

    // Brand
    val Orange get() = current.accent
    val OrangeDeep get() = current.accentDeep
    /** Content colour that sits on an [Orange] fill (near-black on KTM, white on Husqvarna). */
    val OnAccent get() = current.onAccent

    // Backgrounds
    val Board get() = current.board
    val Screen get() = current.screen
    val Black get() = current.black
    val ScreenDeep get() = current.screenDeep

    // Surfaces / cards
    val Surface get() = current.surface
    val SurfaceAlt get() = current.surfaceAlt
    val SurfaceAlt2 get() = current.surfaceAlt2
    val NotifCard get() = current.notifCard
    val SurfaceDisabled get() = current.surfaceDisabled
    val ConnBanner get() = current.connBanner
    val MonoChip get() = current.monoChip
    val DashBanner get() = current.dashBanner

    // Borders
    val Border get() = current.border
    val Bezel get() = current.bezel
    val RowDivider get() = current.rowDivider
    val NotifBorder get() = current.notifBorder
    val ConnBorder get() = current.connBorder
    val MonoChipBorder get() = current.monoChipBorder
    val BorderSoft get() = current.borderSoft

    // Text
    val White get() = current.head // "White" historically meant "strongest heading text"
    val TextPrimary get() = current.textPrimary
    val TextSecondary get() = current.textSecondary
    val Muted get() = current.muted
    val Muted2 get() = current.muted2
    val Dim get() = current.dim
    val Dim2 get() = current.dim2
    val Dim3 get() = current.dim3
    val ConnSub get() = current.connSub

    // Semantic
    val Green get() = current.green
    val Danger get() = current.danger

    // Common radii (brand-independent)
    val RadiusCard = 16.dp
    val RadiusButton = 12.dp
    val RadiusRow = 13.dp
}
