package com.navigator.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.ui.components.BikeBitmaps
import com.navigator.app.ui.components.pressFeedback
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.Brand
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.NavIcons
import com.navigator.app.ui.theme.themeFor
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * "Choose your ride" - a full-bleed showroom, one swipeable page per brand.
 * KTM stands on a drifting topographic terrain map under a mountain ridge;
 * Husqvarna stands on a road running to the horizon, lane dashes rolling
 * toward the viewer. The bike art (res/drawable-nodpi/bike_*.webp, cut out of
 * its background) glides faster than its scenery while swiping, for depth.
 *
 * If a full portrait scene image is added as res/drawable-nodpi/
 * brand_scene_ktm or brand_scene_husqvarna, that image replaces the drawn
 * scene and cut-out bike for that brand automatically.
 *
 * The page's own colours drive this screen, so swiping never re-themes the
 * whole app mid-gesture; the chosen brand is applied once, by [onPair].
 * [onBack] is non-null only when reached from Settings.
 */
@Composable
fun BrandSelectScreen(
    onPair: (Brand) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val brands = Brand.entries
    val startPage = brands.indexOf(Ktm.current.brand).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startPage) { brands.size }
    val scope = rememberCoroutineScope()

    // Continuous 0..brands-1 position, so colours blend while the finger drags.
    val position = pager.currentPage + pager.currentPageOffsetFraction
    fun blend(pick: (Brand) -> Color): Color {
        val i = position.toInt().coerceIn(0, brands.lastIndex)
        val j = (i + 1).coerceAtMost(brands.lastIndex)
        return lerp(pick(brands[i]), pick(brands[j]), (position - i).coerceIn(0f, 1f))
    }
    val base = blend { sceneFor(it).base }
    val current = brands[pager.currentPage]

    val time = rememberInfiniteTransition(label = "scene")
    val drift = time.animateFloat(
        0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "drift",
    )
    val roll = time.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart), label = "roll",
    )

    Box(Modifier.fillMaxSize().background(base)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            // -1..1: how far this page is from settled centre.
            val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
            BrandPage(brands[page], offset, drift, roll)
        }

        // Header: stays put while pages slide beneath it.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Icon(
                    NavIcons.ChevronLeft, contentDescription = "Back", tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(6.dp).size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column {
                Text("NAVIGATOR GEN3", color = Color.White.copy(alpha = 0.55f), fontFamily = JetBrainsMono,
                    fontSize = 11.sp, letterSpacing = 3.5.sp)
                Text("Choose your ride", color = Color.White.copy(alpha = 0.85f), fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }

        // Footer: page indicator + action. The button colour slides between
        // the brands' accents as the page moves.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 22.dp).padding(bottom = 22.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                brands.forEachIndexed { i, b ->
                    val closeness = (1f - abs(position - i)).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .height(4.dp)
                            .width((14 + 30 * closeness).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(lerp(Color.White.copy(alpha = 0.25f), sceneFor(b).accent, closeness))
                            .clickable { scope.launch { pager.animateScrollToPage(i) } },
                    )
                }
                Spacer(Modifier.weight(1f))
                val other = brands[(pager.currentPage + 1) % brands.size]
                Text(
                    "Swipe for ${themeFor(other).displayName} ›",
                    color = Color.White.copy(alpha = 0.6f), fontFamily = Barlow, fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        scope.launch { pager.animateScrollToPage((pager.currentPage + 1) % brands.size) }
                    },
                )
            }
            // The next brand's accent wipes across the button, tracking the finger,
            // instead of blending colours (orange -> blue goes through mauve).
            val lead = position.toInt().coerceIn(0, brands.lastIndex)
            val next = (lead + 1).coerceAtMost(brands.lastIndex)
            val frac = (position - lead).coerceIn(0f, 1f)
            val nearest = brands[if (frac > 0.5f) next else lead]
            Box(
                Modifier
                    .pressFeedback { onPair(current) }
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(sceneFor(brands[lead]).accent)
                    .drawBehind {
                        if (frac > 0f) {
                            val x = size.width * (1f - frac)
                            drawRect(sceneFor(brands[next]).accent, topLeft = Offset(x, 0f),
                                size = androidx.compose.ui.geometry.Size(size.width - x, size.height))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val fg = sceneFor(nearest).onAccent
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (onBack == null) "PAIR YOUR " else "RIDE WITH ") + themeFor(nearest).displayName.uppercase(),
                        color = fg, fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold,
                        fontSize = 19.sp, letterSpacing = 1.2.sp,
                    )
                    Spacer(Modifier.size(10.dp))
                    Icon(NavIcons.ChevronRight, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Per-brand scene palette. Dark for both: the line art is white-on-dark. */
private data class Scene(val base: Color, val deep: Color, val accent: Color, val onAccent: Color, val line: Color)

private fun sceneFor(brand: Brand): Scene = when (brand) {
    Brand.KTM -> Scene(
        base = Color(0xFF060709), deep = Color(0xFF0B0C0E),
        accent = Color(0xFFFF6600), onAccent = Color(0xFF0B0C0E), line = Color(0xFFFFFFFF),
    )
    Brand.HUSQVARNA -> Scene(
        base = Color(0xFF0A162A), deep = Color(0xFF0E1D36),
        accent = Color(0xFF3A6CC4), onAccent = Color(0xFFFFFFFF), line = Color(0xFFE8EEF8),
    )
}

@Composable
private fun BrandPage(brand: Brand, offset: Float, drift: State<Float>, roll: State<Float>) {
    val context = LocalContext.current
    val scene = sceneFor(brand)
    val theme = themeFor(brand)
    // Optional hand-made portrait scene overrides the drawn one.
    val sceneRes = remember(brand) {
        context.resources.getIdentifier("brand_scene_${theme.brand.id}", "drawable", context.packageName)
    }
    val settled by animateFloatAsState(
        if (abs(offset) < 0.01f) 1f else 0f, spring(dampingRatio = 0.8f), label = "settle",
    )

    // Clipped: the scenery is translated for parallax and would otherwise
    // paint across the neighbouring page (drawn later, so on top).
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        if (sceneRes != 0) {
            Image(
                bitmap = BikeBitmaps.get(context, sceneRes), contentDescription = "${theme.displayName} motorcycle",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { translationX = offset * w * 0.45f },
            )
        } else {
            // Scenery moves at half speed against the page: parallax depth.
            Canvas(Modifier.fillMaxSize().graphicsLayer { translationX = offset * w * 0.5f }) {
                when (brand) {
                    Brand.KTM -> drawTerrain(scene, drift.value)
                    Brand.HUSQVARNA -> drawRoad(scene, roll.value, drift.value)
                }
            }
            val art = if (brand == Brand.KTM) com.navigator.app.R.drawable.bike_ktm
                else com.navigator.app.R.drawable.bike_husqvarna
            Image(
                bitmap = BikeBitmaps.get(context, art),
                contentDescription = "${theme.displayName} motorcycle",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(maxHeight * 0.38f)
                    .offset(y = maxHeight * 0.22f)
                    .graphicsLayer {
                        // Bike runs ahead of the page as it leaves: foreground depth.
                        translationX = -offset * w * 0.30f
                        val s = 0.94f + 0.06f * settled
                        scaleX = s; scaleY = s
                        alpha = 1f - 0.5f * abs(offset).coerceAtMost(1f)
                    },
            )
        }

        // Bottom fade so the type always sits on solid colour.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(maxHeight * 0.42f)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to scene.base.copy(alpha = 0.92f), 1f to scene.base)),
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, end = 24.dp, bottom = 150.dp)
                .navigationBarsPadding()
                .graphicsLayer {
                    translationX = -offset * w * 0.15f
                    alpha = 1f - abs(offset).coerceAtMost(1f)
                },
        ) {
            Text(
                theme.wordmark,
                color = Color.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold,
                fontStyle = if (theme.wordmarkItalic) FontStyle.Italic else FontStyle.Normal,
                fontSize = if (brand == Brand.KTM) 76.sp else 50.sp,
                lineHeight = if (brand == Brand.KTM) 72.sp else 52.sp,
                letterSpacing = theme.wordmarkTracking,
            )
            Text(
                theme.tagline, color = scene.accent, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, letterSpacing = 4.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                if (brand == Brand.KTM) "Turn-by-turn, calls and messages on your KTM dash - built for the trail as much as the city."
                else "Directions, calls and messages on your Husqvarna dash - clean, quiet, out of the way.",
                color = Color.White.copy(alpha = 0.62f), fontFamily = Barlow, fontSize = 15.sp, lineHeight = 21.sp,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(0.92f),
            )
        }
    }
}

