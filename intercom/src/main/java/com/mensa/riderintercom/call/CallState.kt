package com.mensa.riderintercom.call

import com.mensa.riderintercom.floor.FloorState
import com.mensa.riderintercom.transport.LinkState

/** The session state machine the service and the UI both read. */
enum class CallPhase {
    Idle,
    Starting,
    Connecting,
    Active,
    Reconnecting,
    Ended,
    Error,
}

/** Everything the call screen draws, in one immutable snapshot. */
data class CallStatus(
    val phase: CallPhase = CallPhase.Idle,
    val link: LinkState = LinkState.Idle,
    val floor: FloorState = FloorState.Idle,
    val statusText: String = "Not connected",
    val peerName: String? = null,
    val isHost: Boolean = false,
    val rttMs: Int = -1,
    val lossPercent: Double = 0.0,
    val jitterDepth: Int = 0,
    val pushToTalk: Boolean = false,
    val muted: Boolean = false,
    val focusHeld: Boolean = false,
    val elapsedSeconds: Long = 0,
    val error: String? = null,
) {
    val isConnected: Boolean get() = phase == CallPhase.Active
    val canTalk: Boolean get() = isConnected && floor == FloorState.Talking
}
