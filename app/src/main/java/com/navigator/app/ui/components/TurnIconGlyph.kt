package com.navigator.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.navigator.app.ble.BccuProtocol.TurnIcon

/**
 * Draws a clear, schematic arrow for each dash [TurnIcon] so the rider can
 * visually match a captured Google Maps maneuver bitmap to the correct dash
 * symbol in the calibration screen. These are Navigator's own drawings, not the
 * dash's firmware glyphs (those render on the bike, not in the app) - but they
 * mirror the maneuver each icon represents (direction, severity, U-turn,
 * roundabout, keep/fork, ramp) closely enough to pick the right one at a glance.
 */
@Composable
fun TurnIconGlyph(
    icon: TurnIcon,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color = Color.White,
) {
    Canvas(modifier = modifier.size(size)) { drawTurnGlyph(icon, color) }
}

/**
 * Preferred reference renderer: shows the **official KTM Gen-3 app icon** for
 * this maneuver (via [TurnIconArt]) when one exists, tinted to [color]; falls
 * back to the drawn [TurnIconGlyph] for codes with no official asset. Use this
 * everywhere a maneuver needs a reference picture the rider can match against.
 */
@Composable
fun TurnIconRef(
    icon: TurnIcon,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color = Color.White,
) {
    val res = TurnIconArt.drawableFor(icon)
    if (res != null) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(res),
            contentDescription = icon.name,
            tint = color,
            modifier = modifier.size(size),
        )
    } else {
        TurnIconGlyph(icon, modifier, size, color)
    }
}

