package com.navigator.app.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory queue of captured notifications, shared between
 * AppNotificationListener (the writer) and the UI / dash-writer (the
 * readers). A plain singleton object is enough here - no persistence needed,
 * this is transient "what's currently pending" state.
 */
object NotificationRepository {

    private const val MAX_ENTRIES = 30

    private val _entries = MutableStateFlow<List<NotificationEntry>>(emptyList())
    val entries: StateFlow<List<NotificationEntry>> = _entries

    /** Text of the most recent notification from whichever app is currently "the nav app". */
    private val _currentNavText = MutableStateFlow<String?>(null)
    val currentNavText: StateFlow<String?> = _currentNavText

    /** Package name of the app auto-detected (or manually overridden) as the nav app. */
    private val _currentNavPackage = MutableStateFlow<String?>(null)
    val currentNavPackage: StateFlow<String?> = _currentNavPackage

    /** Structured turn-by-turn guidance (the same fields mirrored to the dash), for the Direction screen. */
    data class NavGuidance(
        val distance: String?,
        val road: String?,
        val eta: String?,
        val remaining: String?,
        val maneuver: String?,
        /** The classified maneuver icon (same one sent to the dash), for the in-app arrow. */
        val turnIcon: com.navigator.app.ble.BccuProtocol.TurnIcon? = null,
    )

    private val _navGuidance = MutableStateFlow<NavGuidance?>(null)
    val navGuidance: StateFlow<NavGuidance?> = _navGuidance

    /** Currently-playing track ("Title by Artist"), fed by MediaTrackMonitor via the service. */
    private val _currentTrack = MutableStateFlow<String?>(null)
    val currentTrack: StateFlow<String?> = _currentTrack

    fun updateTrack(track: String) {
        _currentTrack.value = track
    }

    /**
     * When the last turn-by-turn update landed, on the monotonic clock. The
     * connection service's idle shutdown reads this: a rider mid-route is
     * plainly still riding, even if the bike link hasn't come up yet, and
     * killing the service under them is how the dash goes blank for the rest
     * of the trip.
     */
    @Volatile
    var lastNavGuidanceElapsedMs: Long = 0L
        private set

    fun updateNavGuidance(guidance: NavGuidance) {
        _navGuidance.value = guidance
        lastNavGuidanceElapsedMs = android.os.SystemClock.elapsedRealtime()
    }

    fun addEntry(entry: NotificationEntry) {
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        if (entry.isNavigation) {
            _currentNavPackage.value = entry.packageName
            _currentNavText.value = entry.text
        }
    }

    fun updateNavText(packageName: String, text: String) {
        _currentNavPackage.value = packageName
        _currentNavText.value = text
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** Reset navigation state when guidance ends (nav app notification removed). */
    fun clearNav() {
        _currentNavText.value = null
        _currentNavPackage.value = null
        _navGuidance.value = null
        lastNavGuidanceElapsedMs = 0L
    }
}
