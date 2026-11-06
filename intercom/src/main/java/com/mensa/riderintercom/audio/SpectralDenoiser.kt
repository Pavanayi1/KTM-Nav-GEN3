package com.mensa.riderintercom.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** How hard the denoiser works. Stronger settings trade voice naturalness for quiet. */
enum class NoiseSuppression(
    val label: String,
    /** Over-subtraction factor: how many times the noise estimate to remove. */
    val overSubtract: Double,
    /** Floor on the per-bin gain, so suppressed bins fade rather than switch off. */
    val gainFloor: Double,
) {
    /** Only the high-pass and gate in the capture path. */
    Off("Off", 0.0, 1.0),

    /** Town riding. Takes the hiss out without touching the voice. */
    Light("Light", 1.4, 0.24),

    /** Highway. Removes engine and wind roar; voice sounds slightly processed. */
    Strong("Strong", 2.5, 0.11),
}

/**
 * Spectral noise suppression on the transmitted voice.
 *
 * This is **not** the active noise cancellation an aviation headset does. ANC cancels
 * sound at your eardrum with an anti-phase signal, from a microphone millimetres away,
 * in well under a millisecond. Nothing on the far side of Android's audio stack can do
 * that: the path to the headset is 150-250 ms, and the engine noise reaching your ear
 * never passes through the phone at all. ANC is a property of the headset hardware.
 *
 * What this does instead is remove the noise from the voice **you send**, which is the
 * half of the problem an app can actually solve, and the half your buddy notices.
 *
 * Method: short-time Fourier transform, a per-bin noise floor tracked with a fast-down,
 * slow-up follower, and a spectral-subtraction gain per bin. Engine and wind are
 * stationary over the window the follower watches, so they settle into the noise
 * estimate; speech is transient and does not. The gain is smoothed across time and
 * floored rather than driven to zero, which is what keeps suppression from turning into
 * the warbling "musical noise" that naive spectral subtraction produces.
 *
 * Adds [WINDOW] samples of latency — 32 ms — which is small against the 270-420 ms the
 * A2DP leg already costs.
 */
class SpectralDenoiser(strength: NoiseSuppression = NoiseSuppression.Light) {

    companion object {
        const val WINDOW = 512          // 32 ms at 16 kHz
        const val HOP = 256             // 50% overlap; Hann at 50% satisfies COLA
        private const val BINS = WINDOW / 2 + 1

        /** The noise follower drops quickly when the room goes quiet... */
        private const val NOISE_FALL = 0.5
        /** ...and rises slowly, so a talker cannot raise their own noise floor. */
        private const val NOISE_RISE = 0.004

        /** Gain smoothing across frames; the main defence against musical noise. */
        private const val GAIN_SMOOTH = 0.55

        /**
         * Frame energy relative to the learned floor above which the frame is assumed to
         * contain speech, and the noise estimate is not allowed to rise. Without this a
         * rider holding a vowel sounds, to a stationarity test, exactly like noise, and
         * the denoiser slowly eats their own voice.
         */
        private const val SPEECH_PRESENT_RATIO = 2.0

        private const val EPS = 1e-9
    }

    @Volatile var strength: NoiseSuppression = strength

    /**
     * Set when the phone's own hardware suppressor is running ahead of this one. The
     * steady noise is already gone, so subtracting the full amount again over-processes
     * the voice; the depth is halved instead.
     */
    @Volatile var platformSuppressionActive: Boolean = false

