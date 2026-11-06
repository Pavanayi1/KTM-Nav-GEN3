package com.mensa.riderintercom.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.mensa.riderintercom.protocol.Codec
import com.mensa.riderintercom.protocol.Hello
import com.mensa.riderintercom.protocol.Message
import com.mensa.riderintercom.protocol.Protocol
import com.mensa.riderintercom.util.TransportPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps one session alive across changes of radio, with no rider intervention.
 *
 * The audio pipeline talks to this and never learns which transport is underneath: it is
 * a [PeerLink] that happens to own other [PeerLink]s and swap them.
 *
 * **Both phones have to move together**, which is the part that is easy to get wrong. Two
 * mechanisms cover the two ways a link goes bad:
 *
 *  - *The link still works but is poor* (loss above 5% for ten seconds). The side that
 *    decides sends [Protocol.TYPE_SWITCH] naming the target, waits briefly so the message
 *    gets out, then moves. The peer receives it and moves to the same transport.
 *  - *The link is already dead.* No message can get through, so each side falls back on
 *    its own timer: after [SwitchPolicy] decides it has been unreachable long enough,
 *    both independently try the other radio. They converge because the order is fixed.
 *
 * The session ID in [Hello] is unchanged across a switch, so the far side treats the new
 * connection as the same call rather than a fresh one.
 */
class LinkSelector(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val role: LinkRole,
    private val peerDevice: BluetoothDevice?,
    private val localHello: Hello,
    preference: TransportPreference,
) : PeerLink {

    companion object {
        private const val TAG = "LinkSelector"
        private const val EVALUATE_EVERY_MS = 1000L
        /** Long enough for a SWITCH to leave the outbox before the socket closes. */
        private const val SWITCH_GRACE_MS = 400L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val policy = SwitchPolicy(preference)

    @Volatile private var active: PeerLink? = null
    @Volatile private var current: Transport = policy.initialTransport()

    override val transport: Transport get() = current

    private val _state = MutableStateFlow(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _rtt = MutableStateFlow(-1)
    override val rttMillis: StateFlow<Int> = _rtt.asStateFlow()

    private val _peerHello = MutableStateFlow<Hello?>(null)
    override val peerHello: StateFlow<Hello?> = _peerHello.asStateFlow()

    private val _status = MutableStateFlow("Idle")
    override val status: StateFlow<String> = _status.asStateFlow()

    /** The radio in use, for the UI. */
    private val _activeTransport = MutableStateFlow(current)
    val activeTransport: StateFlow<Transport> = _activeTransport.asStateFlow()

    /** Set when a switch is under way, so the UI can say so rather than look broken. */
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    override val clockOffsetMicros: Long get() = active?.clockOffsetMicros ?: 0L

    private var onMessage: ((Message) -> Unit)? = null
    override fun setOnMessage(handler: (Message) -> Unit) { onMessage = handler }

    /** Fed from the jitter buffer each tick; the loss rule reads it. */
    @Volatile var lossPercent: Double = 0.0

    /** Frames the active radio would not accept, whichever radio that is. */
    val droppedSends: Long
        get() = when (val l = active) {
            is RfcommLink -> l.droppedSends
            is WifiDirectLink -> l.droppedSends
            else -> 0L
        }

    @Volatile private var running = false
    private var watchdog: Job? = null
    private var mirrorJobs = mutableListOf<Job>()
    @Volatile private var switchInProgress = false

    // ---------------------------------------------------------------- lifecycle

    override fun start() {
        if (running) return
        running = true
        openTransport(current, announce = false)
        watchdog = scope.launch { watch() }
    }

    override fun close() {
        running = false
        watchdog?.cancel()
        stopMirrors()
        active?.close()
        active = null
        scope.cancel()
        _state.value = LinkState.Closed
        _status.value = "Call ended"
    }

    override fun send(message: ByteArray): Boolean = active?.send(message) ?: false

    // ----------------------------------------------------------------- swapping

    private fun openTransport(target: Transport, announce: Boolean) {
        stopMirrors()
        active?.close()

        val link: PeerLink = when (target) {
            Transport.Bluetooth -> RfcommLink(adapter, role, peerDevice, localHello)
            Transport.WifiDirect -> WifiDirectLink(context, role, localHello)
        }
        active = link
        current = target
        _activeTransport.value = target
        policy.onTransportStarted(now(), target)

        link.setOnMessage { m ->
            // A SWITCH from the buddy is an instruction to follow them, not data for the
            // audio pipeline, so it is consumed here.
            if (m.type == Protocol.TYPE_SWITCH) {
                val want = Transport.fromOrdinal(m.payload.firstOrNull()?.toInt() ?: -1)
                if (want != null && want != current && !switchInProgress) {
                    Log.i(TAG, "buddy asked to move to $want")
                    _status.value = "Buddy moved to ${want.label}"
                    scope.launch { performSwitch(want, SwitchReason.Preference, tell = false) }
                }
            } else {
                onMessage?.invoke(m)
            }
        }

        mirror(link)
        link.start()
        Log.i(TAG, "transport now $target")
    }

    /** Republishes the active link's flows as our own, so callers see one stream. */
    private fun mirror(link: PeerLink) {
        mirrorJobs += scope.launch {
            link.state.collect { st ->
                _state.value = st
                if (st == LinkState.Connected) policy.onConnected(now())
                else policy.onDisconnected(now())
            }
        }
        mirrorJobs += scope.launch { link.status.collect { _status.value = it } }
        mirrorJobs += scope.launch { link.rttMillis.collect { _rtt.value = it } }
        mirrorJobs += scope.launch {
            link.peerHello.collect { if (it != null) _peerHello.value = it }
        }
    }

    private fun stopMirrors() {
        mirrorJobs.forEach { it.cancel() }
        mirrorJobs.clear()
    }

    // ---------------------------------------------------------------- watchdog

    private suspend fun watch() {
        while (scope.isActive && running) {
            delay(EVALUATE_EVERY_MS)
            if (switchInProgress) continue
            val link = active ?: continue
            val connected = link.state.value == LinkState.Connected
            val decision = policy.evaluate(now(), current, connected, lossPercent)
            if (decision != null) {
                Log.i(TAG, "switching to ${decision.target} because ${decision.reason}")
                performSwitch(decision.target, decision.reason, tell = connected)
            }
        }
    }

    /**
     * @param tell whether to send SWITCH first. Only worth it while the link still
     *   works; on a dead link the peer's own timer brings it across instead.
     */
    private suspend fun performSwitch(target: Transport, reason: SwitchReason, tell: Boolean) {
        if (switchInProgress) return
        switchInProgress = true
        _switching.value = true
        try {
            _status.value = "Moving to ${target.label} - ${reason.explain}"
            if (tell) {
                active?.send(
                    Codec.encode(
                        Protocol.TYPE_SWITCH, 0, 0, byteArrayOf(target.ordinal.toByte()),
                    )
                )
                delay(SWITCH_GRACE_MS)
            }
            openTransport(target, announce = false)
            policy.onSwitched(now(), target)
        } finally {
            switchInProgress = false
            _switching.value = false
        }
    }

    /** The rider asking for the other radio from the UI. */
    fun switchNow(target: Transport) {
        if (!running || target == current) return
        scope.launch { performSwitch(target, SwitchReason.Preference, tell = true) }
    }

    private fun now() = System.currentTimeMillis()
}
