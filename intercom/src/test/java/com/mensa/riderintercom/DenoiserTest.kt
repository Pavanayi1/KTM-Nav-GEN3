package com.mensa.riderintercom

import com.mensa.riderintercom.audio.AudioConstants
import com.mensa.riderintercom.audio.Fft
import com.mensa.riderintercom.audio.NoiseSuppression
import com.mensa.riderintercom.audio.SpectralDenoiser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class DenoiserTest {

    private val rng = Random(20261106)
    private var phase = 0.0

    private fun rms(buf: ShortArray): Double {
        var sum = 0.0
        for (s in buf) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / buf.size)
    }

    /** Broadband noise, standing in for engine and road roar. */
    private fun noiseFrame(level: Int = 2000) =
        ShortArray(AudioConstants.FRAME_SAMPLES) { rng.nextInt(-level, level + 1).toShort() }

    /** A voice-band tone plus the same noise, standing in for speech at speed. */
    private fun voicePlusNoise(tone: Double = 5000.0, level: Int = 2000) =
        ShortArray(AudioConstants.FRAME_SAMPLES) {
            phase += 2.0 * PI * 600.0 / AudioConstants.SAMPLE_RATE
            (sin(phase) * tone + rng.nextInt(-level, level + 1)).toInt()
                .coerceIn(-32768, 32767).toShort()
        }

    // ---------------------------------------------------------------- FFT

    @Test
    fun `forward then inverse returns the original signal`() {
        val n = 512
        val fft = Fft(n)
        val re = DoubleArray(n) { sin(2.0 * PI * 7.0 * it / n) * 1000.0 }
        val im = DoubleArray(n)
        val original = re.copyOf()

        fft.forward(re, im)
        fft.inverse(re, im)

        for (i in 0 until n) {
            assertEquals("sample $i", original[i], re[i], 1e-6)
            assertEquals("imag $i", 0.0, im[i], 1e-6)
        }
    }

    @Test
    fun `a pure tone lands in the expected bin`() {
        val n = 512
        val fft = Fft(n)
        val bin = 16
        val re = DoubleArray(n) { sin(2.0 * PI * bin * it / n) }
        val im = DoubleArray(n)
        fft.forward(re, im)

        val mags = DoubleArray(n / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        val peak = mags.indices.maxByOrNull { mags[it] }
        assertEquals(bin, peak)
    }

    // ----------------------------------------------------------- denoiser

    @Test
    fun `off is a pure passthrough`() {
        val d = SpectralDenoiser(NoiseSuppression.Off)
        val frame = voicePlusNoise()
        val before = frame.copyOf()
        d.process(frame)
        assertTrue("Off must not alter the signal at all", frame.contentEquals(before))
    }

    @Test
    fun `steady noise is suppressed once the floor is learned`() {
        val d = SpectralDenoiser(NoiseSuppression.Strong)
        var inputRms = 0.0
        var outputRms = 0.0
        var counted = 0

        repeat(120) { i ->
            val frame = noiseFrame()
            val before = rms(frame)
            d.process(frame)
            // Skip the priming window and the settling period of the noise follower.
            if (i >= 90) {
                inputRms += before
                outputRms += rms(frame)
                counted++
            }
        }
        inputRms /= counted
        outputRms /= counted

        val reductionDb = 20.0 * log10((inputRms + 1.0) / (outputRms + 1.0))
        assertTrue(
            "expected steady noise to drop by at least 6 dB, got %.1f dB".format(reductionDb),
            reductionDb > 6.0,
        )
    }

    @Test
    fun `voice survives while the noise around it does not`() {
        val d = SpectralDenoiser(NoiseSuppression.Strong)

        // Learn the noise floor from noise alone, as happens during a rider's pauses.
        repeat(100) { d.process(noiseFrame()) }

        var quietOut = 0.0
        repeat(20) {
            val f = noiseFrame()
            d.process(f)
            quietOut += rms(f)
        }
        quietOut /= 20

        // Now speak over the same noise.
        var voiceOut = 0.0
        repeat(30) {
            val f = voicePlusNoise()
            d.process(f)
            voiceOut += rms(f)
        }
        voiceOut /= 30

        assertTrue(
            "voice must come through far louder than the suppressed noise " +
                "(voice=%.0f noise=%.0f)".format(voiceOut, quietOut),
            voiceOut > quietOut * 3.0,
        )
    }

    @Test
    fun `strong suppresses more than light`() {
        fun residualNoise(strength: NoiseSuppression): Double {
            val d = SpectralDenoiser(strength)
            repeat(100) { d.process(noiseFrame()) }
            var out = 0.0
            repeat(20) {
                val f = noiseFrame()
                d.process(f)
                out += rms(f)
            }
            return out / 20
        }
        val light = residualNoise(NoiseSuppression.Light)
        val strong = residualNoise(NoiseSuppression.Strong)
        assertTrue(
            "Strong (%.0f) should leave less noise than Light (%.0f)".format(strong, light),
            strong < light,
        )
    }

    /**
     * Speech-like: syllables at about 4 Hz with a pitch that moves, which is what makes
     * real speech non-stationary and therefore distinguishable from engine roar. A
     * perfectly steady tone is NOT a valid test signal here - by definition it is
     * stationary noise, and any spectral denoiser will correctly remove it.
     */
    private var speechT = 0.0
    private fun speechFrame(level: Int = 1): ShortArray {
        val dt = 1.0 / AudioConstants.SAMPLE_RATE
        return ShortArray(AudioConstants.FRAME_SAMPLES) {
            speechT += dt
            val syllable = 0.5 + 0.5 * sin(2.0 * PI * 4.0 * speechT)     // ~4 Hz envelope
            val pitch = 180.0 + 40.0 * sin(2.0 * PI * 1.3 * speechT)     // moving pitch
            phase += 2.0 * PI * pitch / AudioConstants.SAMPLE_RATE
            val v = (sin(phase) + 0.5 * sin(2 * phase) + 0.3 * sin(3 * phase)) * 4000.0
            (v * syllable + rng.nextInt(-level, level + 1)).toInt()
                .coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun `real speech is not gutted`() {
        val d = SpectralDenoiser(NoiseSuppression.Light)
        var inRms = 0.0
        var outRms = 0.0
        var counted = 0
        repeat(120) { i ->
            val f = speechFrame()
            val before = rms(f)
            d.process(f)
            if (i >= 60) { inRms += before; outRms += rms(f); counted++ }
        }
        inRms /= counted
        outRms /= counted
        val lossDb = 20.0 * log10((inRms + 1.0) / (outRms + 1.0))
        assertTrue(
            "speech lost %.1f dB, expected under 4 dB".format(lossDb),
            abs(lossDb) < 4.0,
        )
    }

    @Test
    fun `a held vowel is not slowly learned as noise`() {
        // The failure this guards against: a rider holding a note sounds stationary, so
        // a naive noise follower absorbs it and then subtracts their own voice away.
        val d = SpectralDenoiser(NoiseSuppression.Strong)
        repeat(40) { d.process(noiseFrame(level = 300)) } // learn a quiet floor first

        val early = ArrayList<Double>()
        val late = ArrayList<Double>()
        repeat(250) { i ->                                 // 5 seconds of held tone
            val f = ShortArray(AudioConstants.FRAME_SAMPLES) {
                phase += 2.0 * PI * 500.0 / AudioConstants.SAMPLE_RATE
                (sin(phase) * 6000.0).toInt().toShort()
            }
            d.process(f)
            if (i in 30..50) early += rms(f)
            if (i >= 220) late += rms(f)
        }
        val fade = 20.0 * log10((early.average() + 1.0) / (late.average() + 1.0))
        assertTrue(
            "a held vowel faded by %.1f dB over 5 s, expected under 6 dB".format(fade),
            fade < 6.0,
        )
    }

    @Test
    fun `output length always matches input length`() {
        val d = SpectralDenoiser(NoiseSuppression.Strong)
        repeat(50) {
            val f = voicePlusNoise()
            val size = f.size
            d.process(f)
            assertEquals(size, f.size)
        }
    }

    // ---- regression: suppression must never chop the voice -------------------------
    //
    // Reported from a road test: "noise cancellation chops the voice". Three causes
    // compounded, and the first was the real bug - the capture path measured its noise
    // gate AFTER denoising, so quiet speech that the denoiser had attenuated fell under
    // the threshold and was replaced with digital silence.

    @Test
    fun `quiet speech is attenuated, never zeroed`() {
        val d = SpectralDenoiser(NoiseSuppression.Strong)
        repeat(60) { d.process(noiseFrame(level = 1500)) }

        var silentFrames = 0
        var frames = 0
        repeat(80) {
            // Deliberately quiet speech: the tails of words, consonants.
            val f = speechFrame(level = 400)
            d.process(f)
            frames++
            if (f.all { it.toInt() == 0 }) silentFrames++
        }
        assertEquals(
            "suppression must attenuate quiet speech, not replace it with silence",
            0, silentFrames,
        )
        assertTrue(frames > 0)
    }

    @Test
    fun `stacking on the phone's own suppressor is backed off`() {
        fun residual(platform: Boolean): Double {
            val d = SpectralDenoiser(NoiseSuppression.Strong)
            d.platformSuppressionActive = platform
            repeat(100) { d.process(noiseFrame()) }
            var out = 0.0
            repeat(20) {
                val f = speechFrame(level = 1)
                d.process(f)
                out += rms(f)
            }
            return out / 20
        }
        // With the hardware already suppressing, the software pass must be gentler, so
        // more of the voice survives it.
        assertTrue(
            "software depth must back off when hardware NS is active",
            residual(platform = true) > residual(platform = false),
        )
    }
}
