package com.mensa.riderintercom.floor

import com.mensa.riderintercom.audio.AudioConstants
import com.mensa.riderintercom.audio.Biquad
import kotlin.math.log10
import kotlin.math.sqrt

/** How eagerly the detector opens the floor. */
enum class VadSensitivity(val thresholdDb: Double, val attackFrames: Int) {
    /** Noisy helmet, highway speed: needs a clear voice to open. */
    Low(16.0, 4),

    /** The default: 12 dB over the running noise floor, 60 ms of attack. */
    Medium(12.0, 3),

    /** Quiet rider or a good boom mic. */
    High(8.0, 2),
}

/** What the detector just decided about this 20 ms frame. */
enum class VadEvent { None, Open, Close }

/**
 * Voice activity detection on the telephone band.
 *
 * Running the detector on 300-3400 Hz is what makes it usable on a bike: wind and engine
 * energy sits mostly below 300 Hz and road hiss above 4 kHz, so the band-pass alone
 * removes most of what would otherwise open the floor on a gust.
 *
 * Decision for each frame:
 *  1. band-pass the frame,
 *  2. compare its RMS with a running noise floor, which only adapts while the frame is
 *     not speech, so a talker does not raise their own threshold,
 *  3. require [VadSensitivity.attackFrames] consecutive speech frames to open and
 *     [RELEASE_FRAMES] of silence to close.
 *
 * This is the energy detector the design calls for. A neural detector (Silero) would sit
 * behind the same interface as a third stage; the band-pass and the hysteresis below are
 * what it would be confirming.
 */
class Vad(sensitivity: VadSensitivity = VadSensitivity.Medium) {

    companion object {
        /** 500 ms of silence releases the floor. */
        const val RELEASE_FRAMES = 25

        /** How fast the noise floor tracks the room, as a per-frame smoothing factor. */
        private const val FLOOR_RISE = 0.0025   // ~2 s to follow noise upwards
        private const val FLOOR_FALL = 0.05     // fast to follow a quietening room
        private const val FLOOR_MIN = 25.0
    }

    @Volatile var sensitivity: VadSensitivity = sensitivity

    /**
     * Two cascaded high-pass sections, not one. A single second-order section rolls off at
     * 12 dB per octave, which still leaves loud wind at 80 Hz well above the threshold and
     * opens the floor on a gust. Cascading gives 24 dB per octave, about 46 dB down at
     * 80 Hz, which puts rumble safely under the bar while leaving the voice band flat.
     */
    private val highPass1 = Biquad.highPass(AudioConstants.SAMPLE_RATE, AudioConstants.VAD_BAND_LOW_HZ)
    private val highPass2 = Biquad.highPass(AudioConstants.SAMPLE_RATE, AudioConstants.VAD_BAND_LOW_HZ)
    private val lowPass = Biquad.lowPass(AudioConstants.SAMPLE_RATE, AudioConstants.VAD_BAND_HIGH_HZ)
    private val work = ShortArray(AudioConstants.FRAME_SAMPLES)

    private var noiseFloor = 150.0
    private var speechRun = 0
    private var silenceRun = 0

    @Volatile var isOpen = false
        private set

    /** Most recent band RMS above the noise floor, in dB. Drives the UI level meter. */
    @Volatile var lastMarginDb: Double = 0.0
        private set

    fun reset() {
        highPass1.reset()
        highPass2.reset()
        lowPass.reset()
        noiseFloor = 150.0
        speechRun = 0
        silenceRun = 0
        isOpen = false
    }

    /**
     * Feeds one 20 ms frame. Returns [VadEvent.Open] on the frame where speech is
     * confirmed and [VadEvent.Close] after the release window of silence.
     */
    fun process(frame: ShortArray): VadEvent {
        val n = minOf(frame.size, work.size)
        frame.copyInto(work, 0, 0, n)
        highPass1.processInPlace(work, n)
        highPass2.processInPlace(work, n)
        lowPass.processInPlace(work, n)

        var sum = 0.0
        for (i in 0 until n) sum += (work[i].toInt() * work[i].toInt()).toDouble()
        val rms = sqrt(sum / n)

        val floor = noiseFloor.coerceAtLeast(FLOOR_MIN)
        val marginDb = 20.0 * log10((rms + 1.0) / (floor + 1.0))
        lastMarginDb = marginDb

        val speechFrame = marginDb >= sensitivity.thresholdDb

        // The floor only learns from frames that are not speech, so a long sentence
        // cannot slowly raise the bar and cut the talker off.
        if (!speechFrame) {
            val alpha = if (rms < noiseFloor) FLOOR_FALL else FLOOR_RISE
            noiseFloor += alpha * (rms - noiseFloor)
            if (noiseFloor < FLOOR_MIN) noiseFloor = FLOOR_MIN
        }

        if (speechFrame) {
            speechRun++
            silenceRun = 0
        } else {
            silenceRun++
            speechRun = 0
        }

        if (!isOpen && speechRun >= sensitivity.attackFrames) {
            isOpen = true
            return VadEvent.Open
        }
        if (isOpen && silenceRun >= RELEASE_FRAMES) {
            isOpen = false
            return VadEvent.Close
        }
        return VadEvent.None
    }
}
