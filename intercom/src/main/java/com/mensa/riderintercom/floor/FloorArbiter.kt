package com.mensa.riderintercom.floor

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Who holds the channel right now, from this phone's point of view. */
enum class FloorState {
    /** Nobody is talking. The mic is open to the detector but not to the encoder. */
    Idle,

    /** This rider holds the floor and their frames are going out. */
    Talking,

    /** Someone else holds the floor. This mic is closed to the encoder. */
    Muted,
}

/** What the arbiter wants the rest of the system to do. */
interface FloorActions {
    fun sendFloorTaken(startedAtMicros: Long)
    fun sendFloorRelease()
    fun sendFloorDenied()
    /** Play the local "you are muted" tone. Never sent to the other riders. */
    fun beep()
    fun onStateChanged(state: FloorState)
}

/**
 * First speaker wins.
 *
 * A rider's detector opening starts the stream immediately and announces it; it does not
 * wait for a grant, because a round trip on this link is 30-80 ms and waiting for one
 * clips the first word. The two announcements are then reconciled by timestamp, which is
 * why both phones keep a clock offset to each other from the ping exchange.
 *
 * All three states keep capture running so the detector can fire. Only [FloorState.Talking]
 * feeds frames to the encoder.
 */
class FloorArbiter(
    private val localSessionId: Long,
    private val actions: FloorActions,
) {
    companion object {
        private const val TAG = "FloorArbiter"

        /** A stuck detector cannot hold the channel for longer than this. */
        const val MAX_HOLD_MS = 30_000L

        /** No audio from the holder for this long means their link dropped mid-sentence. */
        const val HOLDER_STALE_MS = 2_000L

        /** Two opens inside this window are a tie, resolved on timestamp. */
        const val TIE_WINDOW_MICROS = 300_000L

        /** Closer than this and the timestamps are not meaningfully different. */
        const val TIE_DEAD_HEAT_MICROS = 20_000L
    }

    private val _state = MutableStateFlow(FloorState.Idle)
    val state: StateFlow<FloorState> = _state.asStateFlow()

    private val _holderName = MutableStateFlow<String?>(null)
    val holderName: StateFlow<String?> = _holderName.asStateFlow()

    /** Local monotonic microseconds at which this rider's current turn began. */
    private var localTakenAt = 0L
    private var holderSince = 0L
    private var lastHolderAudioAt = 0L
    private var remoteSessionId = 0L

    /** Push-to-talk overrides the detector entirely while held. */
    @Volatile var pushToTalk = false
        private set

    /**
     * Current link round-trip time in milliseconds, or -1. The clock offset the
     * timestamps are compared through is only accurate to about half of this, so the
     * tie-break log records it: a delta smaller than RTT/2 was not really measurable.
     */
    @Volatile var rttMillisProvider: () -> Int = { -1 }

    private fun now() = System.nanoTime() / 1000L
    private fun nowMs() = System.currentTimeMillis()

    private fun moveTo(s: FloorState) {
        if (_state.value == s) return
        _state.value = s
        actions.onStateChanged(s)
        Log.i(TAG, "floor -> $s")
    }

    // ------------------------------------------------------------ local events

    /** The local detector opened. Returns true when this phone may now stream. */
    @Synchronized
    fun onVadOpen(speechStartMicros: Long): Boolean = when (_state.value) {
        FloorState.Idle -> {
            localTakenAt = speechStartMicros
            holderSince = nowMs()
            _holderName.value = null
            moveTo(FloorState.Talking)
            // Stream first, announce in the same breath. Waiting for a grant clips words.
            actions.sendFloorTaken(speechStartMicros)
            true
        }
        FloorState.Talking -> true
        FloorState.Muted -> {
            // The rider tried to talk over the holder: tell them locally, send nothing.
            actions.beep()
            false
        }
    }

    /** The local detector closed after its release window. */
    @Synchronized
    fun onVadClose() {
        if (pushToTalk) return
        if (_state.value == FloorState.Talking) {
            actions.sendFloorRelease()
            moveTo(FloorState.Idle)
            _holderName.value = null
        }
    }

    /** Headset button or on-screen push-to-talk. Forces the floor while held. */
    @Synchronized
    fun setPushToTalk(down: Boolean, peerName: String?) {
        pushToTalk = down
        if (down) {
            val t = now()
            localTakenAt = t
            holderSince = nowMs()
            _holderName.value = null
            moveTo(FloorState.Talking)
            actions.sendFloorTaken(t)
        } else if (_state.value == FloorState.Talking) {
            actions.sendFloorRelease()
            moveTo(FloorState.Idle)
        }
    }

    // ----------------------------------------------------------- remote events

    /**
     * The buddy announced a turn. [remoteStartInLocalClock] is their speech-start stamp
     * already converted into this phone's clock using the link's measured offset.
     */
    @Synchronized
    fun onRemoteFloorTaken(remoteStartInLocalClock: Long, remoteSession: Long, name: String?) {
        remoteSessionId = remoteSession
        lastHolderAudioAt = nowMs()

        when (_state.value) {
            FloorState.Idle, FloorState.Muted -> {
                holderSince = nowMs()
                _holderName.value = name
                moveTo(FloorState.Muted)
            }
            FloorState.Talking -> {
                if (pushToTalk) {
                    // A deliberate press beats a detector opening; hold the floor.
                    actions.sendFloorDenied()
                    return
                }
                val delta = localTakenAt - remoteStartInLocalClock
                val deadHeat = delta >= -TIE_DEAD_HEAT_MICROS && delta <= TIE_DEAD_HEAT_MICROS
                val localWins = when {
                    delta < -TIE_DEAD_HEAT_MICROS -> true   // local started clearly earlier
                    delta > TIE_DEAD_HEAT_MICROS -> false   // remote started clearly earlier
                    else -> localSessionId < remoteSession  // dead heat: lower id wins
                }
                // Name the rule that decided. "lost by 6731us" reads as though the
                // timestamps settled it when a delta that small is inside the dead-heat
                // window and the session ID actually did.
                val rule = if (deadHeat) {
                    "dead heat within ${TIE_DEAD_HEAT_MICROS}us, decided by session id " +
                        "(local=$localSessionId remote=$remoteSession)"
                } else {
                    "timestamps differ by ${delta}us"
                }
                val rtt = rttMillisProvider()
                Log.i(
                    TAG,
                    "tie-break ${if (localWins) "won" else "lost"}: $rule; " +
                        "delta=${delta}us rtt=${rtt}ms " +
                        "(clock offset is only good to about ${if (rtt < 0) -1 else rtt * 500}us)",
                )
                if (localWins) {
                    actions.sendFloorDenied()
                } else {
                    // Stop streaming at once; the buddy discards whatever already arrived.
                    _holderName.value = name
                    holderSince = nowMs()
                    moveTo(FloorState.Muted)
                }
            }
        }
    }

    @Synchronized
    fun onRemoteFloorRelease() {
        if (_state.value == FloorState.Muted) {
            _holderName.value = null
            moveTo(FloorState.Idle)
        }
    }

    /** The buddy won the tie-break and told this phone to stop. */
    @Synchronized
    fun onRemoteFloorDenied(name: String?) {
        if (_state.value == FloorState.Talking && !pushToTalk) {
            _holderName.value = name
            holderSince = nowMs()
            moveTo(FloorState.Muted)
        }
    }

    /** Any audio frame from the holder refreshes the watchdog. */
    @Synchronized
    fun onRemoteAudio() {
        lastHolderAudioAt = nowMs()
    }

    /** The link went away. Nobody holds the floor any more. */
    @Synchronized
    fun onLinkLost() {
        _holderName.value = null
        if (!pushToTalk) moveTo(FloorState.Idle)
    }

    // --------------------------------------------------------------- watchdogs

    /**
     * Called about once a second. Covers the two ways the floor can get stuck: a detector
     * that never closes, and a holder whose link dropped mid-sentence.
     */
    @Synchronized
    fun tick() {
        val now = nowMs()
        when (_state.value) {
            FloorState.Talking -> {
                if (!pushToTalk && now - holderSince > MAX_HOLD_MS) {
                    Log.w(TAG, "floor held ${MAX_HOLD_MS}ms, forcing release")
                    actions.sendFloorRelease()
                    moveTo(FloorState.Idle)
                }
            }
            FloorState.Muted -> {
                if (now - lastHolderAudioAt > HOLDER_STALE_MS) {
                    Log.w(TAG, "no audio from holder for ${HOLDER_STALE_MS}ms, releasing")
                    _holderName.value = null
                    moveTo(FloorState.Idle)
                }
            }
            FloorState.Idle -> Unit
        }
    }

    @Synchronized
    fun reset() {
        pushToTalk = false
        _holderName.value = null
        moveTo(FloorState.Idle)
    }
}