private fun DrawScope.drawTurnGlyph(icon: TurnIcon, color: Color) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val name = icon.name
    val left = name.contains("LEFT")
    val right = name.contains("RIGHT")

    fun p(x: Float, y: Float) = Offset(s * x, s * y)

    // Arrowhead pointing along the vector (fromDeg unused; we draw a chevron at tip toward dir).
    fun head(tip: Offset, dir: Offset, len: Float = s * 0.16f) {
        // dir is a unit-ish direction; build two barbs.
        val dl = kotlin.math.hypot(dir.x, dir.y).coerceAtLeast(0.0001f)
        val ux = dir.x / dl; val uy = dir.y / dl
        // perpendicular
        val px = -uy; val py = ux
        val back = Offset(tip.x - ux * len, tip.y - uy * len)
        val b1 = Offset(back.x + px * len * 0.7f, back.y + py * len * 0.7f)
        val b2 = Offset(back.x - px * len * 0.7f, back.y - py * len * 0.7f)
        drawLine(color, tip, b1, stroke.width, StrokeCap.Round)
        drawLine(color, tip, b2, stroke.width, StrokeCap.Round)
    }

    when {
        name == "GO_STRAIGHT" || name == "HEAD_TO" || name == "START" -> {
            drawLine(color, p(0.5f, 0.82f), p(0.5f, 0.24f), stroke.width, StrokeCap.Round)
            head(p(0.5f, 0.18f), Offset(0f, -1f))
        }

        name == "END" || name == "PASS_STATION" -> {
            // Destination pin.
            val c = p(0.5f, 0.42f); val r = s * 0.2f
            drawCircle(color, r, c, style = stroke)
            drawCircle(color, s * 0.05f, c)
            val path = Path().apply {
                moveTo(c.x - r * 0.7f, c.y + r * 0.6f)
                lineTo(c.x, s * 0.86f)
                lineTo(c.x + r * 0.7f, c.y + r * 0.6f)
            }
            drawPath(path, color, style = stroke)
        }

        name.startsWith("UTURN") -> {
            // Up, loop over the top, back down with head pointing down on the far side.
            val nearX = if (left) 0.62f else 0.38f
            val farX = if (left) 0.38f else 0.62f
            val path = Path().apply {
                moveTo(s * nearX, s * 0.84f)
                lineTo(s * nearX, s * 0.42f)
                // semicircle across the top
                cubicTo(s * nearX, s * 0.20f, s * farX, s * 0.20f, s * farX, s * 0.42f)
                lineTo(s * farX, s * 0.60f)
            }
            drawPath(path, color, style = stroke)
            head(p(farX, 0.66f), Offset(0f, 1f))
        }

        name.startsWith("KEEP") || name == "CHANGE_LINE" -> {
            // A fork: common stem, two prongs; the taken prong is a full arrow,
            // the other a short faded stub.
            drawLine(color, p(0.5f, 0.86f), p(0.5f, 0.55f), stroke.width, StrokeCap.Round)
            val faded = color.copy(alpha = 0.35f)
            if (left) {
                drawLine(faded, p(0.5f, 0.55f), p(0.66f, 0.34f), stroke.width, StrokeCap.Round)
                drawLine(color, p(0.5f, 0.55f), p(0.34f, 0.28f), stroke.width, StrokeCap.Round)
                head(p(0.33f, 0.26f), Offset(-0.6f, -1f))
            } else if (right) {
                drawLine(faded, p(0.5f, 0.55f), p(0.34f, 0.34f), stroke.width, StrokeCap.Round)
                drawLine(color, p(0.5f, 0.55f), p(0.66f, 0.28f), stroke.width, StrokeCap.Round)
                head(p(0.67f, 0.26f), Offset(0.6f, -1f))
            } else { // KEEP_MIDDLE
                drawLine(faded, p(0.5f, 0.55f), p(0.34f, 0.32f), stroke.width, StrokeCap.Round)
                drawLine(faded, p(0.5f, 0.55f), p(0.66f, 0.32f), stroke.width, StrokeCap.Round)
                drawLine(color, p(0.5f, 0.55f), p(0.5f, 0.26f), stroke.width, StrokeCap.Round)
                head(p(0.5f, 0.22f), Offset(0f, -1f))
            }
        }

        name.contains("HIGHWAY") -> {
            // Ramp: a curved merge/exit arrow.
            val enter = name.startsWith("ENTER")
            val path = Path()
            if (right) {
                path.moveTo(s * 0.30f, s * 0.84f)
                path.cubicTo(s * 0.34f, s * 0.5f, s * 0.5f, s * 0.42f, s * 0.72f, s * 0.34f)
            } else {
                path.moveTo(s * 0.70f, s * 0.84f)
                path.cubicTo(s * 0.66f, s * 0.5f, s * 0.5f, s * 0.42f, s * 0.28f, s * 0.34f)
            }
            drawPath(path, color, style = stroke)
            val tip = if (right) p(0.74f, 0.33f) else p(0.26f, 0.33f)
            head(tip, if (right) Offset(1f, -0.4f) else Offset(-1f, -0.4f))
            if (!enter) { // leaving: dashed continuation of the main road
                drawLine(color.copy(alpha = 0.35f), p(0.5f, 0.84f), p(0.5f, 0.2f),
                    stroke.width * 0.8f, StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 0.06f, s * 0.06f)))
            }
        }

        name.startsWith("RAB_SECT") -> {
            // Roundabout: circle, entry from bottom, exit arrow toward the section side.
            val c = p(0.5f, 0.5f); val r = s * 0.2f
            drawCircle(color, r, c, style = stroke)
            drawLine(color, p(0.5f, 0.86f), Offset(c.x, c.y + r), stroke.width, StrokeCap.Round)
            // Exit: left-hand (LH) sections exit left, else right; default upper.
            val lh = name.endsWith("_LH")
            val exitTip = if (lh) p(0.16f, 0.32f) else p(0.84f, 0.32f)
            drawLine(color, Offset(c.x, c.y - r), exitTip, stroke.width, StrokeCap.Round)
            head(exitTip, if (lh) Offset(-0.8f, -1f) else Offset(0.8f, -1f))
        }

        left || right -> {
            // Graded turn. Severity sets the bend height + head direction.
            val sev = when {
                name.startsWith("LIGHT") -> 0
                name.startsWith("QUITE") -> 1
                name.startsWith("HEAVY") -> 2
                else -> 1
            }
            val bendY = when (sev) { 0 -> 0.42f; 1 -> 0.5f; else -> 0.62f }
            val sideX = if (left) (when (sev) { 0 -> 0.30f; 1 -> 0.22f; else -> 0.20f })
                        else (when (sev) { 0 -> 0.70f; 1 -> 0.78f; else -> 0.80f })
            val topY = when (sev) { 0 -> 0.24f; 1 -> 0.30f; else -> 0.44f }
            val path = Path().apply {
                moveTo(s * 0.5f, s * 0.86f)
                lineTo(s * 0.5f, s * bendY)
                quadraticBezierTo(s * 0.5f, s * (bendY - 0.06f), s * sideX, s * topY)
            }
            drawPath(path, color, style = stroke)
            // Head direction: slight points up-ish, heavy points more sideways/down.
            val dir = when (sev) {
                0 -> Offset(if (left) -0.7f else 0.7f, -1f)
                1 -> Offset(if (left) -1f else 1f, -0.5f)
                else -> Offset(if (left) -1f else 1f, 0.2f)
            }
            head(p(sideX - if (left) 0.02f else -0.02f, topY), dir)
        }

        else -> {
            // UNKNOWN / UNDEFINED / FERRY etc.: a neutral dashed line + dot.
            drawLine(color.copy(alpha = 0.5f), p(0.5f, 0.82f), p(0.5f, 0.26f),
                stroke.width, StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 0.07f, s * 0.06f)))
            drawCircle(color.copy(alpha = 0.5f), s * 0.05f, p(0.5f, 0.2f))
        }
    }
}
