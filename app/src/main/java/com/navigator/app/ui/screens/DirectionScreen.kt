package com.navigator.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.notifications.NotificationRepository
import com.navigator.app.ui.components.Eyebrow
import com.navigator.app.ui.components.KtmPrimaryButton
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.NavIcons

/**
 * Direction / turn-by-turn. An in-app mirror of what the dash's centre display
 * is showing right now: maneuver glyph, distance, road, ETA and remaining -
 * plus a live readout of the five dash text slots as they were actually sent,
 * which is what makes this screen useful for diagnosing a wrong-looking dash
 * without a cable. Glanceable first, diagnostic second.
 */
@Composable
fun DirectionScreen(onOpenMaps: () -> Unit) {
    val guidance by NotificationRepository.navGuidance.collectAsState()
    val navText by NotificationRepository.currentNavText.collectAsState()
    val navPackage by NotificationRepository.currentNavPackage.collectAsState()

    val hasNav = navPackage != null && (guidance != null || navText != null)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ktm.Screen)
            .systemBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 6.dp, bottom = 22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Eyebrow("Mirroring to dash", color = Ktm.Dim, fontSize = 11, letterSpacing = 2.4)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (hasNav) Ktm.Green else Ktm.Dim3),
                )
                Text(
                    if (hasNav) "LIVE" else "IDLE",
                    color = Ktm.Muted, fontFamily = JetBrainsMono, fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (!hasNav) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No active navigation.\nStart a route in Google Maps.",
                    color = Ktm.Muted2, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp, lineHeight = 26.sp, textAlign = TextAlign.Center,
                )
            }
        } else {
            TurnCard(guidance, navText)
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                StatTile("ETA", guidance?.eta ?: "—", Modifier.weight(1f))
                StatTile("Remaining", guidance?.remaining ?: "—", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            DashSlotsCard(guidance)
            Spacer(Modifier.weight(1f))
        }

        KtmPrimaryButton("Open Google Maps", enabled = navPackage != null, onClick = onOpenMaps)
    }
}

@Composable
private fun TurnCard(guidance: NotificationRepository.NavGuidance?, navText: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.SurfaceAlt)
            .border(1.dp, Ktm.RowDivider, RoundedCornerShape(Ktm.RadiusCard))
            .padding(horizontal = 22.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The real classified maneuver (the same glyph sent to the dash) when we
        // have one; the generic banking arrow otherwise.
        val turnIcon = guidance?.turnIcon
        if (turnIcon != null) {
            com.navigator.app.ui.components.TurnIconRef(icon = turnIcon, size = 76.dp, color = Ktm.Orange)
        } else {
            Icon(NavIcons.TurnArrow, contentDescription = null, tint = Ktm.Orange,
                modifier = Modifier.size(76.dp))
        }

        val distance = guidance?.distance
        if (distance != null) {
            Text(
                distanceAnnotated(distance),
                fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                fontSize = 92.sp, lineHeight = 80.sp, letterSpacing = (-1.8).sp, color = Ktm.White,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else if (navText != null) {
            Text(
                navText, color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic, fontSize = 34.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        guidance?.maneuver?.let { maneuver ->
            Text(
                maneuver.uppercase(), color = Ktm.Orange, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.9.sp,
                modifier = Modifier.padding(top = 10.dp), textAlign = TextAlign.Center,
            )
        }
        guidance?.road?.let { road ->
            Text(
                road, color = Ktm.White, fontFamily = Barlow, fontWeight = FontWeight.SemiBold,
                fontSize = 27.sp, lineHeight = 31.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The five centre-display slots exactly as last written, keyed by their
 * protocol names. Deliberately monospace and unglamorous - it's a readout,
 * and lining the values up is what makes a wrong one obvious.
 */
@Composable
private fun DashSlotsCard(guidance: NotificationRepository.NavGuidance?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.SurfaceAlt)
            .border(1.dp, Ktm.RowDivider, RoundedCornerShape(Ktm.RadiusCard))
            .padding(16.dp),
    ) {
        Eyebrow("Dash slots · live payload", color = Ktm.Dim, fontSize = 10, letterSpacing = 2.2)
        Spacer(Modifier.height(12.dp))
        val icon = guidance?.turnIcon
        SlotRow("turn_dist", guidance?.distance)
        SlotRow("icon", icon?.let { "${it.name} · 0x%02X".format(it.binary) })
        SlotRow("road", guidance?.road)
        SlotRow("eta", guidance?.eta)
        SlotRow("remaining", guidance?.remaining)
    }
}

@Composable
private fun SlotRow(key: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 12.sp)
        Text(
            value ?: "—", color = Ktm.TextPrimary, fontFamily = JetBrainsMono, fontSize = 12.sp,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

private fun distanceAnnotated(distance: String) = buildAnnotatedString {
    // Split the trailing unit ("400 m" -> "400" + "m") so the number dominates.
    val match = Regex("""^(\S+)\s*(.*)$""").find(distance.trim())
    if (match != null && match.groupValues[2].isNotBlank()) {
        append(match.groupValues[1])
        withStyle(androidx.compose.ui.text.SpanStyle(fontSize = 34.sp, color = Ktm.Muted2)) {
            append(match.groupValues[2])
        }
    } else {
        append(distance)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.Surface)
            .border(1.dp, Ktm.Border, RoundedCornerShape(Ktm.RadiusCard))
            .padding(16.dp),
    ) {
        Eyebrow(label, color = Ktm.Dim, fontSize = 10, letterSpacing = 2.2)
        Text(
            value, color = Ktm.White, fontFamily = JetBrainsMono,
            fontSize = 26.sp, modifier = Modifier.padding(top = 6.dp),
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
