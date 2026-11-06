package com.mensa.riderintercom

import com.mensa.riderintercom.audio.AudioConstants
import com.mensa.riderintercom.floor.Vad
import com.mensa.riderintercom.floor.VadEvent
import com.mensa.riderintercom.floor.VadSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class VadTest {

    private var phase = 0.0

    /** A tone inside the 300-3400 Hz voice band. */
    private fun voiceFrame(amplitude: Double = 6000.0): ShortArray =
        ShortArray(AudioConstants.FRAME_SAMPLES) {
            phase += 2.0 * PI * 700.0 / AudioConstants.SAMPLE_RATE
            (sin(phase) * amplitude).toInt().toShort()
        }

    /** Low-frequency rumble, the shape of wind and engine noise. */
    private fun rumbleFrame(amplitude: Double = 9000.0): ShortArray =
        ShortArray(AudioConstants.FRAME_SAMPLES) {
            phase += 2.0 * PI * 80.0 / AudioConstants.SAMPLE_RATE
            (sin(phase) * amplitude).toInt().toShort()
        }

    private fun quietFrame() =
        ShortArray(AudioConstants.FRAME_SAMPLES) { Random.nextInt(-40, 41).toShort() }

    private fun settle(vad: Vad, frames: Int = 120) {
        repeat(frames) { vad.process(quietFrame()) }
    }

    @Test
    fun `quiet does not open the floor`() {
        val vad = Vad(VadSensitivity.Medium)
        settle(vad)
        repeat(50) { assertEquals(VadEvent.None, vad.process(quietFrame())) }
        assertFalse(vad.isOpen)
    }

    @Test
    fun `speech opens after the attack and closes after the release window`() {
        val vad = Vad(VadSensitivity.Medium)
        settle(vad)

        var opened = false
        for (i in 0 until 10) {
            if (vad.process(voiceFrame()) == VadEvent.Open) { opened = true; break }
        }
        assertTrue("voice should have opened the floor", opened)
        assertTrue(vad.isOpen)

        // 500 ms of silence, which is 25 frames, must close it.
        var closed = false
        for (i in 0 until Vad.RELEASE_FRAMES + 5) {
            if (vad.process(quietFrame()) == VadEvent.Close) { closed = true; break }
        }
        assertTrue("silence should have closed the floor", closed)
        assertFalse(vad.isOpen)
    }

    @Test
    fun `wind and engine rumble below the voice band does not open the floor`() {
        val vad = Vad(VadSensitivity.Medium)
        settle(vad)
        // Rumble louder than the speech used above, but outside 300-3400 Hz.
        var opened = false
        repeat(60) {
            if (vad.process(rumbleFrame(12000.0)) == VadEvent.Open) opened = true
        }
        assertFalse("low-frequency rumble must not open the floor", opened)
    }

    @Test
    fun `a talker does not raise their own threshold and get cut off`() {
        val vad = Vad(VadSensitivity.Medium)
        settle(vad)
        repeat(10) { vad.process(voiceFrame()) }
        assertTrue(vad.isOpen)
        // Three seconds of continuous speech must not close the floor.
        var closed = false
        repeat(150) { if (vad.process(voiceFrame()) == VadEvent.Close) closed = true }
        assertFalse("a continuous talker must keep the floor", closed)
        assertTrue(vad.isOpen)
    }

    @Test
    fun `low sensitivity needs more than high sensitivity`() {
        assertTrue(VadSensitivity.Low.thresholdDb > VadSensitivity.High.thresholdDb)
        assertTrue(VadSensitivity.Low.attackFrames >= VadSensitivity.High.attackFrames)
    }
}
