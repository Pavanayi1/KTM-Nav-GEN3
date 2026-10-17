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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.navigator.app.ui.components.KtmToggle
import com.navigator.app.ui.components.MonoValue
import com.navigator.app.ui.components.SettingsRow
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.OpenDashIcons

private enum class SettingsDialog { NONE, NAME, GEMINI, GEMINI_MODEL, NAV_APP, MIRROR_APPS, CALL_AUDIO, OVERSPEED_LIMIT }

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
    var gamepadEnabled by remember { mutableStateOf(settings.gamepadEnabled) }
    var geminiModel by remember { mutableStateOf(settings.geminiModel) }
    var turnBeepEnabled by remember { mutableStateOf(settings.turnBeepEnabled) }
    var beepVolume by remember { mutableStateOf(settings.beepVolumePercent) }
    var powerSave by remember { mutableStateOf(settings.powerSaveEnabled) }
    var engineDetect by remember { mutableStateOf(settings.engineDetectEnabled) }
    var vibSensitivity by remember { mutableStateOf(settings.vibrationSensitivity) }
    var overspeedEnabled by remember { mutableStateOf(settings.overspeedEnabled) }
    var overspeedLimit by remember { mutableStateOf(settings.overspeedLimitKmh) }
    var routeRecordEnabled by remember { mutableStateOf(settings.routeAutoRecordEnabled) }
    var waypoint by remember { mutableStateOf(settings.waypoint) }
    var accessibilityGranted by remember {
        mutableStateOf(com.navigator.app.controller.RemoteControlAccessibilityService.isRunning)
    }
    var dialog by remember { mutableStateOf(SettingsDialog.NONE) }

    val installedApps = remember {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString() }
    }
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
                accessibilityGranted = com.navigator.app.controller.RemoteControlAccessibilityService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val connectionState by BccuConnectionService.connectionState.collectAsState()
    val connected = connectionState == BccuConnectionService.ConnectionState.AUTHENTICATED
    val vehicleName = settings.bondedDeviceName ?: "Not paired"
    var showConnectionOptions by remember { mutableStateOf(false) }
    if (showConnectionOptions) {
        settings.bondedDeviceAddress?.let { address ->
            com.navigator.app.ui.components.ConnectionOptionsDialog(settings, address,
                onDismiss = { showConnectionOptions = false }) {
                BccuConnectionService.start(context, address, restartConnection = true)
            }
        }
    }

    fun appLabel(pkg: String): String = try {
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
                OpenDashIcons.ChevronLeft, contentDescription = "Back", tint = Ktm.TextSecondary,
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
                    SettingsRow("Re-pair vehicle", showDivider = true, onClick = onRepair) {
                        OutlinedPill("RE-PAIR")
                    }
                    settings.bondedDeviceAddress?.let { address ->
                        SettingsRow("Connection options", showDivider = true, onClick = { showConnectionOptions = true }) {
                            OutlinedPill("SETUP / RETRY")
                        }
                        com.navigator.app.ui.components.ConnectionProblemNotice(settings, address)
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
                    SettingsRow("Gemini model", showDivider = false, onClick = { dialog = SettingsDialog.GEMINI_MODEL }) {
                        MonoValue("$geminiModel ›")
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

            // ===== Riding =====
            item {
                GroupCard("Riding") {
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
                        MonoValue("$beepVolume% ›")
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
                    SettingsRow(
                        "Detection sensitivity", showDivider = true,
                        onClick = {
                            vibSensitivity = when (vibSensitivity) {
                                -25 -> 0; 0 -> 25; else -> -25
                            }
                            settings.vibrationSensitivity = vibSensitivity
                            BccuConnectionService.reevaluateEngineDetectIfRunning()
                        },
                    ) {
                        MonoValue(
                            when {
                                vibSensitivity < 0 -> "More sensitive ›"
                                vibSensitivity > 0 -> "Less sensitive ›"
                                else -> "Normal ›"
                            }
                        )
                    }
                    SettingsRow("Power saver (full rate on charge)", showDivider = true) {
                        KtmToggle(powerSave, {
                            powerSave = it; settings.powerSaveEnabled = it
                            BccuConnectionService.reevaluateRouteRecordingIfRunning()
                            BccuConnectionService.reevaluateEngineDetectIfRunning()
                        })
                    }
                    SettingsRow("View rides", showDivider = true, onClick = onOpenRides) {
                        MonoValue("${com.navigator.app.location.RouteRecorder.listRoutes(context).size} ›")
                    }
                    SettingsRow("Overspeed alert", showDivider = true) {
                        KtmToggle(overspeedEnabled, { overspeedEnabled = it; settings.overspeedEnabled = it })
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
                    SettingsRow("Speed limit", showDivider = true, onClick = { dialog = SettingsDialog.OVERSPEED_LIMIT }) {
                        MonoValue("$overspeedLimit km/h ›")
                    }
                    SettingsRow("Auto-record routes while charging", showDivider = true) {
                        KtmToggle(routeRecordEnabled, {
                            routeRecordEnabled = it; settings.routeAutoRecordEnabled = it
                            BccuConnectionService.reevaluateRouteRecordingIfRunning()
                        })
                    }
                    SettingsRow(
                        "Export recorded routes", showDivider = false,
                        onClick = { exportRoutes(context) },
                    ) {
                        val count = remember(routeRecordEnabled) {
                            com.navigator.app.location.RouteRecorder.listRoutes(context).size
                        }
                        MonoValue("$count GPX ›")
                    }
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

            // ===== Diagnostics =====
            item {
                GroupCard("Diagnostics") {
                    SettingsRow("View & share logs", showDivider = true, onClick = onOpenLogs) {
                        Text("›", color = Ktm.Dim, fontSize = 18.sp)
                    }
                    SettingsRow("Symbol testing", showDivider = true, onClick = onOpenSymbolTest) {
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
        SettingsDialog.GEMINI -> TextFieldDialog(
            title = "Gemini API key", initial = geminiKey, label = "API key",
            onDismiss = { dialog = SettingsDialog.NONE },
            onConfirm = { geminiKey = it; settings.geminiApiKey = it; dialog = SettingsDialog.NONE },
        )
        SettingsDialog.GEMINI_MODEL -> SingleChoiceDialog(
            title = "Gemini model",
            options = AppSettings.GEMINI_MODELS,
            labelFor = { it },
            selected = geminiModel,
            onDismiss = { dialog = SettingsDialog.NONE },
            onSelect = { geminiModel = it; settings.geminiModel = it; dialog = SettingsDialog.NONE },
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
            options = installedApps.map { it.packageName },
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
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
    })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/gpx+xml"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export routes"))
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
                            Text(labelFor(option), color = Ktm.TextPrimary, fontFamily = Barlow)
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
