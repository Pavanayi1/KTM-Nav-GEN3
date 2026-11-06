package com.navigator.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.ble.BccuProtocol.TurnIcon
import com.navigator.app.logging.AppLogger
import com.navigator.app.notifications.IconHashCache
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.components.Eyebrow
import com.navigator.app.ui.components.TurnIconGlyph
import com.navigator.app.ui.components.TurnIconRef
import com.navigator.app.ui.components.ScreenHeader
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.NavIcons

/**
 * Turn-icon calibration. Google Maps (and most nav apps) reuse a fixed set of
 * byte-identical maneuver bitmaps, but expose no maneuver text - so the pixel
 * heuristic can only ever guess left/right/straight and never U-turns,
 * roundabouts, forks, etc. This screen shows every captured-but-unlabeled icon
 * and lets the rider tap the correct dash symbol once; the mapping
 * (bitmap-hash -> TurnIcon) is then exact and permanent for this bike.
 *
 * Tapping a symbol also sends it live to the dash (if connected) so the rider
 * can confirm it looks right before moving on.
 */
@Composable
fun TurnCalibrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    // Bumping this re-reads the saved-icon list and current assignments.
    var refresh by remember { mutableIntStateOf(0) }
    val icons = remember(refresh) { IconHashCache.savedIcons(context) }

    // The candidate symbols, most-useful first, then the rest - covers the
    // maneuvers the heuristic can't infer (U-turns, roundabout exits, keep/merge).
    val candidates = remember { candidateOrder() }

    Column(
        modifier = Modifier.fillMaxSize().background(Ktm.Screen).systemBarsPadding(),
    ) {
        ScreenHeader("Turn icon calibration", onBack = onBack)

        if (icons.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No turn icons captured yet.\n\nDrive a route with navigation so the app captures each maneuver's icon, then come back here to label them.",
                    color = Ktm.Muted2, fontFamily = Barlow, fontSize = 14.sp,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Tap the symbol that matches each captured icon. It's saved for good and also sent to the dash so you can confirm it.",
                    color = Ktm.Muted2, fontFamily = Barlow, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            items(icons, key = { it.second }) { (file, hash) ->
                val assigned = settings.getTurnCalibration(hash)
                CalibrationRow(
                    file = file,
                    hash = hash,
                    assigned = assigned,
                    candidates = candidates,
                    isWorking = { icon -> settings.getIconTestResult("turn_${icon.name}") == "works" },
                    onAssign = { icon ->
                        settings.setTurnCalibration(hash, icon.name)
                        AppLogger.log("IconCache", "Calibrated hash=$hash -> ${icon.name}")
                        BccuConnectionService.sendTurnIconIfRunning(icon)
                        refresh++
                    },
                    onClear = {
                        settings.clearTurnCalibration(hash)
                        refresh++
                    },
                )
            }
        }
    }
}

@Composable
private fun CalibrationRow(
    file: java.io.File,
    hash: Int,
    assigned: String?,
    candidates: List<BccuProtocol.TurnIcon>,
    isWorking: (BccuProtocol.TurnIcon) -> Boolean,
    onAssign: (BccuProtocol.TurnIcon) -> Unit,
    onClear: () -> Unit,
) {
    val assignedIcon = remember(assigned) { BccuProtocol.TurnIcon.entries.firstOrNull { it.name == assigned } }
    val imageBitmap = remember(file.path) {
        runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ktm.Surface)
            .border(1.dp, if (assigned != null) Ktm.Orange else Ktm.Border, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101216)),
                contentAlignment = Alignment.Center,
            ) {
                if (imageBitmap != null) {
                    // Nav icons are usually white on transparent; scale the 48px capture up.
                    Image(bitmap = imageBitmap, contentDescription = null,
                        modifier = Modifier.size(44.dp).graphicsLayer { })
                } else {
                    Text("?", color = Ktm.Dim, fontSize = 20.sp)
                }
            }
            // Reference glyph for the currently-assigned symbol, right next to the
            // captured Maps bitmap, so "captured vs. dash symbol" reads at a glance.
            if (assignedIcon != null) {
                Icon(
                    NavIcons.ChevronLeft, contentDescription = null, tint = Ktm.Dim,
                    modifier = Modifier.padding(horizontal = 4.dp).size(16.dp),
                )
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101216)),
                    contentAlignment = Alignment.Center,
                ) { TurnIconRef(icon = assignedIcon, size = 40.dp, color = Ktm.Orange) }
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    assigned ?: "Not labeled",
                    color = if (assigned != null) Ktm.Orange else Ktm.Muted2,
                    fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                )
                Text("hash $hash", color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 10.sp)
            }
            if (assigned != null) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Text("CLEAR", color = Ktm.Dim, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                        fontSize = 11.sp, letterSpacing = 1.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClear).padding(6.dp))
                }
            }
        }
        Eyebrow("Pick the matching symbol · ★ = confirmed working on your dash", color = Ktm.Dim, fontSize = 10,
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(candidates, key = { it.name }) { icon ->
                val selected = assigned == icon.name
                val working = isWorking(icon)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(66.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Ktm.Orange else Ktm.SurfaceAlt)
                        .border(
                            if (working) 1.5.dp else 1.dp,
                            if (selected) Ktm.Orange else if (working) Ktm.Green else Ktm.Border,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onAssign(icon) }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    // Reference glyph so the rider can visually match the captured icon.
                    TurnIconRef(icon = icon, size = 34.dp, color = if (selected) Ktm.Screen else Ktm.TextPrimary)
                    Text(
                        (if (working) "★ " else "") + icon.name,
                        color = if (selected) Ktm.Screen else if (working) Ktm.Green else Ktm.TextSecondary,
                        fontFamily = JetBrainsMono, fontSize = 8.sp,
                        maxLines = 2,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

/** Common maneuvers first (the ones riders hit most and the heuristic can't infer), then the rest. */
private fun candidateOrder(): List<TurnIcon> {
    val preferred = listOf(
        TurnIcon.GO_STRAIGHT,
        TurnIcon.LIGHT_LEFT, TurnIcon.QUITE_LEFT, TurnIcon.HEAVY_LEFT, TurnIcon.UTURN_LEFT, TurnIcon.KEEP_LEFT,
        TurnIcon.LIGHT_RIGHT, TurnIcon.QUITE_RIGHT, TurnIcon.HEAVY_RIGHT, TurnIcon.UTURN_RIGHT, TurnIcon.KEEP_RIGHT,
        TurnIcon.ENTER_HIGHWAY_RIGHT_LANE, TurnIcon.ENTER_HIGHWAY_LEFT_LANE,
        TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE, TurnIcon.LEAVE_HIGHWAY_LEFT_LANE,
        TurnIcon.RAB_SECT_1_RH, TurnIcon.RAB_SECT_2_RH, TurnIcon.RAB_SECT_3_RH, TurnIcon.RAB_SECT_4_RH,
        TurnIcon.START, TurnIcon.END, TurnIcon.CHANGE_LINE, TurnIcon.HEAD_TO,
    )
    return preferred + TurnIcon.entries.filter { it !in preferred }
}
