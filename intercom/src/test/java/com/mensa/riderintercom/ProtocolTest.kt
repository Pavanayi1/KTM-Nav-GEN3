package com.mensa.riderintercom

import com.mensa.riderintercom.protocol.Codec
import com.mensa.riderintercom.protocol.Hello
import com.mensa.riderintercom.protocol.Protocol
import com.mensa.riderintercom.transport.FrameReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProtocolTest {

    @Test
    fun `hello survives a round trip`() {
        val h = Hello(
            version = Protocol.VERSION,
            sampleRate = 16000,
            frameSamples = 320,
            codec = Hello.CODEC_ADPCM,
            sessionId = 1234567890123L,
            riderName = "Arun",
        )
        val bytes = h.toBytes()
        assertEquals(Protocol.HELLO_SIZE, bytes.size)
        assertEquals(h, Hello.parse(bytes))
    }

    @Test
    fun `an over-long rider name is truncated not corrupted`() {
        val h = Hello(1, 16000, 320, 1, 7L, "AnExtremelyLongRiderName")
        val parsed = Hello.parse(h.toBytes())
        assertNotNull(parsed)
        assertTrue(parsed!!.riderName.length <= Hello.NAME_BYTES)
        assertEquals(7L, parsed.sessionId)
    }

    @Test
    fun `reader recovers messages split and coalesced by the stream`() {
        val out = ByteArrayOutputStream()
        val payloads = (1..5).map { ByteArray(it * 10) { b -> (b + it).toByte() } }
        payloads.forEachIndexed { i, p ->
            out.write(Codec.encode(Protocol.TYPE_AUDIO, i, 0, p))
        }
        // One stream carrying all five back to back, as RFCOMM would deliver them.
        val reader = FrameReader(ByteArrayInputStream(out.toByteArray()))
        payloads.forEachIndexed { i, p ->
            val m = reader.next()
            assertNotNull("message $i missing", m)
            assertEquals(Protocol.TYPE_AUDIO, m!!.type)
            assertEquals(i, m.seq)
            assertTrue(m.payload.contentEquals(p))
        }
        assertNull(reader.next())
    }

    @Test
    fun `reader resynchronises after garbage on the wire`() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x11, 0x22, 0x33, 0x44)) // junk from a half-closed socket
        out.write(Codec.encode(Protocol.TYPE_PING, 9, 0, ByteArray(8)))
        val reader = FrameReader(ByteArrayInputStream(out.toByteArray()))
        val m = reader.next()
        assertNotNull(m)
        assertEquals(Protocol.TYPE_PING, m!!.type)
        assertEquals(9, m.seq)
    }

    @Test
    fun `long values survive the wire`() {
        val v = -1234567890123456L
        val encoded = Codec.encodeLong(Protocol.TYPE_FLOOR_TAKEN, 3, v)
        val reader = FrameReader(ByteArrayInputStream(encoded))
        val m = reader.next()!!
        assertEquals(v, Codec.readLong(m.payload))
    }
}
