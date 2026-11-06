package com.navigator.app.audio

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only snapshot of whatever media session is currently active, for the
 * Music Player screen. Written by [MediaTrackMonitor] (which already watches
 * MediaSessionManager for the dash track banner) on every metadata/playback
 * change; playback commands themselves go through [MediaControlBridge]
 * separately, same split as NotificationRepository (writer)/UI (reader).
 */
object NowPlayingRepository {

    data class NowPlaying(
        val title: String,
        val artist: String,
        val albumArt: Bitmap?,
        val isPlaying: Boolean,
        /** Position in ms as of [positionUpdateRealtimeMs] - the caller extrapolates while playing. */
        val positionMs: Long,
        val durationMs: Long,
        val positionUpdateRealtimeMs: Long,
        val playbackSpeed: Float,
        val appLabel: String,
    )

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state

    fun update(value: NowPlaying?) {
        _state.value = value
    }
}
