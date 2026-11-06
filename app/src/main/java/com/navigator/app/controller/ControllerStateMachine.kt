package com.navigator.app.controller

import com.navigator.app.ble.BccuProtocol.HandlebarButton
import com.navigator.app.ble.BccuProtocol.HandlebarButton.BACK
import com.navigator.app.ble.BccuProtocol.HandlebarButton.DOWN
import com.navigator.app.ble.BccuProtocol.HandlebarButton.SET
import com.navigator.app.ble.BccuProtocol.HandlebarButton.UP
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ControllerScreen { IDLE, GRID, NOTIFICATIONS, DIRECTION, MUSIC }

/**
 * Pure Kotlin (no Android dependencies) state machine resolving the
 * handlebar remote's four buttons into app behavior. Deliberately
 * framework-free so it's easy to unit test; all real actions (media
 * control, call handling, foregrounding the app) go through the Actions
 * callback interface so the Android-specific implementation lives
 * elsewhere (MainActivity / BccuConnectionService).
 *
 * UI-observable state (screen, grid selection, notification scroll index) is
 * published through [state] as a [StateFlow] so Compose recomposes only when
 * something actually changes. This replaced an earlier design where the
 * Activity polled these fields every 150 ms and force-recomposed the whole
 * tree on every tick - that poll was the main cause of the visible UI stutter.
 *
 * Control scheme: call-ringing overrides everything; IDLE binds Up/Down/Set to
 * media next/previous/play-pause and Back opens the grid menu (this also
 * covers "return to our app while another app like Maps is in front", since
 * the BLE connection - and therefore button events - keeps flowing to our
 * service regardless of which app is foreground); GRID and submenus use
 * Up/Down to navigate, Set to select, Back to go up a level. The inactivity
 * watchdog only runs while GRID/NOTIFICATIONS/DIRECTION are open.
 */
class ControllerStateMachine(private val actions: Actions) {

    interface Actions {
        fun playPauseMedia()
        fun nextTrack()
        fun previousTrack()
        fun answerCall()
        fun rejectCall()
        fun minimizeToHome()
        fun openMaps()
        fun bringAppToForeground()
        fun showWatchdogWarning()
        /** Open the Rides list (recorded routes + analytics). */
        fun openRides()
        /** Fully quit: stop the connection service (removes its notification) and kill the app task. */
        fun exitApp()
        /** Show the handlebar remote's mode picker (Controller / Music / Intercom / Bike off). */
        fun showRemoteModePicker()
        /** Open Rider Intercom, to start or join a call with the pillion. */
        fun openIntercom()
    }

    /** Immutable snapshot of everything the UI renders from. */
    data class UiState(
        val screen: ControllerScreen = ControllerScreen.IDLE,
        val gridSelection: Int = 0,
        val notificationScrollIndex: Int = 0,
    )

    // Order matches the home grid's visual order exactly - the remote walks
    // this list, so a mismatch would move the focus ring to the wrong tile.
    // Joined to GridMenuScreen's `tiles` by POSITION ONLY - the screen draws
    // them, this list decides what each index does and is what the handlebar
    // D-pad cycles. Add a tile to one and not the other and every tile after
    // it silently opens the wrong screen.
    val gridItems = listOf("Notification", "Direction", "Music", "Intercom", "Rides", "Remote", "Home", "Exit")

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    // Convenience accessors so existing callers keep compiling; all reads go
    // through the single source of truth in _state.
    val screen: ControllerScreen get() = _state.value.screen
    val gridSelection: Int get() = _state.value.gridSelection
    val notificationScrollIndex: Int get() = _state.value.notificationScrollIndex

    var isCallRinging: Boolean = false

    private var lastInputAtMs: Long = 0L
    private var warningShown = false

    companion object {
        const val WATCHDOG_WARNING_MS = 10_000L
        const val WATCHDOG_EXIT_MS = 15_000L
    }

    private inline fun update(block: (UiState) -> UiState) {
        _state.value = block(_state.value)
    }

