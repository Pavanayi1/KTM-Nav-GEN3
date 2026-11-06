package com.mensa.riderintercom.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The two microphone glyphs, defined here rather than pulled from
 * material-icons-extended. That library is about 40 MB of the debug APK for two icons,
 * and this app gets sideloaded onto test phones over and over during road testing.
 */
object MicIcons {

    val Mic: ImageVector by lazy {
        ImageVector.Builder(
            name = "Mic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 14f)
                curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f)
                verticalLineTo(5f)
                curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
                reflectiveCurveToRelative(-3f, 1.34f, -3f, 3f)
                verticalLineToRelative(6f)
                curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
                close()
                moveTo(17.3f, 11f)
                curveToRelative(0f, 3f, -2.54f, 5.1f, -5.3f, 5.1f)
                reflectiveCurveTo(6.7f, 14f, 6.7f, 11f)
                horizontalLineTo(5f)
                curveToRelative(0f, 3.41f, 2.72f, 6.23f, 6f, 6.72f)
                verticalLineTo(21f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-3.28f)
                curveToRelative(3.28f, -0.48f, 6f, -3.3f, 6f, -6.72f)
                horizontalLineToRelative(-1.7f)
                close()
            }
        }.build()
    }

    val MicOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "MicOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(19f, 11f)
                horizontalLineToRelative(-1.7f)
                curveToRelative(0f, 0.74f, -0.16f, 1.43f, -0.43f, 2.05f)
                lineToRelative(1.23f, 1.23f)
                curveToRelative(0.56f, -0.98f, 0.9f, -2.09f, 0.9f, -3.28f)
                close()
                moveTo(14.98f, 11.17f)
                curveToRelative(0f, -0.06f, 0.02f, -0.11f, 0.02f, -0.17f)
                verticalLineTo(5f)
                curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
                reflectiveCurveTo(9f, 3.34f, 9f, 5f)
                verticalLineToRelative(0.18f)
                lineToRelative(5.98f, 5.99f)
                close()
                moveTo(4.27f, 3f)
                lineTo(3f, 4.27f)
                lineToRelative(6.01f, 6.01f)
                verticalLineTo(11f)
                curveToRelative(0f, 1.66f, 1.33f, 3f, 2.99f, 3f)
                curveToRelative(0.22f, 0f, 0.44f, -0.03f, 0.65f, -0.08f)
                lineToRelative(1.66f, 1.66f)
                curveToRelative(-0.71f, 0.33f, -1.5f, 0.52f, -2.31f, 0.52f)
                curveToRelative(-2.76f, 0f, -5.3f, -2.1f, -5.3f, -5.1f)
                horizontalLineTo(5f)
                curveToRelative(0f, 3.41f, 2.72f, 6.23f, 6f, 6.72f)
                verticalLineTo(21f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-3.28f)
                curveToRelative(0.91f, -0.13f, 1.77f, -0.45f, 2.54f, -0.9f)
                lineTo(19.73f, 21f)
                lineTo(21f, 19.73f)
                lineTo(4.27f, 3f)
                close()
            }
        }.build()
    }
}