    private val fft = Fft(WINDOW)
    private val hann = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2.0 * PI * it / WINDOW) }

    private val re = DoubleArray(WINDOW)
    private val im = DoubleArray(WINDOW)
    private val noise = DoubleArray(BINS)
    private val gainSmoothed = DoubleArray(BINS) { 1.0 }
    private var primed = false

    // Sliding input and output queues. Sized generously so nothing ever reallocates.
    private val inQ = DoubleArray(WINDOW * 4)
    private var inLen = 0
    private val outQ = DoubleArray(WINDOW * 4)
    private var outLen = 0
    private val olaTail = DoubleArray(WINDOW)

    init { reset() }

    fun reset() {
        inLen = 0
        outLen = 0
        primed = false
        java.util.Arrays.fill(noise, 0.0)
        java.util.Arrays.fill(gainSmoothed, 1.0)
        java.util.Arrays.fill(olaTail, 0.0)
        // Prime the output with one window of silence so every call can return a full
        // frame from the first one onward, instead of the caller handling short reads.
        java.util.Arrays.fill(outQ, 0.0)
        outLen = WINDOW
    }

    /**
     * Denoises one frame in place. Allocation-free: this runs on the capture thread 50
     * times a second.
     */
    fun process(frame: ShortArray) {
        if (strength == NoiseSuppression.Off) return

        // 1. Append the new samples to the analysis queue.
        if (inLen + frame.size > inQ.size) inLen = 0 // cannot happen in steady state
        for (i in frame.indices) inQ[inLen + i] = frame[i].toDouble()
        inLen += frame.size

        // 2. Consume whole windows while there are any.
        while (inLen >= WINDOW) {
            analyseAndSynthesise()
            System.arraycopy(inQ, HOP, inQ, 0, inLen - HOP)
            inLen -= HOP
        }

        // 3. Hand back the same number of samples we were given.
        val n = frame.size
        if (outLen >= n) {
            for (i in 0 until n) {
                frame[i] = outQ[i].coerceIn(-32768.0, 32767.0).toInt().toShort()
            }
            System.arraycopy(outQ, n, outQ, 0, outLen - n)
            outLen -= n
        }
        // If the queue is short (only possible transiently at startup) the caller keeps
        // its own samples, which is the right fallback.
    }

    private fun analyseAndSynthesise() {
        for (i in 0 until WINDOW) {
            re[i] = inQ[i] * hann[i]
            im[i] = 0.0
        }
        fft.forward(re, im)

        val alpha = if (platformSuppressionActive) strength.overSubtract * 0.5
        else strength.overSubtract
        val floor = if (platformSuppressionActive) minOf(1.0, strength.gainFloor * 1.6)
        else strength.gainFloor

        // Decide once per window whether this looks like speech. The per-bin follower
        // below is only allowed to rise when it does not, which is what stops a held
        // vowel from being learned as noise and then subtracted away.
        var totalMag = 0.0
        var totalNoise = 0.0
        for (k in 0 until BINS) {
            totalMag += sqrt(re[k] * re[k] + im[k] * im[k])
            totalNoise += noise[k]
        }
        val speechPresent = primed && totalNoise > EPS &&
            totalMag > SPEECH_PRESENT_RATIO * totalNoise

        for (k in 0 until BINS) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])

            // Track the noise floor per bin: quick to follow a quietening band, slow to
            // follow a rising one, and not at all while speech is present.
            val n = noise[k]
            noise[k] = when {
                !primed -> mag
                mag < n -> n + NOISE_FALL * (mag - n)
                speechPresent -> n
                else -> n + NOISE_RISE * (mag - n)
            }

            // Spectral subtraction expressed as a gain, floored so the bin fades out
            // rather than switching off, which is what avoids musical noise.
            val raw = if (mag <= EPS) 1.0 else (mag - alpha * noise[k]) / mag
            val target = raw.coerceIn(floor, 1.0)
            gainSmoothed[k] = GAIN_SMOOTH * gainSmoothed[k] + (1.0 - GAIN_SMOOTH) * target

            val g = gainSmoothed[k]
            re[k] *= g
            im[k] *= g
            // Keep the spectrum conjugate-symmetric so the inverse transform is real.
            if (k in 1 until WINDOW / 2) {
                val mirror = WINDOW - k
                re[mirror] = re[k]
                im[mirror] = -im[k]
            }
        }
        primed = true

        fft.inverse(re, im)

        // Overlap-add. Hann at 50% overlap sums to a constant, so no extra normalisation.
        for (i in 0 until HOP) {
            outQ[outLen + i] = re[i] * hann[i] + olaTail[i]
        }
        outLen += HOP
        for (i in 0 until WINDOW - HOP) {
            olaTail[i] = re[i + HOP] * hann[i + HOP]
        }
    }
}
