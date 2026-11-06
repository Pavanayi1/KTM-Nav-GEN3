package com.navigator.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.Ktm

/** Small uppercase, letter-spaced eyebrow/group label (Barlow Condensed 700). */
@Composable
fun Eyebrow(
    text: String,
    color: Color = Ktm.Orange,
    fontSize: Int = 12,
    letterSpacing: Double = 2.0,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize.sp,
        letterSpacing = letterSpacing.sp,
    )
}

/** The 42x24 orange pill toggle from the handoff (screens 04 & 05). */
@Composable
fun KtmToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val knobOffset by animateDpAsState(if (checked) 21.dp else 3.dp, label = "knob")
    Box(
        modifier = modifier
            .width(42.dp)
            .height(24.dp)
            .clip(CircleShape)
            .background(if (checked) Ktm.Orange else Ktm.SurfaceDisabled)
            .clickable { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .padding(start = knobOffset)
                .align(Alignment.CenterStart)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) Ktm.OnAccent else Ktm.Muted),
        )
    }
}

/** A labelled group of setting rows inside one bordered card (screen 05). */
@Composable
fun GroupCard(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Eyebrow(
            label,
            color = Ktm.Orange,
            fontSize = 11,
            letterSpacing = 2.0,
            modifier = Modifier.padding(start = 4.dp, bottom = 7.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Ktm.RadiusRow))
                .background(Ktm.Surface)
                .border(1.dp, Ktm.Border, RoundedCornerShape(Ktm.RadiusRow)),
        ) {
            content()
        }
    }
}

/** One row within a [GroupCard]: label on the left, arbitrary trailing content on the right. */
@Composable
fun SettingsRow(
    label: String,
    showDivider: Boolean,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val base = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Column(modifier = base) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                color = Ktm.TextPrimary,
                fontFamily = Barlow,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Box(modifier = Modifier.padding(start = 12.dp)) { trailing() }
        }
        if (showDivider) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Ktm.RowDivider))
        }
    }
}

/**
 * The standard screen header: back chevron, big italic title, and optional
 * trailing content (a count, mode chips, a share action). Every non-home screen
 * uses this so titles stop arriving at four different sizes.
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            com.navigator.app.ui.theme.NavIcons.ChevronLeft,
            contentDescription = "Back",
            tint = Ktm.TextSecondary,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(6.dp)
                .size(24.dp),
        )
        Text(
            title.uppercase(),
            color = Ktm.White,
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
            fontSize = 30.sp,
            letterSpacing = (-0.3).sp,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

/**
 * Explanatory line under a settings row - for the handful of settings whose
 * consequence isn't obvious from the label ("auto-record" needing to say what
 * actually triggers it). Sits inside the card, indented to the label.
 */
@Composable
fun RowNote(text: String) {
    Text(
        text,
        color = Ktm.Dim,
        fontFamily = Barlow,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 11.dp),
    )
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Ktm.RowDivider))
}

/**
 * Segmented picker for a small set of mutually exclusive positions (sensitivity
 * low/normal/high). Preferred over tap-to-cycle wherever all the positions fit:
 * the rider can see where they are and jump straight to where they want.
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Ktm.SurfaceAlt)
            .border(1.dp, Ktm.Border, RoundedCornerShape(9.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) Ktm.Orange else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            ) {
                Text(
                    label,
                    color = if (on) Ktm.OnAccent else Ktm.Muted,
                    fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

/** Full-width primary (orange) action button used across onboarding/pairing. */
@Composable
fun KtmPrimaryButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.pressFeedback(enabled = enabled, onClick = onClick)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusButton))
            .background(if (enabled) Ktm.Orange else Ktm.SurfaceDisabled)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(), color = if (enabled) Ktm.OnAccent else Ktm.Dim,
            fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.5.sp,
        )
    }
}

/** Full-width secondary (outlined) action button. */
@Composable
fun KtmOutlineButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.pressFeedback(enabled = enabled, onClick = onClick)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusButton))
            .background(Ktm.Surface)
            .border(1.dp, if (enabled) Ktm.BorderSoft else Ktm.Border, RoundedCornerShape(Ktm.RadiusButton))
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(), color = if (enabled) Ktm.White else Ktm.Dim,
            fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.5.sp,
        )
    }
}

/** Monospace trailing value (times, RSSI, masked keys). */
@Composable
fun MonoValue(text: String, color: Color = Ktm.Dim, fontSize: Int = 12) {
    Text(
        text,
        color = color,
        fontFamily = com.navigator.app.ui.theme.JetBrainsMono,
        fontSize = fontSize.sp,
    )
}
