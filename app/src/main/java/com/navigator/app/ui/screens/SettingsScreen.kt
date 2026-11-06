package com.navigator.app.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.navigator.app.gemini.GeminiSummarizer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.components.Eyebrow
import com.navigator.app.ui.components.GroupCard
import com.navigator.app.ui.components.RowNote
import com.navigator.app.ui.components.Segmented
import com.navigator.app.ui.components.KtmToggle
import com.navigator.app.ui.components.MonoValue
import com.navigator.app.ui.components.SettingsRow
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.NavIcons

private enum class SettingsDialog { NONE, NAME, GEMINI, GEMINI_MODEL, GEMINI_MODEL_CUSTOM, NAV_APP, MIRROR_APPS, CALL_AUDIO, OVERSPEED_LIMIT, HOME_PLACE, WORK_PLACE }

/**
 * Settings (screen 05) — the §5 restructure into four labelled groups
 * (Connection, Notifications, Navigation, Diagnostics). Every capability from
 * the original flat list is preserved: name, Gemini key, nav detection +
 * manual override, mirror-from apps picker, call-audio device, battery
 * optimization, logs, test notification, test guidance, symbol testing, build
 * version — plus the new Re-pair, quick-mute and marquee toggles.
 */
@SuppressLint("MissingPermission")
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSymbolTest: () -> Unit,
    onOpenDeveloper: () -> Unit = {},
    onOpenTurnCalibration: () -> Unit,
    onOpenVibrationCalibration: () -> Unit = {},
    onOpenRides: () -> Unit = {},
    onChangeBrand: () -> Unit = {},
    onRepair: () -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager

    var geminiKey by remember { mutableStateOf(settings.geminiApiKey ?: "") }
    var userName by remember { mutableStateOf(settings.userName ?: "") }
    var navAutoDetect by remember { mutableStateOf(settings.navAppOverride == null) }
    var navOverride by remember { mutableStateOf(settings.navAppOverride) }
    var sourceApps by remember { mutableStateOf(settings.notificationSourceApps) }
    var preferredAudioAddress by remember { mutableStateOf(settings.preferredCallAudioDeviceAddress) }
    var mirrorEnabled by remember { mutableStateOf(settings.mirrorEnabled) }
    var marqueeEnabled by remember { mutableStateOf(settings.marqueeEnabled) }
    var keepSessionAlive by remember { mutableStateOf(settings.keepSessionAlive) }
    var gamepadEnabled by remember { mutableStateOf(settings.gamepadEnabled) }
    var geminiModel by remember { mutableStateOf(settings.geminiModel) }
    var customGeminiModels by remember { mutableStateOf(settings.customGeminiModels) }
    var aiTestState by remember { mutableStateOf("") }
    // Models fetched live from Google when the picker opens. Google retires
    // model names between app releases (gemini-2.0-flash, the old default, is
    // already gone), so the built-in list is only the offline fallback.
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var turnBeepEnabled by remember { mutableStateOf(settings.turnBeepEnabled) }
    var beepVolume by remember { mutableStateOf(settings.beepVolumePercent) }
    var powerSave by remember { mutableStateOf(settings.powerSaveEnabled) }
    var fileLogging by remember { mutableStateOf(settings.fileLoggingEnabled) }
    var devMode by remember { mutableStateOf(settings.devModeEnabled) }
    var engineDetect by remember { mutableStateOf(settings.engineDetectEnabled) }
    var vibSensitivity by remember { mutableStateOf(settings.vibrationSensitivity) }
    var overspeedEnabled by remember { mutableStateOf(settings.overspeedEnabled) }
    var overspeedLimit by remember { mutableStateOf(settings.overspeedLimitKmh) }
    var routeRecordEnabled by remember { mutableStateOf(settings.routeAutoRecordEnabled) }
    var idleDashEnabled by remember { mutableStateOf(settings.idleDashEnabled) }
    var idleShowClock by remember { mutableStateOf(settings.idleShowClock) }
    var idleShowMusic by remember { mutableStateOf(settings.idleShowMusic) }
    var idleShowWeather by remember { mutableStateOf(settings.idleShowWeather) }
    var idleRotateSec by remember { mutableStateOf(settings.idleRotateSec) }
    var farTurnMusic by remember { mutableStateOf(settings.farTurnMusicEnabled) }
    var waypoint by remember { mutableStateOf(settings.waypoint) }
    var homeDestination by remember { mutableStateOf(settings.homeDestination) }
    var homeAddress by remember { mutableStateOf(settings.homeAddress) }
    var workDestination by remember { mutableStateOf(settings.workDestination) }
    var workAddress by remember { mutableStateOf(settings.workAddress) }
    // Ask Android, not just our own in-process flag: isRunning is false during
    // any rebind, and can't see the OEM battery manager having unticked us.
    fun accessibilityLive() =
        com.navigator.app.controller.RemoteControlAccessibilityService.isRunning ||
            com.navigator.app.controller.RemoteControlAccessibilityService.isEnabledInSettings(context)
    var accessibilityGranted by remember { mutableStateOf(accessibilityLive()) }
    var dialog by remember { mutableStateOf(SettingsDialog.NONE) }

    // Ride backup / restore. Both go through the system picker so the rider
    // chooses where it lands (Drive, Files, a USB stick) and the app needs no
    // storage permission of its own.
    // One count, loaded off the main thread, shared by all three rows that show
    // it. It used to be listed from disk three separate times per open - and
    // once (the "View rides" row) with no remember at all, so that one re-listed
    // the routes directory on every recomposition of the row.
    var rideCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        rideCount = withContext(Dispatchers.IO) {
            com.navigator.app.location.RouteRecorder.listRoutes(context).size
        }
    }
    val zipExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val written = com.navigator.app.location.RideArchive.exportTo(context, uri)
        toast(
            context,
            when {
                written < 0 -> "Backup failed — see logs"
                written == 0 -> "No recorded rides yet"
                written == 1 -> "Backed up 1 ride"
                else -> "Backed up $written rides"
            },
        )
    }
    val zipImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = com.navigator.app.location.RideArchive.importFrom(context, uri)
        rideCount = com.navigator.app.location.RouteRecorder.listRoutes(context).size
        toast(
            context,
            buildString {
                append(if (result.imported == 1) "Restored 1 ride" else "Restored ${result.imported} rides")
                if (result.skipped > 0) append(" · ${result.skipped} already here")
                if (result.failed > 0) append(" · ${result.failed} failed")
            },
        )
    }

    // The launchable-app list for the "Mirror-from apps" picker, as
    // (package, label) pairs. This used to run synchronously inside remember{}
    // during the first composition, and it was why opening Settings stalled:
    // getInstalledApplications, then a getLaunchIntentForPackage BINDER CALL
    // PER PACKAGE (498 of them on this phone), then getApplicationLabel called
    // from inside sortedBy's comparator - so O(n log n) label lookups, ~4500
    // more IPCs. Measured on device: 39 to 809 skipped frames, a 5.0s frame.
    // Now: one queryIntentActivities call for the whole launchable set, each
    // label read exactly once, all of it off the main thread. The list is only
    // needed when the rider actually opens the picker, so an empty first frame
    // costs nothing.
    var installedApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) {
            runCatching {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(launcher, 0)
                    .asSequence()
                    .map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                    .map { it.packageName to pm.getApplicationLabel(it).toString() }
                    .sortedBy { it.second.lowercase() }
                    .toList()
            }.getOrDefault(emptyList())
        }
    }
    val labelByPackage = remember(installedApps) { installedApps.toMap() }
    val bondedAudioDevices = remember {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        adapter?.bondedDevices?.filter {
            it.bluetoothClass?.hasService(android.bluetooth.BluetoothClass.Service.AUDIO) == true
        } ?: emptyList()
    }

    var notificationAccessGranted by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccessGranted =
                    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                accessibilityGranted = accessibilityLive()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val connectionState by BccuConnectionService.connectionState.collectAsState()
    val connected = connectionState == BccuConnectionService.ConnectionState.AUTHENTICATED
    val vehicleName = settings.bondedDeviceName ?: "Not paired"

    // Prefer the labels already resolved above - the mirror picker renders
    // hundreds of rows, and looking each one up again is two binder calls per
    // row per recomposition. The fallback covers packages not in the launchable
    // set (the known-nav-app list), which is a handful of lookups at most.
    fun appLabel(pkg: String): String = labelByPackage[pkg] ?: try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .background(Ktm.Screen),
    ) {
        // Header: back + title.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                NavIcons.ChevronLeft, contentDescription = "Back", tint = Ktm.TextSecondary,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(8.dp).size(22.dp),
            )
            Text(
                "SETTINGS", color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic, fontSize = 28.sp, letterSpacing = 0.3.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp, top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ===== Connection =====
            item {
                GroupCard("Connection") {
                    SettingsRow("Vehicle", showDivider = true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (connected) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(Ktm.Green))
                                androidx.compose.foundation.layout.Spacer(Modifier.size(7.dp))
                            }
                            MonoValue(vehicleName, color = if (connected) Ktm.Green else Ktm.Dim)
                        }
                    }
                    SettingsRow("Keep session alive", showDivider = true) {
                        KtmToggle(keepSessionAlive, { keepSessionAlive = it; settings.keepSessionAlive = it })
                    }
                    SettingsRow("Re-pair vehicle", showDivider = true, onClick = onRepair) {
                        OutlinedPill("RE-PAIR")
                    }
                    SettingsRow("Change bike / brand", showDivider = true, onClick = onChangeBrand) {
                        MonoValue("${com.navigator.app.ui.theme.themeFor(settings.brand).displayName} ›")
                    }
                    SettingsRow("Your name", showDivider = true, onClick = { dialog = SettingsDialog.NAME }) {
                        MonoValue(userName.ifBlank { "Set ›" })
                    }
                    SettingsRow("Gemini API key", showDivider = true, onClick = { dialog = SettingsDialog.GEMINI }) {
                        MonoValue(maskKey(geminiKey))
                    }
                    SettingsRow("Gemini model", showDivider = true, onClick = { dialog = SettingsDialog.GEMINI_MODEL }) {
                        MonoValue("$geminiModel ›")
                    }
                    // Proves the key and the model name together, which matters
                    // most for a hand-typed custom model: the API answers 404
                    // for a name that doesn't exist and 400/403 for a bad key,
                    // so one call tells the rider which half to fix.
                    SettingsRow(
                        "Test AI connection", showDivider = false,
                        onClick = if (aiTestState == "Testing…") null else ({
                            val key = geminiKey.trim()
                            if (key.isEmpty()) {
                                aiTestState = "No key"
                                toast(context, "Add your Gemini API key first")
                            } else {
                                aiTestState = "Testing…"
                                scope.launch {
                                    val result = GeminiSummarizer(key, geminiModel).testConnection()
                                    aiTestState = result.fold(
                                        onSuccess = { "Working" },
                                        onFailure = { "Failed" },
                                    )
                                    toast(
                                        context,
                                        result.fold(
                                            onSuccess = { "$geminiModel replied: ${it.take(40)}" },
                                            onFailure = { e ->
                                                val m = e.message ?: "unknown error"
                                                when {
                                                    m.contains("404") -> "Model \"$geminiModel\" not found — check the name"
                                                    m.contains("400") || m.contains("403") -> "API key rejected — check the key"
                                                    else -> m.take(160)
                                                }
                                            },
                                        ),
                                    )
                                }
                            }
                        }),
                    ) {
                        MonoValue(
                            if (aiTestState.isEmpty()) "TEST ›" else aiTestState,
                            color = when (aiTestState) {
                                "Working" -> Ktm.Green
                                "Failed", "No key" -> Ktm.Orange
                                else -> Ktm.TextPrimary
                            },
                        )
                    }
                }
            }

            // ===== Notifications =====
            item {
                GroupCard("Notifications") {
                    SettingsRow(
                        "Notification access", showDivider = true,
                        onClick = if (notificationAccessGranted) null else ({
                            context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }),
                    ) {
                        MonoValue(
                            if (notificationAccessGranted) "Granted" else "Grant ›",
                            color = if (notificationAccessGranted) Ktm.Green else Ktm.Orange,
                        )
                    }
                    SettingsRow("Mirror-from apps", showDivider = true, onClick = { dialog = SettingsDialog.MIRROR_APPS }) {
                        MonoValue("${sourceApps.size} selected ›")
                    }
                    SettingsRow("Mirror to dash (quick-mute)", showDivider = true) {
                        KtmToggle(mirrorEnabled, { mirrorEnabled = it; settings.mirrorEnabled = it })
                    }
                    SettingsRow("Marquee scroll", showDivider = false) {
                        KtmToggle(marqueeEnabled, { marqueeEnabled = it; settings.marqueeEnabled = it })
                    }
                }
            }

            // ===== Navigation =====
            item {
                GroupCard("Navigation") {
                    SettingsRow("Auto-detect nav app", showDivider = true) {
                        KtmToggle(navAutoDetect, { on ->
                            navAutoDetect = on
                            if (on) { navOverride = null; settings.navAppOverride = null }
                            else { dialog = SettingsDialog.NAV_APP }
                        })
                    }
                    if (!navAutoDetect) {
                        SettingsRow("Nav app", showDivider = true, onClick = { dialog = SettingsDialog.NAV_APP }) {
                            MonoValue((navOverride?.let { appLabel(it) } ?: "Choose") + " ›")
                        }
                    }
                    SettingsRow("Call-audio device", showDivider = false, onClick = { dialog = SettingsDialog.CALL_AUDIO }) {
                        val label = bondedAudioDevices.firstOrNull { it.address == preferredAudioAddress }
                            ?.let { it.name ?: it.address } ?: "None"
                        MonoValue("$label ›")
                    }
                }
            }

            // ===== Sounds =====
            // Split out of the old 15-row "Riding" group: sound, sensors and
            // recording are three different jobs and were never one list.
            item {
                GroupCard("Sounds") {
                    SettingsRow("Turn approach beeps (stereo)", showDivider = true) {
                        KtmToggle(turnBeepEnabled, { turnBeepEnabled = it; settings.turnBeepEnabled = it })
                    }
                    // Tap to cycle presets - a slider is fiddly with gloves on.
                    SettingsRow(
                        "Beep volume", showDivider = true,
                        onClick = {
                            beepVolume = when {
                                beepVolume < 35 -> 35
                                beepVolume < 60 -> 60
                                beepVolume < 100 -> 100
                                else -> 20
                            }
                            settings.beepVolumePercent = beepVolume
                        },
                    ) {
                        MonoValue(beepVolumeLabel(beepVolume) + " \u203a")
                    }
                    SettingsRow("Swap beep left/right", showDivider = true) {
                        var swapBeep by remember { mutableStateOf(settings.swapBeepChannels) }
                        KtmToggle(swapBeep, { swapBeep = it; settings.swapBeepChannels = it })
                    }
                    // Hear-it-before-you-ride previews: the real beep at the
                    // configured volume/side order, the real voice prompt, and
                    // the overspeed tone (R21 field request).
                    SettingsRow(
                        "Hear turn beeps (L-R-L)", showDivider = true,
                        onClick = {
                            com.navigator.app.audio.TurnBeeper.volumePercent = settings.beepVolumePercent
                            com.navigator.app.audio.TurnBeeper.swapChannels = settings.swapBeepChannels
                            com.navigator.app.audio.TurnBeeper.preview()
                        },
                    ) { MonoValue("Play ›") }
                    SettingsRow(
                        "Hear overspeed alert", showDivider = true,
                        onClick = {
                            runCatching {
                                // Match the live alert: alarm stream, max volume.
                                val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                                tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
                                android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed({ runCatching { tg.release() } }, 800)
                            }
                        },
                    ) { MonoValue("Play ›") }
                    SettingsRow("Overspeed alert", showDivider = true) {
                        KtmToggle(overspeedEnabled, { overspeedEnabled = it; settings.overspeedEnabled = it })
                    }
                    SettingsRow("Speed limit", showDivider = false, onClick = { dialog = SettingsDialog.OVERSPEED_LIMIT }) {
                        MonoValue("$overspeedLimit km/h \u203a")
                    }
                }
            }

            // ===== Engine & sensors =====
            item {
                GroupCard("Engine & sensors") {
                    SettingsRow("Engine detect (vibration)", showDivider = true) {
                        KtmToggle(engineDetect, {
                            engineDetect = it; settings.engineDetectEnabled = it
                            BccuConnectionService.reevaluateEngineDetectIfRunning()
                        })
                    }
                    SettingsRow("Calibrate engine detect", showDivider = true, onClick = onOpenVibrationCalibration) {
                        val calibrated = !settings.vibrationIdleRms.isNaN()
                        MonoValue(if (calibrated) "Calibrated ›" else "Set up ›")
                    }
                    // Segmented rather than tap-to-cycle: with gloves on you
                    // want to see all three positions and hit one, not discover
                    // the order by cycling past it.
                    SettingsRow("Detection sensitivity", showDivider = false) {
                        Segmented(
                            options = listOf("LOW", "NORM", "HIGH"),
                            selected = when {
                                vibSensitivity > 0 -> 0
                                vibSensitivity < 0 -> 2
                                else -> 1
                            },
                            onSelect = { index ->
                                vibSensitivity = when (index) {
                                    0 -> 25   // less sensitive
                                    2 -> -25  // more sensitive
                                    else -> 0
                                }
                                settings.vibrationSensitivity = vibSensitivity
                                BccuConnectionService.reevaluateEngineDetectIfRunning()
                            },
                        )
                    }
                }
            }

            // ===== Recording =====
            item {
                GroupCard("Recording") {
                    SettingsRow("Auto-record rides", showDivider = false) {
                        KtmToggle(routeRecordEnabled, {
                            routeRecordEnabled = it; settings.routeAutoRecordEnabled = it
                            BccuConnectionService.reevaluateRouteRecordingIfRunning()
                        })
                    }
                    RowNote(
                        "Starts when the bike authenticates, stops at disconnect. " +
                            "Car trips and walks are never recorded."
                    )
                    SettingsRow("Power saver (full rate on charge)", showDivider = true) {
                        KtmToggle(powerSave, {
                            powerSave = it; settings.powerSaveEnabled = it
                            BccuConnectionService.reevaluateRouteRecordingIfRunning()
                            BccuConnectionService.reevaluateEngineDetectIfRunning()
                        })
                    }
                    SettingsRow("View rides", showDivider = true, onClick = onOpenRides) {
                        MonoValue("$rideCount ›")
                    }
                    // "Allow all the time" is a SEPARATE grant from the normal
                    // location permission and can't be requested in the same
                    // dialog - without it, GPS features are dead whenever the
                    // service auto-started in the background (boot / bike
                    // reconnect) rather than from the app being open.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        var bgLocationGranted by remember {
                            mutableStateOf(
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                            )
                        }
                        val bgLocationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                        ) { granted ->
                            bgLocationGranted = granted
                            if (granted) {
                                BccuConnectionService.reevaluateLocationIfRunning()
                            } else {
                                // Android suppresses the request UI after repeated
                                // denials (or when foreground location is missing) -
                                // never leave this as a silent dead button.
                                android.widget.Toast.makeText(
                                    context,
                                    "Choose \"Allow all the time\" under Permissions → Location",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                                context.startActivity(
                                    Intent(
                                        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        }
                        val fineLocationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
                        ) { result ->
                            // Background can only be requested once foreground is granted.
                            if (result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                                BccuConnectionService.reevaluateLocationIfRunning()
                                bgLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        }
                        SettingsRow(
                            "Background location (\"all the time\")", showDivider = true,
                            onClick = if (bgLocationGranted) null else ({
                                val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                                if (fineGranted) {
                                    bgLocationLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                } else {
                                    fineLocationLauncher.launch(arrayOf(
                                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ))
                                }
                            }),
                        ) {
                            MonoValue(
                                if (bgLocationGranted) "Granted" else "Grant ›",
                                color = if (bgLocationGranted) Ktm.Green else Ktm.Orange,
                            )
                        }
                    }
                    SettingsRow(
                        "Share recorded routes", showDivider = true,
                        onClick = { exportRoutes(context) },
                    ) {
                        MonoValue("$rideCount GPX ›")
                    }
                    // Whole-history backup: one zip out, the same zip back in on
                    // a new phone. Without this, switching phones threw away
                    // every ride and the traffic map built from them.
                    SettingsRow(
                        "Back up all rides (.zip)", showDivider = true,
                        onClick = {
                            if (rideCount == 0) {
                                toast(context, "No recorded rides yet")
                            } else {
                                zipExportLauncher.launch(
                                    com.navigator.app.location.RideArchive
                                        .suggestedName(System.currentTimeMillis())
                                )
                            }
                        },
                    ) { MonoValue("Save ›") }
                    SettingsRow(
                        "Restore rides from a backup", showDivider = false,
                        onClick = { zipImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    ) { MonoValue("Open ›") }
                    RowNote("Moving to a new phone: back up here, then restore on the new phone. Rides already on this phone are never overwritten.")
                }
            }

            // ===== Dash favourites (Home / Work) =====
            item {
                GroupCard("Dash favourites") {
                    SettingsRow(
                        "Home", showDivider = true,
                        onClick = { dialog = SettingsDialog.HOME_PLACE },
                    ) { MonoValue(homeAddress ?: homeDestination ?: "Not set") }
                    SettingsRow(
                        "Set Home to current location", showDivider = true,
                        onClick = {
                            val value = currentLocationTarget(context)
                            if (value != null) {
                                homeDestination = value; settings.homeDestination = value
                                homeAddress = null; settings.homeAddress = null
                            } else {
                                android.widget.Toast.makeText(context, "No GPS fix yet", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) { OutlinedPill("SET") }
                    SettingsRow(
                        "Work", showDivider = true,
                        onClick = { dialog = SettingsDialog.WORK_PLACE },
                    ) { MonoValue(workAddress ?: workDestination ?: "Not set") }
                    SettingsRow(
                        "Set Work to current location", showDivider = true,
                        onClick = {
                            val value = currentLocationTarget(context)
                            if (value != null) {
                                workDestination = value; settings.workDestination = value
                                workAddress = null; settings.workAddress = null
                            } else {
                                android.widget.Toast.makeText(context, "No GPS fix yet", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) { OutlinedPill("SET") }
                    SettingsRow(
                        "Clear both", showDivider = false,
                        onClick = {
                            homeDestination = null; settings.homeDestination = null
                            homeAddress = null; settings.homeAddress = null
                            workDestination = null; settings.workDestination = null
                            workAddress = null; settings.workAddress = null
                        },
                    ) { OutlinedPill("CLEAR") }
                }
            }

            // ===== Waypoint =====
            item {
                GroupCard("Waypoint") {
                    SettingsRow("Saved waypoint", showDivider = true) {
                        MonoValue(waypoint ?: "None")
                    }
                    SettingsRow(
                        "Set to current location", showDivider = true,
                        onClick = {
                            val loc = lastKnownLocation(context)
                            if (loc != null) {
                                // Locale.US: a comma-decimal locale would corrupt the "lat,lon" format.
                                val value = "%.6f,%.6f".format(java.util.Locale.US, loc.latitude, loc.longitude)
                                waypoint = value
                                settings.waypoint = value
                                settings.waypointName = "Waypoint"
                                BccuConnectionService.sendNotificationIfRunning(
                                    "WAYPOINT SAVED",
                                    BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT,
                                )
                            } else {
                                android.widget.Toast.makeText(context, "No GPS fix yet", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) { OutlinedPill("SET") }
                    SettingsRow(
                        "Navigate there (Google Maps)", showDivider = true,
                        onClick = {
                            waypoint?.let { wp ->
                                // google.navigation with mode=l = two-wheeler routing.
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$wp&mode=l"))
                                    .setPackage("com.google.android.apps.maps")
                                runCatching { context.startActivity(intent) }.onFailure {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$wp?q=$wp")))
                                }
                            }
                        },
                    ) { OutlinedPill("GO") }
                    SettingsRow(
                        "Remove waypoint", showDivider = false,
                        onClick = {
                            waypoint = null
                            settings.waypoint = null
                            settings.waypointName = null
                        },
                    ) { OutlinedPill("CLEAR") }
                }
            }

            // ===== Handlebar gamepad =====
            item {
                GroupCard("Handlebar gamepad") {
                    SettingsRow("Control any app with the remote", showDivider = true) {
                        KtmToggle(gamepadEnabled, { on ->
                            gamepadEnabled = on
                            settings.gamepadEnabled = on
                            // remoteMode is what the button pipeline actually gates
                            // on - without setting it this toggle did nothing (and
                            // couldn't turn gamepad mode OFF once the handlebar
                            // overlay had enabled it).
                            settings.remoteMode = if (on) AppSettings.MODE_GAMEPAD else AppSettings.MODE_MEDIA
                            // Turning it on with the service not yet enabled: send
                            // the rider straight to the accessibility settings.
                            if (on && !accessibilityGranted) {
                                context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        })
                    }
                    SettingsRow(
                        "Accessibility service", showDivider = true,
                        onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    ) {
                        MonoValue(
                            if (accessibilityGranted) "Enabled" else "Enable ›",
                            color = if (accessibilityGranted) Ktm.Green else Ktm.Orange,
                        )
                    }
                    SettingsRow("How it works", showDivider = false) {
                        Text(
                            "Up/Down move · Set taps · Back",
                            color = Ktm.Dim, fontFamily = BarlowCondensed, fontSize = 12.sp,
                        )
                    }
                }
            }

            // ===== Idle screen =====
            item {
                GroupCard("Idle screen") {
                    SettingsRow("Show info on dash while not navigating", showDivider = true) {
                        KtmToggle(idleDashEnabled, { idleDashEnabled = it; settings.idleDashEnabled = it })
                    }
                    SettingsRow("Clock & date", showDivider = true) {
                        KtmToggle(idleShowClock, { idleShowClock = it; settings.idleShowClock = it })
                    }
                    SettingsRow("Now playing", showDivider = true) {
                        KtmToggle(idleShowMusic, { idleShowMusic = it; settings.idleShowMusic = it })
                    }
                    SettingsRow("Weather", showDivider = true) {
                        KtmToggle(idleShowWeather, { idleShowWeather = it; settings.idleShowWeather = it })
                    }
                    SettingsRow("Song info while next turn is 500 m+", showDivider = true) {
                        KtmToggle(farTurnMusic, { farTurnMusic = it; settings.farTurnMusicEnabled = it })
                    }
                    SettingsRow(
                        "Rotate every", showDivider = false,
                        onClick = {
                            val steps = listOf(5, 8, 10, 15, 20)
                            val next = steps[(steps.indexOf(idleRotateSec) + 1).mod(steps.size)]
                            idleRotateSec = next
                            settings.idleRotateSec = next
                        },
                    ) { MonoValue("$idleRotateSec s ›") }
                }
            }

            // ===== Diagnostics =====
            item {
                GroupCard("Diagnostics") {
                    SettingsRow("View & share logs", showDivider = true, onClick = onOpenLogs) {
                        Text("›", color = Ktm.Dim, fontSize = 18.sp)
                    }
                    SettingsRow("Write logs to file", showDivider = true) {
                        KtmToggle(fileLogging, {
                            fileLogging = it
                            settings.fileLoggingEnabled = it
                            com.navigator.app.logging.AppLogger.fileLoggingEnabled = it
                        })
                    }
                    RowNote(
                        "Off by default. The Logs screen above always works; this only " +
                            "adds the on-disk copy under Downloads/Navigator, for sharing a " +
                            "long session after the fact."
                    )
                    // Test rows live behind developer mode (ten taps on the
                    // home-screen wordmark) - they're for validating dash
                    // behaviour, not something a rider needs day to day.
                    if (devMode) {
                        SettingsRow("Developer mode", showDivider = true) {
                            KtmToggle(true, {
                                devMode = false
                                settings.devModeEnabled = false
                            })
                        }
                        RowNote("Turn off to hide the test tools below. Re-enable by tapping the logo on the home screen ten times.")
                        SettingsRow("Symbol testing", showDivider = true, onClick = onOpenSymbolTest) {
                            Text("›", color = Ktm.Dim, fontSize = 18.sp)
                        }
                        SettingsRow("Developer settings", showDivider = true, onClick = onOpenDeveloper) {
                            Text("›", color = Ktm.Dim, fontSize = 18.sp)
                        }
                        SettingsRow("Turn icon calibration", showDivider = true, onClick = onOpenTurnCalibration) {
                            Text("›", color = Ktm.Dim, fontSize = 18.sp)
                        }
                        SettingsRow(
                            "Send test notification", showDivider = true,
                            onClick = {
                                BccuConnectionService.sendNotificationIfRunning(
                                    "Hey ${userName.ifBlank { "there" }}",
                                    BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT,
                                )
                            },
                        ) { Text("SEND", color = Ktm.Orange, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp) }
                        SettingsRow(
                            "Send test guidance", showDivider = true,
                            onClick = {
                                BccuConnectionService.sendTurnIconIfRunning(BccuProtocol.TurnIcon.GO_STRAIGHT)
                                BccuConnectionService.sendGuidanceIfRunning("50 m", "Test Road")
                            },
                        ) { Text("SEND", color = Ktm.Orange, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp) }
                    }
                    SettingsRow(
                        "Battery optimization", showDivider = true,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val powerManager = context.getSystemService(PowerManager::class.java)
                                if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                                    context.startActivity(
                                        Intent(
                                            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }
                        },
                    ) { Text("›", color = Ktm.Dim, fontSize = 18.sp) }
                    SettingsRow("Build", showDivider = false) {
                        MonoValue(com.navigator.app.BuildConfig.VERSION_NAME)
                    }
                }
            }
        }
    }

    // ===== Dialogs =====
    when (dialog) {
        SettingsDialog.NAME -> TextFieldDialog(
            title = "Your name", initial = userName, label = "Name",
            onDismiss = { dialog = SettingsDialog.NONE },
            onConfirm = { userName = it; settings.userName = it; dialog = SettingsDialog.NONE },
        )
        SettingsDialog.HOME_PLACE -> TextFieldDialog(
            title = "Home", initial = homeAddress ?: homeDestination.orEmpty(), label = "Address or lat,lon",
            onDismiss = { dialog = SettingsDialog.NONE },
            onConfirm = {
                val entered = it.trim()
                // A typed address is both the routing target and the label the
                // dash shows; "lat,lon" from the SET button has no label.
                homeDestination = entered.ifEmpty { null }
                homeAddress = entered.ifEmpty { null }
                settings.homeDestination = homeDestination
                settings.homeAddress = homeAddress
                dialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.WORK_PLACE -> TextFieldDialog(
            title = "Work", initial = workAddress ?: workDestination.orEmpty(), label = "Address or lat,lon",
            onDismiss = { dialog = SettingsDialog.NONE },
            onConfirm = {
                val entered = it.trim()
                workDestination = entered.ifEmpty { null }
                workAddress = entered.ifEmpty { null }
                settings.workDestination = workDestination
                settings.workAddress = workAddress
                dialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.GEMINI -> TextFieldDialog(
            title = "Gemini API key", initial = geminiKey, label = "API key",
            onDismiss = { dialog = SettingsDialog.NONE },
            onConfirm = { geminiKey = it; settings.geminiApiKey = it; dialog = SettingsDialog.NONE },
        )
        SettingsDialog.GEMINI_MODEL -> {
            LaunchedEffect(Unit) {
                val key = geminiKey.trim()
                if (key.isNotEmpty() && fetchedModels.isEmpty()) {
                    GeminiSummarizer(key, geminiModel).listModels()
                        .onSuccess { if (it.isNotEmpty()) fetchedModels = it }
                }
            }
            SingleChoiceDialog(
            title = if (fetchedModels.isEmpty()) "Gemini model" else "Gemini model (live)",
            // Built-ins, then anything the rider typed in before, then the
            // free-text row - so a model Google released after this build can
            // still be selected, and stays in the list once entered.
            options = (fetchedModels.ifEmpty { AppSettings.GEMINI_MODELS } +
                customGeminiModels.sorted() + geminiModel).distinct() +
                AppSettings.GEMINI_MODEL_CUSTOM,
            labelFor = { if (it == AppSettings.GEMINI_MODEL_CUSTOM) "Custom model…" else it },
            selected = geminiModel,
            onDismiss = { dialog = SettingsDialog.NONE },
            onSelect = {
                if (it == AppSettings.GEMINI_MODEL_CUSTOM) {
                    dialog = SettingsDialog.GEMINI_MODEL_CUSTOM
                } else {
                    geminiModel = it; settings.geminiModel = it; dialog = SettingsDialog.NONE
                }
            },
            // Only hand-typed entries can be removed - a mistyped model name
            // would otherwise be stuck in the list forever.
            removable = { it in customGeminiModels },
            onRemove = { gone ->
                customGeminiModels = customGeminiModels - gone
                settings.customGeminiModels = customGeminiModels
                if (geminiModel == gone) {
                    geminiModel = AppSettings.DEFAULT_GEMINI_MODEL
                    settings.geminiModel = geminiModel
                }
            },
            )
        }
        SettingsDialog.GEMINI_MODEL_CUSTOM -> TextFieldDialog(
            title = "Custom Gemini model",
            // Empty, not the current model: this row exists to ADD a newly
            // released name, and prefilling means clearing it first every time.
            initial = "",
            label = "e.g. gemini-3-flash",
            onDismiss = { dialog = SettingsDialog.NONE },
            onConfirm = { entered ->
                val name = entered.trim()
                if (name.isNotEmpty()) {
                    geminiModel = name
                    settings.geminiModel = name
                    // Remember it only if it isn't already a built-in, so the
                    // picker doesn't grow duplicate rows.
                    if (name !in AppSettings.GEMINI_MODELS) {
                        customGeminiModels = customGeminiModels + name
                        settings.customGeminiModels = customGeminiModels
                    }
                }
                dialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.OVERSPEED_LIMIT -> TextFieldDialog(
            title = "Overspeed limit (km/h)", initial = overspeedLimit.toString(), label = "km/h",
            onDismiss = { dialog = SettingsDialog.NONE },
            onConfirm = { value ->
                value.trim().toIntOrNull()?.takeIf { it in 30..200 }?.let {
                    overspeedLimit = it
                    settings.overspeedLimitKmh = it
                }
                dialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.NAV_APP -> SingleChoiceDialog(
            title = "Navigation app",
            options = AppSettings.KNOWN_NAV_APPS.toList(),
            labelFor = { appLabel(it) },
            selected = navOverride,
            onDismiss = {
                // Cancelling with nothing chosen falls back to auto-detect.
                if (navOverride == null) navAutoDetect = true
                dialog = SettingsDialog.NONE
            },
            onSelect = {
                navOverride = it; settings.navAppOverride = it; navAutoDetect = false
                dialog = SettingsDialog.NONE
            },
        )
        SettingsDialog.CALL_AUDIO -> SingleChoiceDialog(
            title = "Call-audio device",
            options = bondedAudioDevices.map { it.address },
            labelFor = { addr -> bondedAudioDevices.firstOrNull { it.address == addr }?.let { it.name ?: it.address } ?: addr },
            selected = preferredAudioAddress,
            onDismiss = { dialog = SettingsDialog.NONE },
            onSelect = { preferredAudioAddress = it; settings.preferredCallAudioDeviceAddress = it; dialog = SettingsDialog.NONE },
        )
        SettingsDialog.MIRROR_APPS -> MultiChoiceDialog(
            title = "Mirror-from apps",
            options = installedApps.map { it.first },
            labelFor = { appLabel(it) },
            selected = sourceApps,
            onDismiss = { dialog = SettingsDialog.NONE },
            onToggle = { pkg, checked ->
                sourceApps = if (checked) sourceApps + pkg else sourceApps - pkg
                settings.notificationSourceApps = sourceApps
            },
        )
        SettingsDialog.NONE -> {}
    }
}

/** Best-available last known fix across providers (GPS preferred). */
@SuppressLint("MissingPermission")
/**
 * Current position as a Google Maps navigation target, or null without a fix.
 * Locale.US on purpose: a comma-decimal locale would corrupt "lat,lon".
 */
private fun currentLocationTarget(context: android.content.Context): String? {
    val loc = lastKnownLocation(context) ?: return null
    return "%.6f,%.6f".format(java.util.Locale.US, loc.latitude, loc.longitude)
}

private fun lastKnownLocation(context: android.content.Context): android.location.Location? {
    if (androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
    ) return null
    val lm = context.getSystemService(android.location.LocationManager::class.java)
    return listOf(
        android.location.LocationManager.GPS_PROVIDER,
        android.location.LocationManager.NETWORK_PROVIDER,
        android.location.LocationManager.PASSIVE_PROVIDER,
    ).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
}

/** Share all recorded GPX route files via the app's FileProvider. */
private fun exportRoutes(context: android.content.Context) {
    val files = com.navigator.app.location.RouteRecorder.listRoutes(context)
    if (files.isEmpty()) {
        android.widget.Toast.makeText(context, "No recorded routes yet", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val uris = ArrayList(files.map {
        androidx.core.content.FileProvider.getUriForFile(context, "${com.navigator.app.BuildConfig.APPLICATION_ID}.fileprovider", it)
    })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/gpx+xml"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export routes"))
}

private fun toast(context: android.content.Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
}

private fun maskKey(key: String): String = when {
    key.isBlank() -> "Not set ›"
    key.length <= 4 -> "•••• ›"
    else -> "••••••" + key.takeLast(4)
}

@Composable
private fun OutlinedPill(text: String) {
    Text(
        text, color = Ktm.Orange, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
        fontSize = 12.sp, letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, Ktm.Orange, RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun TextFieldDialog(
    title: String, initial: String, label: String,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ktm.Surface,
        titleContentColor = Ktm.White,
        textContentColor = Ktm.TextPrimary,
        title = { Text(title, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                label = { Text(label) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("SAVE", color = Ktm.Orange) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Ktm.Dim) } },
    )
}

@Composable
private fun <T> SingleChoiceDialog(
    title: String, options: List<T>, labelFor: (T) -> String, selected: T?,
    onDismiss: () -> Unit, onSelect: (T) -> Unit,
    /** Rows this returns true for get a "✕" that calls [onRemove]. */
    removable: (T) -> Boolean = { false },
    onRemove: (T) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ktm.Surface,
        titleContentColor = Ktm.White,
        title = { Text(title, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold) },
        text = {
            if (options.isEmpty()) {
                Text("Nothing available.", color = Ktm.Dim, fontFamily = Barlow)
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .selectable(selected == option) { onSelect(option) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == option, onClick = { onSelect(option) },
                                colors = RadioButtonDefaults.colors(selectedColor = Ktm.Orange, unselectedColor = Ktm.Dim),
                            )
                            Text(
                                labelFor(option), color = Ktm.TextPrimary, fontFamily = Barlow,
                                modifier = Modifier.weight(1f),
                            )
                            if (removable(option)) {
                                Text(
                                    "✕", color = Ktm.Dim, fontFamily = Barlow,
                                    modifier = Modifier
                                        .clickable { onRemove(option) }
                                        .padding(horizontal = 10.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("DONE", color = Ktm.Orange) } },
    )
}

@Composable
private fun <T> MultiChoiceDialog(
    title: String, options: List<T>, labelFor: (T) -> String, selected: Set<T>,
    onDismiss: () -> Unit, onToggle: (T, Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ktm.Surface,
        titleContentColor = Ktm.White,
        title = { Text(title, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    val checked = option in selected
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onToggle(option, !checked) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked, onCheckedChange = { onToggle(option, it) },
                            colors = CheckboxDefaults.colors(checkedColor = Ktm.Orange, uncheckedColor = Ktm.Dim),
                        )
                        Text(labelFor(option), color = Ktm.TextPrimary, fontFamily = Barlow)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("DONE", color = Ktm.Orange) } },
    )
}

/**
 * Beep volume as a word. The underlying value is a percentage, but "60%" of a
 * tone the rider has never measured means nothing - what they want to know is
 * whether it will cut through the wind.
 */
private fun beepVolumeLabel(percent: Int): String = when {
    percent <= 20 -> "Quiet"
    percent <= 35 -> "Low"
    percent <= 60 -> "Medium"
    else -> "Loud"
}
