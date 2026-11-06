package com.mensa.riderintercom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.mensa.riderintercom.util.Prefs

/**
 * The intercom's process-wide state: settings and notification channels.
 *
 * In the standalone app this was the Application subclass, and the service and
 * activity reached it with `application as RiderIntercomApp`. Merged into
 * Navigator there is only one Application - NavigatorApplication - so that cast
 * would throw ClassCastException the instant a call started. It is an object
 * now, initialised lazily from whichever Context first asks.
 *
 * [init] is idempotent and returns the object, so the old call sites read
 * `val app = RiderIntercomApp.init(this)` and every `app.prefs` after them is
 * unchanged.
 */
object RiderIntercomApp {

    // Namespaced: these now live in Navigator's channel list beside its own
    // ("bccu_connection", "bccu_alerts"), where a bare "call"/"alert" is vague.
    const val CHANNEL_CALL = "intercom_call"
    const val CHANNEL_ALERT = "intercom_alert"

    @Volatile private var prefsInstance: Prefs? = null

    val prefs: Prefs
        get() = prefsInstance
            ?: error("RiderIntercomApp.init(context) must be called before prefs is read")

    fun init(context: Context): RiderIntercomApp {
        if (prefsInstance == null) {
            synchronized(this) {
                if (prefsInstance == null) {
                    val app = context.applicationContext
                    createChannels(app)
                    prefsInstance = Prefs(app)
                }
            }
        }
        return this
    }

    private fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL,
                "Intercom call",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "The persistent notification that keeps the call alive"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "Intercom alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Told when the call stopped and needs restarting from the app"
            }
        )
    }
}
