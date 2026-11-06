package com.navigator.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.compose.animation.togetherWith
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.navigator.app.audio.CallAudioRouter
import com.navigator.app.audio.MediaControlBridge
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.controller.ControllerScreen
import com.navigator.app.controller.ControllerStateMachine
import com.navigator.app.logging.AppLogger
import com.navigator.app.notifications.NotificationRepository
import com.navigator.app.settings.AppSettings
import com.navigator.app.telephony.CallStateMonitor
import com.navigator.app.ui.screens.DirectionScreen
import com.navigator.app.ui.screens.GridMenuScreen
import com.navigator.app.ui.screens.NotificationScreen
import com.navigator.app.ui.screens.PairingScreen
import com.navigator.app.ui.screens.SettingsScreen
import com.navigator.app.ui.theme.NavigatorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Navigation depth, for choosing the slide direction between two screens. */
private fun routeDepth(r: AppRoute): Int = when (r) {
    AppRoute.BRAND -> 0
    AppRoute.ONBOARDING -> 1
    AppRoute.PAIRING -> 2
    AppRoute.MAIN -> 3
    AppRoute.SETTINGS, AppRoute.RIDES -> 4
    else -> 5
}

private fun routeTransition(from: AppRoute, to: AppRoute): androidx.compose.animation.ContentTransform {
    val forward = routeDepth(to) >= routeDepth(from)
    val dir = if (forward) 1 else -1
    val ease = androidx.compose.animation.core.FastOutSlowInEasing
    val enter = androidx.compose.animation.slideInHorizontally(
        androidx.compose.animation.core.tween(320, easing = ease)
    ) { w -> dir * w / 4 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260, delayMillis = 40))
    val exit = androidx.compose.animation.slideOutHorizontally(
        androidx.compose.animation.core.tween(320, easing = ease)
    ) { w -> -dir * w / 8 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))
    return enter togetherWith exit
}

