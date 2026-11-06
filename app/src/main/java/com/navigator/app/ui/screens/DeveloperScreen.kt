package com.navigator.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.dash.IdleDashboard
import com.navigator.app.logging.AppLogger
import com.navigator.app.notifications.NotificationAudit
import com.navigator.app.ui.components.RowNote
import com.navigator.app.ui.components.Eyebrow
import com.navigator.app.ui.components.GroupCard
import com.navigator.app.ui.components.MonoValue
import com.navigator.app.ui.components.SettingsRow
import com.navigator.app.ui.components.ScreenHeader
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.NavIcons
import com.navigator.app.weather.WeatherProvider
import kotlinx.coroutines.delay

/**
 * Developer Settings: a test harness for how text renders on the bike's dash.
 *  - Slot playground: free text into each of the six text slots, with live
 *    char counters against the protocol limits (8/16/32/8/8 + 16 banner).
 *  - Alignment tests: positional rulers and edge markers per slot.
 *  - Flow tester: feed long text and watch it stream through a slot as a
 *    marquee (sliding window) or in pages, with adjustable speed.
 *  - Templates: the exact idle-screen cards (clock / music / weather) so their
 *    layout can be verified on hardware before enabling the idle screen.
 */
@Composable
fun DeveloperScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // What we last sent, mirrored on a phone-side mock dash for comparison.
    var sentDistance by remember { mutableStateOf("") }
    var sentInfo by remember { mutableStateOf("") }
    var sentRoad by remember { mutableStateOf("") }
    var sentEta by remember { mutableStateOf("") }
    var sentRemaining by remember { mutableStateOf("") }
    var sentBanner by remember { mutableStateOf("") }

    // Hardware finding (R36): the center text slots render ONLY while a turn
    // icon is visible - so every center-slot send is preceded by the selected
    // icon. The bottom banner is independent and needs no icon.
    var centerIcon by remember { mutableStateOf(BccuProtocol.TurnIcon.START) }

    fun sendSlots(
        distance: String? = null, info: String? = null, road: String? = null,
        eta: String? = null, remaining: String? = null,
    ) {
        BccuConnectionService.sendTurnIconIfRunning(centerIcon)
        BccuConnectionService.sendDashTextIfRunning(distance, info, road, eta, remaining)
        distance?.let { sentDistance = it }
        info?.let { sentInfo = it }
        road?.let { sentRoad = it }
        eta?.let { sentEta = it }
        remaining?.let { sentRemaining = it }
    }

    fun sendBanner(text: String) {
        BccuConnectionService.sendNotificationIfRunning(text, BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT)
        sentBanner = text
    }

    fun sendCard(card: IdleDashboard.Card) {
        card.icon?.let { BccuConnectionService.sendTurnIconIfRunning(it) }
        BccuConnectionService.sendDashTextIfRunning(card.distance, card.info, card.road, card.eta, card.remaining)
        sentDistance = card.distance; sentInfo = card.info; sentRoad = card.road
        sentEta = card.eta; sentRemaining = card.remaining
    }

    // --- Slot playground state ---
    var distText by remember { mutableStateOf("110 m") }
    var infoText by remember { mutableStateOf("2nd exit") }
    var roadText by remember { mutableStateOf("Outer Ring Road") }
    var etaText by remember { mutableStateOf("12:45") }
    var remText by remember { mutableStateOf("123 km") }
    var bannerText by remember { mutableStateOf("Hello from dev") }

    // --- Flow tester state ---
    var flowText by remember {
        mutableStateOf("The quick brown fox jumps over the lazy dog 0123456789 - continuous flow test")
    }
    var flowTarget by remember { mutableStateOf(FlowTarget.ROAD) }
    var flowPaged by remember { mutableStateOf(false) }
    var stepMs by remember { mutableStateOf(320L) }
    var dwellMs by remember { mutableStateOf(1500L) }
    var flowRunning by remember { mutableStateOf(false) }
    var flowWindow by remember { mutableStateOf("") }

    // The flow loop lives in the composition: leaving the screen stops it.
    LaunchedEffect(flowRunning) {
        if (!flowRunning) return@LaunchedEffect
        val text = flowText.trim()
        val width = flowTarget.width
        AppLogger.log("DevDash", "Flow start target=${flowTarget.name} paged=$flowPaged step=$stepMs dwell=$dwellMs len=${text.length}")
        fun show(window: String) {
            flowWindow = window
            when (flowTarget) {
                FlowTarget.ROAD -> sendSlots(road = window)
                FlowTarget.INFO -> sendSlots(info = window)
                FlowTarget.BANNER -> sendBanner(window)
            }
        }
        if (flowPaged) {
            val pages = text.chunked(width)
            while (flowRunning) {
                for (page in pages) {
                    if (!flowRunning) break
                    show(page)
                    delay(dwellMs)
                }
            }
        } else {
            val loop = "$text   "
            val doubled = loop + loop
            while (flowRunning) {
                for (start in loop.indices) {
                    if (!flowRunning) break
                    show(doubled.substring(start, start + minOf(width, doubled.length - start)))
                    delay(stepMs)
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { flowRunning = false } }

    Column(modifier = Modifier.fillMaxSize().background(Ktm.Screen).systemBarsPadding()) {
        ScreenHeader("Developer settings", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ===== Notification audit =====
            item {
                GroupCard("Notification audit") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Every notification the phone posted while developer mode is on, and what Navigator decided to do with it.",
                            color = Ktm.Muted2, fontFamily = Barlow, fontSize = 12.sp,
                        )
                        val auditEvents by NotificationAudit.events.collectAsState()
                        var auditFilter by remember { mutableStateOf<NotificationAudit.Verdict?>(null) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("ALL ${auditEvents.size}", selected = auditFilter == null) { auditFilter = null }
                            NotificationAudit.Verdict.entries.forEach { v ->
                                val count = auditEvents.count { it.verdict == v }
                                Chip("${v.name} $count", selected = auditFilter == v) { auditFilter = v }
                            }
                        }
                        val filtered = auditFilter?.let { f -> auditEvents.filter { it.verdict == f } } ?: auditEvents
                        if (filtered.isEmpty()) {
                            Text(
                                "Nothing recorded yet. Recording starts when developer mode is on - trigger any notification (or use the tests below).",
                                color = Ktm.Dim, fontFamily = Barlow, fontSize = 12.sp,
                            )
                        }
                        filtered.take(AUDIT_ROWS_SHOWN).forEach { e -> AuditRow(e) }
                        if (filtered.size > AUDIT_ROWS_SHOWN) {
                            Text(
                                "…and ${filtered.size - AUDIT_ROWS_SHOWN} older (buffer keeps the last 200)",
                                color = Ktm.Dim, fontFamily = Barlow, fontSize = 11.sp,
                            )
                        }
                        DevPill("CLEAR AUDIT", filled = false) { NotificationAudit.clear() }
                    }
                }
            }

            // ===== Phone notification tests =====
            item {
                GroupCard("Phone notification tests") {
                    SettingsRow("Post a test phone notification", showDivider = true, onClick = {
                        postDevTestNotification(context)
                    }) { SendLabel() }
                    RowNote(
                        "A real notification from Navigator itself. It round-trips through the " +
                            "listener, so it also lands in the audit above (as SKIPPED - Navigator " +
                            "isn't in the mirrored-apps list)."
                    )
                    SettingsRow("Preview \"handlebar remote dead\" alert", showDivider = true, onClick = {
                        BccuConnectionService.previewAccessibilityAlert(context)
                    }) { SendLabel() }
                    RowNote(
                        "The exact production alert the accessibility watchdog fires, plus the " +
                            "REMOTE OFF dash banner when the bike is connected."
                    )
                    SettingsRow("Inject a fake WhatsApp message", showDivider = false, onClick = {
                        injectFakeMessage(context)
                    }) { SendLabel() }
                    RowNote(
                        "Adds an entry to the in-app notification feed and mirrors it to the dash " +
                            "banner, exactly like a real message - without needing anyone to text you."
                    )
                }
            }

            // ===== Mock dash: what we last sent, phone-side =====
            item {
                GroupCard("Last sent (phone-side mirror)") {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MockSlot("DIST 8", sentDistance, 8)
                        MockSlot("INFO 16", sentInfo, 16)
                        MockSlot("ROAD 32", sentRoad, 32)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f)) { MockSlot("ETA 8", sentEta, 8) }
                            Box(Modifier.weight(1f)) { MockSlot("REM 8", sentRemaining, 8) }
                        }
                        MockSlot("BANNER 13", sentBanner, 13)
                    }
                }
            }

            // ===== Slot playground =====
            item {
                GroupCard("Slot playground") {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "The dash shows the center slots only while a turn icon is visible - this icon is sent with every center-slot test:",
                            color = Ktm.Muted2, fontFamily = Barlow, fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                BccuProtocol.TurnIcon.START,
                                BccuProtocol.TurnIcon.GO_STRAIGHT,
                                BccuProtocol.TurnIcon.HEAD_TO,
                                BccuProtocol.TurnIcon.PASS_STATION,
                            ).forEach { icon ->
                                Chip(icon.name, selected = centerIcon == icon) {
                                    centerIcon = icon
                                    BccuConnectionService.sendTurnIconIfRunning(icon)
                                }
                            }
                        }
                        SlotField("TURN_DISTANCE", distText, 8, { distText = it }) { sendSlots(distance = distText) }
                        SlotField("TURN_INFO", infoText, 16, { infoText = it }) { sendSlots(info = infoText) }
                        SlotField("TURN_ROAD", roadText, 32, { roadText = it }) { sendSlots(road = roadText) }
                        SlotField("ETA", etaText, 8, { etaText = it }) { sendSlots(eta = etaText) }
                        SlotField("REMAINING_DIST", remText, 8, { remText = it }) { sendSlots(remaining = remText) }
                        SlotField("BANNER", bannerText, 13, { bannerText = it }) { sendBanner(bannerText) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DevPill("SEND ALL", filled = true, modifier = Modifier.weight(1f)) {
                                sendSlots(distText, infoText, roadText, etaText, remText)
                                sendBanner(bannerText)
                            }
                            DevPill("CLEAR ALL", filled = false, modifier = Modifier.weight(1f)) {
                                BccuConnectionService.clearAllDashTextIfRunning()
                                sentDistance = ""; sentInfo = ""; sentRoad = ""
                                sentEta = ""; sentRemaining = ""; sentBanner = ""
                            }
                        }
                    }
                }
            }

            // ===== Alignment tests =====
            item {
                GroupCard("Alignment tests") {
                    SettingsRow(
                        "Rulers: 12345678 in every slot", showDivider = true,
                        onClick = {
                            sendSlots(
                                distance = RULER.take(8), info = RULER.take(16),
                                road = RULER.take(32), eta = RULER.take(8), remaining = RULER.take(8),
                            )
                            sendBanner(RULER.take(16))
                        },
                    ) { SendLabel() }
                    SettingsRow(
                        "Edges: [......] to check clipping", showDivider = true,
                        onClick = {
                            sendSlots(
                                distance = edge(8), info = edge(16), road = edge(32),
                                eta = edge(8), remaining = edge(8),
                            )
                            sendBanner(edge(16))
                        },
                    ) { SendLabel() }
                    SettingsRow(
                        "Overflow: 1 char past every limit", showDivider = false,
                        onClick = {
                            // One char over: shows whether the dash clips, wraps, or
                            // our "..." ellipsis kicks in per slot.
                            sendSlots(
                                distance = RULER.take(9), info = RULER.take(17),
                                road = RULER.take(33), eta = RULER.take(9), remaining = RULER.take(9),
                            )
                            sendBanner(RULER.take(17))
                        },
                    ) { SendLabel() }
                }
            }

            // ===== Flow tester =====
            item {
                GroupCard("Continuous text flow") {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DevTextField(flowText, { flowText = it })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Eyebrow("Target", fontSize = 10)
                            Row(modifier = Modifier.weight(1f).padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FlowTarget.entries.forEach { t ->
                                    Chip("${t.name} ${t.width}", selected = flowTarget == t) { flowTarget = t }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Eyebrow("Mode", fontSize = 10)
                            Row(modifier = Modifier.weight(1f).padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Chip("MARQUEE", selected = !flowPaged) { flowPaged = false }
                                Chip("PAGED", selected = flowPaged) { flowPaged = true }
                            }
                        }
                        if (!flowPaged) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Eyebrow("Step", fontSize = 10)
                                Row(modifier = Modifier.weight(1f).padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(160L, 320L, 500L, 800L).forEach { ms ->
                                        Chip("${ms}ms", selected = stepMs == ms) { stepMs = ms }
                                    }
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Eyebrow("Dwell", fontSize = 10)
                                Row(modifier = Modifier.weight(1f).padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(1000L, 1500L, 2500L, 4000L).forEach { ms ->
                                        val label = if (ms % 1000 == 0L) "${ms / 1000}s" else "${ms / 1000.0}s"
                                        Chip(label, selected = dwellMs == ms) { dwellMs = ms }
                                    }
                                }
                            }
                        }
                        if (flowRunning) {
                            MockSlot("LIVE", flowWindow, flowTarget.width)
                        }
                        DevPill(if (flowRunning) "STOP" else "START FLOW", filled = !flowRunning) {
                            flowRunning = !flowRunning
                            if (!flowRunning) BccuConnectionService.clearAllDashTextIfRunning()
                        }
                    }
                }
            }

            // ===== Idle-card templates =====
            item {
                GroupCard("Idle-screen templates") {
                    SettingsRow("Clock card (time + date)", showDivider = true, onClick = {
                        sendCard(IdleDashboard.clockCard())
                    }) { SendLabel() }
                    SettingsRow("Music card (current or sample track)", showDivider = true, onClick = {
                        sendCard(IdleDashboard.musicCard("Adventure of a Lifetime by Coldplay"))
                    }) { SendLabel() }
                    SettingsRow("Weather card (live or sample)", showDivider = true, onClick = {
                        val w = WeatherProvider.cached() ?: WeatherProvider.WeatherInfo(24, 27, "Partly cloudy", 12)
                        sendCard(IdleDashboard.weatherCard(w))
                    }) { SendLabel() }
                    SettingsRow("Notification banner (marquee)", showDivider = false, onClick = {
                        sendBanner("This long message tests the banner marquee scroll timing")
                    }) { SendLabel() }
                }
            }

            // ===== Connection hint =====
            item {
                val state by BccuConnectionService.connectionState.collectAsState()
                Text(
                    if (state == BccuConnectionService.ConnectionState.AUTHENTICATED)
                        "Bike connected - everything you send appears on the dash."
                    else
                        "Bike NOT connected (${state.name}) - sends are logged but won't reach the dash.",
                    color = Ktm.Muted2, fontFamily = Barlow, fontSize = 12.sp,
                )
            }
        }
    }
}

