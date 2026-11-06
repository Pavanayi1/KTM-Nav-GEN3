package com.mensa.riderintercom.media

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Ducks the rider's music while someone is speaking, and gives it straight back.
 *
 * Music never enters this app. It reaches the headset through Android's own mixer from
 * whatever app is playing it; all this class does is ask the system to duck it, which on
 * Android 8 and later lowers the other app to roughly 20% and restores it when focus is
 * abandoned.
 *
 * Focus is not abandoned the instant the floor is released. A [RELEASE_HOLDOFF_MS]
 * hold-off stops the music pumping up and down between sentences.
 */
class FocusManager(
    private val audioManager: AudioManager,
    private val onFocusLost: (permanent: Boolean) -> Unit,
    private val onFocusRegained: () -> Unit,
) {
    companion object {
        private const val TAG = "FocusManager"
        const val RELEASE_HOLDOFF_MS = 700L

        /** Extra voice gain when the rider chose not to take focus at all. */
        const val NO_FOCUS_VOICE_BOOST_DB = 6.0
    }

    /**
     * Some music apps treat any focus loss as a pause rather than a duck. When the rider
     * turns this on the intercom takes no focus at all; the music then plays at full
     * volume under the voice, and the voice track is lifted by 6 dB to compensate.
     */
    @Volatile var takeFocus: Boolean = true

    private val handler = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null
    private var held = false
    private var abandonPending: Runnable? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "focus lost permanently")
                held = false
                onFocusLost(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // A real phone call or the assistant took the headset. Capture pauses,
                // the link stays up on pings, and the pipeline resumes on GAIN.
                Log.i(TAG, "focus lost transiently ($change)")
                onFocusLost(false)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "focus regained")
                onFocusRegained()
            }
        }
    }

    private fun buildRequest(): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(listener, handler)
            .build()

    /** Called when anyone starts talking, local or remote. */
    @Synchronized
    fun duck() {
        abandonPending?.let { handler.removeCallbacks(it); abandonPending = null }
        if (!takeFocus || held) return

        val req = request ?: buildRequest().also { request = it }
        val result = runCatching { audioManager.requestAudioFocus(req) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "duck requested, granted=$held")
    }

    /** Called when the floor is released. The hold-off runs before focus actually goes. */
    @Synchronized
    fun undock() {
        if (!held) return
        abandonPending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            synchronized(this) {
                val req = request
                if (held && req != null) {
                    runCatching { audioManager.abandonAudioFocusRequest(req) }
                    held = false
                    Log.i(TAG, "focus abandoned")
                }
                abandonPending = null
            }
        }
        abandonPending = r
        handler.postDelayed(r, RELEASE_HOLDOFF_MS)
    }

    /** Drops focus immediately, without the hold-off. Used when the call ends. */
    @Synchronized
    fun release() {
        abandonPending?.let { handler.removeCallbacks(it); abandonPending = null }
        val req = request
        if (held && req != null) {
            runCatching { audioManager.abandonAudioFocusRequest(req) }
        }
        held = false
    }

    val isHeld: Boolean get() = held
}