private enum class AppRoute { BRAND, ONBOARDING, PAIRING, MAIN, SETTINGS, LOGS, SYMBOL_TEST, TURN_CALIBRATION, VIBRATION_CALIBRATION, RIDES, DEVELOPER, RIDE_ANALYTICS, TRAFFIC_MAP }

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * GPX handed to us via ACTION_VIEW (file manager, WhatsApp, …), already
         * copied into the routes folder. Compose observes this to jump to the
         * ride viewer; cleared when the viewer is left. A flow (not an intent
         * extra) because with launchMode=singleTask a second GPX arrives via
         * onNewIntent on the LIVE activity - there is no recomposition-from-
         * scratch to re-read an extra from.
         */
        val importedGpx = kotlinx.coroutines.flow.MutableStateFlow<java.io.File?>(null)

        /** Grid tile / handlebar "Rides" request - the composable owns the route state, so signal it via a flow. */
        val openRidesRequest = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    private lateinit var settings: AppSettings
    private lateinit var mediaControl: MediaControlBridge
    private lateinit var callAudioRouter: CallAudioRouter
    private lateinit var callStateMonitor: CallStateMonitor
    private lateinit var stateMachine: ControllerStateMachine
    private lateinit var actions: ControllerStateMachine.Actions

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // The long-lived connection service usually starts BEFORE this async
        // grant lands - tell it to re-check location-gated features (overspeed
        // monitor, route recorder, FGS location type) now.
        BccuConnectionService.reevaluateLocationIfRunning()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Decode the bike art off the main thread before the first screen needs it.
        com.navigator.app.ui.components.BikeBitmaps.preload(this)

        // Draw edge-to-edge from the very first frame so the layout fills the
        // screen immediately instead of settling once the window insets arrive
        // (each screen applies systemBarsPadding() to keep content clear of the
        // status/nav bars). Without this the UI visibly "jumped" on launch.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        settings = AppSettings(this)
        // Apply the saved brand theme before the first frame so the app opens in
        // the right skin (KTM dark / Husqvarna light) with no flash of the wrong one.
        com.navigator.app.ui.theme.Ktm.applyBrand(settings.brand)
        mediaControl = MediaControlBridge(this)
        callAudioRouter = CallAudioRouter(this)

        // First-run users grant permissions with context in the onboarding flow;
        // returning users get a silent top-up request for anything since revoked.
        if (settings.onboardingComplete) requestRuntimePermissions()

        // Auto-connect: if we already have a bonded bike, bring the connection
        // service up as soon as the app is opened (it's a foreground service and
        // owns its own reconnect loop from there on). The AutoConnectReceiver
        // covers the "app never opened" cases (boot, bike back in range).
        // Opening the app is the rider un-quitting.
        settings.userQuit = false
        settings.bondedDeviceAddress?.let { BccuConnectionService.start(this, it) }

        var callRingingState = mutableStateOf(false)
        actions = object : ControllerStateMachine.Actions {
            override fun playPauseMedia() = mediaControl.playPause()
            override fun nextTrack() = mediaControl.next()
            override fun previousTrack() = mediaControl.previous()
            override fun answerCall() {
                callStateMonitor.answer()
                callAudioRouter.routeCallToPreferredDevice()
            }
            override fun rejectCall() = callStateMonitor.silenceRinger()
            override fun minimizeToHome() { moveTaskToBack(true) }
            override fun openMaps() {
                val pkg = NotificationRepository.currentNavPackage.value
                    ?: settings.navAppOverride
                val launchIntent = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))
                }
            }
            override fun bringAppToForeground() {
                if (AppForegroundState.isForeground) return
                val intent = Intent(this@MainActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            override fun openIntercom() {
                // Android 10 is the intercom's own tested floor. Below it, lint
                // flags a Wi-Fi lock mode (WIFI_MODE_FULL_LOW_LATENCY) that can
                // throw on 8/9 - so say so instead of opening into a crash.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.widget.Toast.makeText(
                        this@MainActivity, "Intercom needs Android 10 or newer", android.widget.Toast.LENGTH_LONG,
                    ).show()
                    return
                }
                startActivity(
                    Intent(this@MainActivity, com.mensa.riderintercom.ui.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            override fun openRides() {
                bringAppToForeground()
                openRidesRequest.value = true
            }
            override fun showWatchdogWarning() {
                // NOTIFICATION_WAYPOINT is confirmed rendering on real hardware;
                // WARNING is confirmed NOT to render - see AppNotificationListener.kt.
                BccuConnectionService.sendNotificationIfRunning(
                    "RMT EXIT IN 5 SEC",
                    BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT
                )
            }
            override fun showRemoteModePicker() {
                BccuConnectionService.showModeOverlayIfRunning()
            }
            override fun exitApp() {
                // Stop the foreground service (removes its ongoing notification),
                // cancel any of our notifications, then remove the app task entirely.
                // Persisted, so nothing restarts the service behind the rider's back.
                settings.userQuit = true
                BccuConnectionService.stop(this@MainActivity)
                (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancelAll()
                finishAndRemoveTask()
            }
        }
        stateMachine = ControllerStateMachine(actions)

        callStateMonitor = CallStateMonitor(
            this,
            onRingingChanged = { ringing ->
                callRingingState.value = ringing
                stateMachine.isCallRinging = ringing
            },
            onCallActive = {
                // Route AFTER the call settles: the bike's BT SCO grabs the audio
                // at connect, so an immediate setCommunicationDevice gets stomped
                // (R40 field report: call went to the bike despite a headset
                // preference). Retry a few times until the route sticks.
                lifecycleScope.launch {
                    for (delayMs in listOf(2000L, 3000L, 4000L)) {
                        delay(delayMs)
                        if (callAudioRouter.routeCallToPreferredDevice()) break
                    }
                }
            },
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            callStateMonitor.start()
        }

        // Drive the state machine from RCM button events. EXCEPTION: when the
        // handlebar-gamepad is enabled AND Navigator is in the background, the
        // buttons belong to the accessibility gamepad (to control other apps) —
        // so we must NOT also feed them to the media/menu state machine, or media
        // would swallow Up/Down/Set and only Back would appear to work.
        lifecycleScope.launch {
            BccuConnectionService.buttonEvents.collect { button ->
                try {
                    val gamepadOwnsIt = settings.remoteMode == AppSettings.MODE_GAMEPAD &&
                        !AppForegroundState.isForeground &&
                        com.navigator.app.controller.RemoteControlAccessibilityService.isRunning
                    if (gamepadOwnsIt) {
                        AppLogger.log("Controller", "Button=$button -> gamepad mode (accessibility owns it)")
                        return@collect
                    }
                    AppLogger.log("Controller", "Button=$button screen=${stateMachine.screen} callRinging=${stateMachine.isCallRinging}")
                    stateMachine.onButton(button, System.currentTimeMillis(), NotificationRepository.entries.value.size)
                    AppLogger.log("Controller", "-> screen=${stateMachine.screen} gridSelection=${stateMachine.gridSelection}")
                } catch (e: Exception) {
                    AppLogger.log("Controller", "!! Handling button=$button threw: $e")
                }
            }
        }
        // Inactivity watchdog tick.
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                stateMachine.tick(System.currentTimeMillis())
            }
        }
        // On disconnect, drop back to idle and let whatever was showing (e.g. Maps) remain visible.
        lifecycleScope.launch {
            BccuConnectionService.connectionState.collect { state ->
                if (state == BccuConnectionService.ConnectionState.DISCONNECTED) {
                    stateMachine.reset()
                }
            }
        }

        // Notification "Exit" pressed while this activity sits in the recents
        // stack: the service can't finish us, so it broadcasts and we do.
        finishReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: Intent?) {
                if (i?.action == BccuConnectionService.ACTION_IDLE_SHUTDOWN) {
                    AppLogger.log("Idle", "Service idled out with no vehicle - closing the app")
                }
                finishAndRemoveTask()
            }
        }.also {
            // Same handling for both: the notification's Exit action, and the
            // service shutting itself down after 10 minutes with no bike.
            val filter = android.content.IntentFilter(BccuConnectionService.ACTION_FINISH_ACTIVITY)
            filter.addAction(BccuConnectionService.ACTION_IDLE_SHUTDOWN)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(it, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(it, filter)
            }
        }

        importGpxFromIntent(intent)

        setContent {
            NavigatorApp(settings = settings, stateMachine = stateMachine, actions = actions)
        }

        // DEBUG-only: `adb shell am broadcast -a com.navigator.app.EXPORT_MANEUVERS`
        // renders Google Maps' own labeled maneuver icons to files/maneuver_dataset/
        // to build a training set for an independent classifier. Stripped from release.
        if (com.navigator.app.BuildConfig.DEBUG) {
            maneuverExportReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    lifecycleScope.launch {
                        val msg = com.navigator.app.notifications.MapsIconExporter.exportAll(this@MainActivity)
                        AppLogger.log("Dataset", msg)
                    }
                }
            }
            val filter = android.content.IntentFilter("com.navigator.app.EXPORT_MANEUVERS")
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(maneuverExportReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(maneuverExportReceiver, filter)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importGpxFromIntent(intent)
    }

    /**
     * ACTION_VIEW with a GPX: copy it into the routes folder (so it lives in
     * the ride list permanently) and publish it for the UI to open. Sniffs the
     * content because the octet-stream manifest filter matches non-GPX too.
     */
    private fun importGpxFromIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        runCatching {
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            } ?: uri.lastPathSegment ?: "imported_${System.currentTimeMillis()}.gpx"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val head = String(bytes, 0, minOf(bytes.size, 512))
            if (!head.contains("<gpx", ignoreCase = true) && !name.endsWith(".gpx", ignoreCase = true)) {
                AppLogger.log("Route", "Ignoring non-GPX ACTION_VIEW: $name")
                return
            }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .let { if (it.endsWith(".gpx", ignoreCase = true)) it else "$it.gpx" }
            val dest = java.io.File(com.navigator.app.location.RouteRecorder.routesDir(this), safe)
            dest.writeBytes(bytes)
            AppLogger.log("Route", "Imported GPX \"$name\" (${bytes.size / 1024} KB) -> ${dest.name}")
            importedGpx.value = dest
        }.onFailure { AppLogger.log("Route", "!! GPX import failed: $it") }
    }

    private var maneuverExportReceiver: android.content.BroadcastReceiver? = null
    private var finishReceiver: android.content.BroadcastReceiver? = null

    override fun onDestroy() {
        maneuverExportReceiver?.let { runCatching { unregisterReceiver(it) } }
        finishReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AppForegroundState.isForeground = true
        // While visible we're eligible for while-in-use location even without
        // background permission - let the service (re)claim the location FGS
        // type and start any location features that were waiting on it.
        BccuConnectionService.reevaluateLocationIfRunning()
    }

    override fun onPause() {
        super.onPause()
        AppForegroundState.isForeground = false
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        perms += Manifest.permission.READ_PHONE_STATE
        perms += Manifest.permission.ANSWER_PHONE_CALLS
        // GPS: overspeed alerts, waypoints, route recording. FINE alone is
        // silently ignored on Android 12+; COARSE must be in the same request.
        perms += Manifest.permission.ACCESS_FINE_LOCATION
        perms += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

@Composable
private fun NavigatorApp(
    settings: AppSettings,
    stateMachine: ControllerStateMachine,
    actions: ControllerStateMachine.Actions
) {
    var route by remember {
        mutableStateOf(
            when {
                MainActivity.importedGpx.value != null -> AppRoute.RIDES // opened a GPX from another app
                !settings.brandChosen -> AppRoute.BRAND      // first run: pick the bike brand first
                !settings.onboardingComplete -> AppRoute.ONBOARDING
                settings.bondedDeviceAddress == null -> AppRoute.PAIRING
                else -> AppRoute.MAIN
            }
        )
    }
    // Where "Change bike / brand" returns to: Settings when reached from there,
    // null (forward-only to pairing) on first run.
    var brandReturnRoute by remember { mutableStateOf<AppRoute?>(null) }
    // A GPX arriving while we're already running (singleTask onNewIntent):
    // jump to the ride viewer for it.
    val importedGpx by MainActivity.importedGpx.collectAsState()
    LaunchedEffect(importedGpx) {
        if (importedGpx != null && route != AppRoute.ONBOARDING) route = AppRoute.RIDES
    }
    val openRides by MainActivity.openRidesRequest.collectAsState()
    LaunchedEffect(openRides) {
        if (openRides) {
            MainActivity.openRidesRequest.value = false
            if (route == AppRoute.MAIN) route = AppRoute.RIDES
        }
    }
    var logsReturnRoute by remember { mutableStateOf(AppRoute.SETTINGS) }
    // Developer screen is reachable from Settings AND from the home header's
    // DEV pill (dev mode) - back returns to whichever opened it.
    var devReturnRoute by remember { mutableStateOf(AppRoute.SETTINGS) }
    var analyticsFile by remember { mutableStateOf<java.io.File?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Reactively observe the controller state instead of polling it - Compose
    // now recomposes only when screen/selection actually changes.
    val uiState by stateMachine.state.collectAsState()

    // Touch back navigation: sub-pages return to their parent instead of
    // minimizing the app (the physical RCM BACK still works independently).
    androidx.activity.compose.BackHandler(
        enabled = route != AppRoute.PAIRING && route != AppRoute.ONBOARDING &&
            !(route == AppRoute.BRAND && brandReturnRoute == null)
    ) {
        when (route) {
            AppRoute.BRAND -> {
                com.navigator.app.ui.theme.Ktm.applyBrand(settings.brand)
                route = brandReturnRoute ?: AppRoute.MAIN
                brandReturnRoute = null
            }
            AppRoute.SETTINGS -> route = AppRoute.MAIN
            AppRoute.LOGS -> route = logsReturnRoute
            AppRoute.SYMBOL_TEST -> route = AppRoute.SETTINGS
            AppRoute.DEVELOPER -> route = devReturnRoute
            AppRoute.TURN_CALIBRATION -> route = AppRoute.SETTINGS
            AppRoute.VIBRATION_CALIBRATION -> route = AppRoute.SETTINGS
            AppRoute.RIDES -> { MainActivity.importedGpx.value = null; route = AppRoute.MAIN }
            AppRoute.RIDE_ANALYTICS -> route = AppRoute.RIDES
            AppRoute.TRAFFIC_MAP -> route = AppRoute.RIDES
            AppRoute.MAIN -> if (!stateMachine.touchBack()) {
                (context as? ComponentActivity)?.moveTaskToBack(true)
            }
            AppRoute.PAIRING, AppRoute.ONBOARDING -> {}
        }
    }

    // Connect greeting: when the bike authenticates, show a brief "Hi <name>!"
    // banner with the walking-person icon over whatever screen is up.
    val connState by BccuConnectionService.connectionState.collectAsState()
    var showGreeting by remember { mutableStateOf(false) }
    var prevConn by remember { mutableStateOf(connState) }
    LaunchedEffect(connState) {
        if (connState == BccuConnectionService.ConnectionState.AUTHENTICATED &&
            prevConn != BccuConnectionService.ConnectionState.AUTHENTICATED
        ) {
            showGreeting = true
            delay(4000)
            showGreeting = false
        }
        prevConn = connState
    }

    val highContrast = uiState.screen == ControllerScreen.NOTIFICATIONS
    NavigatorTheme(highContrast = highContrast) {
      androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        // Screen-to-screen motion: deeper screens slide in from the right,
        // going back slides the parent in from the left, with a short fade.
        // Offsets are a fraction of the width (not a full push) so both
        // screens are mostly overlapping - less to draw per frame.
        androidx.compose.animation.AnimatedContent(
            targetState = route,
            transitionSpec = { routeTransition(initialState, targetState) },
            label = "route",
        ) { r ->
        when (r) {
            AppRoute.BRAND -> com.navigator.app.ui.screens.BrandSelectScreen(
                onPair = { brand ->
                    settings.brand = brand
                    com.navigator.app.ui.theme.Ktm.applyBrand(brand)
                    // From Settings → just return; on first run → continue setup.
                    route = brandReturnRoute ?: if (!settings.onboardingComplete) AppRoute.ONBOARDING
                        else if (settings.bondedDeviceAddress == null) AppRoute.PAIRING
                        else AppRoute.MAIN
                    brandReturnRoute = null
                },
                onBack = brandReturnRoute?.let { back -> {
                    // Cancelled: revert live preview to the persisted brand.
                    com.navigator.app.ui.theme.Ktm.applyBrand(settings.brand)
                    route = back
                    brandReturnRoute = null
                } },
            )
            AppRoute.ONBOARDING -> com.navigator.app.ui.screens.OnboardingScreen(
                settings = settings,
                onComplete = { route = AppRoute.PAIRING }
            )
            AppRoute.PAIRING -> PairingScreen(
                settings = settings,
                onPaired = { route = AppRoute.MAIN },
                onOpenLogs = { logsReturnRoute = AppRoute.PAIRING; route = AppRoute.LOGS }
            )
            AppRoute.MAIN -> {
                // No on-screen D-pad any more (R76 field request): the bike's own
                // handlebar remote and touch both drive these screens directly.
                when (uiState.screen) {
                    ControllerScreen.NOTIFICATIONS -> NotificationScreen(
                        settings = settings,
                        scrollIndex = uiState.notificationScrollIndex
                    )
                    ControllerScreen.DIRECTION -> DirectionScreen(onOpenMaps = actions::openMaps)
                    ControllerScreen.MUSIC -> com.navigator.app.ui.screens.MusicPlayerScreen(
                        onBack = { stateMachine.touchBack() },
                        onPlayPause = actions::playPauseMedia,
                        onNext = actions::nextTrack,
                        onPrevious = actions::previousTrack,
                    )
                    else -> GridMenuScreen(
                        gridSelection = uiState.gridSelection,
                        onSelectGrid = { index ->
                            stateMachine.touchSelectGrid(index, System.currentTimeMillis())
                        },
                        onOpenSettings = { route = AppRoute.SETTINGS },
                        onOpenRides = { route = AppRoute.RIDES },
                        onOpenDeveloper = { devReturnRoute = AppRoute.MAIN; route = AppRoute.DEVELOPER }
                    )
                }
            }
            AppRoute.SETTINGS -> SettingsScreen(
                settings = settings,
                onBack = { route = AppRoute.MAIN },
                onOpenLogs = { logsReturnRoute = AppRoute.SETTINGS; route = AppRoute.LOGS },
                onOpenSymbolTest = { route = AppRoute.SYMBOL_TEST },
                onOpenDeveloper = { devReturnRoute = AppRoute.SETTINGS; route = AppRoute.DEVELOPER },
                onOpenTurnCalibration = { route = AppRoute.TURN_CALIBRATION },
                onOpenVibrationCalibration = { route = AppRoute.VIBRATION_CALIBRATION },
                onOpenRides = { route = AppRoute.RIDES },
                onChangeBrand = { brandReturnRoute = AppRoute.SETTINGS; route = AppRoute.BRAND },
                onRepair = {
                    // Forget the pairing flag too, not just the address - otherwise
                    // re-pairing the same bike still replies GENERATE_KEYS and the
                    // handshake stalls if the dash has lost its keys. Clearing it
                    // makes the next handshake do a fresh HELLO (dash prompts).
                    settings.bondedDeviceAddress?.let { settings.clearPairedBefore(it) }
                    settings.bondedDeviceAddress = null
                    settings.bondedDeviceName = null
                    BccuConnectionService.stop(context)
                    stateMachine.reset()
                    route = AppRoute.PAIRING
                }
            )
            AppRoute.LOGS -> com.navigator.app.ui.screens.LogsScreen(
                onBack = { route = logsReturnRoute }
            )
            AppRoute.SYMBOL_TEST -> com.navigator.app.ui.screens.SymbolTestScreen(
                onBack = { route = AppRoute.SETTINGS }
            )
            AppRoute.DEVELOPER -> com.navigator.app.ui.screens.DeveloperScreen(
                onBack = { route = devReturnRoute }
            )
            AppRoute.TURN_CALIBRATION -> com.navigator.app.ui.screens.TurnCalibrationScreen(
                onBack = { route = AppRoute.SETTINGS }
            )
            AppRoute.VIBRATION_CALIBRATION -> com.navigator.app.ui.screens.VibrationCalibrationScreen(
                onBack = { route = AppRoute.SETTINGS }
            )
            AppRoute.RIDES -> com.navigator.app.ui.screens.RidesScreen(
                initialFile = importedGpx,
                onBack = {
                    MainActivity.importedGpx.value = null
                    route = AppRoute.MAIN
                },
                onOpenAnalytics = { f ->
                    analyticsFile = f
                    route = AppRoute.RIDE_ANALYTICS
                },
                onOpenHeatmap = { route = AppRoute.TRAFFIC_MAP }
            )
            AppRoute.TRAFFIC_MAP -> com.navigator.app.ui.screens.TrafficHeatmapScreen(
                onBack = { route = AppRoute.RIDES }
            )
            AppRoute.RIDE_ANALYTICS -> analyticsFile?.let { f ->
                com.navigator.app.ui.screens.RideAnalyticsScreen(
                    file = f,
                    onBack = { route = AppRoute.RIDES }
                )
            } ?: run { route = AppRoute.RIDES }
        }
        }
        if (showGreeting) {
            ConnectGreeting(name = settings.userName?.trim().orEmpty())
        }
      }
    }
}

/** Brief "Hi <name>!" banner with the walking-person icon, shown on connect. */
@Composable
private fun ConnectGreeting(name: String) {
    val text = if (name.isNotBlank()) "Hi $name!" else "Welcome!"
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(top = 96.dp),
        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = androidx.compose.ui.Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(com.navigator.app.ui.theme.Ktm.Orange)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(com.navigator.app.R.drawable.dash_pedestrian),
                contentDescription = null,
                tint = com.navigator.app.ui.theme.Ktm.OnAccent,
                modifier = androidx.compose.ui.Modifier.size(34.dp),
            )
            androidx.compose.material3.Text(
                text,
                color = com.navigator.app.ui.theme.Ktm.OnAccent,
                fontFamily = com.navigator.app.ui.theme.BarlowCondensed,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontSize = 26.sp,
                modifier = androidx.compose.ui.Modifier.padding(start = 12.dp),
            )
        }
    }
}