// Banner: protocol accepts 16 chars but the Gen-3 viewport clips at ~13 (R38 photos).
private enum class FlowTarget(val width: Int) { ROAD(32), INFO(16), BANNER(13) }

private const val AUDIT_ROWS_SHOWN = 40

/** One audit event: verdict + package + time, the notification text, and why. */
@Composable
private fun AuditRow(e: NotificationAudit.Event) {
    val verdictColor = when (e.verdict) {
        NotificationAudit.Verdict.NAV -> Ktm.Orange
        NotificationAudit.Verdict.MIRRORED -> Ktm.Green
        NotificationAudit.Verdict.CAPTURED -> Ktm.TextSecondary
        NotificationAudit.Verdict.SKIPPED -> Ktm.Dim
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Ktm.Screen)
            .border(1.dp, Ktm.Border, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${e.verdict.name}  ${e.packageName}",
                color = verdictColor, fontFamily = JetBrainsMono, fontSize = 10.sp, maxLines = 1,
            )
            Text(
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.timeMs)),
                color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 10.sp,
            )
        }
        val body = if (e.title.isNotBlank()) "${e.title}: ${e.text}" else e.text
        if (body.isNotBlank()) {
            Text(body, color = Ktm.TextPrimary, fontFamily = Barlow, fontSize = 12.sp, maxLines = 2)
        }
        Text(e.reason, color = Ktm.Muted2, fontFamily = Barlow, fontSize = 10.sp, maxLines = 1)
    }
}

