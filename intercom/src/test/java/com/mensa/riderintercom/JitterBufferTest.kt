package com.mensa.riderintercom

import com.mensa.riderintercom.audio.AudioConstants
import com.mensa.riderintercom.audio.JitterBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

    private fun frame(value: Int) = ShortArray(AudioConstants.FRAME_SAMPLES) { value.toShort() }

    @Test
    fun `holds back until the target depth then plays in order`() {
        val jb = JitterBuffer(targetFrames = 3)
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)

        jb.put(0, frame(10))
        assertFalse("should still be filling", jb.take(out))
        jb.put(1, frame(20))
        assertFalse("should still be filling", jb.take(out))
        jb.put(2, frame(30))

        assertTrue(jb.take(out)); assertEquals(10, out[0].toInt())
        assertTrue(jb.take(out)); assertEquals(20, out[0].toInt())
        assertTrue(jb.take(out)); assertEquals(30, out[0].toInt())
    }

    @Test
    fun `reorders frames that arrive out of sequence`() {
        val jb = JitterBuffer(targetFrames = 3)
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)

        jb.put(0, frame(10))
        jb.put(2, frame(30))
        jb.put(1, frame(20))

        assertTrue(jb.take(out)); assertEquals(10, out[0].toInt())
        assertTrue(jb.take(out)); assertEquals(20, out[0].toInt())
        assertTrue(jb.take(out)); assertEquals(30, out[0].toInt())
    }

    @Test
    fun `a lost frame is concealed and the stream carries on`() {
        val jb = JitterBuffer(targetFrames = 2)
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)

        jb.put(0, frame(1000))
        jb.put(2, frame(3000)) // frame 1 never arrives
        jb.put(3, frame(4000))

        assertTrue(jb.take(out))
        assertEquals(1000, out[0].toInt())

        // Slot 1 is concealed from the previous frame rather than stalling the stream.
        assertTrue(jb.take(out))
        assertEquals(1L, jb.lostFrames)
        assertEquals(600, out[0].toInt()) // 1000 * 0.6 decay

        assertTrue(jb.take(out))
        assertEquals(3000, out[0].toInt())
    }

    @Test
    fun `consecutive losses become comfort noise not silence`() {
        val jb = JitterBuffer(targetFrames = 1)
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)
        jb.put(0, frame(2000))
        assertTrue(jb.take(out))

        jb.put(5, frame(9000)) // a four-frame hole
        var sawNoise = false
        repeat(4) {
            jb.take(out)
            if (out.any { it.toInt() != 0 }) sawNoise = true
        }
        assertTrue("expected comfort noise during the gap", sawNoise)
    }

    @Test
    fun `a frame that arrives after its slot was played is dropped`() {
        val jb = JitterBuffer(targetFrames = 1)
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)
        jb.put(10, frame(100))
        assertTrue(jb.take(out))
        jb.put(10, frame(100)) // too late
        assertTrue(jb.lateFrames >= 1)
    }

    @Test
    fun `a reconnect flushes stale speech instead of replaying it`() {
        val jb = JitterBuffer(targetFrames = 2)
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)
        repeat(5) { jb.put(it, frame(500)) }
        assertEquals(5, jb.depth)
        jb.reset()
        assertEquals(0, jb.depth)
        assertFalse("nothing should play straight after a reset", jb.take(out))
    }

    // ---- regression: an underrun must never produce digital silence ----------------
    //
    // The first two-phone call sounded jittery. The cause was here: after any underrun
    // the buffer dropped back into its prefill state, so take() returned false until the
    // target depth rebuilt, and VoicePlayer writes hard zeroes on false. One late frame
    // therefore cost 60 ms of silence instead of 20 ms of concealment.

    @Test
    fun `once playing an underrun conceals instead of returning silence`() {
        val jb = JitterBuffer(targetFrames = 3)
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)

        jb.put(0, frame(1000)); jb.put(1, frame(1000)); jb.put(2, frame(1000))
        repeat(3) { assertTrue(jb.take(out)) }

        // Buffer is now empty and the next frame is late.
        assertTrue("an underrun must still hand back audio, not silence", jb.take(out))
        assertEquals(1L, jb.underruns)
        assertTrue("concealment must not be digital silence", out.any { it.toInt() != 0 })
    }

    @Test
    fun `a late frame resumes exactly where the stream left off`() {
        val jb = JitterBuffer(targetFrames = 2)
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)

        jb.put(0, frame(111)); jb.put(1, frame(222))
        assertTrue(jb.take(out)); assertEquals(111, out[0].toInt())
        assertTrue(jb.take(out)); assertEquals(222, out[0].toInt())

        // Underrun: frame 2 has not arrived yet.
        assertTrue(jb.take(out))
        assertEquals(1L, jb.underruns)

        // It arrives late but is still the frame we want, so it must play, not be dropped.
        jb.put(2, frame(333))
        assertTrue(jb.take(out))
        assertEquals("the stream must resume at the held slot", 333, out[0].toInt())
    }

    @Test
    fun `the buffer deepens after underruns and walks back down when clean`() {
        val jb = JitterBuffer(targetFrames = 2)
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)

        jb.put(0, frame(1)); jb.put(1, frame(1))
        repeat(2) { jb.take(out) }
        assertEquals(2, jb.targetDepth)

        repeat(3) { jb.take(out) } // three underruns
        assertEquals("underruns must deepen the buffer", 5, jb.targetDepth)
        assertEquals(3L, jb.underruns)

        // A long clean run must bring the added latency back off again.
        var seq = 2
        repeat(600) {
            jb.put(seq, frame(7))
            jb.take(out)
            seq++
        }
        assertTrue("a clean link must shed the extra depth", jb.targetDepth < 5)
    }

    @Test
    fun `added depth is bounded so latency cannot creep up for ever`() {
        val jb = JitterBuffer(targetFrames = 2)
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)
        jb.put(0, frame(1)); jb.put(1, frame(1))
        repeat(2) { jb.take(out) }
        repeat(100) { jb.take(out) } // relentless underruns
        assertTrue(
            "depth must stay within the 200 ms ceiling",
            jb.targetDepth <= AudioConstants.JITTER_MAX,
        )
    }

    @Test
    fun `a burst after a stall does not leave the rider a second behind`() {
        val jb = JitterBuffer(targetFrames = 2)
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)
        // Far more than the ceiling arrives at once, as after a radio stall.
        repeat(40) { jb.put(it, frame(500)) }
        assertTrue(
            "queued depth must be capped, was ${jb.depth}",
            jb.depth <= AudioConstants.JITTER_MAX,
        )
    }
}
