package com.mensa.riderintercom.transport

import com.mensa.riderintercom.protocol.Hello
import com.mensa.riderintercom.protocol.Message
import kotlinx.coroutines.flow.StateFlow

/** Lifecycle of one link to one peer. */
enum class LinkState {
    Idle,
    Listening,
    Connecting,
    Handshaking,
    Connected,
    Reconnecting,
    Failed,
    Closed,
}

/** How this phone takes part in the link. */
enum class LinkRole { Host, Client }

/**
 * The radios a session can run on, in the order the selector prefers them.
 *
 * Ordinals go on the wire in a SWITCH message, so do not reorder them without bumping
 * the protocol version.
 */
enum class Transport(val label: String) {
    /** Bluetooth Classic RFCOMM. 10-30 m, needs the phones bonded. Phase-1 default. */
    Bluetooth("Bluetooth"),

    /** Wi-Fi Direct. 50-100 m line of sight, no pairing, more battery. */
    WifiDirect("Wi-Fi Direct"),
    ;

    companion object {
        fun fromOrdinal(i: Int): Transport? = entries.getOrNull(i)
    }
}

/**
 * The single seam between the audio pipeline and the radio.
 *
 * The pipeline never learns which transport is in use: it sends and receives framed
 * [Message]s and watches [state]. RFCOMM is the phase-1 implementation; Wi-Fi Direct
 * and Nearby Connections slot in behind the same interface.
 */
interface PeerLink {
    /** Which radio this link runs on. */
    val transport: Transport

    val state: StateFlow<LinkState>

    /** Round-trip time in milliseconds, or -1 before the first pong. */
    val rttMillis: StateFlow<Int>

    /** Peer's handshake, available once [state] reaches [LinkState.Connected]. */
    val peerHello: StateFlow<Hello?>

    /** Human-readable reason for the current state, for the UI and the log. */
    val status: StateFlow<String>

    /** Opens the link and keeps it open, reconnecting on its own, until [close]. */
    fun start()

    /** Queues one message. Returns false when the link is not writable. */
    fun send(message: ByteArray): Boolean

    /** Called on the link's reader thread for every decoded message. */
    fun setOnMessage(handler: (Message) -> Unit)

    /** Sends END and tears everything down. */
    fun close()

    /**
     * Local-clock offset to the peer in microseconds, so that
     * `peerTimestamp - offset` is comparable with the local monotonic clock.
     */
    val clockOffsetMicros: Long
}
