package com.navigator.app.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.navigator.app.logging.AppLogger

/**
 * Detects incoming-call ringing so the controller state machine can route
 * Set/Back to answer/reject, and answers calls via TelecomManager.
 *
 * Important platform limitation, stated honestly rather than silently
 * failing: Android does NOT let a normal third-party app end/reject an
 * in-progress call (TelecomManager.endCall() requires the app to be the
 * default dialer, which is a much larger scope than this app takes on).
 * "Reject" here is a best-effort fallback that only silences the ringer -
 * it does not actually terminate the call the way a real dialer's decline
 * button would.
 */
@SuppressLint("MissingPermission") // permission checked by caller before starting monitoring
class CallStateMonitor(
    private val context: Context,
    private val onRingingChanged: (Boolean) -> Unit,
    /** Fired when a call becomes ACTIVE (offhook) - the audio-routing hook. */
    private val onCallActive: () -> Unit = {},
) {
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private var lastState = TelephonyManager.CALL_STATE_IDLE

    @Suppress("DEPRECATION")
    private val listener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            val ringing = state == TelephonyManager.CALL_STATE_RINGING
            AppLogger.log("Call", "State changed: $state (ringing=$ringing)")
            onRingingChanged(ringing)
            if (state == TelephonyManager.CALL_STATE_OFFHOOK && lastState != TelephonyManager.CALL_STATE_OFFHOOK) {
                onCallActive()
            }
            lastState = state
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    @Suppress("DEPRECATION")
    fun stop() {
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
    }

    fun answer() {
        AppLogger.log("Call", "Answering via TelecomManager.acceptRingingCall()")
        val telecomManager = context.getSystemService(TelecomManager::class.java) ?: return
        telecomManager.acceptRingingCall()
    }

    /**
     * Best-effort only - see class doc. Silences the currently-ringing call
     * rather than truly rejecting it.
     *
     * Uses [TelecomManager.silenceRinger], which is scoped to the *current*
     * ringing call and resets itself for the next call. The previous
     * implementation used `AudioManager.setStreamMute(STREAM_RING, true)`, which
     * is sticky: it muted the ring stream permanently, so every subsequent call
     * also rang silently until the process restarted. Falls back to a temporary
     * (auto-unmuting) stream adjust only if Telecom is unavailable.
     */
    fun silenceRinger() {
        AppLogger.log("Call", "Silencing ringer (true call reject is not possible for a non-default-dialer app)")
        val telecomManager = context.getSystemService(TelecomManager::class.java)
        if (telecomManager != null) {
            try {
                telecomManager.silenceRinger()
                return
            } catch (e: SecurityException) {
                AppLogger.log("Call", "silenceRinger() denied (${e.message}); falling back to stream adjust")
            }
        }
        // ADJUST_MUTE (unlike the old setStreamMute) is not sticky across calls.
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
    }
}
