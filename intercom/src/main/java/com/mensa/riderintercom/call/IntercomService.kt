package com.mensa.riderintercom.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mensa.riderintercom.R
import com.mensa.riderintercom.RiderIntercomApp
import com.mensa.riderintercom.discovery.HostBeacon
import com.mensa.riderintercom.floor.FloorState
import com.mensa.riderintercom.media.ButtonGesture
import com.mensa.riderintercom.media.ButtonSession
import com.mensa.riderintercom.transport.LinkRole
import com.mensa.riderintercom.util.TalkMode
import com.mensa.riderintercom.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The call lives here, not in the Activity.
 *
 * One critical Android 14 rule shapes the whole design: a foreground service that
 * declares the microphone type cannot be created while the app is in the background, and
 * the system throws a SecurityException if it is tried. So this service is only ever
 * started from the Start button on screen, and it never tries to restart itself from a
 * receiver after a crash - it posts a notification that opens the app instead, because a
 * silent retry is what produces the one-way-audio bug.
 */
class IntercomService : Service() {

    companion object {
        private const val TAG = "IntercomService"

        const val ACTION_START = "com.mensa.riderintercom.START"
        const val ACTION_STOP = "com.mensa.riderintercom.STOP"
        const val ACTION_TOGGLE_PTT = "com.mensa.riderintercom.TOGGLE_PTT"
        const val ACTION_TOGGLE_MUTE = "com.mensa.riderintercom.TOGGLE_MUTE"
        const val ACTION_CYCLE_NS = "com.mensa.riderintercom.CYCLE_NS"

        const val EXTRA_ROLE = "role"
        const val EXTRA_DEVICE = "device"

        const val ROLE_HOST = "host"
        const val ROLE_CLIENT = "client"

        private const val NOTIFICATION_ID = 42

        /** Live handle for the UI. Null when no call is running. */
        private val _instance = MutableStateFlow<IntercomService?>(null)
        val instance: StateFlow<IntercomService?> = _instance

        fun startCall(context: Context, role: LinkRole, device: BluetoothDevice?) {
            val i = Intent(context, IntercomService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ROLE, if (role == LinkRole.Host) ROLE_HOST else ROLE_CLIENT)
                putExtra(EXTRA_DEVICE, device)
            }
            // Must be called while the app is visible; see the class comment.
            context.startForegroundService(i)
        }

        fun stopCall(context: Context) {
            context.startService(
                Intent(context, IntercomService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var controller: CallController
        private set

    private var buttons: ButtonSession? = null
    private var beacon: HostBeacon? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    private val _lastKeyCode = MutableStateFlow(0)
    /** Raw headset key codes, for the calibration screen in settings. */
    val lastKeyCode: StateFlow<Int> = _lastKeyCode

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = RiderIntercomApp.init(this)
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = bm.adapter

        controller = CallController(applicationContext, app.prefs, adapter)
        _instance.value = this

        scope.launch {
            controller.status.collect { s ->
                updateNotification(s)
                // The intercom takes the headset button briefly around each floor change,
                // then hands it straight back to the music app.
                if (s.floor != FloorState.Idle) buttons?.claimBriefly()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> { stopCall(); return START_NOT_STICKY }
            ACTION_TOGGLE_PTT -> controller.togglePushToTalk()
            ACTION_CYCLE_NS -> controller.cycleNoiseSuppression()
            ACTION_TOGGLE_MUTE -> controller.setPushToTalk(false)
            else -> if (!started) { stopSelf(); return START_NOT_STICKY }
        }
        // Never STICKY: the system would try to recreate a microphone-type service from
        // the background, which Android 14 refuses.
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (started) return

        val roleName = intent.getStringExtra(EXTRA_ROLE) ?: ROLE_HOST
        val role = if (roleName == ROLE_HOST) LinkRole.Host else LinkRole.Client
        @Suppress("DEPRECATION")
        val device: BluetoothDevice? = intent.getParcelableExtra(EXTRA_DEVICE)

        goForeground(controller.status.value)
        acquireWakeLock()

        val app = RiderIntercomApp.init(this)
        if (app.prefs.current.headsetButtonEnabled) {
            buttons = ButtonSession(
                context = this,
                onGesture = ::onButtonGesture,
                onRawKey = { code -> _lastKeyCode.value = code },
            ).also { it.claim() }
        }

        // While hosting, tell nearby phones so a buddy can join by tapping a name
        // instead of hunting through the paired-devices list.
        if (role == LinkRole.Host) {
            val bm2 = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            beacon = HostBeacon(bm2.adapter).also {
                it.start(app.prefs.current.riderName, acceptingJoiners = true)
            }
        }

        started = true
        controller.start(role, device)
    }

    /**
     * The button means different things depending on whether a call is up, because the
     * music app owns it the rest of the time.
     */
    private fun onButtonGesture(g: ButtonGesture) {
        Log.i(TAG, "headset gesture $g")
        when (g) {
            ButtonGesture.Single -> Unit // leave play/pause to the music app
            ButtonGesture.Double -> controller.togglePushToTalk()
            ButtonGesture.Triple, ButtonGesture.LongPress -> stopCall()
        }
    }

    // ------------------------------------------------------------- foreground

    private fun goForeground(status: CallStatus) {
        val notification = buildNotification(status)
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // On Android 14+ this is the SecurityException from trying to create a
            // microphone-type service from the background. Tell the rider rather than
            // silently retrying into one-way audio.
            Log.e(TAG, "startForeground failed", t)
            notifyServiceFailed()
            stopSelf()
        }
    }

    private fun updateNotification(status: CallStatus) {
        if (!started) return
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(status)) }
    }

