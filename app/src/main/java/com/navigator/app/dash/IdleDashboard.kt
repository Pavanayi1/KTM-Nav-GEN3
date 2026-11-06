package com.navigator.app.dash

import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.logging.AppLogger
import com.navigator.app.notifications.NotificationRepository
import com.navigator.app.settings.AppSettings
import com.navigator.app.weather.WeatherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Idle-screen rotation for the bike's dash: while we're authenticated and NO
 * navigation is running, cycle useful info cards (clock, current track,
 * weather) through the dash's center text slots. The moment real guidance
 * appears the loop backs off instantly - navigation always owns the display.
 *
 * A "card" fully owns all five label slots plus the turn icon, so switching
 * cards can never leave a stale field from the previous one on screen. Card
 * layouts live here (companion builders) so the Developer screen can send the
 * exact same payloads for alignment testing.
 */
class IdleDashboard(
    private val service: BccuConnectionService,
    private val scope: CoroutineScope,
) {

    /** One full frame for the dash's center view. "" blanks a slot (visibility OFF). */
    data class Card(
        val label: String,
        val distance: String,
        val info: String,
        val road: String,
        val eta: String,
        val remaining: String,
        val icon: BccuProtocol.TurnIcon? = null,
    )

    private var cardIndex = 0
    private var showing = false

    /**
     * One instance, not one per tick. This loop runs every 1.5s for the whole
     * life of the service, and `AppSettings(...)` opens an
     * EncryptedSharedPreferences behind a Keystore round-trip - rebuilding it
     * each iteration was doing that work continuously, even on a fresh install
     * where the idle dashboard is switched off.
     */
    private val settings by lazy { AppSettings(service) }

    fun start() {
        scope.launch {
            while (isActive) {
                val active = settings.idleDashEnabled &&
                    BccuConnectionService.connectionState.value == BccuConnectionService.ConnectionState.AUTHENTICATED &&
                    NotificationRepository.navGuidance.value == null
                if (!active) {
                    if (showing) {
                        showing = false
                        // Only blank the dash if navigation didn't already take it
                        // over - nav's own writes must not race a clear.
                        if (NotificationRepository.navGuidance.value == null) service.clearAllDashText()
                        AppLogger.log("IdleDash", "Idle rotation stopped")
                    }
                    delay(IDLE_POLL_MS)
                    continue
                }
                if (!showing) {
                    // Let the connect greeting finish before the first card.
                    delay(GREETING_GRACE_MS)
                    showing = true
                    AppLogger.log("IdleDash", "Idle rotation started")
                    continue
                }
                // Keep the weather cache warm while the card is enabled (no-op
                // when fresh; provider handles its own TTL and locking).
                if (settings.idleShowWeather) launch { WeatherProvider.refresh(service) }

                val cards = buildList {
                    if (settings.idleShowClock) add(clockCard())
                    if (settings.idleShowMusic) NotificationRepository.currentTrack.value?.let {
                        add(musicCard(it, service.trackRemainingSec()))
                    }
                    if (settings.idleShowWeather) WeatherProvider.cached()?.let { add(weatherCard(it)) }
                }
                if (cards.isEmpty()) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                val card = cards[cardIndex % cards.size]
                cardIndex++
                service.sendDashCard(card)
                delay(settings.idleRotateSec * 1000L)
            }
        }
    }

    companion object {
        private const val IDLE_POLL_MS = 1500L
        /** The connect greeting shows for 6 s (GREETING_MS) - don't stomp it. */
        private const val GREETING_GRACE_MS = 7000L

        // Hardware finding (R36 field test): the dash renders the center text
        // slots ONLY while a turn icon is visible - no icon, no center view
        // (the bottom banner is independent). So every card must carry a real,
        // calibrated-WORKS icon; UNDEFINED/OFF hides the whole view.

        fun clockCard(now: Date = Date()): Card {
            val time = SimpleDateFormat("HH:mm", Locale.US).format(now)
            val date = SimpleDateFormat("EEE d MMM", Locale.US).format(now)
            return Card(
                label = "CLOCK", distance = time, info = "", road = date, eta = time, remaining = "",
                icon = BccuProtocol.TurnIcon.PASS_STATION,
            )
        }

        /** Split MediaTrackMonitor's "Title by Artist" string. */
        fun splitTrack(track: String): Pair<String, String> {
            val sep = track.lastIndexOf(" by ")
            val title = if (sep > 0) track.substring(0, sep) else track
            val artist = if (sep > 0) track.substring(sep + 4) else ""
            return title to artist
        }

        /** [remainingSec] = time left in the track, shown big instead of a static "MUSIC" label. */
        fun musicCard(track: String, remainingSec: Long? = null): Card {
            val (title, artist) = splitTrack(track)
            val big = remainingSec?.let { "-%d:%02d".format(it / 60, it % 60) } ?: "MUSIC"
            return Card(
                label = "MUSIC", distance = big, info = artist, road = title, eta = "", remaining = "",
                icon = BccuProtocol.TurnIcon.START,
            )
        }

        // Temperature lives on the road line, NOT the big distance line: the
        // dash auto-appends a small "km" unit there ("24Ckm", R37 photos).
        fun weatherCard(w: WeatherProvider.WeatherInfo): Card = Card(
            label = "WEATHER",
            distance = "",
            info = "Wind ${w.windKmh} kmh",
            road = "${w.tempC}C ${w.condition} fl ${w.feelsC}C",
            eta = "",
            remaining = "",
            icon = BccuProtocol.TurnIcon.HEAD_TO,
        )
    }
}
