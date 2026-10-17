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
        private const val NOTIFICATION_CLEAR_DELAY_MS = 5000L
        /** The dash's bottom banner shows at most 16 characters. */
        private const val NOTIFICATION_BANNER_CHARS = 16
        /** Per-character marquee step; ~3 chars/sec reads comfortably at a glance. */
        private const val MARQUEE_STEP_MS = 320L
        /** How many times a long message scrolls fully before the banner clears. */
        private const val MARQUEE_CYCLES = 2
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
        /** Three Up presses within this window open the handlebar mode-picker overlay. */
        private const val TRIPLE_UP_WINDOW_MS = 1500L
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

        fun sendNotificationIfRunning(text: String, icon: BccuProtocol.NotificationIcon) {
            runningInstance?.sendNotification(text, icon)
        }

        /** Low-priority live status (overspeed readout): yields to any message currently on the banner. */
        fun sendSpeedBannerIfRunning(text: String) {
            runningInstance?.sendSpeedBanner(text)
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
    }

    private var gatt: BluetoothGatt? = null
    private var currentDeviceAddress: String? = null
    private var tempIv: ByteArray? = null
    private var tempSecret: ByteArray? = null
    private var sessionKeys: List<ByteArray>? = null
    private var activeSessionKey: ByteArray? = null
    private var lastRcmMask: Int = 0
    private var clearJob: Job? = null
    private var marqueeJob: Job? = null
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
    private val upPressTimes = ArrayDeque<Long>()

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
     * opendash_log_20260709_004422 showed the app stuck on "Connected" forever
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
        adapterStateReceiver?.let { runCatching { unregisterReceiver(it) } }
        adapterStateReceiver = null
        speedMonitor?.stop(); speedMonitor = null
        routeRecorder?.stop(); routeRecorder = null
        vibrationMonitor?.destroy(); vibrationMonitor = null
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
    }

    private fun buildForegroundNotification(status: String): Notification {
        // Target the stable activity: a launcher alias can be disabled after a brand change.
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.navigator.app.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            PendingIntent.FLAG_IMMUTABLE
        )
        // A real way out from the notification: stops this service (removing
        // the ongoing notification) and closes the app task - the R20 field
        // request was an exit that actually exits, not just opens the app.
        val exitIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BccuConnectionService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Navigator Gen3")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Exit", exitIntent)
            .build()
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
                prpcAvailable = g.getService(BccuProtocol.PRPC_SERVICE) != null
                AppLogger.log("BLE", "PRPC telemetry service (0600) present: $prpcAvailable")
                val mainService = g.getService(BccuProtocol.MAIN_SERVICE)
                if (mainService == null) {
                    AppLogger.log("BLE", "!! MAIN_SERVICE not found on this device")
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
                logProbeValue(readLabel(c, status), data)
            } catch (e: Exception) {
                AppLogger.log("Probe", "!! onCharacteristicRead threw: $e")
            } finally {
                gattOpFinished()
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            try {
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
                // Always echo HELLO back. Field logs proved this dash decides
                // whether to show its "confirm pairing" prompt entirely from its
                // own bond memory - not from which command we reply. Echoing
                // HELLO is the ONLY reply it acts on: to a bonded dash it answers
                // with GENERATE_KEYS in a few seconds and no prompt; to a genuinely
                // new device it prompts and answers once the rider confirms (~30s).
                // The old "known bike -> reply GENERATE_KEYS to skip the prompt"
                // shortcut was silently ignored by the dash, stalling every single
                // reconnect and (via the recovery) forcing a spurious re-pair.
                val address = currentDeviceAddress
                val known = address != null && AppSettings(this@BccuConnectionService).hasPairedBefore(address)
                AppLogger.log("Auth", "cmd=HELLO (${if (known) "known" else "new"} bike $address) - echoing HELLO; dash decides whether to prompt")
                replyControl(BccuProtocol.CMD_HELLO)
            }
            cmd == BccuProtocol.CMD_GENERATE_KEYS -> {
                AppLogger.log("Auth", "cmd=GENERATE_KEYS, deriving session keys")
                val mirrored = BccuCrypto.buildMirrored(decrypted)
                val keys = BccuCrypto.deriveSessionKeys(decrypted, mirrored, iv, secret)
                sessionKeys = keys
                // The dash keeps this pool across ignition cycles and resumes
                // later sessions by key-select alone (no re-derivation), so our
                // copy must survive disconnects and process death too - persist
                // it per-MAC or every reconnect stalls on an empty pool.
                currentDeviceAddress?.let {
                    AppSettings(this@BccuConnectionService).storeSessionKeys(it, keys)
                }
                replyControl(2)
            }
            cmd in 16..31 -> {
                val keyIndex = cmd and 0x0F
                var keys = sessionKeys
                if (keys == null) {
                    // Reconnect: the dash skipped GENERATE_KEYS and selected a
                    // key from the pool it has kept since pairing. Restore the
                    // pool we persisted then instead of stalling forever.
                    keys = currentDeviceAddress?.let {
                        AppSettings(this@BccuConnectionService).loadSessionKeys(it)
                    }
                    if (keys != null) {
                        AppLogger.log("Auth", "Restored persisted session key pool (${keys.size} keys)")
                        sessionKeys = keys
                    }
                }
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
        val mask = rcm.pressed.entries.fold(0) { acc, e -> if (e.value) acc or (1 shl e.key.bitIndex) else acc }
        for (button in BccuProtocol.HandlebarButton.entries) {
            val wasPressed = (lastRcmMask shr button.bitIndex) and 1 == 1
            val isPressed = (mask shr button.bitIndex) and 1 == 1
            if (!wasPressed && isPressed) {
                AppLogger.log("RCM", "Handlebar button event: ${button.name}")
                onHandlebarButton(button)
            }
        }
        lastRcmMask = mask
    }

    /**
     * Central routing for every handlebar press. Precedence:
     * 1. If the mode-picker overlay is up, the buttons drive IT (Up/Down select,
     *    Set confirm, Back close) and go nowhere else.
     * 2. Triple-press Up (3 within [TRIPLE_UP_WINDOW_MS]) opens the mode picker.
     * 3. Otherwise emit as normal - consumers (media/menu state machine, gamepad
     *    accessibility service) act based on the chosen [AppSettings.remoteMode].
     */
    private fun onHandlebarButton(button: BccuProtocol.HandlebarButton) {
        if (modeOverlay?.isShowing == true) {
            mainHandler.post {
                val ov = modeOverlay ?: return@post
                when (button) {
                    BccuProtocol.HandlebarButton.UP -> ov.moveSelection(-1)
                    BccuProtocol.HandlebarButton.DOWN -> ov.moveSelection(+1)
                    BccuProtocol.HandlebarButton.SET -> ov.confirm()
                    BccuProtocol.HandlebarButton.BACK -> ov.hide()
                }
            }
            return
        }

        if (button == BccuProtocol.HandlebarButton.UP) {
            val now = System.currentTimeMillis()
            upPressTimes.addLast(now)
            while (upPressTimes.isNotEmpty() && now - upPressTimes.first() > TRIPLE_UP_WINDOW_MS) upPressTimes.removeFirst()
            if (upPressTimes.size >= 3) {
                upPressTimes.clear()
                showModeOverlay()
                return
            }
        } else {
            upPressTimes.clear()
        }

        _buttonEvents.tryEmit(button)

        if (button == BccuProtocol.HandlebarButton.BACK && !AppForegroundState.isForeground) {
            // In gamepad mode, BACK falls through to the foreground app (the
            // accessibility service makes it a global Back); otherwise bring
            // Navigator Gen3 forward so Back opens its menu.
            val gamepad = AppSettings(this).remoteMode == AppSettings.MODE_GAMEPAD &&
                com.navigator.app.controller.RemoteControlAccessibilityService.isRunning
            if (gamepad) {
                AppLogger.log("RCM", "Gamepad mode - leaving BACK to the foreground app")
            } else {
                val intent = Intent(this, com.navigator.app.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(intent)
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
        when (mode) {
            AppSettings.MODE_GAMEPAD -> {
                s.gamepadEnabled = true
                if (!com.navigator.app.controller.RemoteControlAccessibilityService.isRunning) {
                    runCatching {
                        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            AppSettings.MODE_DASH -> {
                s.gamepadEnabled = false
                runCatching {
                    startActivity(Intent(this, com.navigator.app.ui.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
            else -> s.gamepadEnabled = false // MEDIA
        }
    }

    private fun replyControl(command: Int) {
        val iv = tempIv ?: return
        val secret = tempSecret ?: return
        val msg = BccuCrypto.buildControlMessage(command)
        val encrypted = BccuCrypto.encryptControl(msg, secret, iv)
        writeCharacteristic(BccuProtocol.AUTH_REPLY, encrypted)
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
    fun sendNotification(text: String, icon: BccuProtocol.NotificationIcon) {
        val key = activeSessionKey
        val iv = tempIv
        if (key == null || iv == null) {
            AppLogger.log("Dash", "!! Cannot send NOTIFICATION \"$text\" - not authenticated yet (state=${_connectionState.value})")
            return
        }
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
    private suspend fun runMarquee(text: String, icon: BccuProtocol.NotificationIcon) {
        val gap = "   "
        val loop = text + gap
        // Doubling lets a fixed-width window wrap past the end without special-casing.
        val doubled = loop + loop
        repeat(MARQUEE_CYCLES) {
            for (start in loop.indices) {
                val window = doubled.substring(start, start + NOTIFICATION_BANNER_CHARS)
                writeNotificationFrame(window, icon, BccuProtocol.Visibility.FULL)
                delay(MARQUEE_STEP_MS)
            }
        }
        clearNotificationDisplay()
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
        val payload = BccuProtocol.buildNotificationPayload(icon, text, visibility)
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

    /**
     * Updates the dash's center guidance view - TURN_DISTANCE, TURN_ROAD, ETA,
     * and REMAINING_DISTANCE, confirmed from the official app's NavableBleImpl
     * (see BCCU_BLE_PROTOCOL.md). This is a different display region than
     * sendNotification()'s bottom banner. Any field can be null to leave that
     * characteristic unchanged.
     */
    fun sendGuidance(distanceText: String?, roadText: String?, etaText: String? = null, remainingDistanceText: String? = null) {
        val key = activeSessionKey
        val iv = tempIv
        if (key == null || iv == null) {
            AppLogger.log("Dash", "!! Cannot send guidance update - not authenticated yet")
            return
        }
        // A fresh guidance update means navigation is still active; cancel any
        // pending "nav ended" clear that a transient notification-remove queued.
        guidanceClearJob?.cancel()
        if (distanceText != null) {
            AppLogger.log("Dash", "Sending TURN_DISTANCE = \"$distanceText\"")
            val payload = BccuProtocol.buildTurnDistancePayload(distanceText)
            writeCharacteristic(BccuProtocol.TURN_DISTANCE, BccuCrypto.encryptData(payload, key, iv), coalesce = true)
        }
        if (roadText != null) {
            AppLogger.log("Dash", "Sending TURN_ROAD = \"$roadText\"")
            val payload = BccuProtocol.buildTurnRoadPayload(roadText)
            writeCharacteristic(BccuProtocol.TURN_ROAD, BccuCrypto.encryptData(payload, key, iv), coalesce = true)
        }
        if (etaText != null) {
            AppLogger.log("Dash", "Sending ETA = \"$etaText\"")
            val payload = BccuProtocol.buildEtaPayload(etaText)
            writeCharacteristic(BccuProtocol.ETA, BccuCrypto.encryptData(payload, key, iv), coalesce = true)
        }
        if (remainingDistanceText != null) {
            AppLogger.log("Dash", "Sending REMAINING_DISTANCE = \"$remainingDistanceText\"")
            val payload = BccuProtocol.buildRemainingDistancePayload(remainingDistanceText)
            writeCharacteristic(BccuProtocol.REMAINING_DISTANCE, BccuCrypto.encryptData(payload, key, iv), coalesce = true)
        }
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