/**
 * A genuine Android notification from Navigator's own dev-test channel, so the
 * whole pipeline (system post → listener callback → audit) can be exercised
 * without waiting for a real app to notify.
 */
private fun postDevTestNotification(context: android.content.Context) {
    val nm = context.getSystemService(android.app.NotificationManager::class.java)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                "dev_test", "Developer tests", android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
    val n = androidx.core.app.NotificationCompat.Builder(context, "dev_test")
        .setContentTitle("Navigator test notification")
        .setContentText("Posted from Developer settings at $time")
        .setSmallIcon(com.navigator.app.R.drawable.ic_notification)
        .setAutoCancel(true)
        .build()
    // Fresh id each time so repeated taps post new notifications instead of
    // silently replacing one the tester already dismissed.
    runCatching { nm.notify((System.currentTimeMillis() % 100_000).toInt(), n) }
}

/**
 * Injects a fake mirrored message end-to-end EXCEPT the system-listener hop:
 * in-app feed entry, dash banner, and an audit record - the same three places
 * a real WhatsApp message lands.
 */
private fun injectFakeMessage(context: android.content.Context) {
    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
    val title = "Dev Tester"
    val text = "Fake message injected at $time"
    com.navigator.app.notifications.NotificationRepository.addEntry(
        com.navigator.app.notifications.NotificationEntry(
            packageName = "com.whatsapp",
            appLabel = "WhatsApp (fake)",
            title = title,
            text = text,
            postTimeMs = System.currentTimeMillis(),
            isNavigation = false,
        )
    )
    BccuConnectionService.sendNotificationIfRunning(
        "$title: $text", BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT
    )
    NotificationAudit.record(
        NotificationAudit.Event(
            timeMs = System.currentTimeMillis(),
            packageName = "com.whatsapp",
            title = title,
            text = text,
            category = null,
            verdict = NotificationAudit.Verdict.MIRRORED,
            reason = "Injected from Developer settings",
        )
    )
}

