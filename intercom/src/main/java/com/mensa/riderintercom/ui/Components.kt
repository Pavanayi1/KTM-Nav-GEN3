package com.mensa.riderintercom.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.mensa.riderintercom.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ------------------------------------------------------------------ brand mark */

/**
 * The wordmark's wave: five rounded bars at speaking-level heights. Drawn rather than
 * shipped as a bitmap so it stays crisp at any size and recolours with state.
 */
@Composable
fun WaveMark(
    modifier: Modifier = Modifier,
    color: Color = LocalAccents.current.base,
    bars: List<Float> = listOf(0.46f, 0.82f, 1f, 0.72f, 0.38f),
) {
    Canvas(modifier) {
        val gap = size.width / (bars.size * 2f - 1f)
        val w = gap
        bars.forEachIndexed { i, h ->
            val barH = size.height * h
            drawRoundRect(
                color = color,
                topLeft = Offset(i * gap * 2f, (size.height - barH) / 2f),
                size = Size(w, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

/** App title block: wave mark, name, and the letterspaced descriptor beneath it. */
@Composable
fun BrandHeader(
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(2.dp))
        WaveMark(Modifier.size(34.dp, 30.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Rider Intercom",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Brand.TextPrimary,
            )
            Text(
                "RIDER-TO-RIDER VOICE",
                fontSize = 10.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Medium,
                color = Brand.TextDim,
            )
        }
        trailing?.invoke()
    }
}

/* ----------------------------------------------------------------- containers */

/** The standard card: flat dark fill, hairline border, generous radius. */
@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    var m = modifier
        .clip(shape)
        .background(if (selected) LocalAccents.current.selectedFill else Brand.Surface)
        .border(
            BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) LocalAccents.current.base else Brand.Border),
            shape,
        )
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(
        m.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A titled section, used down the settings and guidance screens. */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BrandCard(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (title != null) {
            Text(
                title.uppercase(),
                fontSize = 11.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.SemiBold,
                color = Brand.TextDim,
            )
        }
        content()
    }
}

/* ------------------------------------------------------------- action tiles */

/** One of the two big choices at the top of the start screen. */
@Composable
fun ActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = when {
        !enabled -> Brand.Idle
        selected -> LocalAccents.current.base
        else -> Brand.TextPrimary
    }
    BrandCard(
        modifier = modifier.height(136.dp),
        selected = selected && enabled,
        onClick = if (enabled) onClick else null,
        contentPadding = 14.dp,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Brand.TextPrimary else Brand.Idle,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                fontSize = 12.sp,
                color = Brand.TextDim,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp,
            )
        }
    }
}

/* --------------------------------------------------------------- status pill */

/** Full-face helmet, from a vector drawable so the shell and visor actually read. */
@Composable
fun HelmetGlyph(modifier: Modifier = Modifier, color: Color = Brand.TextPrimary) {
    Icon(
        imageVector = ImageVector.vectorResource(R.drawable.ic_helmet),
        contentDescription = null,
        tint = color,
        modifier = modifier,
    )
}

/** The connection read-out: helmet, a state dot, headline and one line of detail. */
@Composable
fun StatusPill(
    headline: String,
    detail: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    BrandCard(modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HelmetGlyph(Modifier.size(38.dp), Brand.TextPrimary)
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    headline,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.TextPrimary,
                )
                Text(detail, fontSize = 13.sp, color = Brand.TextDim, lineHeight = 16.sp)
            }
        }
    }
}

/* ------------------------------------------------------------------ mic dial */

/**
 * Runs the breathing animation only while something is actually happening.
 *
 * Returning early when idle means the infinite transition is never composed, so no frame
 * callback is scheduled at all. An always-on transition animating 1f to 1f still drives
 * the choreographer every frame, which is free jank for no visible motion.
 */
@Composable
private fun pulseScale(active: Boolean): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "scale",
    )
    return scale
}

/**
 * The centrepiece: a glowing ring with the microphone in it, flanked by the arcs that
 * stand for the voice going out and coming back. Its colour is the one thing a rider has
 * to read at a glance, so state drives the whole dial rather than a small badge on it.
 *
 * Everything that can be built once is built once. Brushes created inside a DrawScope
 * rebuild their shader on every frame; at 60 fps on a 210dp circle that was a measurable
 * part of the stutter. The halo is drawn as stacked translucent circles for the same
 * reason - no shader at all.
 */