/* ---------------------------------------------------------------- scenes */

/** KTM: topographic contours drifting slowly, a double mountain ridge, a dirt trail under the bike. */
private fun DrawScope.drawTerrain(s: Scene, t: Float) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(0f to s.deep, 1f to s.base))
    drawRect(Brush.radialGradient(
        0f to s.accent.copy(alpha = 0.24f), 0.5f to s.accent.copy(alpha = 0.06f), 1f to Color.Transparent,
        center = Offset.Zero, radius = w * 1.05f,
    ))
    // Contours: stacked wavy lines, each phase-shifted, drifting sideways.
    val phase = t * 2f * Math.PI.toFloat()
    for (i in 0 until 16) {
        val y0 = h * (0.06f + i * 0.036f)
        val path = Path()
        var x = -20f
        path.moveTo(x, y0)
        while (x <= w + 20f) {
            val y = y0 +
                h * 0.018f * sin(x / w * 5.2f + i * 0.9f + phase) +
                h * 0.010f * sin(x / w * 11f - i * 0.5f - phase * 2f)
            path.lineTo(x, y)
            x += w / 48f
        }
        val a = if (i % 4 == 0) 0.13f else 0.06f
        drawPath(path, s.line.copy(alpha = a), style = Stroke(width = if (i % 4 == 0) 1.6f else 1f))
    }
    // Far and near ridges; the near one is filled so contours stop behind it.
    // Each ridge: a filled body (hides the contours behind it) and a stroke
    // along the crest only - stroking the closed body also drew its side
    // and bottom edges as stray lines down the screen.
    fun crest(yBase: Float, amp: Float, seed: Float): Path = Path().apply {
        var x = 0f
        moveTo(0f, yBase - amp * (0.55f + 0.45f * sin(seed)) * (0.6f + 0.4f * sin(seed * 2)))
        while (x <= w + w / 28f) {
            val y = yBase - amp * (0.55f + 0.45f * sin(x / w * 7f + seed)) * (0.6f + 0.4f * sin(x / w * 2.3f + seed * 2))
            lineTo(x, y); x += w / 28f
        }
    }
    fun body(c: Path): Path = Path().apply { addPath(c); lineTo(w + w / 28f, h); lineTo(0f, h); close() }
    val farCrest = crest(h * 0.52f, h * 0.10f, 1.3f)
    drawPath(body(farCrest), s.base.copy(alpha = 0.55f))
    drawPath(farCrest, s.accent.copy(alpha = 0.30f), style = Stroke(width = 1.5f))
    val nearCrest = crest(h * 0.60f, h * 0.05f, 4.1f)
    drawPath(body(nearCrest), Brush.verticalGradient(0f to s.deep, 1f to s.base, startY = h * 0.55f, endY = h))
    drawPath(nearCrest, s.accent.copy(alpha = 0.55f), style = Stroke(width = 2f))
    // Dirt trail curving out from under the bike.
    for (k in 0..1) {
        val p = Path().apply {
            moveTo(w * (0.30f + k * 0.34f), h * 0.60f)
            cubicTo(w * (0.25f + k * 0.4f), h * 0.72f, w * (0.05f + k * 0.8f), h * 0.86f, w * (-0.2f + k * 1.4f), h)
        }
        drawPath(p, s.accent.copy(alpha = 0.22f), style = Stroke(width = 2f, cap = StrokeCap.Round))
    }
}