    fun onButton(button: HandlebarButton, nowMs: Long, notificationCount: Int = 0) {
        lastInputAtMs = nowMs
        warningShown = false

        if (isCallRinging) {
            when (button) {
                SET -> actions.answerCall()
                BACK -> actions.rejectCall()
                else -> {}
            }
            return
        }

        when (screen) {
            ControllerScreen.IDLE -> when (button) {
                SET -> actions.playPauseMedia()
                UP -> actions.nextTrack()
                DOWN -> actions.previousTrack()
                BACK -> {
                    update { it.copy(screen = ControllerScreen.GRID, gridSelection = 0) }
                    actions.bringAppToForeground()
                }
            }

            ControllerScreen.GRID -> when (button) {
                UP -> update { it.copy(gridSelection = (it.gridSelection - 1 + gridItems.size) % gridItems.size) }
                DOWN -> update { it.copy(gridSelection = (it.gridSelection + 1) % gridItems.size) }
                SET -> selectGridItem()
                BACK -> update { it.copy(screen = ControllerScreen.IDLE) }
            }

            ControllerScreen.NOTIFICATIONS -> when (button) {
                UP -> update { if (it.notificationScrollIndex > 0) it.copy(notificationScrollIndex = it.notificationScrollIndex - 1) else it }
                DOWN -> update { if (it.notificationScrollIndex < notificationCount - 1) it.copy(notificationScrollIndex = it.notificationScrollIndex + 1) else it }
                BACK -> update { it.copy(screen = ControllerScreen.GRID, notificationScrollIndex = 0) }
                SET -> {}
            }

            ControllerScreen.DIRECTION -> when (button) {
                SET -> actions.openMaps()
                BACK -> update { it.copy(screen = ControllerScreen.GRID) }
                UP, DOWN -> {}
            }

            ControllerScreen.MUSIC -> when (button) {
                SET -> actions.playPauseMedia()
                UP -> actions.nextTrack()
                DOWN -> actions.previousTrack()
                BACK -> update { it.copy(screen = ControllerScreen.GRID) }
            }
        }
    }

    /**
     * Touch equivalent of the handlebar BACK button. Returns true if it moved
     * within the app (so the system back press is consumed); false when already
     * at the grid/idle top level (letting Android minimize the app as usual).
     */
    fun touchBack(): Boolean = when (screen) {
        ControllerScreen.NOTIFICATIONS, ControllerScreen.DIRECTION, ControllerScreen.MUSIC -> {
            update { it.copy(screen = ControllerScreen.GRID, notificationScrollIndex = 0) }
            true
        }
        else -> false
    }

    /**
     * Touch equivalent of moving the handlebar selection to [index] and pressing
     * SET - lets the on-screen grid tiles feed the exact same pipeline as the
     * physical remote, so behavior stays identical whether tapped or clicked.
     */
    fun touchSelectGrid(index: Int, nowMs: Long) {
        if (isCallRinging) return
        lastInputAtMs = nowMs
        warningShown = false
        if (screen != ControllerScreen.GRID) update { it.copy(screen = ControllerScreen.GRID) }
        if (index in gridItems.indices) {
            update { it.copy(gridSelection = index) }
            selectGridItem()
        }
    }

    private fun selectGridItem() {
        when (gridItems[gridSelection]) {
            "Notification" -> update { it.copy(screen = ControllerScreen.NOTIFICATIONS) }
            "Direction" -> update { it.copy(screen = ControllerScreen.DIRECTION) }
            "Music" -> update { it.copy(screen = ControllerScreen.MUSIC) }
            "Remote" -> {
                actions.showRemoteModePicker()
                update { it.copy(screen = ControllerScreen.IDLE) }
            }
            "Intercom" -> {
                actions.openIntercom()
                update { it.copy(screen = ControllerScreen.IDLE) }
            }
            "Rides" -> {
                actions.openRides()
                update { it.copy(screen = ControllerScreen.IDLE) }
            }
            "Home" -> {
                actions.minimizeToHome()
                update { it.copy(screen = ControllerScreen.IDLE) }
            }
            "Exit" -> {
                update { it.copy(screen = ControllerScreen.IDLE) }
                actions.exitApp()
            }
        }
    }

    /** Call periodically (e.g. every second) with the current time to drive the inactivity watchdog. */
    fun tick(nowMs: Long) {
        if (screen == ControllerScreen.IDLE) return
        val elapsed = nowMs - lastInputAtMs
        if (!warningShown && elapsed >= WATCHDOG_WARNING_MS) {
            warningShown = true
            actions.showWatchdogWarning()
        }
        if (elapsed >= WATCHDOG_EXIT_MS) {
            update { it.copy(screen = ControllerScreen.IDLE) }
        }
    }

    /** Force back to IDLE, e.g. when the BLE connection to the bike drops. */
    fun reset() {
        warningShown = false
        _state.value = UiState()
    }
}
