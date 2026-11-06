package com.mensa.riderintercom.media

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent

/** Gestures the app can recognise on a headset's main button. */
enum class ButtonGesture { Single, Double, Triple, LongPress }

/**
 * Headset button handling.
 *
 * This is the least deterministic part of the system and the code says so. Since Android
 * 8 the system routes a media key to the last app with an active [MediaSessionCompat]
 * that played audio locally, and there is no reliable way to take it from a music app
 * that is playing: priority broadcast receivers stopped working at the same release.
 *
 * So the session is held only when the app actually wants the button - before a call
 * starts, and for a short window after each floor change - and handed back to the music
 * app the rest of the time. Headsets also differ in what they send, which is why the
 * app can report the raw key code for a calibration screen.
 */
class ButtonSession(
    context: Context,
    private val onGesture: (ButtonGesture) -> Unit,
    /** Raw key codes, for the settings calibration screen. */
    private val onRawKey: (Int) -> Unit = {},
) {
    companion object {
        private const val TAG = "ButtonSession"
        private const val MULTI_PRESS_WINDOW_MS = 500L
        private const val LONG_PRESS_MS = 1500L

        /** How long the app keeps the session after a floor change. */
        const val RECLAIM_WINDOW_MS = 2000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val session = MediaSessionCompat(context, "RiderIntercom")

    private var pressCount = 0
    private var downAt = 0L
    private var longFired = false
    private var pendingMulti: Runnable? = null
    private var pendingLong: Runnable? = null

    init {
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val event: KeyEvent? =
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                return event?.let { handleKey(it) } ?: false
            }

            override fun onPlay() { onGesture(ButtonGesture.Single) }
            override fun onPause() { onGesture(ButtonGesture.Single) }
            override fun onSkipToNext() { onGesture(ButtonGesture.Double) }
            override fun onSkipToPrevious() { onGesture(ButtonGesture.Triple) }
            override fun onStop() { onGesture(ButtonGesture.LongPress) }
        })
        setPlaybackState(PlaybackStateCompat.STATE_STOPPED)
    }

    val sessionToken: MediaSessionCompat.Token get() = session.sessionToken
    val mediaSession: MediaSessionCompat get() = session

    private fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        onRawKey(code)

        val interesting = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            code == KeyEvent.KEYCODE_HEADSETHOOK ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY ||
            code == KeyEvent.KEYCODE_MEDIA_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_STOP

        // Some headsets send a raw double press as NEXT rather than two PLAY_PAUSEs.
        if (code == KeyEvent.KEYCODE_MEDIA_NEXT) {
            if (event.action == KeyEvent.ACTION_DOWN) onGesture(ButtonGesture.Double)
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            if (event.action == KeyEvent.ACTION_DOWN) onGesture(ButtonGesture.Triple)
            return true
        }
        if (!interesting) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                downAt = SystemClock.uptimeMillis()
                longFired = false
                pendingLong?.let { handler.removeCallbacks(it) }
                val r = Runnable {
                    longFired = true
                    pressCount = 0
                    pendingMulti?.let { handler.removeCallbacks(it) }
                    Log.i(TAG, "gesture: long press")
                    onGesture(ButtonGesture.LongPress)
                }
                pendingLong = r
                handler.postDelayed(r, LONG_PRESS_MS)
            }
            KeyEvent.ACTION_UP -> {
                pendingLong?.let { handler.removeCallbacks(it) }
                if (longFired) { longFired = false; return true }

                pressCount++
                pendingMulti?.let { handler.removeCallbacks(it) }
                val r = Runnable {
                    val g = when (pressCount) {
                        1 -> ButtonGesture.Single
                        2 -> ButtonGesture.Double
                        else -> ButtonGesture.Triple
                    }
                    pressCount = 0
                    Log.i(TAG, "gesture: $g")
                    onGesture(g)
                }
                pendingMulti = r
                handler.postDelayed(r, MULTI_PRESS_WINDOW_MS)
            }
        }
        return true
    }

    /**
     * Takes the button. Publishing STATE_PLAYING is what makes this the session the
     * system routes media keys to.
     */
    fun claim() {
        session.isActive = true
        setPlaybackState(PlaybackStateCompat.STATE_PLAYING)
    }

    /** Hands the button back to the music app. */
    fun release() {
        setPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        session.isActive = false
    }

    /** Takes the button briefly after a floor change, then gives it back. */
    fun claimBriefly() {
        claim()
        handler.postDelayed({ release() }, RECLAIM_WINDOW_MS)
    }

    private fun setPlaybackState(state: Int) {
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )
    }

    fun destroy() {
        pendingLong?.let { handler.removeCallbacks(it) }
        pendingMulti?.let { handler.removeCallbacks(it) }
        runCatching { session.release() }
    }
}