/** Husqvarna: night road to a vanishing point, rolling lane dashes, low hills, a pale horizon glow. */
private fun DrawScope.drawRoad(s: Scene, roll: Float, t: Float) {
    val w = size.width; val h = size.height
    val horizon = h * 0.47f
    drawRect(Brush.verticalGradient(0f to s.deep, 0.6f to s.base, 1f to Color(0xFF07101F)))
    drawRect(Brush.radialGradient(
        0f to s.line.copy(alpha = 0.14f), 1f to Color.Transparent,
        center = Offset(w * 0.5f, horizon), radius = w * 0.75f,
    ))
    // Sky: sparse horizontal hairlines.
    for (i in 0 until 9) {
        val y = h * (0.10f + i * 0.052f)
        drawLine(s.line.copy(alpha = 0.04f + 0.01f * (i % 3)), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
    }
    // Low hills on the horizon.
    val hills = Path().apply {
        moveTo(0f, horizon)
        var x = 0f
        while (x <= w) {
            lineTo(x, horizon - h * 0.035f * (0.5f + 0.5f * sin(x / w * 6f + 0.7f)) - h * 0.02f * sin(x / w * 2f))
            x += w / 32f
        }
        lineTo(w, horizon); close()
    }
    drawPath(hills, s.base)
    drawPath(hills, s.accent.copy(alpha = 0.5f), style = Stroke(width = 1.5f))
    drawLine(s.line.copy(alpha = 0.18f), Offset(0f, horizon), Offset(w, horizon), strokeWidth = 1f)
    // Road surface, edges converge on the vanishing point.
    val vp = Offset(w * 0.5f, horizon)
    val road = Path().apply {
        moveTo(vp.x - w * 0.02f, horizon); lineTo(vp.x + w * 0.02f, horizon)
        lineTo(w * 1.25f, h); lineTo(-w * 0.25f, h); close()
    }
    drawPath(road, Brush.verticalGradient(0f to Color(0xFF12264A), 1f to Color(0xFF0B1830), startY = horizon, endY = h))
    drawLine(s.line.copy(alpha = 0.45f), Offset(vp.x - w * 0.02f, horizon), Offset(-w * 0.25f, h), strokeWidth = 2f)
    drawLine(s.line.copy(alpha = 0.45f), Offset(vp.x + w * 0.02f, horizon), Offset(w * 1.25f, h), strokeWidth = 2f)
    // Centre dashes: perspective spacing (quadratic depth), rolling toward the viewer.
    val n = 9
    for (i in 0 until n) {
        val z0 = ((i + roll) / n)
        val z1 = z0 + 0.45f / n
        val y0 = horizon + (h - horizon) * z0 * z0
        val y1 = horizon + (h - horizon) * (z1 * z1).coerceAtMost(1f)
        val width = 1.5f + 7f * z0
        drawLine(s.line.copy(alpha = (0.25f + 0.55f * z0)), Offset(vp.x, y0), Offset(vp.x, y1), strokeWidth = width, cap = StrokeCap.Butt)
    }
}
