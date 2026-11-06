package com.mensa.riderintercom.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import com.mensa.riderintercom.protocol.Codec
import com.mensa.riderintercom.protocol.Hello
import com.mensa.riderintercom.protocol.Message
import com.mensa.riderintercom.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wi-Fi Direct transport: 50 to 100 m line of sight against Bluetooth's 10 to 30 m.
 *
 * Two field-tested details shape this class.
 *
 * **One phone always makes the group.** Letting both ends negotiate group ownership is
 * what produces the "Invited" state that never resolves. The host calls [createGroup] and
 * is therefore always the group owner, at the fixed address [GROUP_OWNER_IP]; the joiner
 * only ever connects to it.
 *
 * **Discovery is not continuous on most phones.** `discoverPeers()` is re-issued on a
 * timer until the group is up, rather than being called once and trusted.
 *
 * Voice goes over UDP and control over TCP. Losing a voice frame costs 20 ms, which the
 * jitter buffer already conceals; losing a floor message would leave a rider muted, so
 * those are worth the retransmit.
 *
 * NOTE: this has been built and unit-tested where it can be, but the Wi-Fi Direct
 * handshake itself needs two phones in the same place and has not been exercised on
 * hardware. Treat the first road test of Range mode as the real bring-up.
 */
class WifiDirectLink(
    private val context: Context,
    private val role: LinkRole,
    private val localHello: Hello,
) : PeerLink {

    override val transport = Transport.WifiDirect

    companion object {
        private const val TAG = "WifiDirectLink"

        /** The group owner's address is fixed by the framework. */
        const val GROUP_OWNER_IP = "192.168.49.1"
        const val CONTROL_PORT = 8988
        const val VOICE_PORT = 8989

        private const val DISCOVER_INTERVAL_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val PING_INTERVAL_MS = 1000L
        private const val MISSED_PINGS_LOST = 3
        private const val HANDSHAKE_TIMEOUT_MS = 8000L

        /** The selector gives up on Wi-Fi Direct if no group forms inside this. */
        const val GROUP_FORM_TIMEOUT_MS = 30_000L
    }

    private val io = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "wfd-io").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _state = MutableStateFlow(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _rtt = MutableStateFlow(-1)
    override val rttMillis: StateFlow<Int> = _rtt.asStateFlow()

    private val _peerHello = MutableStateFlow<Hello?>(null)
    override val peerHello: StateFlow<Hello?> = _peerHello.asStateFlow()

    private val _status = MutableStateFlow("Idle")
    override val status: StateFlow<String> = _status.asStateFlow()

    @Volatile override var clockOffsetMicros: Long = 0L
        private set

    private var onMessage: ((Message) -> Unit)? = null
    override fun setOnMessage(handler: (Message) -> Unit) { onMessage = handler }

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private var serverSocket: ServerSocket? = null
    @Volatile private var control: Socket? = null
    @Volatile private var controlOut: OutputStream? = null
    @Volatile private var voice: DatagramSocket? = null
    @Volatile private var peerAddress: InetAddress? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private val outbox = Channel<ByteArray>(capacity = 96)
    private val seq = AtomicInteger(0)
    @Volatile private var running = false
    @Volatile private var lastInboundAtMs = 0L
    @Volatile private var groupFormed = false
    private var mainJob: Job? = null
    private var receiver: BroadcastReceiver? = null

    @Volatile var droppedSends: Long = 0L
        private set

    private fun nextSeq() = seq.incrementAndGet() and 0xFFFF

    // ---------------------------------------------------------------- lifecycle

    @SuppressLint("MissingPermission")
    override fun start() {
        if (running) return
        val mgr = manager
        if (mgr == null) {
            _state.value = LinkState.Failed
            _status.value = "This phone has no Wi-Fi Direct"
            return
        }
        running = true
        channel = mgr.initialize(context, Looper.getMainLooper(), null)
        acquireWifiLock()
        registerReceiver()
        mainJob = scope.launch { supervise() }
    }

    override fun close() {
        if (!running) return
        running = false
        runCatching { send(Codec.encode(Protocol.TYPE_END, nextSeq(), 0, null)) }
        unregisterReceiver()
        tearDownGroup()
        closeSockets()
        releaseWifiLock()
        mainJob?.cancel()
        scope.cancel()
        outbox.close()
        io.close()
        _state.value = LinkState.Closed
        _status.value = "Wi-Fi Direct closed"
    }

    override fun send(message: ByteArray): Boolean {
        if (!running) return false
        val ok = outbox.trySend(message).isSuccess
        if (!ok) droppedSends++
        return ok
    }

    // ------------------------------------------------------------- group setup

    @SuppressLint("MissingPermission")
    private suspend fun supervise() {
        while (running) {
            try {
                _state.value = LinkState.Connecting
                _status.value = if (role == LinkRole.Host) "Creating Wi-Fi group"
                else "Looking for the group"

                val ok = if (role == LinkRole.Host) createGroup() else joinGroup()
                if (!ok) {
                    _status.value = "Wi-Fi Direct could not form a group"
                    _state.value = LinkState.Failed
                    return
                }

                openSockets()

                _state.value = LinkState.Handshaking
                val peer = handshake() ?: run {
                    closeSockets()
                    delay(1500)
                    return@run null
                }
                if (peer == null) continue

                _peerHello.value = peer
                lastInboundAtMs = System.currentTimeMillis()
                _state.value = LinkState.Connected
                _status.value = "Wi-Fi Direct to ${peer.riderName.ifBlank { "buddy" }}"

                pump()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "wifi direct session error: ${t.message}")
                _status.value = "Wi-Fi Direct error: ${t.message ?: "unknown"}"
            } finally {
                closeSockets()
                _rtt.value = -1
            }
            if (running) {
                _state.value = LinkState.Reconnecting
                delay(2000)
            }
        }
    }

    /**
     * The host becomes an autonomous group owner. Doing this explicitly, rather than
     * letting the two phones negotiate, is what avoids the stuck "Invited" state.
     */
    @SuppressLint("MissingPermission")
    private suspend fun createGroup(): Boolean = withContext(io) {
        val mgr = manager ?: return@withContext false
        val ch = channel ?: return@withContext false
        groupFormed = false

        // Remove any group left behind by a previous run, or createGroup fails as BUSY.
        runCatching { mgr.removeGroup(ch, null) }
        delay(600)

        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.i(TAG, "createGroup accepted") }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "createGroup failed: $reason")
                _status.value = "Could not create the Wi-Fi group (code $reason)"
            }
        })

        waitForGroup()
    }

    /** The joiner discovers and connects; it never tries to become the owner. */
    @SuppressLint("MissingPermission")
    private suspend fun joinGroup(): Boolean = withContext(io) {
        val mgr = manager ?: return@withContext false
        val ch = channel ?: return@withContext false
        groupFormed = false

        val deadline = System.currentTimeMillis() + GROUP_FORM_TIMEOUT_MS
        while (running && System.currentTimeMillis() < deadline && !groupFormed) {
            // Discovery stops on its own on most phones, so re-issue it on a timer.
            mgr.discoverPeers(ch, null)
            val found = awaitPeer()
            if (found != null) {
                val config = WifiP2pConfig().apply {
                    deviceAddress = found
                    // Never contest ownership: the host already made the group.
                    groupOwnerIntent = 0
                }
                mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { Log.i(TAG, "connect accepted") }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "connect failed: $reason")
                    }
                })
            }
            withTimeoutOrNullMs(DISCOVER_INTERVAL_MS) { while (!groupFormed && running) delay(200) }
        }
        groupFormed
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitPeer(): String? = withContext(io) {
        val mgr = manager ?: return@withContext null
        val ch = channel ?: return@withContext null
        var address: String? = null
        mgr.requestPeers(ch) { peers ->
            // The host advertises itself as a group owner, so prefer one of those.
            address = peers.deviceList.firstOrNull { it.isGroupOwner }?.deviceAddress
                ?: peers.deviceList.firstOrNull()?.deviceAddress
        }
        delay(1200)
        address
    }

    private suspend fun waitForGroup(): Boolean {
        val deadline = System.currentTimeMillis() + GROUP_FORM_TIMEOUT_MS
        while (running && System.currentTimeMillis() < deadline) {
            if (groupFormed) return true
            delay(250)
        }
        return groupFormed
    }

    private suspend fun <T> withTimeoutOrNullMs(ms: Long, block: suspend () -> T): T? =
        kotlinx.coroutines.withTimeoutOrNull(ms) { block() }

    @SuppressLint("MissingPermission")
    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val mgr = manager
                        val ch = channel
                        if (mgr != null && ch != null) {
                            mgr.requestConnectionInfo(ch) { info ->
                                if (info != null && info.groupFormed) {
                                    groupFormed = true
                                    peerAddress = info.groupOwnerAddress
                                    Log.i(
                                        TAG,
                                        "group formed, owner=${info.isGroupOwner} " +
                                            "ownerAddr=${info.groupOwnerAddress?.hostAddress}",
                                    )
                                } else {
                                    groupFormed = false
                                }
                            }
                        }
                    }
                }
            }
        }
        receiver = r
        context.registerReceiver(r, filter)
    }

    private fun unregisterReceiver() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    @SuppressLint("MissingPermission")
    private fun tearDownGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching { mgr.removeGroup(ch, null) }
        groupFormed = false
    }

    // ---------------------------------------------------------------- sockets

    private suspend fun openSockets() = withContext(io) {
        closeSockets()
        val v = DatagramSocket(if (role == LinkRole.Host) VOICE_PORT else 0)
        v.soTimeout = 1000
        voice = v

        if (role == LinkRole.Host) {
            val ss = ServerSocket(CONTROL_PORT)
            ss.soTimeout = GROUP_FORM_TIMEOUT_MS.toInt()
            serverSocket = ss
            val s = ss.accept()
            s.tcpNoDelay = true
            control = s
            controlOut = s.outputStream
            peerAddress = s.inetAddress
            runCatching { ss.close() }
            serverSocket = null
        } else {
            val s = Socket()
            s.connect(InetSocketAddress(GROUP_OWNER_IP, CONTROL_PORT), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            control = s
            controlOut = s.outputStream
            peerAddress = InetAddress.getByName(GROUP_OWNER_IP)
        }
    }

    private fun closeSockets() {
        runCatching { serverSocket?.close() }
        runCatching { control?.close() }
        runCatching { voice?.close() }
        serverSocket = null
        control = null
        controlOut = null
        voice = null
    }

    // -------------------------------------------------------------- handshake

    private suspend fun handshake(): Hello? = withContext(io) {
        try {
            val out = controlOut ?: return@withContext null
            out.write(Codec.encode(Protocol.TYPE_HELLO, nextSeq(), 0, localHello.toBytes()))
            out.flush()

            val reader = FrameReader(control?.inputStream ?: return@withContext null)
            val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val m = reader.next() ?: return@withContext null
                if (m.type != Protocol.TYPE_HELLO) continue
                val peer = Hello.parse(m.payload) ?: return@withContext null
                if (peer.version != Protocol.VERSION) {
                    _status.value = "Version mismatch over Wi-Fi Direct"
                    return@withContext null
                }
                handshakeReader = reader
                return@withContext peer
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "wfd handshake failed: ${t.message}")
            null
        }
    }

    private var handshakeReader: FrameReader? = null

    // ------------------------------------------------------------------- pump

    private suspend fun pump() {
        val reader = handshakeReader ?: FrameReader(control?.inputStream ?: return)
        handshakeReader = null

        val controlReader = scope.launch { controlLoop(reader) }
        val voiceReader = scope.launch { voiceLoop() }
        val writer = scope.launch { writeLoop() }
        val pinger = scope.launch { pingLoop() }

        try {
            controlReader.join()
        } finally {
            voiceReader.cancel()
            writer.cancel()
            pinger.cancel()
            controlReader.cancel()
        }
    }

    private suspend fun controlLoop(reader: FrameReader) {
        try {
            while (scope.isActive && running) {
                val m = reader.next() ?: break
                lastInboundAtMs = System.currentTimeMillis()
                handleMessage(m)
                if (m.type == Protocol.TYPE_END) return
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "control loop ended: ${t.message}")
        }
        _status.value = "Wi-Fi Direct link lost"
    }

    /** Voice arrives as datagrams: a dropped frame is cheaper than a retransmitted one. */
    private suspend fun voiceLoop() = withContext(io) {
        val buf = ByteArray(2048)
        while (scope.isActive && running) {
            val v = voice ?: break
            try {
                val packet = DatagramPacket(buf, buf.size)
                v.receive(packet)
                lastInboundAtMs = System.currentTimeMillis()
                val reader = FrameReader(
                    java.io.ByteArrayInputStream(packet.data, 0, packet.length)
                )
                var m = reader.next()
                while (m != null) {
                    handleMessage(m)
                    m = reader.next()
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Expected; the loop just checks for cancellation and goes round again.
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "voice loop: ${t.message}")
                break
            }
        }
    }

    private fun handleMessage(m: Message) {
        when (m.type) {
            Protocol.TYPE_PING -> {
                val origin = Codec.readLong(m.payload)
                val now = nowMicros()
                val body = ByteArray(16)
                writeLong(body, 0, origin)
                writeLong(body, 8, now)
                send(Codec.encode(Protocol.TYPE_PONG, nextSeq(), 0, body))
            }
            Protocol.TYPE_PONG -> {
                val origin = Codec.readLong(m.payload, 0)
                val peerTs = Codec.readLong(m.payload, 8)
                val now = nowMicros()
                val rtt = now - origin
                _rtt.value = (rtt / 1000).toInt()
                clockOffsetMicros = peerTs + rtt / 2 - now
            }
            else -> onMessage?.invoke(m)
        }
    }

    private suspend fun writeLoop() {
        try {
            for (bytes in outbox) {
                val type = if (bytes.size > 1) bytes[1] else 0
                if (type == Protocol.TYPE_AUDIO) {
                    // Voice over UDP: late is worse than lost.
                    val v = voice ?: break
                    val addr = peerAddress ?: break
                    runCatching {
                        v.send(DatagramPacket(bytes, bytes.size, addr, VOICE_PORT))
                    }
                } else {
                    val out = controlOut ?: break
                    out.write(bytes)
                    out.flush()
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "write loop ended: ${t.message}")
        }
    }

    private suspend fun pingLoop() {
        while (scope.isActive && running) {
            delay(PING_INTERVAL_MS)
            send(Codec.encodeLong(Protocol.TYPE_PING, nextSeq(), nowMicros()))
            val silent = System.currentTimeMillis() - lastInboundAtMs
            if (silent > PING_INTERVAL_MS * MISSED_PINGS_LOST) {
                _status.value = "No reply over Wi-Fi Direct for ${silent / 1000}s"
                closeSockets()
                return
            }
        }
    }

    // --------------------------------------------------------------- wifi lock

    /**
     * Without this Android drifts back to a saved infrastructure network and drops the
     * P2P group, which looks to the rider like a random mid-ride disconnect.
     */
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return
        wifiLock = wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
            "RiderIntercom::wfd",
        ).apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private fun nowMicros(): Long = System.nanoTime() / 1000L

    private fun writeLong(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) dst[off + i] = ((v ushr (56 - i * 8)) and 0xFF).toByte()
    }
}