@Composable
fun MicDial(
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 190.dp,
    active: Boolean = false,
) {
    val px = with(LocalDensity.current) { size.toPx() }
    val ringR = px * 0.33f
    val ringBrush = remember(color, px) {
        Brush.verticalGradient(
            colors = listOf(color, color.copy(alpha = 0.6f)),
            startY = px / 2f - ringR,
            endY = px / 2f + ringR,
        )
    }
    val haloAlpha = if (active) 0.30f else 0.16f
    val scale = pulseScale(active)

    Box(
        modifier
            .size(size)
            // graphicsLayer, not Modifier.scale: this is a draw-time transform, so the
            // pulse never triggers a measure or layout pass.
            .graphicsLayer { scaleX = scale; scaleY = scale },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val d = this.size.minDimension
            val c = Offset(this.size.width / 2f, this.size.height / 2f)

            // Halo: three stacked translucent discs instead of a radial-gradient shader.
            for (i in 3 downTo 1) {
                drawCircle(
                    color = color.copy(alpha = haloAlpha / (i * 2.2f)),
                    radius = ringR + d * 0.045f * i,
                    center = c,
                )
            }

            // Side arcs: three on each flank, fading outwards.
            for (i in 1..3) {
                val r = ringR + d * (0.055f * i + 0.03f)
                for (startAngle in listOf(135f, 315f)) {
                    drawArc(
                        color = Brand.Idle.copy(alpha = 0.45f - i * 0.11f),
                        startAngle = startAngle,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(c.x - r, c.y - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(width = d * 0.018f, cap = StrokeCap.Round),
                    )
                }
            }

            drawCircle(color = Brand.Surface, radius = ringR, center = c)
            drawCircle(
                brush = ringBrush,
                radius = ringR,
                center = c,
                style = Stroke(width = d * 0.028f),
            )
        }
        Icon(
            icon,
            contentDescription = null,
            tint = Brand.TextPrimary,
            modifier = Modifier.size(size * 0.30f),
        )
    }
}

/* --------------------------------------------------------------- bottom bar */

data class BarItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val active: Boolean = false,
    val onClick: () -> Unit = {},
)

/** The three-up footer: what music, the intercom and the audio route are each doing. */
@Composable
fun StatusBar(items: List<BarItem>, modifier: Modifier = Modifier) {
    BrandCard(modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            items.forEach { item ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = item.onClick)
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(50.dp).clip(CircleShape).let {
                            if (item.active) it.background(LocalAccents.current.gradient)
                            else it.background(Brand.SurfaceElevated)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = item.title,
                            tint = if (item.active) Color(0xFF1A0D02) else Brand.TextPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        item.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        item.subtitle,
                        fontSize = 10.sp,
                        color = Brand.TextDim,
                        textAlign = TextAlign.Center,
                        lineHeight = 13.sp,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ buttons */

/** Full-width primary action with the brand gradient. */
@Composable
fun PrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .let { if (enabled) it.background(LocalAccents.current.gradient) else it.background(Brand.SurfaceElevated) }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color(0xFF1A0D02) else Brand.Idle,
        )
    }
}

/** Secondary action: outlined, quieter, same geometry. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalAccents.current.base,
    enabled: Boolean = true,
    height: Dp = 52.dp,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(Brand.SurfaceElevated)
            .border(BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.55f) else Brand.Border), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) color else Brand.Idle,
        )
    }
}

/* ------------------------------------------------------------------- pieces */

/** Horizontal level meter, used for the live microphone reading. */
@Composable
fun LevelBar(level: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Brand.SurfaceElevated),
    ) {
        Box(
            Modifier
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
    }
}

@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color = Brand.TextPrimary) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = Brand.TextDim)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

/** A dot plus a word, for "Intercom Ready" style state lines. */
@Composable
fun DotStatus(text: String, color: Color, subtitle: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(9.dp))
            Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Brand.TextPrimary)
        }
        if (subtitle != null) {
            Spacer(Modifier.height(5.dp))
            Text(subtitle, fontSize = 13.sp, color = Brand.TextDim, textAlign = TextAlign.Center)
        }
    }
}

/** Back-arrow + title row for the secondary screens. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Brand.Surface)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Brand.TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Brand.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, color = Brand.TextDim, lineHeight = 16.sp)
            }
        }
        trailing?.invoke()
    }
}