private const val RULER = "123456789012345678901234567890123456"

private fun edge(width: Int): String =
    if (width <= 2) "[]" else "[" + ".".repeat(width - 2) + "]"

/** Fixed-width mock slot: cell grid so you can count characters against the dash. */
@Composable
private fun MockSlot(label: String, text: String, width: Int) {
    Column {
        Eyebrow(label, fontSize = 9, letterSpacing = 1.5)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Ktm.Screen)
                .border(1.dp, Ktm.Border, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text.take(width).padEnd(width, '·'),
                color = Ktm.TextPrimary, fontFamily = JetBrainsMono, fontSize = 12.sp, maxLines = 1,
            )
        }
    }
}

@Composable
private fun SlotField(label: String, value: String, max: Int, onChange: (String) -> Unit, onSend: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Eyebrow(label, fontSize = 10)
            MonoValue(
                "${value.length}/$max",
                color = if (value.length > max) Ktm.Danger else Ktm.Dim, fontSize = 10,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) { DevTextField(value, onChange) }
            DevPill("SEND", filled = true, onClick = onSend)
        }
    }
}

@Composable
private fun DevTextField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = TextStyle(color = Ktm.TextPrimary, fontFamily = JetBrainsMono, fontSize = 13.sp),
        cursorBrush = SolidColor(Ktm.Orange),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Ktm.Screen)
            .border(1.dp, Ktm.Border, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    )
}

@Composable
private fun DevPill(text: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (filled) Ktm.Orange else Ktm.Surface)
            .border(1.dp, if (filled) Ktm.Orange else Ktm.Border, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (filled) Ktm.OnAccent else Ktm.TextSecondary,
            fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Ktm.Orange else Ktm.Surface)
            .border(1.dp, if (selected) Ktm.Orange else Ktm.Border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            text, color = if (selected) Ktm.OnAccent else Ktm.TextSecondary,
            fontFamily = JetBrainsMono, fontSize = 10.sp,
        )
    }
}

@Composable
private fun SendLabel() {
    Text(
        "SEND", color = Ktm.Orange, fontFamily = BarlowCondensed,
        fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp,
    )
}
