package com.mensa.riderintercom

import com.mensa.riderintercom.audio.AdpcmCodec
import com.mensa.riderintercom.audio.AudioConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class CodecTest {

    private fun tone(freq: Double, samples: Int, amplitude: Double = 8000.0): ShortArray =
        ShortArray(samples) { i ->
            (sin(2.0 * PI * freq * i / AudioConstants.SAMPLE_RATE) * amplitude).toInt().toShort()
        }

    @Test
    fun `frame payload is the documented size`() {
        val enc = AdpcmCodec.Encoder()
        val frame = tone(440.0, AudioConstants.FRAME_SAMPLES)
        val payload = enc.encodeFrame(frame)
        assertEquals(163, payload.size)
        assertEquals(AdpcmCodec.FRAME_PAYLOAD_BYTES, payload.size)
        // Must fit comfortably inside one RFCOMM write.
        assertTrue("payload must stay under 200 bytes", payload.size < 200)
    }

    @Test
    fun `round trip keeps the signal recognisable`() {
        val enc = AdpcmCodec.Encoder()
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)

        var signal = 0.0
        var noise = 0.0
        // Run several frames so the encoder settles, as it would in a real call.
        repeat(20) { f ->
            val input = ShortArray(AudioConstants.FRAME_SAMPLES) { i ->
                val n = f * AudioConstants.FRAME_SAMPLES + i
                (sin(2.0 * PI * 440.0 * n / AudioConstants.SAMPLE_RATE) * 8000.0).toInt().toShort()
            }
            val payload = enc.encodeFrame(input)
            val decoded = AdpcmCodec.decodeFrame(payload, out)
            assertEquals(AudioConstants.FRAME_SAMPLES, decoded)

            if (f >= 2) { // skip the settling frames
                for (i in 0 until decoded) {
                    val s = input[i].toDouble()
                    val d = out[i].toDouble()
                    signal += s * s
                    noise += (s - d) * (s - d)
                }
            }
        }
        val snr = 10.0 * log10(signal / noise)
        assertTrue("SNR was $snr dB, expected > 20 dB", snr > 20.0)
    }

    @Test
    fun `every frame decodes on its own so one loss costs only that frame`() {
        val enc = AdpcmCodec.Encoder()
        val frames = (0 until 10).map { f ->
            enc.encodeFrame(
                ShortArray(AudioConstants.FRAME_SAMPLES) { i ->
                    val n = f * AudioConstants.FRAME_SAMPLES + i
                    (sin(2.0 * PI * 300.0 * n / AudioConstants.SAMPLE_RATE) * 6000.0)
                        .toInt().toShort()
                }
            )
        }
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)

        // Decode frame 7 alone, with nothing before it, exactly as it would arrive after
        // frames 0-6 were lost on the radio.
        val n = AdpcmCodec.decodeFrame(frames[7], out)
        assertEquals(AudioConstants.FRAME_SAMPLES, n)

        var sum = 0.0
        for (s in out) sum += s.toDouble() * s.toDouble()
        val rms = sqrt(sum / n)
        assertTrue("standalone frame decoded to near-silence (rms=$rms)", rms > 1000.0)
    }

    @Test
    fun `silence stays silent`() {
        val enc = AdpcmCodec.Encoder()
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)
        val silent = ShortArray(AudioConstants.FRAME_SAMPLES)
        repeat(5) {
            val p = enc.encodeFrame(silent)
            AdpcmCodec.decodeFrame(p, out)
        }
        val peak = out.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("silence decoded with peak $peak", peak < 64)
    }
}
