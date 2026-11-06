package com.navigator.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * App-wide motion vocabulary. Everything here animates through
 * `graphicsLayer` (transform + alpha on the render thread), never layout
 * size or position, so it costs no recomposition or relayout per frame -
 * the cheap way to make the app feel smooth on a weak phone.
 */

/**
 * Tap target with physical feedback: the element sinks to [pressedScale] on a
 * spring while the finger is down and springs back on release, with the
 * normal ripple on top. Put it FIRST in a modifier chain - modifiers before a
 * graphicsLayer aren't scaled, so a background or border placed ahead of it
 * would stay put while the content shrinks.
 */
fun Modifier.pressFeedback(
    enabled: Boolean = true,
    pressedScale: Float = 0.965f,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * Cascade-in on first appearance: fades up from a small offset, each [index]
 * a beat after the previous one, so a screen assembles instead of popping in
 * all at once. Runs once per composition of the element.
 */
fun Modifier.enterStagger(index: Int, stepMs: Int = 45, offsetDp: Int = 22): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((index * stepMs).toLong())
        progress.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
    }
    this.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * offsetDp.dp.toPx()
    }
}
