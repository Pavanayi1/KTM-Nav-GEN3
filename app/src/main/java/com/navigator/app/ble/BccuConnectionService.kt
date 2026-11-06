package com.navigator.app.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.navigator.app.R
import com.navigator.app.logging.AppLogger
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.AppForegroundState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Owns the BLE connection to the bike's BCCU: handshake, session crypto, and
 * all reads/writes. This is the real-app home for the protocol validated in
 * bccu-test-app's BccuBleClient - same handshake, same framing, same RCM
 * decode, carried over unchanged. Runs as a foreground service so it
 * survives while the app is backgrounded (e.g. while Maps is in front).
 */
@SuppressLint("MissingPermission") // permissions are checked before starting this service
class BccuConnectionService : LifecycleService() {

    enum class ConnectionState { DISCONNECTED, CONNECTING, AUTHENTICATED }

    data class NotificationSent(val text: String, val icon: BccuProtocol.NotificationIcon)

    companion object {
        private const val CHANNEL_ID = "bccu_connection"
        private const val NOTIFICATION_ID = 1
        /** Separate, alerting channel: the rider must actually notice these, unlike the ongoing status one. */
        private const val ALERT_CHANNEL_ID = "bccu_alerts"
        private const val ACCESSIBILITY_ALERT_ID = 2
        /** How often we re-check that the handlebar gamepad's accessibility service is still alive. */
        private const val ACCESSIBILITY_CHECK_MS = 20_000L
        private const val NOTIFICATION_CLEAR_DELAY_MS = 5000L
        /** The dash's bottom banner shows at most 16 characters. */
        // The protocol accepts 16 chars but the Gen-3 banner's viewport clips at
        // ~13 (R38 ruler photos: "1234567890123" of a 16-char send) - so 13 is
        // the width that decides static-vs-marquee and sizes each marquee frame.
        private const val NOTIFICATION_BANNER_CHARS = 13
        /** Per-character marquee step; ~3 chars/sec reads comfortably at a glance. */
        private const val MARQUEE_STEP_MS = 320L
        /** How many times a long message scrolls fully before the banner clears. */
        private const val MARQUEE_CYCLES = 2
        /** Separates the end of one marquee pass from the start of the next. */
        private const val MARQUEE_GAP = "   "
        /** Grace period before a removed nav notification blanks the center guidance. */
        private const val GUIDANCE_CLEAR_DELAY_MS = 4000L
        /** How long the "Hi <name>!" connect greeting stays on the center display before clearing. */
        private const val GREETING_MS = 6000L
        /**
         * If a handshake doesn't reach AUTHENTICATED within this long, drop the
         * link and re-scan. Armed at LINK-UP (not on m1): a reused/zombie ACL
         * never re-sends m1, so an m1-armed timer can leave a silent connection
         * unguarded forever (field log 20260709_173524, 19:17:15). A bonded dash
         * finishes the whole handshake in ~3s and kicks unauthenticated links
         * itself at ~15s, so 25s means "dead or dash still booting - recycle".
         * A genuine first pairing includes the rider physically confirming the
         * "add device" prompt on the dash (~30s in field logs), so it gets a
         * much longer budget. We never clear the pairing here (see the
         * cmd=HELLO handler: the dash owns the prompt decision, not us).
         */
        private const val HANDSHAKE_TIMEOUT_KNOWN_MS = 25_000L
        private const val HANDSHAKE_TIMEOUT_FIRST_PAIR_MS = 75_000L
        /**
         * Grace between SEEING the bike advertise and actually connecting. The
         * dash's radio wakes well before its pairing manager after ignition-on;
         * connecting into that gap stalls the handshake and - hammered
         * repeatedly - can push the dash into re-showing its "add device"
         * prompt (R21 field report). Ten seconds of settling lets the dash
         * finish waking before our first attempt.
         */
        private const val CONNECT_SETTLE_MS = 10_000L
        /** Cooldown before rescanning after a stalled handshake: one quickish retry, then hang back. */
        private const val RETRY_COOLDOWN_FIRST_MS = 15_000L
        private const val RETRY_COOLDOWN_LATER_MS = 45_000L
        /** Hold at least this long for a LONG press (from live calibration: taps were <210ms, holds seconds). */
        private const val LONG_PRESS_MS = 400L
        /** After a short release, wait this long for a second click before deciding it was a SINGLE. */
        private const val DOUBLE_CLICK_WINDOW_MS = 350L
        /**
         * Last-resort cap on a push-to-talk hold from the handlebar. The intercom's
         * own 30 s floor watchdog deliberately exempts push-to-talk, so a stuck
         * button would otherwise keep the rider's mic open and the pillion's
         * channel jammed for the rest of the call. Nobody holds a bike button for a
         * full minute to speak.
         */
        private const val BIKE_PTT_MAX_HOLD_MS = 60_000L
        /** How often the reconnect watchdog checks that we're linked (or retries). */
        private const val RECONNECT_WATCHDOG_MS = 20_000L
        /** A foreground (direct) connect attempt stuck this long is recycled and retried. */
        private const val STUCK_CONNECTING_MS = 45_000L
        /**
         * A background (autoConnect=true) attempt stuck this long is recycled once
         * as a rare safety net for the "autoConnect silently never fires" quirk.
         * Kept LONG (10 min): field logs proved autoConnect DOES relink on its own
         * the instant the dash powers back on (ignition-on → reconnected in ~3 s),
         * so recycling every 90 s just churned the radio with failed connects while
         * the bike was simply off (dash BLE powers down with the ignition). Leaving
         * the low-power autoConnect pending is both correct and battery-friendly.
         */
        private const val STUCK_BACKGROUND_MS = 600_000L
        const val EXTRA_DEVICE_ADDRESS = "device_address"

        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val connectionState: StateFlow<ConnectionState> = _connectionState

        private val _buttonEvents = MutableSharedFlow<BccuProtocol.HandlebarButton>(extraBufferCapacity = 8)
        val buttonEvents: SharedFlow<BccuProtocol.HandlebarButton> = _buttonEvents

        /** Recognised handlebar gestures (single/double/long). The accessibility
         * gamepad consumes these in Controller mode. */
        private val _gestureEvents = MutableSharedFlow<com.navigator.app.controller.RemoteGesture>(extraBufferCapacity = 16)
        val gestureEvents: SharedFlow<com.navigator.app.controller.RemoteGesture> = _gestureEvents

        private val _navNotificationText = MutableStateFlow<String?>(null)
        val navNotificationText: StateFlow<String?> = _navNotificationText

        /** Human-readable name of the bike we're connected/connecting to (e.g. "KTM3638"). */
        private val _deviceName = MutableStateFlow<String?>(null)
        val deviceName: StateFlow<String?> = _deviceName

        /** Live signal strength in dBm while authenticated, polled every few seconds; null when unknown. */
        private val _signalRssi = MutableStateFlow<Int?>(null)
        val signalRssi: StateFlow<Int?> = _signalRssi

        /** Latest decoded vehicle telemetry values by datapoint name (e.g. "ECU_Engine_Rpm" -> "4200 rpm"). Empty if the bike doesn't expose the PRPC service. */
        private val _telemetry = MutableStateFlow<Map<String, String>>(emptyMap())
        val telemetry: StateFlow<Map<String, String>> = _telemetry

        /**
         * What kind of dash we're connected to (detected model/generation +
         * capability flags). Null until services are discovered. Lets the UI
         * show the auto-detected model and lets us adapt to non-Gen-3 units
         * instead of silently failing. See [VehicleProfile].
         */
        private val _vehicleProfile = MutableStateFlow<VehicleProfile?>(null)
        val vehicleProfile: StateFlow<VehicleProfile?> = _vehicleProfile

        fun start(context: Context, deviceAddress: String) {
            val intent = Intent(context, BccuConnectionService::class.java)
                .putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BccuConnectionService::class.java))
        }

        // Simple same-process bridge so other components (notification listener,
        // controller state machine) can reach the running service instance
        // without binder/messenger plumbing. Null when the service isn't running.
        private var runningInstance: BccuConnectionService? = null

        /** True while the connection service is alive (its own process component, not just the app). */
        val isRunning: Boolean get() = runningInstance != null

        private var lastEnsureMs = 0L

        /**
         * Bring the service up if it isn't already, from wherever we are.
         *
         * The reason this exists: every dash-bound call below is an
         * `...IfRunning` no-op when the service is dead, and the service CAN be
         * dead while the app process is very much alive - the notification
         * listener is bound by the system independently, so turn beeps keep
         * playing (they're local audio) while nothing at all reaches the bike.
         * That's the exact "I hear beeps but the dash is blank until I open the
         * app" symptom: opening MainActivity was the only thing that restarted
         * the service.
         *
         * Starting a foreground service from the background is restricted on
         * Android 12+; we qualify via the battery-optimization exemption and
         * SYSTEM_ALERT_WINDOW, but a refusal must never take down the caller
         * (the notification listener especially), so it's caught and logged.
         * Throttled so a burst of nav notifications can't retry-storm.
         */
        fun ensureRunning(context: Context) {
            if (runningInstance != null) return
            // Throttle FIRST so the bail reasons below are logged at most once
            // per window - guidance calls this about once a second.
            val now = System.currentTimeMillis()
            if (now - lastEnsureMs < ENSURE_THROTTLE_MS) return
            lastEnsureMs = now
            val settings = AppSettings(context)
            // Every one of these used to be a silent return, which made the
            // commonest field report - "the beeps play but the dash stays
            // blank" - impossible to diagnose from a log: the listener fires,
            // nothing reaches the bike, and nothing says why.
            if (settings.userQuit) {
                AppLogger.log("BLE", "ensureRunning: SKIPPED - the rider pressed Stop. Open the app, or switch the ignition on, to clear it.")
                return
            }
            val address = settings.bondedDeviceAddress
            if (address == null) {
                AppLogger.log("BLE", "ensureRunning: SKIPPED - no bonded bike saved (pair one first)")
                return
            }
            AppLogger.log("BLE", "ensureRunning: service is down - restarting it for $address")
            try {
                start(context, address)
            } catch (e: Exception) {
                AppLogger.log("BLE", "!! ensureRunning: background start refused: $e")
            }
        }

        private const val ENSURE_THROTTLE_MS = 20_000L

        /** Idempotent: the alert channel must exist before anything posts to it, service up or not. */
        private fun ensureAlertChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID, "Alerts", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Something the rider needs to fix, like the handlebar remote going dead."
                }
            )
        }

        /**
         * Both channels at once, because the rider's attention is on the road:
         * the dash banner is what they'll actually see from the saddle, the phone
         * notification is what they can act on when stopped (it opens Android's
         * accessibility settings directly). Companion so the Developer screen can
         * preview the exact production notification without waiting for a real
         * accessibility failure.
         */
        fun postAccessibilityAlertNotification(context: Context, enabledInSettings: Boolean) {
            ensureAlertChannel(context)
            val tap = PendingIntent.getActivity(
                context, 2,
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val detail = if (enabledInSettings) {
                "Android hasn't restarted Navigator's accessibility service. Tap to re-enable it."
            } else {
                "Android switched Navigator's accessibility service off. Tap to turn it back on."
            }
            val n = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setContentTitle("Handlebar remote isn't working")
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build()
            runCatching {
                context.getSystemService(NotificationManager::class.java).notify(ACCESSIBILITY_ALERT_ID, n)
            }
        }

        /** Dev-mode preview: the real alert on the phone, plus the dash banner when connected. */
        fun previewAccessibilityAlert(context: Context) {
            postAccessibilityAlertNotification(context, enabledInSettings = false)
            sendNotificationIfRunning("REMOTE OFF", BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT)
        }

        fun sendNotificationIfRunning(text: String, icon: BccuProtocol.NotificationIcon) {
            runningInstance?.sendNotification(text, icon)
        }

        /**
         * How long the service will sit unconnected before shutting itself
         * down. The app is started by the bike's Bluetooth connecting and by
         * boot, so without this it can end up running all day in a pocket on a
         * day the rider never rides.
         */
        const val NO_VEHICLE_TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * Guidance seen more recently than this counts as "actively navigating"
         * and holds the idle shutdown off. Google Maps repeats its notification
         * every few seconds while a route is running, so a couple of minutes is
         * generous even through a tunnel or a long straight.
         */
        private const val NAV_ACTIVE_WINDOW_MS = 2 * 60 * 1000L

        /** Broadcast the Activity listens for, so the UI closes with the service. */
        const val ACTION_IDLE_SHUTDOWN = "com.navigator.app.IDLE_SHUTDOWN"

        /**
         * Open the remote's mode picker from the UI (the home grid's Remote
         * tile), the same overlay a long-press of UP raises on the bars.
         */
        fun showModeOverlayIfRunning() {
            runningInstance?.showModeOverlay()
        }

        /** Which mode the handlebar buttons are in right now, for the Remote tile's sub-status. */
        fun remoteModeLabel(context: Context): String =
            when (AppSettings(context).remoteMode) {
                AppSettings.MODE_CONTROLLER -> "Controller"
                AppSettings.MODE_MUSIC -> "Music"
                AppSettings.MODE_INTERCOM -> "Intercom"
                else -> "Bike (off)"
            }

        /** Low-priority live status (overspeed readout): yields to any message currently on the banner. */
        fun sendSpeedBannerIfRunning(text: String) {
            runningInstance?.sendSpeedBanner(text)
        }

        /** Rides screen Record button. Null = service not running; false = no location permission. */
        fun startManualRecordingIfRunning(): Boolean? = runningInstance?.routeRecorder?.startManual()

        /** Rides screen Stop button. */
        fun stopRecordingIfRunning() {
            runningInstance?.routeRecorder?.stopManual()
        }

        /** Re-check the route-recording gate (setting toggled from the UI). */
        fun reevaluateRouteRecordingIfRunning() {
            runningInstance?.routeRecorder?.reevaluate()
        }

        /** Re-check location permission-dependent features (permission granted / app foregrounded). */
        fun reevaluateLocationIfRunning() {
            runningInstance?.reevaluateLocationFeatures()
        }

        /** Engine-vibration detection was toggled or recalibrated: restart it with fresh thresholds. */
        fun reevaluateEngineDetectIfRunning() {
            runningInstance?.vibrationMonitor?.reevaluate()
        }

        /** Notification action: fully quit - stop the service, drop notifications, close the app task. */
        const val ACTION_EXIT = "com.navigator.app.action.EXIT"
        /** Broadcast the running MainActivity listens for to finish its task on notification-exit. */
        const val ACTION_FINISH_ACTIVITY = "com.navigator.app.action.FINISH_ACTIVITY"

        fun sendTurnIconIfRunning(icon: BccuProtocol.TurnIcon) {
            runningInstance?.sendTurnIcon(icon)
        }

        /** Updates the dash's center guidance view (distance/road/ETA/remaining distance), not the bottom notification banner. */
        fun sendGuidanceIfRunning(distanceText: String?, roadText: String?, etaText: String? = null, remainingDistanceText: String? = null) {
            runningInstance?.sendGuidance(distanceText, roadText, etaText, remainingDistanceText)
        }

        /**
         * Clear the dash's center guidance view when navigation ends (the nav
         * app's notification was removed). Debounced inside the service so a
         * transient remove+repost (which Google Maps does routinely while
         * navigating) doesn't blank the display for a frame.
         */
        fun clearGuidanceIfRunning() {
            runningInstance?.scheduleGuidanceClear()
        }

        /** Developer/idle-screen access to every center text slot (null = leave untouched, "" = blank). */
        fun sendDashTextIfRunning(
            distance: String? = null,
            info: String? = null,
            road: String? = null,
            eta: String? = null,
            remaining: String? = null,
        ) {
            runningInstance?.sendDashText(distance, info, road, eta, remaining)
        }

        /** Immediately blank the whole dash (center slots + bottom banner) - no debounce. */
        fun clearAllDashTextIfRunning() {
            runningInstance?.clearAllDashText()
        }
    }

    private var gatt: BluetoothGatt? = null
    private var currentDeviceAddress: String? = null
    private var tempIv: ByteArray? = null
    private var tempSecret: ByteArray? = null
    private var sessionKeys: List<ByteArray>? = null
    private var activeSessionKey: ByteArray? = null
    private var lastRcmMask: Int = 0
    /** Calibration timing: when the RCM mask last changed, and when each button was last pressed. */
    private var lastRcmChangeMs: Long = 0
    private val rcmPressAtMs = java.util.EnumMap<BccuProtocol.HandlebarButton, Long>(BccuProtocol.HandlebarButton::class.java)
    private var clearJob: Job? = null
    private var marqueeJob: Job? = null
    /**
     * Scrollers for the dash's CENTER text slots (the bottom banner has its own
     * in [marqueeJob]). Key = slot UUID, value = the text that slot is
     * currently scrolling, plus its job.
     *
     * Keeping the text is the whole trick: Maps reposts its guidance roughly
     * once a second, and restarting the scroll on each repost would leave every
     * long label frozen on its first frame forever. Same text = leave the
     * running scroll alone.
     */
    private val slotScrolls = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Pair<String, Job>>()
    /** Edge-trigger so the arrival banner fires once per route, not on every repost. */
    @Volatile private var arrivalAnnounced = false
    private var guidanceClearJob: Job? = null
    private var lastNotificationIcon: BccuProtocol.NotificationIcon = BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT
    private var rssiJob: Job? = null
    private var reconnectWatchdogJob: Job? = null
    private var handshakeTimeoutJob: Job? = null
    private var handshakeRecoveries: Int = 0
    /** Pending scan-found -> connect delay (dash boot grace); active means "we will connect shortly". */
    private var settleJob: Job? = null
    private var scanCallback: android.bluetooth.le.ScanCallback? = null
    @Volatile private var scanning: Boolean = false
    @Volatile private var scanTargetAddress: String? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var modeOverlay: com.navigator.app.controller.RemoteModeOverlay? = null

    private var prpcAvailable = false
    private var telemetrySchema: List<Datapoint>? = null
    private var telemetrySid: Int = 0
    private fun nextSid(): Int { telemetrySid = (telemetrySid + 1) and 0xFF; return telemetrySid }

    // Guarded by gattQueueLock: ops are enqueued/finished on BLE binder threads
    // while teardown paths (adapter-off receiver, watchdog, handshake recovery)
    // clear the queue from the main thread - ArrayDeque is not thread-safe.
    // coalesceKey: non-null marks a "latest value wins" write (guidance labels,
    // marquee frames, banner) - enqueueing drops any still-pending write with
    // the same key, so a degraded link can never build an unbounded backlog of
    // stale frames. Control-plane ops (auth, CCCD, PRPC requests) keep null.
    private class GattOp(val coalesceKey: java.util.UUID?, val run: () -> Unit)
    private val gattQueueLock = Any()
    private val gattQueue = ArrayDeque<GattOp>()
    private var gattBusy = false

    private var connectingSinceMs: Long = 0L
    private var pendingAutoConnect: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithTypes("Connecting...")
        runningInstance = this
        startReconnectWatchdog()
        registerAdapterStateReceiver()
        speedMonitor = com.navigator.app.location.SpeedMonitor(this).also { it.start() }
        routeRecorder = com.navigator.app.location.RouteRecorder(this).also { it.start() }
        vibrationMonitor = com.navigator.app.sensors.VibrationMonitor(this).also { it.start() }
        // Show the current track on the dash banner (scroll once) whenever it
        // changes and we're connected. Uses the media-session metadata directly.
        // Idle info rotation (clock/music/weather on the dash while not
        // navigating). Created BEFORE the track monitor: start() below emits the
        // current track synchronously and the idle music card must not miss it.
        idleDashboard = com.navigator.app.dash.IdleDashboard(this, lifecycleScope).also { it.start() }
        mediaTrackMonitor = com.navigator.app.audio.MediaTrackMonitor(this) { track ->
            com.navigator.app.notifications.NotificationRepository.updateTrack(track)
            if (_connectionState.value == ConnectionState.AUTHENTICATED) sendTrackBanner(track)
        }.also { it.start() }
        // Keep the live movement signals flowing to their consumers continuously,
        // not just when a guidance update happens to arrive: the beeper's
        // stationary duck and the GPX engine trace both read these.
        lifecycleScope.launch {
            com.navigator.app.location.SpeedMonitor.speedKmh.collect { kmh ->
                vibrationMonitor?.currentSpeedKmh = kmh
                com.navigator.app.audio.TurnBeeper.gpsSpeedKmh = kmh
            }
        }
        lifecycleScope.launch {
            com.navigator.app.sensors.VibrationMonitor.engineOn.collect { on ->
                com.navigator.app.audio.TurnBeeper.engineOn = on
                routeRecorder?.onEngineState(on)
            }
        }
        // Route recording is gated on the bike actually being connected (not
        // just "phone is charging") - re-check the moment that changes so a
        // ride starts right at authentication and stops right at disconnect.
        lifecycleScope.launch {
            connectionState.collect { routeRecorder?.reevaluate() }
        }
        startNoVehicleWatchdog()
        // A hold that loses the dash link never sees its release edge. Watch the
        // state itself rather than the four places that set DISCONNECTED, so a
        // fifth one added later is covered too.
        lifecycleScope.launch {
            _connectionState.collect { state ->
                if (state != ConnectionState.AUTHENTICATED) {
                    pttConsumedPress = false
                    releaseBikePtt("dash link ${state.name.lowercase()}")
                }
            }
        }
        startAccessibilityWatchdog()
        // Debug-only: `adb shell am broadcast -a com.navigator.app.DEBUG_MODE_OVERLAY`
        // shows the handlebar mode picker without a bike, to verify it renders.
        if (com.navigator.app.BuildConfig.DEBUG) {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { showModeOverlay() }
            }
            val f = android.content.IntentFilter("com.navigator.app.DEBUG_MODE_OVERLAY")
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(r, f)
        }
    }

    private var adapterStateReceiver: android.content.BroadcastReceiver? = null
    private var speedMonitor: com.navigator.app.location.SpeedMonitor? = null
    private var routeRecorder: com.navigator.app.location.RouteRecorder? = null
    var vibrationMonitor: com.navigator.app.sensors.VibrationMonitor? = null
        private set
    private var mediaTrackMonitor: com.navigator.app.audio.MediaTrackMonitor? = null
    private var idleDashboard: com.navigator.app.dash.IdleDashboard? = null

    /**
     * Explicit FGS types (API 34 enforces them): connectedDevice always;
     * location only when the permission is actually granted AND we're eligible
     * to use it (app visible, or "allow all the time" granted) - defaulting to
     * the manifest types would throw SecurityException on a fresh install where
     * the boot/ACL receiver starts us before the rider grants location.
     * Safe to call again later to UPGRADE the type once permissions arrive.
     */
    private fun startForegroundWithTypes(status: String) {
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var t = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val background = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (fine && (background || AppForegroundState.isForeground)) {
                t = t or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            t
        } else 0
        androidx.core.app.ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildForegroundNotification(status), fgsType
        )
    }

    /**
     * Re-check location-dependent features: called when the app comes to the
     * foreground or a permission was just granted. Location permission is often
     * granted AFTER this long-lived service started (the permission dialog is
     * async), which used to leave the speed monitor dead and the FGS without
     * the location type until the next reboot.
     */
    private fun reevaluateLocationFeatures() {
        runCatching { startForegroundWithTypes(statusText) }
        speedMonitor?.start()
        routeRecorder?.reevaluate()
    }

    private var statusText: String = "Connecting..."

    /**
     * Android does NOT reliably deliver onConnectionStateChange when the LOCAL
     * adapter is switched off (unlike an out-of-range drop) - field log
     * navigator_log_20260709_004422 showed the app stuck on "Connected" forever
     * after the rider toggled Bluetooth off. Observe the adapter state directly:
     * on OFF force-disconnect and show it; on ON resume the scan-to-reconnect
     * path immediately (the manifest AutoConnectReceiver also covers the case
     * where this service isn't running).
     */
    private fun registerAdapterStateReceiver() {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        AppLogger.log("BLE", "Bluetooth adapter turned OFF - forcing disconnect state")
                        stopBleScan()
                        settleJob?.cancel(); settleJob = null
                        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { /* stack is going down anyway */ }
                        gatt = null
                        rssiJob?.cancel(); rssiJob = null
                        handshakeTimeoutJob?.cancel(); handshakeTimeoutJob = null
                        tempIv = null; tempSecret = null; sessionKeys = null; activeSessionKey = null
                        clearGattQueue()
                        _signalRssi.value = null
                        _connectionState.value = ConnectionState.DISCONNECTED
                        updateForegroundNotification("Bluetooth is off")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        AppLogger.log("BLE", "Bluetooth adapter turned ON - resuming reconnect scan")
                        updateForegroundNotification("Reconnecting…")
                        val addr = AppSettings(this@BccuConnectionService).bondedDeviceAddress
                        if (addr != null && gatt == null) startBleScan(addr)
                    }
                }
            }
        }
        registerReceiver(r, android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        adapterStateReceiver = r
    }

    /**
     * Belt-and-suspenders auto-connect. `connectGatt(autoConnect=true)` usually
     * relinks on its own, but it can silently never fire (a known Android BLE
     * quirk), and the initial connect is deferred when Bluetooth is off. This
     * loop re-issues a connect when we're not linked and no GATT client is in
     * flight, and force-recycles a connection that's been stuck "connecting"
     * far too long.
     */
    private fun startReconnectWatchdog() {
        reconnectWatchdogJob?.cancel()
        reconnectWatchdogJob = lifecycleScope.launch {
            while (true) {
                delay(RECONNECT_WATCHDOG_MS)
                try {
                    val address = AppSettings(this@BccuConnectionService).bondedDeviceAddress ?: continue
                    if (_connectionState.value == ConnectionState.AUTHENTICATED) continue
                    val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    if (adapter == null || !adapter.isEnabled) continue
                    if (gatt == null) {
                        // Not linked and nothing in flight: make sure we're scanning
                        // for the bike so we grab it the moment it advertises. A
                        // pending settle delay or retry cooldown counts as in
                        // flight - restarting the scan would cut the pacing short.
                        if (!scanning && settleJob?.isActive != true && handshakeTimeoutJob?.isActive != true) {
                            AppLogger.log("BLE", "Watchdog: not connected - (re)starting scan")
                            startBleScan(address)
                        }
                    } else if (_connectionState.value == ConnectionState.CONNECTING &&
                        System.currentTimeMillis() - connectingSinceMs > STUCK_CONNECTING_MS
                    ) {
                        if (tempIv != null && !AppSettings(this@BccuConnectionService).hasPairedBefore(address)) {
                            // A FIRST-pair handshake is genuinely in progress (m1
                            // arrived) - the rider may be confirming the "add
                            // device" prompt on the dash, which takes >45s. The
                            // long first-pair handshake timeout owns that recovery;
                            // recycling here would tear the link mid-confirm. A
                            // KNOWN bike never legitimately waits this long (its
                            // 25s handshake timer fires first), so it falls through.
                            continue
                        }
                        // A direct connect that's been hung too long is dead - recycle
                        // the GATT and fall back to scanning (which reconnects when the
                        // dash is actually reachable, avoiding the status-255 churn).
                        // Clear the op queue and any leftover crypto too: a lost
                        // in-flight op would otherwise leave gattBusy stuck true and
                        // stall every op on the NEXT connection forever.
                        AppLogger.log("BLE", "Watchdog: connect stuck >${STUCK_CONNECTING_MS}ms - recycling + scanning")
                        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { /* ignore */ }
                        gatt = null
                        clearGattQueue()
                        handshakeTimeoutJob?.cancel(); handshakeTimeoutJob = null
                        tempIv = null; tempSecret = null; sessionKeys = null; activeSessionKey = null
                        startBleScan(address)
                    }
                } catch (e: Exception) {
                    AppLogger.log("BLE", "!! Watchdog iteration threw: $e")
                }
            }
        }
    }

    /**
     * Actively scan for the bonded bike and connect the instant it advertises.
     * More reliable than connectGatt(autoConnect=true), which was observed to
     * silently never relink on real phones. Matches the target by MAC (the same
     * device the phone is already bonded/paired with for media), so it also
     * covers "the bike is already saved on my phone". Low-power scan; stops as
     * soon as the device is found or we authenticate.
     */
    @SuppressLint("MissingPermission")
    private fun startBleScan(address: String) {
        if (scanning) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            AppLogger.log("BLE", "Scan: adapter/scanner unavailable, deferring")
            return
        }
        val target = address.uppercase()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val dev = result.device ?: return
                if (dev.address?.uppercase() != target) return
                AppLogger.log("BLE", "Scan: found bike ${dev.address} (rssi=${result.rssi}) - settling ${CONNECT_SETTLE_MS / 1000}s before connect (dash boot grace)")
                stopBleScan()
                if (gatt != null || settleJob?.isActive == true) return
                settleJob = lifecycleScope.launch {
                    delay(CONNECT_SETTLE_MS)
                    if (gatt == null) connect(target, autoConnect = false)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                AppLogger.log("BLE", "!! Scan failed: $errorCode")
                scanning = false
            }
        }
        val filters = listOf(
            android.bluetooth.le.ScanFilter.Builder().setDeviceAddress(address).build()
        )
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(filters, settings, cb)
            scanCallback = cb
            scanTargetAddress = target
            scanning = true
            _connectionState.value = ConnectionState.CONNECTING
            AppLogger.log("BLE", "Scanning for bike $address to reconnect")
        } catch (e: Exception) {
            AppLogger.log("BLE", "!! startScan threw: $e")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!scanning) return
        scanning = false
        scanTargetAddress = null
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        } catch (e: Exception) { /* ignore */ }
        scanCallback = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_EXIT) {
            AppLogger.log("BLE", "Exit requested from the notification - shutting down")
            AppSettings(this).userQuit = true
            // Ask the (possibly backgrounded) activity to remove its task, drop
            // every notification we own, and stop - onDestroy tears the rest down.
            sendBroadcast(Intent(ACTION_FINISH_ACTIVITY).setPackage(packageName))
            runCatching { getSystemService(NotificationManager::class.java).cancelAll() }
            androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: AppSettings(this).bondedDeviceAddress
        if (address != null) {
            connect(address)
        }
        return START_STICKY
    }

    /**
     * @param autoConnect false for the initial (fast, foreground) attempt;
     * true for reconnects, so Android holds a background connection request and
     * links up on its own the moment the bike is back in range - far more
     * reliable and battery-friendly than us polling connectGatt() on a timer.
     */
    private fun connect(address: String, autoConnect: Boolean = false) {
        if (gatt != null) return
        // Already scanning for this same bike (e.g. the in-service adapter-ON
        // handler races AutoConnectReceiver's onStartCommand): let the scan win.
        // A blind direct connect while the bike is off just churns status-255
        // failures - the scan-first design exists precisely to avoid that.
        if (scanning && address.equals(scanTargetAddress, ignoreCase = true)) {
            AppLogger.log("BLE", "connect($address) skipped - scan already hunting for it")
            return
        }
        if (scanning) stopBleScan()
        AppLogger.log("BLE", "Connecting to $address (autoConnect=$autoConnect)")
        currentDeviceAddress = address
        _connectionState.value = ConnectionState.CONNECTING
        connectingSinceMs = System.currentTimeMillis()
        pendingAutoConnect = autoConnect
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            // Bluetooth is off; stay CONNECTING and let the AutoConnectReceiver's
            // adapter-on broadcast (or the next onStartCommand) retry.
            AppLogger.log("BLE", "Bluetooth adapter unavailable/off, deferring connect")
            return
        }
        val device = adapter.getRemoteDevice(address)
        _deviceName.value = AppSettings(this).bondedDeviceName ?: device.name
        gatt = device.connectGatt(this, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun onDestroy() {
        AppLogger.log("BLE", "Service destroyed")
        releaseBikePtt("service destroyed")
        adapterStateReceiver?.let { runCatching { unregisterReceiver(it) } }
        adapterStateReceiver = null
        speedMonitor?.stop(); speedMonitor = null
        routeRecorder?.stop(); routeRecorder = null
        vibrationMonitor?.destroy(); vibrationMonitor = null
        mediaTrackMonitor?.stop(); mediaTrackMonitor = null
        stopBleScan()
        settleJob?.cancel(); settleJob = null
        mainHandler.post { modeOverlay?.hide(); modeOverlay = null }
        reconnectWatchdogJob?.cancel(); reconnectWatchdogJob = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
        if (runningInstance === this) runningInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // --- Foreground notification plumbing ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "Bike connection", NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        ensureAlertChannel(this)
    }

    private fun buildForegroundNotification(status: String): Notification {
        val pendingIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        // A real way out from the notification: stops this service (removing
        // the ongoing notification) and closes the app task - the R20 field
        // request was an exit that actually exits, not just opens the app.
        val exitIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BccuConnectionService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val bike = _deviceName.value ?: AppSettings(this).bondedDeviceName ?: "your bike"
        val (title, text) = when {
            status == "Connected" -> "Connected to $bike" to "Directions and alerts are going to your dash"
            status == "Bluetooth is off" -> "Bluetooth is off" to "Turn Bluetooth on to connect to $bike"
            status.startsWith("Connecting") || status.startsWith("Reconnecting") ->
                "Looking for $bike" to (searchHint ?: "Switch the ignition on to connect. Tap Stop to quit.")
            else -> "Navigator" to status
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", exitIntent)
            .build()
    }

    /**
     * Second line of the "Looking for your bike" notification: why we're still
     * searching, or how long until we give up. Set by the no-vehicle watchdog.
     */
    private var searchHint: String? = null

    private fun setSearchHint(hint: String) {
        if (hint == searchHint) return
        searchHint = hint
        if (_connectionState.value != ConnectionState.AUTHENTICATED &&
            (statusText.startsWith("Connecting") || statusText.startsWith("Reconnecting"))
        ) {
            runCatching { updateForegroundNotification(statusText) }
        }
    }

    private fun updateForegroundNotification(status: String) {
        statusText = status
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(status))
    }

    // --- Serial GATT operation queue (Android allows only one outstanding op at a time) ---

    private fun enqueueGattOp(coalesceKey: java.util.UUID? = null, op: () -> Unit) {
        synchronized(gattQueueLock) {
            if (coalesceKey != null) {
                gattQueue.removeAll { it.coalesceKey == coalesceKey }
            }
            gattQueue.addLast(GattOp(coalesceKey, op))
        }
        runNextGattOp()
    }

    private fun runNextGattOp() {
        val next = synchronized(gattQueueLock) {
            if (gattBusy) return
            val op = gattQueue.pollFirst() ?: return
            gattBusy = true
            op
        }
        next.run()
    }

    private fun gattOpFinished() {
        synchronized(gattQueueLock) { gattBusy = false }
        runNextGattOp()
    }

    private fun clearGattQueue() {
        synchronized(gattQueueLock) {
            gattQueue.clear()
            gattBusy = false
        }
    }

    /**
     * Every override here runs try/catch-wrapped. BLE stack callbacks are a
     * classic place for a rare/edge-case exception (stale GATT reference,
     * unexpected characteristic state after a fast disconnect/reconnect) to
     * otherwise kill the whole app process - we'd rather log it and keep
     * running than crash the foreground service mid-ride.
     */
    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            try {
                AppLogger.log("BLE", "onConnectionStateChange status=$status newState=$newState")
                // A late callback from a client we already recycled (adapter-off
                // teardown, stuck-connect recycle, handshake recovery) must not
                // clobber the CURRENT connection's state - just release it.
                if (g !== gatt) {
                    AppLogger.log("BLE", "Ignoring state change from a stale GATT client")
                    try { g.close() } catch (e: Exception) { /* already gone */ }
                    return
                }
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    // Arm the handshake watchdog NOW, not when m1 arrives: a link
                    // where the BCCU never restarts auth (reused ACL) would
                    // otherwise sit silent with no timer guarding it at all.
                    startHandshakeTimeout()
                    g.discoverServices()
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    // An AUTHENTICATED session ending (ignition off, out of
                    // range) closes this boot cycle - the next one deserves a
                    // fresh retry budget, not leftovers from the last stall.
                    if (_connectionState.value == ConnectionState.AUTHENTICATED) handshakeRecoveries = 0
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _signalRssi.value = null
                    rssiJob?.cancel(); rssiJob = null
                    handshakeTimeoutJob?.cancel(); handshakeTimeoutJob = null
                    tempIv = null; tempSecret = null; sessionKeys = null; activeSessionKey = null
                    clearGattQueue()
                    // Release the old GATT client fully before requesting a new
                    // one - reusing a disconnected BluetoothGatt is a known source
                    // of silent reconnect failures (status 133).
                    try { g.close() } catch (e: Exception) { /* already gone */ }
                    gatt = null
                    updateForegroundNotification("Reconnecting…")
                    // Reconnect by actively SCANNING for the bike and connecting the
                    // moment it advertises again. Field logs showed connectGatt(
                    // autoConnect=true) silently never relinks on some phones, and a
                    // blind direct connect to the address fails with status 255 while
                    // the bike is off - even though classic/media BT reconnects fine.
                    // Scanning is the reliable signal that the dash is actually back.
                    lifecycleScope.launch {
                        delay(1500)
                        val addr = AppSettings(this@BccuConnectionService).bondedDeviceAddress
                        if (addr != null && gatt == null) startBleScan(addr)
                    }
                }
            } catch (e: Exception) {
                AppLogger.log("BLE", "!! onConnectionStateChange threw: $e")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            try {
                AppLogger.log("BLE", "onServicesDiscovered status=$status, ${g.services.size} services")
                dumpGattTable(g)

                // Auto-detect which dash this is (model + capability set) before
                // we assume the Gen-3 layout. Built from the live GATT table so
                // it's correct for whatever bike is actually on the other end.
                val profile = VehicleProfile.fromGatt(g, _deviceName.value)
                _vehicleProfile.value = profile
                prpcAvailable = profile.hasModernPrpc
                AppLogger.log("BLE", "Detected dash: ${profile.describe()}")

                val mainService = g.getService(BccuProtocol.MAIN_SERVICE)
                if (mainService == null) {
                    // Not the Gen-3 legacy-ETBT layout we drive. Rather than the
                    // old silent bail (rider saw "handshaking" forever with no
                    // clue why), say so plainly AND capture the full service table
                    // so an unsupported bike (field reports: 1290/890) can be
                    // diagnosed from the exported log. We keep the link so the
                    // dump is complete, but there's no legacy auth path here.
                    AppLogger.log("BLE", "!! MAIN_SERVICE (0700) absent - this dash isn't a legacy-ETBT BCCU unit")
                    AppLogger.log("BLE", "   Capabilities: ${profile.describe()}")
                    logUnsupportedGattDump(g)
                    updateForegroundNotification("Unsupported dash — see Logs")
                    return
                }
                g.requestMtu(517)
            } catch (e: Exception) {
                AppLogger.log("BLE", "!! onServicesDiscovered threw: $e")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            try {
                AppLogger.log("BLE", "onMtuChanged mtu=$mtu status=$status")
                if (g.getService(BccuProtocol.MAIN_SERVICE) == null) {
                    // MTU event raced ahead of service discovery (seen on a reused
                    // ACL, field log 19:17:15). onServicesDiscovered's requestMtu
                    // lands us here again once the services actually exist.
                    AppLogger.log("BLE", "MTU changed before services discovered - deferring CCCD setup")
                    return
                }
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                enableIndication(BccuProtocol.AUTH_REQUEST)
            } catch (e: Exception) {
                AppLogger.log("BLE", "!! onMtuChanged threw: $e")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            try {
                val statusName = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "FAILED($status)"
                AppLogger.log("BLE", "onDescriptorWrite ${d.characteristic?.uuid} -> $statusName")
                gattOpFinished()
            } catch (e: Exception) {
                AppLogger.log("BLE", "!! onDescriptorWrite threw: $e")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            try {
                val statusName = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "FAILED($status)"
                AppLogger.log("BLE", "onCharacteristicWrite ${c.uuid} -> $statusName")
                gattOpFinished()
            } catch (e: Exception) {
                AppLogger.log("BLE", "!! onCharacteristicWrite threw: $e")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            try {
                handleIncoming(c.uuid, c.value ?: ByteArray(0))
            } catch (e: Exception) {
                AppLogger.log("BLE", "!! onCharacteristicChanged (legacy) threw: $e")
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            try {
                handleIncoming(c.uuid, value)
            } catch (e: Exception) {
                AppLogger.log("BLE", "!! onCharacteristicChanged threw: $e")
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) _signalRssi.value = rssi
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            try {
                val data = c.value ?: ByteArray(0)
                if (status == BluetoothGatt.GATT_SUCCESS) captureDeviceInfo(c.uuid, data)
                logProbeValue(readLabel(c, status), data)
            } catch (e: Exception) {
                AppLogger.log("Probe", "!! onCharacteristicRead threw: $e")
            } finally {
                gattOpFinished()
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            try {
                if (status == BluetoothGatt.GATT_SUCCESS) captureDeviceInfo(c.uuid, value)
                logProbeValue(readLabel(c, status), value)
            } catch (e: Exception) {
                AppLogger.log("Probe", "!! onCharacteristicRead threw: $e")
            } finally {
                gattOpFinished()
            }
        }
    }

    /** Poll RSSI every 3s while the session is up so the Grid banner can show live signal strength. */
    private fun startRssiPolling() {
        rssiJob?.cancel()
        rssiJob = lifecycleScope.launch {
            while (true) {
                gatt?.readRemoteRssi()
                delay(3000)
            }
        }
    }

    private fun handleIncoming(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            BccuProtocol.AUTH_REQUEST -> handleAuthMessage(value)
            BccuProtocol.RCM_REMOTE_CONTROL -> handleRcm(value)
            BccuProtocol.TBT_NAV_REQUEST -> handleNavRequest(value)
            BccuProtocol.PRPC_NOTIFICATION -> handlePrpcNotification(value)
            BccuProtocol.PRPC_RESPONSE -> handlePrpcResponse(value)
            BccuProtocol.BASE_VIN -> {
                // VIN pushed on 0002 after we wrote the GET_VIN_REQUEST. Decrypt
                // on the data plane (framed) and log both raw and decoded.
                logProbeValue("NOTIFY VIN 0002", value)
                val key = activeSessionKey; val iv = tempIv
                if (key != null && iv != null) {
                    runCatching { BccuCrypto.decryptData(value, key, iv) }.onSuccess {
                        AppLogger.log("Probe", "  VIN decoded: \"${printableAscii(it)}\"")
                    }
                }
            }
            else -> {
                // Undocumented services 0200/0300 - log whatever they push (raw
                // plus decrypt attempts) so we can reverse-engineer them.
                val s = uuid.toString()
                if (s.startsWith("71ced1ac-020") || s.startsWith("71ced1ac-030")) {
                    logProbeValue("NOTIFY $uuid", value)
                }
            }
        }
    }

    private fun readLabel(c: BluetoothGattCharacteristic, status: Int): String {
        val ok = if (status == BluetoothGatt.GATT_SUCCESS) "" else " (status=$status)"
        return "READ ${c.service?.uuid}/${c.uuid}$ok"
    }

    /**
     * If a just-read characteristic belongs to the standard Device Information
     * service (model number / firmware / hardware / manufacturer), fold its
     * UTF-8 string into the live [vehicleProfile] so the detected model refines
     * from "BCCU dash" to the real model string as the reads complete.
     */
    private fun captureDeviceInfo(uuid: java.util.UUID, value: ByteArray) {
        val known = uuid == VehicleProfile.MODEL_NUMBER ||
            uuid == VehicleProfile.FIRMWARE_REVISION ||
            uuid == VehicleProfile.SOFTWARE_REVISION ||
            uuid == VehicleProfile.HARDWARE_REVISION ||
            uuid == VehicleProfile.MANUFACTURER_NAME
        if (!known) return
        val text = value.toString(Charsets.UTF_8).trim().trim(' ')
        if (text.isEmpty()) return
        val current = _vehicleProfile.value ?: VehicleProfile()
        _vehicleProfile.value = current.withDeviceInfo(uuid, text)
        AppLogger.log("BLE", "Device info $uuid = \"$text\" -> ${_vehicleProfile.value?.generationLabel}")
    }

    private fun printableAscii(data: ByteArray): String =
        data.map { b -> val c = b.toInt() and 0xFF; if (c in 32..126) c.toChar() else '.' }.joinToString("")

    /**
     * Log a probed characteristic value as hex plus its printable-ASCII
     * rendering, followed by session-crypto decrypt attempts. AES-CBC/NoPadding
     * "succeeds" on any block-aligned input, so both renderings are candidates,
     * not confirmations - whichever line reads as sane text/values in the log
     * is the real framing. Attempted with the active session key (data plane)
     * and, failing that, the handshake temp secret, in both framed and raw modes.
     */
    private fun logProbeValue(label: String, data: ByteArray) {
        AppLogger.log("Probe", "$label (${data.size}B): ${BccuCrypto.hex(data)}  \"${printableAscii(data)}\"")
        val iv = tempIv ?: return
        if (data.isEmpty() || data.size % 16 != 0) return // not AES-CBC ciphertext
        val keys = listOfNotNull(
            activeSessionKey?.let { "session" to it },
            tempSecret?.let { "tempSecret" to it },
        )
        for ((name, key) in keys) {
            runCatching { BccuCrypto.decryptData(data, key, iv) }.onSuccess {
                AppLogger.log("Probe", "  decrypt[$name,framed] (${it.size}B): ${BccuCrypto.hex(it)}  \"${printableAscii(it)}\"")
            }
            runCatching { BccuCrypto.decryptControl(data, key, iv) }.onSuccess {
                AppLogger.log("Probe", "  decrypt[$name,raw]   (${it.size}B): ${BccuCrypto.hex(it)}  \"${printableAscii(it)}\"")
            }
        }
    }

    private fun handleAuthMessage(value: ByteArray) {
        val iv = tempIv
        val secret = tempSecret

        if (iv == null || secret == null) {
            if (value.size < 16) return
            AppLogger.log("Auth", "Received m1 (bike nonce)")
            val m1 = value.copyOfRange(0, 16)
            val m2 = BccuCrypto.randomNonce()
            val (newIv, newSecret) = BccuCrypto.computeTempIvAndSecret(m1, m2)
            tempIv = newIv
            tempSecret = newSecret
            writeCharacteristic(BccuProtocol.AUTH_REPLY, m2)
            return
        }

        val decrypted = try {
            BccuCrypto.decryptControl(value, secret, iv)
        } catch (e: Exception) {
            AppLogger.log("Auth", "!! Failed to decrypt control message: ${e.message}")
            return
        }
        if (decrypted.size < 3) return
        val cmd = decrypted[2].toInt() and 0xFF

        when {
            cmd == BccuProtocol.CMD_HELLO -> {
                val address = currentDeviceAddress
                val settings = AppSettings(this@BccuConnectionService)
                val known = address != null && settings.hasPairedBefore(address)
                val pool = address?.let { settings.loadKeyPool(it) }
                // Known-device silent reconnect (EXPERIMENT 2026-07-18). BCCU spec
                // §2: a device that holds the persisted 16-key pool replies
                // GENERATE_KEYS (cmd 1); the dash then goes straight to key-select
                // (cmd 16-31) against that stored pool - no re-derive, no "add
                // device" prompt. We preload the persisted pool so the key-select
                // ACK uses it. Only on the FIRST attempt of a cycle: if it stalls
                // (handshakeRecoveries>0) the stored pool is likely stale (dash
                // reset / forgot us), so fall back to echoing HELLO, which lets the
                // dash prompt for a genuine manual re-pair.
                if (known && pool != null && handshakeRecoveries == 0) {
                    sessionKeys = pool
                    AppLogger.log("Auth", "cmd=HELLO (known bike $address) - have persisted key pool, replying GENERATE_KEYS for silent reconnect")
                    replyControl(BccuProtocol.CMD_GENERATE_KEYS)
                } else {
                    val why = when {
                        !known -> "new"
                        pool == null -> "known, no stored pool"
                        else -> "known, retrying after stall"
                    }
                    AppLogger.log("Auth", "cmd=HELLO ($why bike $address) - echoing HELLO; dash decides whether to prompt")
                    replyControl(BccuProtocol.CMD_HELLO)
                }
            }
            cmd == BccuProtocol.CMD_GENERATE_KEYS -> {
                // The dash asked us to (re)derive this session's pool - the
                // first-pairing / manual-re-pair path. Derive, persist as the
                // long-term pool for this bike (so future cold boots can take the
                // silent known-device path above), then reply.
                AppLogger.log("Auth", "cmd=GENERATE_KEYS, deriving session keys")
                val mirrored = BccuCrypto.buildMirrored(decrypted)
                val keys = BccuCrypto.deriveSessionKeys(decrypted, mirrored, iv, secret)
                sessionKeys = keys
                currentDeviceAddress?.let { AppSettings(this@BccuConnectionService).saveKeyPool(it, keys) }
                replyControl(2)
            }
            cmd in 16..31 -> {
                val keyIndex = cmd and 0x0F
                val keys = sessionKeys
                if (keys == null || keyIndex >= keys.size) {
                    AppLogger.log("Auth", "!! Bike selected key index $keyIndex but no key pool yet")
                    return
                }
                activeSessionKey = keys[keyIndex]
                replyControl(BccuProtocol.CMD_KEY_ACK_BASE or keyIndex)
                if (_connectionState.value != ConnectionState.AUTHENTICATED) {
                    AppLogger.log("Auth", ">>> AUTHENTICATED <<< (key index $keyIndex)")
                    handshakeTimeoutJob?.cancel(); handshakeTimeoutJob = null
                    handshakeRecoveries = 0
                    stopBleScan()
                    _connectionState.value = ConnectionState.AUTHENTICATED
                    currentDeviceAddress?.let {
                        val s = AppSettings(this@BccuConnectionService)
                        s.markPairedBefore(it)
                        // Read straight back so a future "first time pairing" log
                        // unambiguously means the flag failed to persist (vs. a
                        // genuine first run) - narrows the reconnect-prompt bug.
                        AppLogger.log("Auth", "markPairedBefore($it) persisted=${s.hasPairedBefore(it)}")
                        // Self-heal the saved bike name from the live device. The
                        // R22 encrypted->plain migration could carry a STALE name
                        // (field: banner showed "Arun's JBL" instead of KTM3638);
                        // once we're authenticated we KNOW this MAC is the bike, so
                        // adopt its real advertised name and let the detected model
                        // stand in if the radio never reported one.
                        val liveName = gatt?.device?.name?.takeIf { n -> n.isNotBlank() }
                        val label = liveName ?: _vehicleProfile.value?.generationLabel
                        if (label != null && label != s.bondedDeviceName) {
                            s.bondedDeviceName = label
                            _deviceName.value = label
                            AppLogger.log("Auth", "Refreshed bonded bike name -> \"$label\"")
                        }
                    }
                    updateForegroundNotification("Connected")
                    enableIndication(BccuProtocol.RCM_REMOTE_CONTROL)
                    enableNotification(BccuProtocol.TBT_NAV_REQUEST)
                    sendNavigationState(guidanceOn = true, gpsIconOn = true)
                    startRssiPolling()
                    sendGreeting()
                    probeVehicleInfo()
                    if (prpcAvailable) tryStartTelemetry()
                }
            }
        }
    }

    /**
     * Watchdog for a handshake that never completes, armed the moment the link
     * comes up. The budget spans link-up -> AUTHENTICATED: a bonded dash gets
     * there in ~3s, but right after ignition-on its pairing manager can lag its
     * radio - it sends m1/HELLO then goes silent (or, on a reused ACL, never
     * sends m1 at all) - and the only cure is a clean link drop + a genuinely
     * fresh connection. Recovery goes through SCAN, never a direct connect: the
     * dash only advertises once the old ACL is truly gone, so "found in scan"
     * guarantees the next connection restarts auth with a fresh m1. (A direct
     * connect 1.5s after close() was field-observed re-attaching to the
     * still-live ACL in 78ms: no m1, no auth, dead until ignition-off.) There
     * is no retry cap - each cycle is cheap and self-paced by the dash's own
     * advertising, and capping it left a stalled dash permanently unrecovered.
     * We deliberately do NOT clear the pairing: the dash owns the prompt
     * decision from its own bond memory, so forgetting our flag only risks a
     * needless "add device" prompt.
     */
    private fun startHandshakeTimeout() {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = lifecycleScope.launch {
            val addr = currentDeviceAddress ?: return@launch
            val known = AppSettings(this@BccuConnectionService).hasPairedBefore(addr)
            val budget = if (known) HANDSHAKE_TIMEOUT_KNOWN_MS else HANDSHAKE_TIMEOUT_FIRST_PAIR_MS
            delay(budget)
            if (_connectionState.value == ConnectionState.AUTHENTICATED) return@launch
            handshakeRecoveries++
            // Back off instead of looping: one quickish retry, then long
            // cooldowns. Rapid drop/reconnect cycles against a booting dash
            // were suspected of re-triggering its "add device" prompt (R21
            // field report) - giving it time to finish waking beats hammering.
            val cooldown = if (handshakeRecoveries <= 1) RETRY_COOLDOWN_FIRST_MS else RETRY_COOLDOWN_LATER_MS
            AppLogger.log("Auth", "Handshake didn't complete in ${budget}ms - dropping the link, retrying in ${cooldown / 1000}s (attempt $handshakeRecoveries; pairing kept)")
            try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { /* ignore */ }
            gatt = null
            // Drop any op stranded in flight on the dead link - otherwise
            // gattBusy stays true and every op on the next connection stalls
            // behind it forever (the stale-GATT guard means the late
            // DISCONNECTED callback no longer clears the queue for us).
            clearGattQueue()
            tempIv = null; tempSecret = null; sessionKeys = null; activeSessionKey = null
            _connectionState.value = ConnectionState.DISCONNECTED
            delay(cooldown)
            if (gatt == null) startBleScan(addr)
        }
    }

    private fun handleRcm(value: ByteArray) {
        val iv = tempIv
        val key = activeSessionKey
        if (iv == null || key == null) return
        // Raw AES decrypt, no unframe() - confirmed from c8.k.d() in the official app.
        val decoded = try {
            BccuCrypto.decryptControl(value, key, iv)
        } catch (e: Exception) {
            return
        }
        val rcm = BccuProtocol.parseRcmValue(decoded)
        // Only trust the button bits when the controller reports ACTIVE
        // (byte[16]==0xFF, BCCU_BLE_PROTOCOL.md §6). Inactive frames carry
        // stale/garbage bytes; the old code treated them as presses, producing
        // phantom multi-button gestures (SET+BACK+DOWN flipping every ~150ms)
        // that hijacked the mode picker and made the gamepad unusable
        // (field log 20260718_184618, ~20:06). Treat inactive as "all released".
        val mask = if (rcm.active) {
            rcm.pressed.entries.fold(0) { acc, e -> if (e.value) acc or (1 shl e.key.bitIndex) else acc }
        } else 0

        // Calibration instrumentation: log EVERY RCM notification with a precise
        // timestamp (AppLogger stamps ms), the raw decoded bytes, the active flag
        // and the full pressed-button set. This is what lets us measure, from the
        // log alone, press DURATION (press->release gap), whether the dash repeats
        // while a button is held, and the interval between rapid multi-clicks.
        val now = android.os.SystemClock.elapsedRealtime()
        val dtMask = if (lastRcmMask != mask) now - lastRcmChangeMs else 0
        // Only when the mask actually CHANGED. The dash streams RCM
        // indications continuously (~7-10 Hz) whether or not a button moved,
        // and this line builds a binary string, a joinToString and a hex dump
        // before handing it to the logger - so unconditionally it was a
        // sustained allocation load for the whole ride that told us nothing
        // the state-change lines below don't already say.
        if (lastRcmMask != mask) {
            val pressedNames = BccuProtocol.HandlebarButton.entries
                .filter { (mask shr it.bitIndex) and 1 == 1 }
                .joinToString("+") { it.name }.ifEmpty { "none" }
            AppLogger.log("RCMcal", "notif active=${rcm.active} mask=0b${Integer.toBinaryString(mask).padStart(4, '0')} pressed=[$pressedNames] raw=${BccuCrypto.hex(decoded)} dSinceChange=${dtMask}ms")
        }

        for (button in BccuProtocol.HandlebarButton.entries) {
            val wasPressed = (lastRcmMask shr button.bitIndex) and 1 == 1
            val isPressed = (mask shr button.bitIndex) and 1 == 1
            if (!wasPressed && isPressed) {
                rcmPressAtMs[button] = now
                AppLogger.log("RCMcal", "PRESS   ${button.name}")
                // While the mode picker is up it's driven directly on PRESS (snappy
                // menu feel); mark the button so its RELEASE isn't also recognised
                // as a gesture.
                if (modeOverlay?.isShowing == true) {
                    overlayConsumed.add(button)
                    mainHandler.post { driveOverlay(button) }
                } else if (button == BccuProtocol.HandlebarButton.SET &&
                    AppSettings(this).remoteMode == AppSettings.MODE_INTERCOM
                ) {
                    // Push-to-talk acts on the raw PRESS edge, not a recognised
                    // gesture: gestures only fire on RELEASE, which for "hold to
                    // talk" is exactly backwards. Same consume-the-pair shape as
                    // the picker above, so the release can't become a gesture.
                    pttConsumedPress = true
                    mainHandler.post { bikePushToTalk(down = true) }
                }
            } else if (wasPressed && !isPressed) {
                val heldMs = rcmPressAtMs[button]?.let { now - it } ?: 0L
                AppLogger.log("RCMcal", "RELEASE ${button.name} held=${heldMs}ms")
                if (overlayConsumed.remove(button)) {
                    // this press was driving the overlay - don't emit a gesture
                } else if (button == BccuProtocol.HandlebarButton.SET && pttConsumedPress) {
                    // Keyed on the press having been push-to-talk, not on the
                    // current mode: a mode change mid-hold must still release.
                    pttConsumedPress = false
                    mainHandler.post { bikePushToTalk(down = false) }
                } else {
                    // All gestures register on RELEASE (rider's spec). Recognise
                    // single/double/long on the main thread.
                    mainHandler.post { recognizeRelease(button, heldMs) }
                }
            }
        }
        if (lastRcmMask != mask) lastRcmChangeMs = now
        lastRcmMask = mask
    }

    // --- Gesture recognition (single / double / long, all decided on release) ---

    /** Runnables for a short press awaiting a possible second click, per button. */
    private val pendingSingle =
        java.util.EnumMap<BccuProtocol.HandlebarButton, Runnable>(BccuProtocol.HandlebarButton::class.java)
    /** Buttons whose current press is being consumed by the mode-picker overlay. */
    private val overlayConsumed =
        java.util.EnumSet.noneOf(BccuProtocol.HandlebarButton::class.java)

    /** Only buttons that actually have a double-click action wait for the double
     * window; everything else fires immediately on release so the D-pad stays snappy. */
    private fun canDouble(button: BccuProtocol.HandlebarButton): Boolean =
        when (AppSettings(this).remoteMode) {
            // SET now has single (right), double (select) AND long (long-click),
            // so it must wait the double window. BACK has single (left) + double (back).
            AppSettings.MODE_CONTROLLER ->
                button == BccuProtocol.HandlebarButton.BACK || button == BccuProtocol.HandlebarButton.SET
            AppSettings.MODE_MUSIC ->
                button == BccuProtocol.HandlebarButton.UP || button == BccuProtocol.HandlebarButton.DOWN
            // SET never reaches here (push-to-talk consumes it on the raw edges),
            // and UP/DOWN are volume - only BACK has single + double.
            AppSettings.MODE_INTERCOM -> button == BccuProtocol.HandlebarButton.BACK
            else -> false
        }

    /** Main-thread. Classify a release into SINGLE / DOUBLE / LONG and emit it. */
    private fun recognizeRelease(button: BccuProtocol.HandlebarButton, heldMs: Long) {
        if (heldMs >= LONG_PRESS_MS) {
            pendingSingle.remove(button)?.let { mainHandler.removeCallbacks(it) }
            emitGesture(button, com.navigator.app.controller.GestureKind.LONG)
            return
        }
        val pending = pendingSingle.remove(button)
        if (pending != null) {
            mainHandler.removeCallbacks(pending)
            emitGesture(button, com.navigator.app.controller.GestureKind.DOUBLE)
        } else if (canDouble(button)) {
            val r = Runnable {
                pendingSingle.remove(button)
                emitGesture(button, com.navigator.app.controller.GestureKind.SINGLE)
            }
            pendingSingle[button] = r
            mainHandler.postDelayed(r, DOUBLE_CLICK_WINDOW_MS)
        } else {
            emitGesture(button, com.navigator.app.controller.GestureKind.SINGLE)
        }
    }

    private fun emitGesture(button: BccuProtocol.HandlebarButton, kind: com.navigator.app.controller.GestureKind) {
        AppLogger.log("RCMcal", "GESTURE ${button.name} $kind")
        onHandlebarGesture(com.navigator.app.controller.RemoteGesture(button, kind))
    }

    /** Drive the mode-picker overlay from a raw button press (main thread). */
    private fun driveOverlay(button: BccuProtocol.HandlebarButton) {
        val ov = modeOverlay ?: return
        when (button) {
            BccuProtocol.HandlebarButton.UP -> ov.moveSelection(-1)
            BccuProtocol.HandlebarButton.DOWN -> ov.moveSelection(+1)
            BccuProtocol.HandlebarButton.SET -> ov.confirm()
            BccuProtocol.HandlebarButton.BACK -> ov.hide()
        }
    }

    /**
     * Central routing for a recognised handlebar gesture (already classified
     * single/double/long, on the main thread).
     *
     * Global, works even when the remote is OFF: long-press UP opens the mode
     * picker (Bike-off / Controller / Music) from any screen.
     *
     * Otherwise the mode decides:
     *  - OFF        : ignore everything else - the buttons belong to the bike.
     *  - long-press DOWN (either live mode): open Google Maps.
     *  - CONTROLLER : forward to the accessibility gamepad (D-pad + select).
     *  - MUSIC      : volume / track transport here in the service.
     */
    private fun onHandlebarGesture(g: com.navigator.app.controller.RemoteGesture) {
        val up = BccuProtocol.HandlebarButton.UP
        val down = BccuProtocol.HandlebarButton.DOWN
        val long = com.navigator.app.controller.GestureKind.LONG

        if (g.button == up && g.kind == long) {
            AppLogger.log("RCM", "Long-press UP -> open mode picker")
            showModeOverlay()
            return
        }

        val mode = AppSettings(this).remoteMode
        if (mode == AppSettings.MODE_OFF) {
            AppLogger.log("RCM", "Remote OFF - $g ignored (bike keeps its buttons)")
            return
        }

        if (g.button == down && g.kind == long) {
            AppLogger.log("RCM", "Long-press DOWN -> open Google Maps")
            openGoogleMaps()
            return
        }

        when (mode) {
            AppSettings.MODE_MUSIC -> handleMusicGesture(g)
            AppSettings.MODE_INTERCOM -> handleIntercomGesture(g)
            AppSettings.MODE_CONTROLLER -> {
                if (com.navigator.app.controller.RemoteControlAccessibilityService.isRunning) {
                    _gestureEvents.tryEmit(g)
                } else {
                    AppLogger.log("Gamepad", "Controller gesture $g but accessibility service is OFF - enable it in Settings")
                }
            }
        }
    }

    private fun handleMusicGesture(g: com.navigator.app.controller.RemoteGesture) {
        val b = g.button; val k = g.kind
        val single = com.navigator.app.controller.GestureKind.SINGLE
        val double = com.navigator.app.controller.GestureKind.DOUBLE
        val media = com.navigator.app.audio.MediaControlBridge(this)
        when {
            b == BccuProtocol.HandlebarButton.UP && k == single -> adjustMusicVolume(raise = true)
            b == BccuProtocol.HandlebarButton.DOWN && k == single -> adjustMusicVolume(raise = false)
            b == BccuProtocol.HandlebarButton.UP && k == double -> { AppLogger.log("Music", "next track"); media.next() }
            b == BccuProtocol.HandlebarButton.DOWN && k == double -> { AppLogger.log("Music", "previous track"); media.previous() }
            b == BccuProtocol.HandlebarButton.SET && k == single -> { AppLogger.log("Music", "play/pause"); media.playPause() }
        }
    }

    // --- Intercom mode: talk to the pillion from the handlebar ---

    /**
     * Set by a push-to-talk PRESS so its matching RELEASE is routed back here.
     * Volatile: written on the BLE callback thread in handleRcm, cleared on the
     * main thread when the link drops.
     */
    @Volatile private var pttConsumedPress = false
    /** True between a successful push-to-talk press and its release. */
    private var bikePttHeld = false
    private val bikePttCap = Runnable { releaseBikePtt("held over ${BIKE_PTT_MAX_HOLD_MS / 1000} s - treated as stuck") }

    /**
     * UP/DOWN single = volume (the intercom plays on the media stream, so this is
     * the pillion's voice and the music together); BACK single = step noise
     * suppression; BACK double = end the call. SET is push-to-talk and is
     * handled on the raw edges in [handleRcm], never here.
     */
    private fun handleIntercomGesture(g: com.navigator.app.controller.RemoteGesture) {
        val b = g.button; val k = g.kind
        val single = com.navigator.app.controller.GestureKind.SINGLE
        val double = com.navigator.app.controller.GestureKind.DOUBLE
        when {
            b == BccuProtocol.HandlebarButton.UP && k == single -> adjustMusicVolume(raise = true)
            b == BccuProtocol.HandlebarButton.DOWN && k == single -> adjustMusicVolume(raise = false)
            b == BccuProtocol.HandlebarButton.BACK && k == single -> {
                val level = com.mensa.riderintercom.IntercomRemote.cycleNoiseSuppression()
                if (level == null) noIntercomCall("BACK") else {
                    AppLogger.log("Intercom", "Noise suppression -> $level")
                    sendNotification("Noise: $level", BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT)
                }
            }
            b == BccuProtocol.HandlebarButton.BACK && k == double -> {
                releaseBikePtt("ending the call")
                if (com.mensa.riderintercom.IntercomRemote.endCall(this)) {
                    AppLogger.log("Intercom", "Call ended from the handlebar")
                    sendNotification("Intercom ended", BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT)
                } else noIntercomCall("BACK double")
            }
        }
    }

    /**
     * Hold-to-talk from the handlebar (main thread). A press with no call
     * running says so on the dash instead of silently doing nothing - the rider
     * can't see the phone, and "nothing happened" is indistinguishable from
     * "the button didn't register".
     */
    private fun bikePushToTalk(down: Boolean) {
        if (!down) { releaseBikePtt("SET released"); return }
        if (!com.mensa.riderintercom.IntercomRemote.setPushToTalk(true)) {
            noIntercomCall("SET")
            return
        }
        bikePttHeld = true
        mainHandler.removeCallbacks(bikePttCap)
        mainHandler.postDelayed(bikePttCap, BIKE_PTT_MAX_HOLD_MS)
        AppLogger.log("Intercom", "Push-to-talk ON (handlebar SET)")
    }

    /**
     * Close the mic. Idempotent, and called from every way a hold can end: the
     * release edge, the dash link dropping (no release edge will ever arrive),
     * the service going away, ending the call, and the stuck-button cap.
     */
    private fun releaseBikePtt(reason: String) {
        if (!bikePttHeld) return
        bikePttHeld = false
        mainHandler.removeCallbacks(bikePttCap)
        com.mensa.riderintercom.IntercomRemote.setPushToTalk(false)
        AppLogger.log("Intercom", "Push-to-talk OFF ($reason)")
    }

    private fun noIntercomCall(button: String) {
        AppLogger.log("Intercom", "$button pressed in Intercom mode but no call is running")
        sendNotification("No intercom call", BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT)
    }

    private fun adjustMusicVolume(raise: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val dir = if (raise) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI)
        AppLogger.log("Music", "volume ${if (raise) "up" else "down"}")
    }

    private fun openGoogleMaps() {
        val i = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0")).setPackage("com.google.android.apps.maps")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }.onFailure { AppLogger.log("RCM", "!! open Maps failed: $it") }
    }

    /**
     * Shut down if the dash never turns up. The clock runs whenever we are not
     * authenticated and resets the moment we are, so a normal ride (or a mid-ride
     * dropout that reconnects) is never affected - only a session that spends a
     * full [NO_VEHICLE_TIMEOUT_MS] with no bike at all.
     *
     * Checked on a coarse tick rather than a single delay so that a reconnect
     * genuinely resets it, and skipped entirely while a ride is recording: a
     * recording ride means the bike was there, and killing it mid-ride would
     * truncate the GPX.
     */
    /**
     * Watch the handlebar gamepad's accessibility service while the bike is
     * connected, and tell the rider the moment it goes away.
     *
     * OEM battery managers (and an Android update, and a "clean up background
     * apps" tap) silently untick accessibility services. When that happens the
     * remote keeps clicking and the dash keeps showing guidance, so from the
     * saddle there is no sign at all - the buttons just stop doing anything.
     * The alert only fires while the bike is actually connected AND gamepad
     * mode is on, i.e. exactly when the rider is about to reach for a button
     * and find it dead.
     */
    private fun startAccessibilityWatchdog() {
        lifecycleScope.launch {
            var warned = false
            var missingTicks = 0
            while (true) {
                delay(ACCESSIBILITY_CHECK_MS)
                try {
                    val s = AppSettings(this@BccuConnectionService)
                    val wanted = s.gamepadEnabled || s.remoteMode == AppSettings.MODE_CONTROLLER
                    if (!wanted || _connectionState.value != ConnectionState.AUTHENTICATED) {
                        if (warned) { clearAccessibilityAlert(); warned = false }
                        missingTicks = 0
                        continue
                    }
                    val enabled = com.navigator.app.controller.RemoteControlAccessibilityService
                        .isEnabledInSettings(this@BccuConnectionService)
                    val bound = com.navigator.app.controller.RemoteControlAccessibilityService.isRunning
                    // Unticked in Android's settings is immediate and certain.
                    // Ticked-but-not-bound is usually just a rebind in flight
                    // after a process restart, so give that two ticks before
                    // crying wolf at a rider who can't do anything about it.
                    val lost = !enabled || (!bound && ++missingTicks >= 2)
                    if (enabled && bound) missingTicks = 0
                    if (lost && !warned) {
                        warned = true
                        AppLogger.log(
                            "Gamepad",
                            "!! Handlebar gamepad is DEAD while connected (enabledInSettings=$enabled bound=$bound) - alerting the rider",
                        )
                        notifyAccessibilityLost(enabled)
                    } else if (!lost && warned) {
                        warned = false
                        missingTicks = 0
                        clearAccessibilityAlert()
                        AppLogger.log("Gamepad", "Accessibility service is back - handlebar gamepad live again")
                    }
                } catch (e: Exception) {
                    AppLogger.log("Gamepad", "!! Accessibility watchdog iteration threw: $e")
                }
            }
        }
    }

    /**
     * Both channels at once, because the rider's attention is on the road:
     * the dash banner is what they'll actually see from the saddle, the phone
     * notification is what they can act on when stopped (it opens Android's
     * accessibility settings directly).
     */
    private fun notifyAccessibilityLost(enabledInSettings: Boolean) {
        // 13 chars is the dash banner's real viewport - see NOTIFICATION_BANNER_CHARS.
        sendNotification("REMOTE OFF", BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT)
        postAccessibilityAlertNotification(this, enabledInSettings)
    }

    private fun clearAccessibilityAlert() {
        runCatching { getSystemService(NotificationManager::class.java).cancel(ACCESSIBILITY_ALERT_ID) }
    }

    private fun startNoVehicleWatchdog() {
        lifecycleScope.launch {
            var unconnectedSinceMs = android.os.SystemClock.elapsedRealtime()
            while (true) {
                delay(30_000)
                if (_connectionState.value == ConnectionState.AUTHENTICATED) {
                    unconnectedSinceMs = android.os.SystemClock.elapsedRealtime()
                    continue
                }
                // The rider asked for a persistent session: never time out, no
                // matter how long the bike stays off. Their explicit choice
                // outranks the battery heuristic, and Stop still works.
                if (AppSettings(this@BccuConnectionService).keepSessionAlive) {
                    unconnectedSinceMs = android.os.SystemClock.elapsedRealtime()
                    setSearchHint("Keeping the session alive. Tap Stop to quit.")
                    continue
                }
                if (routeRecorder?.isRecording == true) {
                    unconnectedSinceMs = android.os.SystemClock.elapsedRealtime()
                    setSearchHint("Still looking while a ride is being recorded. Tap Stop to quit.")
                    continue
                }
                // Turn-by-turn guidance is flowing: the rider is mid-route, so
                // they're riding whether or not the dash link is up yet. Shutting
                // down here is exactly what left the dash blank for the rest of a
                // trip - once we're gone, nothing but reopening the app or a
                // fresh ACL_CONNECTED brings us back.
                // On the charger: that's the bike mount, not a pocket. The
                // timeout exists to stop us running all day on a day the rider
                // never rides - a phone that's plugged in is the opposite case,
                // and shutting down there is how the dash stayed dead for a
                // whole ride that started more than ten minutes after the phone
                // powered up.
                if (runCatching {
                        (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).isCharging
                    }.getOrDefault(false)
                ) {
                    unconnectedSinceMs = android.os.SystemClock.elapsedRealtime()
                    setSearchHint("Still looking while the phone is charging. Tap Stop to quit.")
                    continue
                }
                val navAgeMs = com.navigator.app.notifications.NotificationRepository
                    .lastNavGuidanceElapsedMs
                    .takeIf { it > 0L }
                    ?.let { android.os.SystemClock.elapsedRealtime() - it }
                if (navAgeMs != null && navAgeMs < NAV_ACTIVE_WINDOW_MS) {
                    unconnectedSinceMs = android.os.SystemClock.elapsedRealtime()
                    setSearchHint("Still looking while you're navigating. Tap Stop to quit.")
                    continue
                }
                val idleMs = android.os.SystemClock.elapsedRealtime() - unconnectedSinceMs
                val minsLeft = ((NO_VEHICLE_TIMEOUT_MS - idleMs + 59_999) / 60_000).coerceAtLeast(1)
                setSearchHint("Switch the ignition on to connect. Stops looking in $minsLeft min.")
                if (idleMs >= NO_VEHICLE_TIMEOUT_MS) {
                    AppLogger.log(
                        "Idle",
                        "No vehicle for ${idleMs / 60_000} min - shutting down service and app",
                    )
                    // Tell the Activity first: once stopSelf lands, this scope is
                    // cancelled and nothing else here will run.
                    runCatching {
                        sendBroadcast(Intent(ACTION_IDLE_SHUTDOWN).setPackage(packageName))
                    }
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    private fun showModeOverlay() {
        mainHandler.post {
            if (modeOverlay == null) modeOverlay = com.navigator.app.controller.RemoteModeOverlay(this) { mode ->
                applyRemoteMode(mode)
            }
            val ov = modeOverlay ?: return@post
            if (!ov.canShow()) {
                AppLogger.log("RCM", "Mode overlay: no draw-over-apps permission - opening settings")
                runCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return@post
            }
            AppLogger.log("RCM", "Opening handlebar mode overlay")
            ov.show(AppSettings(this).remoteMode)
        }
    }

    private fun applyRemoteMode(mode: String) {
        val s = AppSettings(this)
        s.remoteMode = mode
        AppLogger.log("RCM", "Remote mode set to $mode")
        // Controller mode needs the accessibility service to drive other apps /
        // the D-pad; if the rider hasn't enabled it yet, send them to the settings
        // screen to turn it on. Music and Off need no special permission.
        if (mode == AppSettings.MODE_CONTROLLER) {
            s.gamepadEnabled = true
            if (!com.navigator.app.controller.RemoteControlAccessibilityService.isRunning) {
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        } else {
            s.gamepadEnabled = false
        }
    }

    private fun replyControl(command: Int) {
        val iv = tempIv ?: return
        val secret = tempSecret ?: return
        val msg = BccuCrypto.buildControlMessage(command)
        val encrypted = BccuCrypto.encryptControl(msg, secret, iv)
        writeCharacteristic(BccuProtocol.AUTH_REPLY, encrypted)
    }

    // ---------------------------------------------------------------------
    // The dash's own navigation menu
    // ---------------------------------------------------------------------

    /** One entry in the cluster's Favourites list. */
    private data class DashFavorite(val title: String, val label: String, val target: String)

    /**
     * Home and Work, in that order, skipping either one the rider hasn't set.
     * A favourite's position in this list IS the `itemId` the dash sends back
     * in START_NAVIGATION, so the order has to stay stable between the list we
     * publish and the request we get back.
     */
    private fun dashFavorites(): List<DashFavorite> {
        val s = AppSettings(this)
        return buildList {
            s.homeDestination?.let { add(DashFavorite("Home", s.homeAddress ?: it, it)) }
            s.workDestination?.let { add(DashFavorite("Work", s.workAddress ?: it, it)) }
        }
    }

    /**
     * How 070b frames are wrapped is the one thing the decompiled source does
     * not settle: the ETBT data plane is framed+encrypted (TURN_*), while RCM
     * on the same link is raw AES with no frame. So try the data-plane
     * transform, then the control-plane one, then the bytes as they arrived,
     * and accept the first that yields a request byte we recognise. Which one
     * won is logged once, so the first ride with a real dash settles it.
     */
    @Volatile private var navFrameCodec: String? = null

    private fun decodeNavRequestFrame(value: ByteArray): ByteArray? {
        val key = activeSessionKey
        val iv = tempIv
        val candidates = buildList<Pair<String, () -> ByteArray>> {
            if (key != null && iv != null) {
                add("data" to { BccuCrypto.decryptData(value, key, iv) })
                add("control" to { BccuCrypto.decryptControl(value, key, iv) })
            }
            add("raw" to { value })
        }
        for ((name, decode) in candidates) {
            val decoded = runCatching { decode() }.getOrNull() ?: continue
            if (BccuProtocol.parseNavRequest(decoded) != null) {
                if (navFrameCodec != name) {
                    navFrameCodec = name
                    AppLogger.log("Nav", "070b frames decode as '$name'")
                }
                return decoded
            }
        }
        AppLogger.log("Nav", "!! Unrecognised 070b frame: ${BccuCrypto.hex(value)}")
        return null
    }

    /**
     * A request from the dash's navigation menu (070b, notify-only - the dash
     * drives, we answer). Until this existed the cluster's Favourites screen
     * came up empty: we subscribed to the characteristic and then dropped
     * every request on the floor.
     */
    private fun handleNavRequest(value: ByteArray) {
        val decoded = decodeNavRequestFrame(value) ?: return
        val request = BccuProtocol.parseNavRequest(decoded) ?: return
        AppLogger.log("Nav", "Dash request: ${request.type.name}" +
            (request.argument?.let { " arg=$it" } ?: ""))
        when (request.type) {
            BccuProtocol.NavRequestType.GET_FAVORITES -> sendFavorites()
            BccuProtocol.NavRequestType.START_NAVIGATION -> startDashNavigation(request.argument ?: 0)
            // Answer rather than ignore: the official app always replies, and a
            // dash waiting on a list it never gets sits on a spinner.
            BccuProtocol.NavRequestType.GET_LAST_DESTINATIONS ->
                sendEmptyNavList(BccuProtocol.LAST_DESTINATION, "No destinations")
            BccuProtocol.NavRequestType.GET_ACTIVE_WAYPOINT ->
                sendEmptyNavList(BccuProtocol.ACTIVE_WAYPOINT, "No route")
            else -> AppLogger.log("Nav", "No handler for ${request.type.name} yet - ignored")
        }
    }

    /** Publish the Favourites list on 070e, one write per entry. */
    private fun sendFavorites() {
        val key = activeSessionKey ?: return
        val iv = tempIv ?: return
        val favorites = dashFavorites()
        if (favorites.isEmpty()) {
            AppLogger.log("Nav", "Dash asked for favourites - none set yet")
            sendEmptyNavList(BccuProtocol.FAVORITE, "Set Home in app")
            return
        }
        favorites.forEachIndexed { index, favorite ->
            AppLogger.log("Nav", "Favourite $index = \"${favorite.title}\" (${favorite.label})")
            val payload = BccuProtocol.buildWaypointItemPayload(
                responseCode = BccuProtocol.NavResponseCode.OK,
                responsePhrase = "OK",
                nrListItems = favorites.size,
                itemId = index,
                title = favorite.title,
                description = favorite.label,
            )
            // NOT coalesced: every entry goes to the same characteristic, and
            // coalescing would drop all but the last one.
            writeCharacteristic(BccuProtocol.FAVORITE, BccuCrypto.encryptData(payload, key, iv))
        }
    }

    private fun sendEmptyNavList(uuid: java.util.UUID, phrase: String) {
        val key = activeSessionKey ?: return
        val iv = tempIv ?: return
        val payload = BccuProtocol.buildEmptyListPayload(phrase = phrase)
        writeCharacteristic(uuid, BccuCrypto.encryptData(payload, key, iv))
    }

    /**
     * The rider picked an entry on the cluster and pressed SET. We have no
     * router of our own - Google Maps is the router, and its guidance comes
     * back to the dash through the notification listener that already drives
     * every other route. So "start navigation" means: launch Maps at this
     * destination in two-wheeler mode.
     *
     * Starting an activity from a background service needs SYSTEM_ALERT_WINDOW
     * (declared in the manifest, and already required by the gamepad overlay).
     * Without that grant Android silently drops the launch, so the failure is
     * reported on the dash banner rather than vanishing.
     */
    private fun startDashNavigation(waypointId: Int) {
        val favorites = dashFavorites()
        val favorite = favorites.getOrNull(waypointId)
        if (favorite == null) {
            AppLogger.log("Nav", "!! Dash asked for waypoint $waypointId, only ${favorites.size} exist")
            sendNotification("No such place", BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT)
            return
        }
        AppLogger.log("Nav", "Dash started navigation to \"${favorite.title}\" (${favorite.target})")
        sendNotification("Routing ${favorite.title}", BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT)
        // mode=l is Maps' two-wheeler routing - same as the Waypoint "GO" button.
        val uri = android.net.Uri.parse("google.navigation:q=${android.net.Uri.encode(favorite.target)}&mode=l")
        val maps = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { startActivity(maps); true }.getOrElse {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
        if (!launched) {
            AppLogger.log("Nav", "!! Could not launch navigation - no maps app, or background start blocked")
            sendNotification("Open app first", BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT)
        }
    }

    fun sendTurnIcon(icon: BccuProtocol.TurnIcon, visibility: BccuProtocol.Visibility = BccuProtocol.Visibility.FULL) {
        val key = activeSessionKey
        val iv = tempIv
        if (key == null || iv == null) {
            AppLogger.log("Dash", "!! Cannot send TURN_ICON - not authenticated yet")
            return
        }
        AppLogger.log("Dash", "Sending TURN_ICON = ${icon.name}")
        val payload = BccuProtocol.buildTurnIconPayload(icon, visibility)
        writeCharacteristic(BccuProtocol.TURN_ICON, BccuCrypto.encryptData(payload, key, iv), coalesce = true)
        announceArrival(icon, visibility)
    }

    /**
     * Say "you're there" in words on the bottom banner the first time a route
     * reaches its destination. The chequered icon alone is a small tile at the
     * edge of the rider's vision; the banner is the part they actually read.
     *
     * Edge-triggered on purpose: Maps reposts its notification about once a
     * second, so firing per-update would re-start the banner forever. The flag
     * resets on any other maneuver and in [clearGuidance], so the next route
     * announces again.
     */
    private fun announceArrival(icon: BccuProtocol.TurnIcon, visibility: BccuProtocol.Visibility) {
        if (icon != BccuProtocol.TurnIcon.END || visibility == BccuProtocol.Visibility.OFF) {
            arrivalAnnounced = false
            return
        }
        if (arrivalAnnounced) return
        arrivalAnnounced = true
        AppLogger.log("Dash", "Destination reached - announcing on the banner")
        sendNotification("Location reached", BccuProtocol.NotificationIcon.TARGET_REACHED)
    }

    /**
     * Send a notification to the dash's bottom banner.
     *
     * - Short text (fits the 16-char banner): sent once, then auto-cleared
     *   after [NOTIFICATION_CLEAR_DELAY_MS], matching BikeConnect's timed clear.
     * - Long text, with marquee enabled: scrolled one character at a time
     *   right-to-left across the 16-char window so the whole message can be
     *   read, then cleared. See [runMarquee].
     */
    fun sendNotification(rawText: String, icon: BccuProtocol.NotificationIcon) {
        val key = activeSessionKey
        val iv = tempIv
        if (key == null || iv == null) {
            AppLogger.log("Dash", "!! Cannot send NOTIFICATION \"$rawText\" - not authenticated yet (state=${_connectionState.value})")
            return
        }
        // The dash renders a plain ASCII font: strip emoji and any non-ASCII
        // symbols so messages read cleanly instead of showing boxes/garbage.
        val text = asciiClean(rawText)
        if (text.isBlank()) return
        clearJob?.cancel()
        marqueeJob?.cancel()
        lastNotificationIcon = icon
        bannerOwnedByMessage = true

        val marquee = AppSettings(this).marqueeEnabled && text.length > NOTIFICATION_BANNER_CHARS
        if (marquee) {
            AppLogger.log("Dash", "Sending NOTIFICATION (marquee) icon=${icon.name} text=\"$text\"")
            marqueeJob = lifecycleScope.launch { runMarquee(text, icon) }
        } else {
            AppLogger.log("Dash", "Sending NOTIFICATION icon=${icon.name} text=\"$text\"")
            writeNotificationFrame(text, icon, BccuProtocol.Visibility.FULL)
            clearJob = lifecycleScope.launch {
                delay(NOTIFICATION_CLEAR_DELAY_MS)
                clearNotificationDisplay()
            }
        }
    }

    /**
     * Scroll [text] across the dash's 16-char banner one character per step.
     * The window slides right-to-left (start index advances), so the text
     * appears to scroll leftwards - the effect the user asked for. A gap of a
     * few spaces separates the end of one pass from the start of the next; the
     * whole message is scrolled [MARQUEE_CYCLES] times, then the banner clears.
     */
    private suspend fun runMarquee(text: String, icon: BccuProtocol.NotificationIcon, cycles: Int = MARQUEE_CYCLES) {
        val gap = MARQUEE_GAP
        val loop = text + gap
        // Doubling lets a fixed-width window wrap past the end without special-casing.
        val doubled = loop + loop
        repeat(cycles) {
            for (start in loop.indices) {
                val window = doubled.substring(start, start + NOTIFICATION_BANNER_CHARS)
                writeNotificationFrame(window, icon, BccuProtocol.Visibility.FULL)
                delay(MARQUEE_STEP_MS)
            }
        }
        clearNotificationDisplay()
    }

    /**
     * Strip everything the dash's ASCII font can't render (emoji, accented
     * letters, symbols) so mirrored text reads cleanly. Non-ASCII becomes a
     * space; runs of whitespace collapse; result is trimmed.
     */
    private fun asciiClean(s: String): String =
        buildString(s.length) { for (c in s) append(if (c.code in 32..126) c else ' ') }
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Show the currently-playing track on the dash's bottom banner, scrolled
     * ONCE then cleared (rider's spec: "Adventure of a life time by Coldplay").
     * Called on track change by [MediaTrackMonitor]; text is already ASCII-cleaned.
     */
    fun sendTrackBanner(rawText: String) {
        if (activeSessionKey == null || tempIv == null) return
        val text = asciiClean(rawText)
        if (text.isBlank()) return
        clearJob?.cancel()
        marqueeJob?.cancel()
        lastNotificationIcon = BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT
        bannerOwnedByMessage = true
        AppLogger.log("Dash", "Sending TRACK banner (scroll once): \"$text\"")
        if (text.length > NOTIFICATION_BANNER_CHARS) {
            marqueeJob = lifecycleScope.launch {
                runMarquee(text, BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT, cycles = 1)
            }
        } else {
            writeNotificationFrame(text, BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT, BccuProtocol.Visibility.FULL)
            clearJob = lifecycleScope.launch {
                delay(NOTIFICATION_CLEAR_DELAY_MS)
                clearNotificationDisplay()
            }
        }
    }

    // True while a mirrored message (not a status readout) is on the banner.
    // The overspeed readout must never steal the banner mid-message: without
    // this, speed refreshes every second cancelled the message marquee and the
    // two texts ping-ponged unreadably (R19 review finding).
    @Volatile private var bannerOwnedByMessage = false

    /**
     * Live overspeed readout for the bottom banner. Lower priority than
     * messages: skipped while a message is still displaying; auto-clears a few
     * seconds after the last update (i.e. once the rider slows back down).
     */
    fun sendSpeedBanner(text: String) {
        if (activeSessionKey == null || tempIv == null) return
        if (bannerOwnedByMessage && (marqueeJob?.isActive == true || clearJob?.isActive == true)) {
            return // a message is on the banner - let it finish its scroll/clear
        }
        bannerOwnedByMessage = false
        marqueeJob?.cancel()
        clearJob?.cancel()
        lastNotificationIcon = BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT
        writeNotificationFrame(text, BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT, BccuProtocol.Visibility.FULL)
        clearJob = lifecycleScope.launch {
            delay(NOTIFICATION_CLEAR_DELAY_MS)
            clearNotificationDisplay()
        }
    }

    private fun writeNotificationFrame(text: String, icon: BccuProtocol.NotificationIcon, visibility: BccuProtocol.Visibility) {
        val key = activeSessionKey ?: return
        val iv = tempIv ?: return
        // Final safety net: map any stray non-ASCII glyph to a space (no trim/
        // collapse here - callers already cleaned; trimming would misalign the
        // marquee window's intentional leading spaces).
        val safe = buildString(text.length) { for (c in text) append(if (c.code in 32..126) c else ' ') }
        val payload = BccuProtocol.buildNotificationPayload(icon, safe, visibility)
        writeCharacteristic(BccuProtocol.NOTIFICATION, BccuCrypto.encryptData(payload, key, iv), coalesce = true)
    }

    /**
     * Clear the banner the way BikeConnect does (confirmed from its decompiled
     * `h9.b`): keep the last valid icon byte and flip visibility to OFF (the
     * enum's byte value 1 = NONE), rather than sending icon UNKNOWN(0). The old
     * clear used UNKNOWN(0), which the hardware calibration pass found does NOT
     * render - a very likely reason earlier clear attempts left the banner
     * stuck on screen. Text is blanked too for good measure.
     */
    private fun clearNotificationDisplay() {
        bannerOwnedByMessage = false
        val key = activeSessionKey ?: return
        val iv = tempIv ?: return
        AppLogger.log("Dash", "Clearing NOTIFICATION display (visibility OFF, icon=${lastNotificationIcon.name})")
        val payload = BccuProtocol.buildNotificationPayload(lastNotificationIcon, "", BccuProtocol.Visibility.OFF)
        writeCharacteristic(BccuProtocol.NOTIFICATION, BccuCrypto.encryptData(payload, key, iv), coalesce = true)
    }

    /**
     * Friendly greeting shown on the dash's center display the moment we connect
     * & authenticate: the START/"begin your ride" turn icon plus "Hi <name>!" on
     * the road line. Auto-clears after [GREETING_MS], or the instant real
     * navigation guidance arrives (sendGuidance cancels the same clear job). The
     * dash can only render its own built-in glyphs, so the icon is the START code
     * (the app's own connect banner shows the walking-person icon).
     */
    private fun sendGreeting() {
        val key = activeSessionKey ?: return
        val iv = tempIv ?: return
        val name = AppSettings(this).userName?.trim()
        val text = if (!name.isNullOrBlank()) "Hi $name!" else "Welcome!"
        AppLogger.log("Dash", "Sending connect greeting: \"$text\"")
        val full = BccuProtocol.Visibility.FULL
        writeCharacteristic(BccuProtocol.TURN_ICON,
            BccuCrypto.encryptData(BccuProtocol.buildTurnIconPayload(BccuProtocol.TurnIcon.START, full), key, iv), coalesce = true)
        writeCharacteristic(BccuProtocol.TURN_ROAD,
            BccuCrypto.encryptData(BccuProtocol.buildTurnRoadPayload(text, full), key, iv), coalesce = true)
        writeCharacteristic(BccuProtocol.TURN_DISTANCE,
            BccuCrypto.encryptData(BccuProtocol.buildTurnDistancePayload("", BccuProtocol.Visibility.OFF), key, iv), coalesce = true)
        // Reuse the guidance-clear job: a real guidance update cancels it, so the
        // greeting is replaced seamlessly the moment navigation starts.
        guidanceClearJob?.cancel()
        guidanceClearJob = lifecycleScope.launch {
            delay(GREETING_MS)
            clearGuidance()
        }
    }

    fun sendNavigationState(guidanceOn: Boolean, gpsIconOn: Boolean, volume: Int = 255) {
        val key = activeSessionKey
        val iv = tempIv
        if (key == null || iv == null) {
            AppLogger.log("Dash", "!! Cannot send NAVIGATION_STATE - not authenticated yet")
            return
        }
        AppLogger.log("Dash", "Sending NAVIGATION_STATE guidanceOn=$guidanceOn gpsIconOn=$gpsIconOn volume=$volume")
        val payload = BccuProtocol.buildNavigationStatePayload(guidanceOn, gpsIconOn, volume)
        writeCharacteristic(BccuProtocol.NAVIGATION_STATE, BccuCrypto.encryptData(payload, key, iv))
    }

    /** Stop scrolling one center slot (it fits again, or the slot is being blanked). */
    private fun stopSlotScroll(uuid: java.util.UUID) {
        slotScrolls.remove(uuid)?.second?.cancel()
    }

    /** Stop every center-slot scroller - guidance ended, or the dash is being blanked. */
    private fun stopAllSlotScrolls() {
        slotScrolls.keys.toList().forEach { stopSlotScroll(it) }
    }

    /** One write of a center text slot, at the current session key. */
    private fun writeLabel(
        uuid: java.util.UUID,
        text: String,
        build: (String, BccuProtocol.Visibility) -> ByteArray,
    ) {
        val key = activeSessionKey ?: return
        val iv = tempIv ?: return
        writeCharacteristic(uuid, BccuCrypto.encryptData(build(text, BccuProtocol.Visibility.FULL), key, iv), coalesce = true)
    }

    /**
     * Write a center text slot, scrolling it when it doesn't fit.
     *
     * The slots are small - 8 characters for TURN_DISTANCE, 16 for TURN_INFO,
     * 32 for TURN_ROAD - and the protocol's own truncation turns anything
     * longer into "Head tow...". A real Maps instruction ("Head toward 6th A
     * Cross Rd") is three times the distance slot, so the rider saw a stub.
     * Now anything over a slot's width slides through a window exactly that
     * wide, the same way the bottom banner already did, and the whole label is
     * readable. Short text is still written once and left alone.
     *
     * Honours the rider's "Marquee scroll" setting: off = the old truncation.
     */
    private fun writeLabelSlot(
        uuid: java.util.UUID,
        raw: String,
        maxLen: Int,
        build: (String, BccuProtocol.Visibility) -> ByteArray,
    ) {
        // R37 field finding (see sendDashText): a genuinely empty string does
        // not blank a slot - a single space does.
        val text = asciiClean(raw).ifEmpty { " " }
        if (!AppSettings(this).marqueeEnabled || text.length <= maxLen) {
            stopSlotScroll(uuid)
            writeLabel(uuid, text, build)
            return
        }
        synchronized(slotScrolls) {
            val running = slotScrolls[uuid]
            if (running != null && running.first == text && running.second.isActive) return
            running?.second?.cancel()
            AppLogger.log("Dash", "Slot marquee (${maxLen}ch): \"$text\"")
            val job = lifecycleScope.launch {
                val loop = text + MARQUEE_GAP
                // Doubling lets the fixed-width window wrap past the end with
                // no special case at the seam.
                val doubled = loop + loop
                var start = 0
                while (isActive) {
                    writeLabel(uuid, doubled.substring(start, start + maxLen), build)
                    delay(MARQUEE_STEP_MS)
                    start = (start + 1) % loop.length
                }
            }
            slotScrolls[uuid] = text to job
        }
    }

    /**
     * Updates the dash's center guidance view - TURN_DISTANCE, TURN_ROAD, ETA,
     * and REMAINING_DISTANCE, confirmed from the official app's NavableBleImpl
     * (see BCCU_BLE_PROTOCOL.md). This is a different display region than
     * sendNotification()'s bottom banner. Any field can be null to leave that
     * characteristic unchanged.
     */
    fun sendGuidance(distanceText: String?, roadText: String?, etaText: String? = null, remainingDistanceText: String? = null) {
        if (activeSessionKey == null || tempIv == null) {
            AppLogger.log("Dash", "!! Cannot send guidance update - not authenticated yet")
            return
        }
        // A fresh guidance update means navigation is still active; cancel any
        // pending "nav ended" clear that a transient notification-remove queued.
        guidanceClearJob?.cancel()
        if (distanceText != null) {
            AppLogger.log("Dash", "Sending TURN_DISTANCE = \"$distanceText\"")
            writeLabelSlot(BccuProtocol.TURN_DISTANCE, distanceText, BccuProtocol.TURN_DISTANCE_MAX, BccuProtocol::buildTurnDistancePayload)
        }
        if (roadText != null) {
            AppLogger.log("Dash", "Sending TURN_ROAD = \"$roadText\"")
            writeLabelSlot(BccuProtocol.TURN_ROAD, roadText, BccuProtocol.TURN_ROAD_MAX, BccuProtocol::buildTurnRoadPayload)
        }
        if (etaText != null) {
            AppLogger.log("Dash", "Sending ETA = \"$etaText\"")
            writeLabelSlot(BccuProtocol.ETA, etaText, BccuProtocol.ETA_MAX, BccuProtocol::buildEtaPayload)
        }
        if (remainingDistanceText != null) {
            AppLogger.log("Dash", "Sending REMAINING_DISTANCE = \"$remainingDistanceText\"")
            writeLabelSlot(BccuProtocol.REMAINING_DISTANCE, remainingDistanceText, BccuProtocol.REMAINING_DISTANCE_MAX, BccuProtocol::buildRemainingDistancePayload)
        }
    }

    /**
     * Free-form write to the dash's center text slots, used by the Developer
     * screen and the idle dashboard (never during navigation - guidance owns
     * the display then). Unlike [sendGuidance] it also covers TURN_INFO.
     * Per field: null = leave the slot untouched, "" = blank it (visibility
     * OFF), anything else = show it (ASCII-cleaned, protocol-truncated).
     */
    fun sendDashText(
        distance: String? = null,
        info: String? = null,
        road: String? = null,
        eta: String? = null,
        remaining: String? = null,
    ) {
        if (activeSessionKey == null || tempIv == null) {
            AppLogger.log("Dash", "!! Cannot send dash text - not authenticated yet")
            return
        }
        guidanceClearJob?.cancel()
        fun write(uuid: java.util.UUID, raw: String?, maxLen: Int, build: (String, BccuProtocol.Visibility) -> ByteArray) {
            if (raw == null) return
            // Blanking semantics and the scroll-when-too-long behaviour both
            // live in writeLabelSlot now, so the idle cards and the Developer
            // screen scroll their long text exactly like guidance does.
            writeLabelSlot(uuid, raw, maxLen, build)
        }
        AppLogger.log("Dash", "Dash text: dist=$distance info=$info road=$road eta=$eta rem=$remaining")
        write(BccuProtocol.TURN_DISTANCE, distance, BccuProtocol.TURN_DISTANCE_MAX, BccuProtocol::buildTurnDistancePayload)
        write(BccuProtocol.TURN_INFO, info, BccuProtocol.TURN_INFO_MAX, BccuProtocol::buildTurnInfoPayload)
        write(BccuProtocol.TURN_ROAD, road, BccuProtocol.TURN_ROAD_MAX, BccuProtocol::buildTurnRoadPayload)
        write(BccuProtocol.ETA, eta, BccuProtocol.ETA_MAX, BccuProtocol::buildEtaPayload)
        write(BccuProtocol.REMAINING_DISTANCE, remaining, BccuProtocol.REMAINING_DISTANCE_MAX, BccuProtocol::buildRemainingDistancePayload)
    }

    /** Seconds left in the currently-playing track, for the idle music card. */
    fun trackRemainingSec(): Long? = mediaTrackMonitor?.nowPlayingRemainingSec()

    /** One idle card = every center slot + the turn icon, so cards can't leave stale fields behind. */
    fun sendDashCard(card: com.navigator.app.dash.IdleDashboard.Card) {
        if (activeSessionKey == null || tempIv == null) return
        AppLogger.log("Dash", "Idle card: ${card.label}")
        val icon = card.icon
        if (icon != null) sendTurnIcon(icon)
        else sendTurnIcon(BccuProtocol.TurnIcon.UNDEFINED, BccuProtocol.Visibility.OFF)
        sendDashText(card.distance, card.info, card.road, card.eta, card.remaining)
    }

    /** Blank everything NOW (center slots + banner) - the Developer screen's "clear all". */
    fun clearAllDashText() {
        guidanceClearJob?.cancel()
        stopAllSlotScrolls()
        marqueeJob?.cancel()
        clearJob?.cancel()
        clearGuidance()
        clearNotificationDisplay()
    }

    /**
     * Debounced clear of the center guidance view. Google Maps briefly removes
     * and re-posts its notification during a route, so we wait a moment and let
     * a subsequent guidance update cancel this - only a genuine end-of-nav (no
     * update for GUIDANCE_CLEAR_DELAY_MS) actually blanks the display.
     */
    private fun scheduleGuidanceClear() {
        guidanceClearJob?.cancel()
        guidanceClearJob = lifecycleScope.launch {
            delay(GUIDANCE_CLEAR_DELAY_MS)
            clearGuidance()
        }
    }

    /**
     * Blank the dash's center guidance view, the way the official app's
     * `disableGuidanceAndNotificationWidgets()` does: every label characteristic
     * to empty text + visibility OFF, and the turn icon to UNDEFINED + OFF.
     * Without this, the last turn ("1st Cross Rd / 0 m") stayed on the dash
     * forever after the rider exited Maps.
     */
    private fun clearGuidance() {
        // Navigation genuinely ended (this call is debounced past Maps' routine
        // remove+repost) - let the next route's first approach beep fire again.
        com.navigator.app.audio.TurnBeeper.reset()
        // Nothing may keep writing the slots we are about to blank, and the
        // next route gets its own arrival announcement.
        stopAllSlotScrolls()
        arrivalAnnounced = false
        val key = activeSessionKey ?: return
        val iv = tempIv ?: return
        AppLogger.log("Dash", "Clearing center guidance (navigation ended)")
        val off = BccuProtocol.Visibility.OFF
        writeCharacteristic(BccuProtocol.TURN_ICON,
            BccuCrypto.encryptData(BccuProtocol.buildTurnIconPayload(BccuProtocol.TurnIcon.UNDEFINED, off), key, iv), coalesce = true)
        writeCharacteristic(BccuProtocol.TURN_DISTANCE,
            BccuCrypto.encryptData(BccuProtocol.buildTurnDistancePayload("", off), key, iv), coalesce = true)
        writeCharacteristic(BccuProtocol.TURN_INFO,
            BccuCrypto.encryptData(BccuProtocol.buildTurnInfoPayload("", off), key, iv), coalesce = true)
        writeCharacteristic(BccuProtocol.TURN_ROAD,
            BccuCrypto.encryptData(BccuProtocol.buildTurnRoadPayload("", off), key, iv), coalesce = true)
        writeCharacteristic(BccuProtocol.ETA,
            BccuCrypto.encryptData(BccuProtocol.buildEtaPayload("", off), key, iv), coalesce = true)
        writeCharacteristic(BccuProtocol.REMAINING_DISTANCE,
            BccuCrypto.encryptData(BccuProtocol.buildRemainingDistancePayload("", off), key, iv), coalesce = true)
    }

    // --- Telemetry (PRPC) ---

    /** Log every discovered service + characteristic, so a real run reveals what this specific bike exposes (e.g. whether the PRPC telemetry service is present). */
    private fun dumpGattTable(g: BluetoothGatt) {
        AppLogger.log("GATT", "=== GATT table dump (${g.services.size} services) ===")
        for (service in g.services) {
            AppLogger.log("GATT", "Service ${service.uuid}")
            for (c in service.characteristics) {
                val props = c.properties
                val flags = buildString {
                    if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("R")
                    if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("W")
                    if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("w")
                    if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("N")
                    if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("I")
                }
                AppLogger.log("GATT", "  char ${c.uuid} [$flags]")
            }
        }
        AppLogger.log("GATT", "=== end GATT dump ===")
    }

    /**
     * Extra, easy-to-grep dump for a dash we can't drive (no 0700 ETBT). The
     * standard [dumpGattTable] already ran; this re-emits the service UUIDs
     * under an "UNSUPPORTED-DASH" tag with the detected capabilities so a rider
     * on a 1290/890 (or any non-Gen-3 unit) can export one log and we can see
     * exactly what their bike exposes - the missing input for backward-compat
     * work (issue-tracker item #6).
     */
    private fun logUnsupportedGattDump(g: BluetoothGatt) {
        AppLogger.log("UNSUPPORTED-DASH", "This dash exposes ${g.services.size} services but no 0700 ETBT nav service.")
        AppLogger.log("UNSUPPORTED-DASH", "Detected: ${_vehicleProfile.value?.describe() ?: "?"}")
        for (service in g.services) {
            AppLogger.log("UNSUPPORTED-DASH", "  service ${service.uuid} (${service.characteristics.size} chars)")
        }
        AppLogger.log("UNSUPPORTED-DASH", "Please export this log so support can add your model.")
    }

    /**
     * Exploratory: if the bike exposes the PRPC service, subscribe to telemetry
     * notifications and ask it to stream a small set of core datapoints (engine
     * RPM, wheel speed, gear, coolant temp, fuel). Values are logged and pushed
     * to [telemetry]. Kept to a handful of datapoints so a bike that doesn't
     * really implement this can't be flooded; expand once confirmed working.
     */
    private fun tryStartTelemetry() {
        val key = activeSessionKey ?: return
        val iv = tempIv ?: return
        val schema = telemetrySchema ?: try {
            TelemetrySchema.load(this).also { telemetrySchema = it }
        } catch (e: Exception) {
            AppLogger.log("Telemetry", "!! Failed to load telemetry schema: $e")
            return
        }
        val wanted = listOf(
            "ECU_Engine_Rpm", "ABS_Display_Front_Wheel_Speed", "ECU_Gear_Position",
            "ECU_Water_Temperature", "DASH_Fuel_Level", "bCCU_Voltage",
        )
        val datapoints = schema.filter { it.telemetryable && it.name in wanted }
        AppLogger.log("Telemetry", "PRPC present - trying telemetry for ${datapoints.size} core datapoints")
        enableNotification(BccuProtocol.PRPC_NOTIFICATION)
        enableNotification(BccuProtocol.PRPC_RESPONSE)
        for (dp in datapoints) {
            val request = BccuProtocol.buildTelemetryConfigureRequest(nextSid(), dp.id, dp.sampleRateDefaultMs.coerceAtLeast(1000))
            AppLogger.log("Telemetry", "Configuring ${dp.name} (id=${dp.id}) @${dp.sampleRateDefaultMs}ms")
            writeCharacteristic(BccuProtocol.PRPC_REQUEST, BccuCrypto.encryptData(request, key, iv))
        }
        val start = BccuProtocol.buildTelemetryControlRequest(nextSid(), BccuProtocol.TELEMETRY_CMD_START)
        AppLogger.log("Telemetry", "Sending telemetryControl(START)")
        writeCharacteristic(BccuProtocol.PRPC_REQUEST, BccuCrypto.encryptData(start, key, iv))
    }

    private fun handlePrpcResponse(value: ByteArray) {
        val key = activeSessionKey ?: return
        val iv = tempIv ?: return
        val decoded = try { BccuCrypto.decryptData(value, key, iv) } catch (e: Exception) {
            AppLogger.log("Telemetry", "!! Failed to decrypt PRPC_RESPONSE: ${e.message}"); return
        }
        AppLogger.log("Telemetry", "PRPC_RESPONSE: ${BccuCrypto.hex(decoded)}")
    }

    private fun handlePrpcNotification(value: ByteArray) {
        val key = activeSessionKey ?: return
        val iv = tempIv ?: return
        val decoded = try { BccuCrypto.decryptData(value, key, iv) } catch (e: Exception) {
            AppLogger.log("Telemetry", "!! Failed to decrypt PRPC_NOTIFICATION: ${e.message}"); return
        }
        val schema = telemetrySchema
        val triples = BccuProtocol.parseTelemetryNotification(decoded) { id ->
            schema?.firstOrNull { it.id == id }?.let { TelemetrySchema.typeLength(it.type) } ?: 0
        }
        if (triples.isEmpty()) {
            AppLogger.log("Telemetry", "PRPC_NOTIFICATION (unparsed): ${BccuCrypto.hex(decoded)}")
            return
        }
        val updated = _telemetry.value.toMutableMap()
        for (t in triples) {
            val dp = schema?.firstOrNull { it.id == t.datapointId }
            if (dp == null) {
                AppLogger.log("Telemetry", "datapoint ${t.datapointId} (unknown): ${BccuCrypto.hex(t.value)}")
                continue
            }
            val v = TelemetrySchema.decodeValue(dp.type, t.value)
            val unit = if (!dp.unit.isNullOrBlank()) " ${dp.unit}" else ""
            AppLogger.log("Telemetry", "${dp.name} = $v$unit")
            updated[dp.name] = "$v$unit"
        }
        _telemetry.value = updated
    }

    private fun findCharacteristic(g: BluetoothGatt, uuid: java.util.UUID): BluetoothGattCharacteristic? {
        for (service in g.services) {
            val c = service.getCharacteristic(uuid)
            if (c != null) return c
        }
        return null
    }

    private fun enableIndication(uuid: java.util.UUID) = enableCccd(uuid, indication = true)
    private fun enableNotification(uuid: java.util.UUID) = enableCccd(uuid, indication = false)

    private fun enableCccd(uuid: java.util.UUID, indication: Boolean) {
        enqueueGattOp {
            val g = gatt
            val characteristic = g?.let { findCharacteristic(it, uuid) }
            if (g == null || characteristic == null) {
                AppLogger.log("BLE", "!! Cannot enable ${if (indication) "indication" else "notification"} on $uuid - characteristic not found")
                gattOpFinished()
                return@enqueueGattOp
            }
            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(BccuProtocol.CCCD)
            if (descriptor == null) {
                AppLogger.log("BLE", "!! Cannot enable ${if (indication) "indication" else "notification"} on $uuid - no CCCD descriptor")
                gattOpFinished()
                return@enqueueGattOp
            }
            val value = if (indication)
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            val accepted = g.writeDescriptor(descriptor)
            AppLogger.log("BLE", "writeDescriptor CCCD for $uuid (${if (indication) "indication" else "notification"}) queued=$accepted")
            if (!accepted) {
                gattOpFinished()
            }
        }
    }

    /** Queue a read of a single characteristic (result surfaces in onCharacteristicRead -> logProbeValue). */
    private fun readCharacteristic(uuid: java.util.UUID) {
        enqueueGattOp {
            val g = gatt
            val characteristic = g?.let { findCharacteristic(it, uuid) }
            if (g == null || characteristic == null) {
                AppLogger.log("Probe", "read: characteristic $uuid not found, skipping")
                gattOpFinished()
                return@enqueueGattOp
            }
            val accepted = g.readCharacteristic(characteristic)
            AppLogger.log("Probe", "readCharacteristic $uuid queued=$accepted")
            if (!accepted) gattOpFinished()
        }
    }

    /**
     * Post-auth exploration of everything this bike might expose beyond nav.
     * Reads the standard Device Information service (model/serial/firmware),
     * reads the VIN, and enables notify/indicate + reads on the two
     * undocumented services (0200/0300) so a subsequent engine-on/revving
     * capture shows whether any of those characteristics carry live telemetry
     * (RPM/speed/gear/etc). All output lands under the [Probe] log tag. Safe to
     * call on any bike: missing services/characteristics are skipped with a log
     * line, never a crash.
     */
    private fun probeVehicleInfo() {
        val g = gatt ?: return
        AppLogger.log("Probe", "=== probeVehicleInfo: reading device info / VIN / mystery services ===")

        // Standard Device Information service: model, serial, all revisions.
        val deviceInfo = g.getService(BccuProtocol.DEVICE_INFO_SERVICE)
        if (deviceInfo != null) {
            for (c in deviceInfo.characteristics) readCharacteristic(c.uuid)
        } else {
            AppLogger.log("Probe", "Device Information service (180a) not present")
        }

        // VIN: reading 0002 cold returns all-zeros (confirmed in the field) - the
        // official app first *requests* it by writing to GET_VIN_REQUEST (0001),
        // then the value shows up on 0002 (readable + notify). Enable notify so we
        // catch the async push, fire the request, then also read.
        val key = activeSessionKey; val iv = tempIv
        if (key != null && iv != null && g.getService(BccuProtocol.BASE_SERVICE) != null) {
            enableNotification(BccuProtocol.BASE_VIN)
            // A 1-byte request marker; encrypted on the data plane like every 000x write.
            writeCharacteristic(BccuProtocol.BASE_GET_VIN_REQUEST, BccuCrypto.encryptData(byteArrayOf(0x01), key, iv))
        }
        readCharacteristic(BccuProtocol.BASE_VIN)

        // Undocumented 0200/0300: subscribe to every notify/indicate char and
        // read every readable one, so an engine-on capture reveals live data.
        for (svcUuid in listOf(BccuProtocol.MYSTERY_SERVICE_0200, BccuProtocol.MYSTERY_SERVICE_0300)) {
            val svc = g.getService(svcUuid)
            if (svc == null) {
                AppLogger.log("Probe", "Mystery service $svcUuid not present")
                continue
            }
            for (c in svc.characteristics) {
                val props = c.properties
                if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) enableNotification(c.uuid)
                else if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) enableIndication(c.uuid)
                if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) readCharacteristic(c.uuid)
            }
        }
        AppLogger.log("Probe", "=== probeVehicleInfo: requests queued ===")
    }

    /**
     * @param coalesce true for "latest value wins" display writes (guidance
     * labels, marquee frames, banner): a still-queued older write to the same
     * characteristic is dropped, keeping the queue bounded and frames fresh on
     * a slow link. Never set for control-plane writes (auth replies, PRPC
     * requests) where every message matters.
     */
    @Suppress("DEPRECATION")
    private fun writeCharacteristic(uuid: java.util.UUID, data: ByteArray, coalesce: Boolean = false) {
        enqueueGattOp(if (coalesce) uuid else null) {
            val g = gatt
            val characteristic = g?.let { findCharacteristic(it, uuid) }
            if (g == null || characteristic == null) {
                AppLogger.log("BLE", "!! Characteristic $uuid not found, cannot write")
                gattOpFinished()
                return@enqueueGattOp
            }
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = data
            val accepted = g.writeCharacteristic(characteristic)
            AppLogger.log("BLE", "writeCharacteristic $uuid (${data.size} bytes) queued=$accepted")
            if (!accepted) {
                gattOpFinished()
            }
        }
    }
}
