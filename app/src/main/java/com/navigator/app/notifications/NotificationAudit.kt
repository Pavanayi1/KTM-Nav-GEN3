package com.navigator.app.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Dev-mode audit trail: every notification the listener saw this session and
 * what was decided about it (mirrored to the dash, treated as nav guidance,
 * or skipped - and why). In-memory only, capped, and recorded ONLY while
 * developer mode is on: the listener runs for every notification on the
 * phone, and message text has no business being retained during normal use.
 *
 * Read from the Developer screen's "Notification audit" card.
 */
object NotificationAudit {

    enum class Verdict {
        /** Turn-by-turn guidance - drove the dash center view. */
        NAV,
        /** Mirrored to the dash's bottom banner. */
        MIRRORED,
        /** Kept for the in-app feed only (mirroring toggled off). */
        CAPTURED,
        /** Seen and deliberately not used - [Event.reason] says why. */
        SKIPPED,
    }

    data class Event(
        val timeMs: Long,
        val packageName: String,
        val title: String,
        val text: String,
        val category: String?,
        val verdict: Verdict,
        val reason: String,
    )

    private const val MAX_EVENTS = 200

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events

    /**
     * Newest first. A nav app reposts its notification every second or two for
     * the whole ride, so a NAV event from the same package REPLACES the
     * previous head entry instead of prepending - the audit shows "latest
     * guidance from Maps", not two hundred copies of it.
     */
    fun record(event: Event) {
        val current = _events.value
        val head = current.firstOrNull()
        _events.value =
            if (event.verdict == Verdict.NAV && head?.verdict == Verdict.NAV &&
                head.packageName == event.packageName
            ) {
                listOf(event) + current.drop(1)
            } else {
                (listOf(event) + current).take(MAX_EVENTS)
            }
    }

    fun clear() {
        _events.value = emptyList()
    }
}
