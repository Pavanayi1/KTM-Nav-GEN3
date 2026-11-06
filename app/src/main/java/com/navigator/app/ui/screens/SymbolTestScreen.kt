package com.navigator.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.logging.AppLogger
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.components.Eyebrow
import com.navigator.app.ui.components.TurnIconRef
import com.navigator.app.ui.components.ScreenHeader
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.NavIcons

/**
 * Manual hardware-calibration screen (restyled to the KTM theme): send every
 * known TURN_ICON / NOTIFICATION code to the dash and record Works/Wrong.
 * Results persist across sessions via [AppSettings].
 */
@Composable
fun SymbolTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }

    Column(
        modifier = Modifier.fillMaxSize().background(Ktm.Screen).systemBarsPadding(),
    ) {
        ScreenHeader("Symbol testing", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    "Send each symbol, look at the dash, then mark Works or Wrong. The dash shows the icon's name as text so photos are self-explanatory.",
                    color = Ktm.Muted2, fontFamily = Barlow, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            item { Eyebrow("Turn icons · center display", color = Ktm.Orange, fontSize = 11) }
            items(BccuProtocol.TurnIcon.entries.toList()) { icon ->
                IconTestRow(
                    label = icon.name, settings = settings, key = "turn_${icon.name}",
                    leading = {
                        Box(
                            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF101216)),
                            contentAlignment = Alignment.Center,
                        ) { TurnIconRef(icon = icon, size = 26.dp, color = Ktm.TextPrimary) }
                    },
                    onSend = {
                        BccuConnectionService.sendTurnIconIfRunning(icon)
                        BccuConnectionService.sendGuidanceIfRunning("code=${icon.binary}", icon.name)
                    },
                )
            }
            item {
                Eyebrow("Notification icons · bottom banner", color = Ktm.Orange, fontSize = 11,
                    modifier = Modifier.padding(top = 14.dp))
            }
            items(BccuProtocol.NotificationIcon.entries.toList()) { icon ->
                IconTestRow(
                    label = icon.name, settings = settings, key = "notif_${icon.name}",
                    onSend = { BccuConnectionService.sendNotificationIfRunning(icon.name, icon) },
                )
            }
        }
    }
}

@Composable
private fun IconTestRow(
    label: String,
    settings: AppSettings,
    key: String,
    onSend: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    var result by remember { mutableStateOf(settings.getIconTestResult(key)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) leading()
        Text(label, color = Ktm.TextPrimary, fontFamily = JetBrainsMono, fontSize = 12.sp, modifier = Modifier.weight(1f))
        PillButton("SEND", Ktm.Orange, Ktm.OnAccent) {
            AppLogger.log("SymbolTest", "Sending $key")
            onSend()
        }
        PillButton("OK", if (result == "works") Ktm.Green else Ktm.Surface,
            if (result == "works") Ktm.Screen else Ktm.TextSecondary) {
            result = "works"; settings.setIconTestResult(key, "works")
            AppLogger.log("SymbolTest", "Result: $key = WORKS")
        }
        PillButton("X", if (result == "wrong") Ktm.Danger else Ktm.Surface,
            if (result == "wrong") Ktm.White else Ktm.TextSecondary) {
            result = "wrong"; settings.setIconTestResult(key, "wrong")
            AppLogger.log("SymbolTest", "Result: $key = WRONG")
        }
    }
}

@Composable
private fun PillButton(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .border(1.dp, if (bg == Ktm.Surface) Ktm.Border else bg, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, color = fg, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp)
    }
}
