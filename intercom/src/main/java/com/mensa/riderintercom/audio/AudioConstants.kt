package com.mensa.riderintercom.audio

/**
 * One set of numbers for the whole pipeline. Both phones check these in the handshake,
 * because a sample-rate or frame-size mismatch produces audio that sounds broken rather
 * than an error anyone can act on.
 */
object AudioConstants {
    const val SAMPLE_RATE = 16_000
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000      // 320
    const val FRAME_BYTES = FRAME_SAMPLES * 2                     // 640 bytes of PCM16

    /** 100 ms of pre-roll covers the VAD's 60 ms attack so the first syllable survives. */
    const val PREROLL_FRAMES = 5

    /** Jitter buffer targets, in frames of [FRAME_MS]. */
    const val JITTER_TARGET_BT = 3        // 60 ms
    const val JITTER_TARGET_WIFI = 2      // 40 ms
    const val JITTER_MAX = 10             // 200 ms

    /** Wind and engine energy sits below this; the transmit path removes it. */
    const val TX_HIGHPASS_HZ = 150.0

    /** The telephone band the voice detector listens on. */
    const val VAD_BAND_LOW_HZ = 300.0
    const val VAD_BAND_HIGH_HZ = 3400.0
}
