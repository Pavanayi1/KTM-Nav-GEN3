package com.navigator.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Brand-driven theme. The app re-skins per motorcycle brand (see [Ktm]/[BrandTheme]);
 * KTM is dark (orange on near-black), Husqvarna is light (blue on white). The
 * Material colorScheme and the system bar appearance are both derived from the
 * live [Ktm.current] theme, so switching brand recolours everything. The
 * Notifications screen still switches to a max-contrast background via
 * [highContrast] for outdoor legibility (pure black on dark, white on light).
 */
private fun schemeFor(t: BrandTheme) = if (t.isDark) {
    darkColorScheme(
        primary = t.accent, onPrimary = t.onAccent,
        secondary = t.accent, tertiary = t.green,
        background = t.screen, onBackground = t.head,
        surface = t.surface, onSurface = t.textPrimary,
        surfaceVariant = t.surface, onSurfaceVariant = t.textSecondary,
        primaryContainer = t.accent, onPrimaryContainer = t.onAccent,
        outline = t.border, error = t.danger, onError = t.onAccent,
        errorContainer = Color(0xFF3A1512), onErrorContainer = t.textPrimary,
    )
} else {
    lightColorScheme(
        primary = t.accent, onPrimary = t.onAccent,
        secondary = t.accent, tertiary = t.green,
        background = t.screen, onBackground = t.head,
        surface = t.surface, onSurface = t.textPrimary,
        surfaceVariant = t.surfaceAlt, onSurfaceVariant = t.textSecondary,
        primaryContainer = t.accent, onPrimaryContainer = t.onAccent,
        outline = t.border, error = t.danger, onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF7D9D6), onErrorContainer = t.textPrimary,
    )
}

@Composable
fun NavigatorTheme(
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    val theme = Ktm.current // reactive: recomposes on brand switch
    var colorScheme = schemeFor(theme)
    if (highContrast) {
        colorScheme = colorScheme.copy(
            background = theme.black, surface = theme.black,
            onBackground = theme.head, onSurface = theme.head,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.value.toInt()
            window.navigationBarColor =
                (if (highContrast) theme.black else theme.screen).value.toInt()
            // Light theme needs DARK system-bar icons; dark theme needs light.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !theme.isDark
                isAppearanceLightNavigationBars = !theme.isDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
