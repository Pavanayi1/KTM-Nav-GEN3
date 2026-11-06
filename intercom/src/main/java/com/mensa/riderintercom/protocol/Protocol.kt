package com.mensa.riderintercom.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Wire protocol version 1.
 *
 * RFCOMM is a byte stream, not a datagram service: writes coalesce and split.
 * Every message therefore carries its own frame header so the reader can resynchronise
 * after a partial read or a reconnect.
 *
 *   byte 0      SYNC (0xAA)
 *   byte 1      type
 *   bytes 2-3   sequence number, unsigned 16-bit big endian
 *   byte 4      flags
 *   bytes 5-6   payload length, unsigned 16-bit big endian
 *   bytes 7..   payload
 */
object Protocol {
    const val VERSION = 1
    const val SYNC: Byte = 0xAA.toByte()
    const val HEADER_SIZE = 7
    const val MAX_PAYLOAD = 512

    // Message types
    const val TYPE_AUDIO: Byte = 0x01
    const val TYPE_PING: Byte = 0x02
    const val TYPE_FLOOR_TAKEN: Byte = 0x03
    const val TYPE_FLOOR_RELEASE: Byte = 0x04
    const val TYPE_FLOOR_DENIED: Byte = 0x05
    const val TYPE_END: Byte = 0x06
    const val TYPE_PONG: Byte = 0x07

    /**
     * "I am moving to another radio; follow me."
     *
     * Payload is one byte of [Transport] ordinal. Without this the two phones would
     * each have to notice the degradation independently and happen to pick the same
     * transport, which they will not do at the same moment. The side that decides sends
     * SWITCH first and only then tears its link down.
     */
    const val TYPE_SWITCH: Byte = 0x08
    const val TYPE_HELLO: Byte = 0x10

    const val HELLO_SIZE = 32

    fun typeName(t: Byte): String = when (t) {
        TYPE_AUDIO -> "AUDIO"
        TYPE_PING -> "PING"
        TYPE_PONG -> "PONG"
        TYPE_SWITCH -> "SWITCH"
        TYPE_FLOOR_TAKEN -> "FLOOR_TAKEN"
        TYPE_FLOOR_RELEASE -> "FLOOR_RELEASE"
        TYPE_FLOOR_DENIED -> "FLOOR_DENIED"
        TYPE_END -> "END"
        TYPE_HELLO -> "HELLO"
        else -> "UNKNOWN(0x%02X)".format(t)
    }
}

/** One decoded message off the wire. [payload] is owned by the receiver. */
data class Message(
    val type: Byte,
    val seq: Int,
    val flags: Byte,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is Message && type == other.type && seq == other.seq &&
            flags == other.flags && payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        ((type.toInt() * 31 + seq) * 31 + flags) * 31 + payload.contentHashCode()

    override fun toString(): String =
        "Message(${Protocol.typeName(type)}, seq=$seq, ${payload.size}B)"
}

/** Serialises messages into the framed form described in [Protocol]. */
object Codec {

    fun encode(type: Byte, seq: Int, flags: Byte, payload: ByteArray?): ByteArray {
        val body = payload ?: ByteArray(0)
        require(body.size <= Protocol.MAX_PAYLOAD) { "payload too large: ${body.size}" }
        val out = ByteArray(Protocol.HEADER_SIZE + body.size)
        out[0] = Protocol.SYNC
        out[1] = type
        out[2] = ((seq ushr 8) and 0xFF).toByte()
        out[3] = (seq and 0xFF).toByte()
        out[4] = flags
        out[5] = ((body.size ushr 8) and 0xFF).toByte()
        out[6] = (body.size and 0xFF).toByte()
        System.arraycopy(body, 0, out, Protocol.HEADER_SIZE, body.size)
        return out
    }

    fun encodeLong(type: Byte, seq: Int, value: Long): ByteArray {
        val b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
        return encode(type, seq, 0, b)
    }

    fun readLong(payload: ByteArray, offset: Int = 0): Long =
        ByteBuffer.wrap(payload, offset, 8).order(ByteOrder.BIG_ENDIAN).long
}

/**
 * The 32-byte handshake. Both sides send it immediately after the socket opens, and
 * refuse the call on a protocol-version mismatch rather than playing garbled audio.
 *
 *   version(2) sampleRate(4) frameSamples(2) codec(1) sessionId(8) nameLen(1) name(14)
 */
data class Hello(
    val version: Int,
    val sampleRate: Int,
    val frameSamples: Int,
    val codec: Int,
    val sessionId: Long,
    val riderName: String,
) {
    fun toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(Protocol.HELLO_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(version.toShort())
        buf.putInt(sampleRate)
        buf.putShort(frameSamples.toShort())
        buf.put(codec.toByte())
        buf.putLong(sessionId)
        val raw = riderName.toByteArray(Charsets.UTF_8)
        val name = if (raw.size > NAME_BYTES) raw.copyOf(NAME_BYTES) else raw
        buf.put(name.size.toByte())
        buf.put(name)
        while (buf.position() < Protocol.HELLO_SIZE) buf.put(0)
        return buf.array()
    }

    companion object {
        const val NAME_BYTES = 14
        const val CODEC_ADPCM = 1

        fun parse(bytes: ByteArray): Hello? {
            if (bytes.size < Protocol.HELLO_SIZE) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val version = buf.short.toInt() and 0xFFFF
            val sampleRate = buf.int
            val frameSamples = buf.short.toInt() and 0xFFFF
            val codec = buf.get().toInt() and 0xFF
            val sessionId = buf.long
            val nameLen = (buf.get().toInt() and 0xFF).coerceAtMost(NAME_BYTES)
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            return Hello(
                version = version,
                sampleRate = sampleRate,
                frameSamples = frameSamples,
                codec = codec,
                sessionId = sessionId,
                riderName = String(nameBytes, Charsets.UTF_8),
            )
        }

        fun newSessionId(): Long = Random.nextLong() and Long.MAX_VALUE
    }
}
