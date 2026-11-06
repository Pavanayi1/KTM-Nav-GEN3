package com.mensa.riderintercom.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bluetooth Classic RFCOMM link between two phones.
 *
 * This class carries the whole list of known RFCOMM failure modes and their fixes:
 * discovery is cancelled before every dial, the socket is recreated rather than reused
 * after a failure, the insecure socket is tried when the secure one fails SDP lookup,
 * the undocumented reflection channel is the last attempt only, and the server socket is
 * always closed so the channel is not still held on the next run.
 */
class RfcommLink(
    private val adapter: BluetoothAdapter,
    private val role: LinkRole,
    private val peerDevice: BluetoothDevice?,
    private val localHello: Hello,
) : PeerLink {

    override val transport = Transport.Bluetooth

    companion object {
        private const val TAG = "RfcommLink"

        /** One hard-coded UUID; both sides must agree or connect() hangs then fails. */
        val APP_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        private const val SERVICE_NAME = "RiderIntercom"

        private const val PING_INTERVAL_MS = 1000L
        private const val MISSED_PINGS_LOST = 3
        private const val HANDSHAKE_TIMEOUT_MS = 8000L

        /** Give the host's SDP record time to register before the client dials. */
        private const val SDP_SETTLE_MS = 500L

        private val BACKOFF_MS = longArrayOf(1000, 2000, 4000, 8000)
    }

    private val io = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "rfcomm-io").apply { isDaemon = true }
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

    private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null

    /** Bounded so a stalled radio drops old audio instead of growing without limit. */
    private val outbox = Channel<ByteArray>(capacity = 64)

    private val seq = AtomicInteger(0)
    @Volatile private var running = false
    @Volatile private var lastInboundAtMs = 0L
    private var mainJob: Job? = null

    private fun nextSeq() = seq.incrementAndGet() and 0xFFFF

    // ---------------------------------------------------------------- lifecycle

    override fun start() {
        if (running) return
        running = true
        mainJob = scope.launch { supervise() }
    }

    override fun close() {
        if (!running) return
        running = false
        runCatching { send(Codec.encode(Protocol.TYPE_END, nextSeq(), 0, null)) }
        closeSockets()
        mainJob?.cancel()
        scope.cancel()
        outbox.close()
        io.close()
        _state.value = LinkState.Closed
        _status.value = "Call ended"
    }

    /**
     * Frames the radio could not accept. The outbox is bounded on purpose — a stalled
     * link should drop old audio rather than grow without limit — but a silent drop is
     * indistinguishable from a radio loss at the far end, so it is counted here.
     */
    @Volatile var droppedSends: Long = 0L
        private set

    override fun send(message: ByteArray): Boolean {
        if (!running) return false
        val ok = outbox.trySend(message).isSuccess
        if (!ok) droppedSends++
        return ok
    }

    // ------------------------------------------------------------ connect loop

    /**
     * Owns the whole session: connect, handshake, pump, and reconnect with backoff.
     * The session outlives any single socket, which is what makes a tunnel or a traffic
     * light cost nothing instead of a manual restart.
     */
    private suspend fun supervise() {
        var attempt = 0
        while (running) {
            val s = openSocket(attempt)
            if (s == null) {
                attempt++
                if (!running) break
                val wait = BACKOFF_MS[(attempt - 1).coerceIn(0, BACKOFF_MS.size - 1)]
                _state.value = LinkState.Reconnecting
                _status.value = "Retrying in ${wait / 1000}s (attempt $attempt)"
                delay(wait)
                continue
            }

            socket = s
            output = s.outputStream

            _state.value = LinkState.Handshaking
            _status.value = "Handshaking"
            val peer = handshake(s)
            if (peer == null) {
                closeSocketOnly()
                attempt++
                if (running) delay(1000)
                continue
            }

            _peerHello.value = peer
            attempt = 0
            lastInboundAtMs = System.currentTimeMillis()
            _state.value = LinkState.Connected
            _status.value = "Connected to ${peer.riderName.ifBlank { "buddy" }}"

            try {
                pump(s)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "session error: ${t.message}")
                _status.value = "Link error: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                closeSocketOnly()
                _rtt.value = -1
            }

            if (running) _state.value = LinkState.Reconnecting
        }
    }

    private suspend fun openSocket(attempt: Int): BluetoothSocket? = when (role) {
        LinkRole.Host -> acceptOnce()
        LinkRole.Client -> connectOnce(attempt)
    }

    @SuppressLint("MissingPermission")
    private suspend fun acceptOnce(): BluetoothSocket? = withContext(io) {
        try {
            _state.value = LinkState.Listening
            _status.value = "Waiting for buddy"
            // A server socket left open from a previous session still holds the RFCOMM
            // channel, which is why "works once, fails on second run" happens.
            closeServerSocket()
            val ss = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
            serverSocket = ss
            val s = ss.accept()
            closeServerSocket()
            Log.i(TAG, "accepted ${s.remoteDevice?.address}")
            s
        } catch (t: Throwable) {
            Log.w(TAG, "accept failed: ${t.message}")
            _status.value = "Listen failed: ${t.message ?: "unknown"}"
            closeServerSocket()
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectOnce(attempt: Int): BluetoothSocket? = withContext(io) {
        val device = peerDevice ?: return@withContext null
        _state.value = LinkState.Connecting
        _status.value = "Connecting to ${deviceLabel(device)}"

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            _status.value = "Not paired - pair in Bluetooth settings first"
            return@withContext null
        }

        // Discovery is a heavyweight radio operation; leaving it running is the single
        // biggest cause of "read failed, socket might closed or timeout, read ret: -1".
        runCatching { if (adapter.isDiscovering) adapter.cancelDiscovery() }
        if (attempt == 0) delay(SDP_SETTLE_MS)

        // Attempt 1: the documented secure socket.
        tryConnect("secure") { device.createRfcommSocketToServiceRecord(APP_UUID) }?.let { return@withContext it }
        delay(300)
        // Attempt 2: insecure, which skips SDP on some stacks.
        tryConnect("insecure") { device.createInsecureRfcommSocketToServiceRecord(APP_UUID) }?.let { return@withContext it }
        delay(300)
        // Attempt 3: the undocumented reflection channel. Last resort only.
        tryConnect("reflection") {
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            m.invoke(device, 1) as BluetoothSocket
        }
    }

    // connect() sits inside catch (Throwable), so a revoked grant surfaces as a
    // logged failed attempt rather than a crash - same as every caller here.
    @SuppressLint("MissingPermission")
    private fun tryConnect(label: String, factory: () -> BluetoothSocket): BluetoothSocket? {
        var s: BluetoothSocket? = null
        return try {
            s = factory()
            s.connect()
            Log.i(TAG, "connected via $label")
            s
        } catch (t: Throwable) {
            Log.w(TAG, "$label connect failed: ${t.message}")
            // Always discard the socket: a failed BluetoothSocket is not reusable.
            runCatching { s?.close() }
            _status.value = "$label attempt failed: ${t.message ?: "unknown"}"
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceLabel(d: BluetoothDevice): String =
        runCatching { d.name }.getOrNull() ?: d.address

    // -------------------------------------------------------------- handshake

    private suspend fun handshake(s: BluetoothSocket): Hello? = withContext(io) {
        try {
            s.outputStream.write(
                Codec.encode(Protocol.TYPE_HELLO, nextSeq(), 0, localHello.toBytes())
            )
            s.outputStream.flush()

            val reader = FrameReader(s.inputStream)
            val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val m = reader.next() ?: return@withContext null
                if (m.type != Protocol.TYPE_HELLO) continue
                val peer = Hello.parse(m.payload) ?: return@withContext null
                if (peer.version != Protocol.VERSION) {
                    _status.value =
                        "Version mismatch: buddy has v${peer.version}, this phone has v${Protocol.VERSION}"
                    return@withContext null
                }
                if (peer.sampleRate != localHello.sampleRate ||
                    peer.frameSamples != localHello.frameSamples
                ) {
                    _status.value = "Audio format mismatch with buddy"
                    return@withContext null
                }
                handshakeReader = reader
                return@withContext peer
            }
            _status.value = "Handshake timed out"
            null
        } catch (t: Throwable) {
            Log.w(TAG, "handshake failed: ${t.message}")
            _status.value = "Handshake failed: ${t.message ?: "unknown"}"
            null
        }
    }

    /** The reader that already consumed HELLO; reused so no bytes are lost. */
    private var handshakeReader: FrameReader? = null

    // ------------------------------------------------------------------- pump

    /** Runs reader, writer and keep-alive until one of them ends the session. */
    private suspend fun pump(s: BluetoothSocket) {
        val reader = handshakeReader ?: FrameReader(s.inputStream)
        handshakeReader = null

        val readerJob = scope.launch { readLoop(reader) }
        val writerJob = scope.launch { writeLoop() }
        val pingJob = scope.launch { pingLoop() }

        try {
            readerJob.join()
        } finally {
            writerJob.cancel()
            pingJob.cancel()
            readerJob.cancel()
        }
    }

    private suspend fun readLoop(reader: FrameReader) {
        try {
            while (scope.isActive && running) {
                val m = reader.next() ?: break
                lastInboundAtMs = System.currentTimeMillis()
                when (m.type) {
                    Protocol.TYPE_PING -> {
                        // Echo the origin stamp plus our own, so the sender can work out
                        // both round-trip time and the clock offset between the phones.
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
                        // Peer clock as seen locally, corrected for half the round trip.
                        clockOffsetMicros = peerTs + rtt / 2 - now
                    }
                    Protocol.TYPE_END -> {
                        _status.value = "Buddy ended the call"
                        onMessage?.invoke(m)
                        return
                    }
                    else -> onMessage?.invoke(m)
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "read loop ended: ${t.message}")
        }
        _status.value = "Link lost"
    }

    private suspend fun writeLoop() {
        try {
            for (bytes in outbox) {
                val out = output ?: break
                out.write(bytes)
                out.flush()
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "write loop ended: ${t.message}")
        }
    }

    /**
     * One ping a second. Voice frames also refresh [lastInboundAtMs], so in conversation
     * the pings cost nothing; in silence they are the only traffic on the radio.
     */
    private suspend fun pingLoop() {
        while (scope.isActive && running) {
            delay(PING_INTERVAL_MS)
            send(Codec.encodeLong(Protocol.TYPE_PING, nextSeq(), nowMicros()))
            val silentMs = System.currentTimeMillis() - lastInboundAtMs
            if (silentMs > PING_INTERVAL_MS * MISSED_PINGS_LOST) {
                _status.value = "No reply for ${silentMs / 1000}s - reconnecting"
                closeSocketOnly()
                return
            }
        }
    }

    // ---------------------------------------------------------------- teardown

    private fun closeServerSocket() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun closeSocketOnly() {
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    private fun closeSockets() {
        closeServerSocket()
        closeSocketOnly()
    }

    private fun nowMicros(): Long = System.nanoTime() / 1000L

    private fun writeLong(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) dst[off + i] = ((v ushr (56 - i * 8)) and 0xFF).toByte()
    }
}

/**
 * Pulls framed messages out of a byte stream, resynchronising on the SYNC byte when
 * the stream is corrupted or picked up mid-message.
 */
class FrameReader(private val input: InputStream) {

    private val header = ByteArray(Protocol.HEADER_SIZE)

    /** Blocks until a whole message is available. Returns null at end of stream. */
    @Throws(IOException::class)
    fun next(): Message? {
        while (true) {
            // Resynchronise: discard bytes until a SYNC marker.
            var b = input.read()
            if (b < 0) return null
            while (b.toByte() != Protocol.SYNC) {
                b = input.read()
                if (b < 0) return null
            }
            header[0] = Protocol.SYNC
            if (!readFully(header, 1, Protocol.HEADER_SIZE - 1)) return null

            val type = header[1]
            val seq = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            val flags = header[4]
            val len = ((header[5].toInt() and 0xFF) shl 8) or (header[6].toInt() and 0xFF)

            if (len > Protocol.MAX_PAYLOAD) continue // bad framing, resync

            val payload = ByteArray(len)
            if (len > 0 && !readFully(payload, 0, len)) return null
            return Message(type, seq, flags, payload)
        }
    }

    @Throws(IOException::class)
    private fun readFully(dst: ByteArray, offset: Int, count: Int): Boolean {
        var read = 0
        while (read < count) {
            val n = input.read(dst, offset + read, count - read)
            if (n < 0) return false
            read += n
        }
        return true
    }
}