    private fun buildNotification(status: CallStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun action(label: String, act: String, code: Int) = NotificationCompat.Action(
            0, label,
            PendingIntent.getService(
                this, code,
                Intent(this, IntercomService::class.java).apply { action = act },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

        val floorText = when (status.floor) {
            FloorState.Talking -> "You are talking"
            FloorState.Muted -> "${status.peerName ?: "Buddy"} is talking"
            FloorState.Idle -> status.statusText
        }

        val app = RiderIntercomApp.init(this)
        val settings = app.prefs.current

        val builder = NotificationCompat.Builder(this, RiderIntercomApp.CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (status.isConnected) "Intercom with ${status.peerName ?: "buddy"}"
                else if (status.isHost) "Hosting \u00b7 waiting for buddy"
                else "Rider Intercom"
            )
            .setContentText(floorText)
            .setSubText(
                "${settings.talkMode.label} \u00b7 noise ${settings.noiseSuppression.label}"
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Push-to-talk is only worth a notification slot when the rider is actually in
        // that mode; in an open call the button would do nothing they want.
        if (settings.talkMode == TalkMode.PushToTalk) {
            builder.addAction(
                action(if (status.pushToTalk) "Talking" else "Talk", ACTION_TOGGLE_PTT, 1)
            )
        }
        builder.addAction(action("Noise: ${settings.noiseSuppression.label}", ACTION_CYCLE_NS, 3))
        // Stop is always last and always present: it is the one the rider reaches for
        // with a glove on, and it must never move around between states.
        builder.addAction(action("Stop", ACTION_STOP, 2))
        return builder.build()
    }

    private fun notifyServiceFailed() {
        val open = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, RiderIntercomApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Intercom stopped")
            .setContentText("Open the app and tap Start to bring the call back")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(43, n) }
    }

    // ------------------------------------------------------------- wake locks

    /** The radio and the audio threads need the CPU awake for the whole ride. */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RiderIntercom::call",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    // ---------------------------------------------------------------- teardown

    private fun stopCall() {
        if (!started) { stopSelf(); return }
        started = false
        controller.stop()
        beacon?.stop()
        beacon = null
        buttons?.release()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        started = false
        buttons?.destroy()
        buttons = null
        beacon?.stop()
        beacon = null
        runCatching { controller.destroy() }
        releaseWakeLock()
        _instance.value = null
        scope.cancel()
        super.onDestroy()
    }
}
