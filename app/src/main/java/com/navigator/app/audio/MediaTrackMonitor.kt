package com.navigator.app.audio

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.navigator.app.logging.AppLogger
import com.navigator.app.notifications.AppNotificationListener

/**
 * Watches whatever app currently holds a media session and reports the track
 * whenever it changes ("Title by Artist"). Uses the same MediaSessionManager +
 * notification-listener component the media buttons already use, so it needs no
 * extra permission. The callback fires on real metadata changes only (deduped),
 * so the dash banner shows the song exactly once per track change.
 */
class MediaTrackMonitor(
    private val context: Context,
    private val onTrackChanged: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private var lastTrackKey: String? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            hookControllers(controllers ?: emptyList())
        }

    fun start() {
        val mgr = context.getSystemService(MediaSessionManager::class.java) ?: return
        manager = mgr
        val component = ComponentName(context, AppNotificationListener::class.java)
        try {
            mgr.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            hookControllers(mgr.getActiveSessions(component))
            AppLogger.log("Music", "MediaTrackMonitor started")
        } catch (e: SecurityException) {
            AppLogger.log("Music", "MediaTrackMonitor: no notification-listener access yet")
        }
    }

    private fun hookControllers(controllers: List<MediaController>) {
        val current = controllers.toSet()
        // Drop callbacks for sessions that went away.
        val it = callbacks.iterator()
        while (it.hasNext()) {
            val (c, cb) = it.next()
            if (c !in current) {
                runCatching { c.unregisterCallback(cb) }
                it.remove()
            }
        }
        // Register for any new sessions, and read their current state now.
        for (c in controllers) {
            if (callbacks.containsKey(c)) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = emit(c, metadata, c.playbackState)
                override fun onPlaybackStateChanged(state: PlaybackState?) = emit(c, c.metadata, state)
            }
            runCatching { c.registerCallback(cb, handler) }
            callbacks[c] = cb
            emit(c, c.metadata, c.playbackState)
        }
        if (controllers.isEmpty()) NowPlayingRepository.update(null)
    }

    private fun emit(controller: MediaController, metadata: MediaMetadata?, state: PlaybackState?) {
        val md = metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim().orEmpty()

        // Full now-playing snapshot for the Music Player screen - published on
        // every metadata OR playback-state change so play/pause and seek bars
        // stay live, not just track changes.
        if (title.isNotBlank()) {
            val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val appLabel = runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(controller.packageName, 0)
                ).toString()
            }.getOrDefault(controller.packageName)
            NowPlayingRepository.update(
                NowPlayingRepository.NowPlaying(
                    title = title,
                    artist = artist,
                    albumArt = art,
                    isPlaying = state?.state == PlaybackState.STATE_PLAYING,
                    positionMs = state?.position ?: 0L,
                    durationMs = duration,
                    positionUpdateRealtimeMs = SystemClock.elapsedRealtime(),
                    playbackSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                    appLabel = appLabel,
                )
            )
        }

        // Dash track-banner text: fires once per genuine track change only.
        if (title.isBlank()) return
        val key = "$title|$artist"
        if (key == lastTrackKey) return
        lastTrackKey = key
        val display = if (artist.isNotBlank()) "$title by $artist" else title
        AppLogger.log("Music", "Track changed -> \"$display\"")
        onTrackChanged(display)
    }

    /**
     * Seconds left in the currently-PLAYING track, or null if nothing is
     * playing / the session doesn't report duration. Read on demand (idle
     * music card) - position comes from the session's PlaybackState.
     */
    fun nowPlayingRemainingSec(): Long? {
        for (c in callbacks.keys) {
            val state = runCatching { c.playbackState }.getOrNull() ?: continue
            if (state.state != android.media.session.PlaybackState.STATE_PLAYING) continue
            val duration = runCatching { c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) }.getOrNull() ?: continue
            if (duration <= 0) continue
            val remaining = (duration - state.position) / 1000
            return remaining.coerceAtLeast(0)
        }
        return null
    }

    fun stop() {
        callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        manager?.let { m -> runCatching { m.removeOnActiveSessionsChangedListener(sessionsListener) } }
        manager = null
        lastTrackKey = null
        NowPlayingRepository.update(null)
    }
}
