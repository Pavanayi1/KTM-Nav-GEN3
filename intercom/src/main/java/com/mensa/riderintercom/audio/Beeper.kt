package com.mensa.riderintercom.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * The "you are muted" tone.
 *
 * It is generated into the same [VoicePlayer] stream as the voice, so it reaches the
 * headset over A2DP like everything else and never opens a second audio path. It is
 * local only: it is never sent to the other riders.
 */
class Beeper(
    private val toneHz: Double = 880.0,
    private val durationMs: Int = 100,
    private val amplitude: Double = 0.30,
    private val minGapMs: Long = 2000,
) {
    private val totalSamples = AudioConstants.SAMPLE_RATE * durationMs / 1000
    private val fadeSamples = AudioConstants.SAMPLE_RATE * 5 / 1000 // 5 ms edges, no click

    @Volatile private var remaining = 0
    private var phase = 0.0
    private var lastBeepAt = 0L

    /** Rate limited, so a rider holding a conversation against the mute does not buzz. */
    fun request(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBeepAt < minGapMs) return false
        lastBeepAt = now
        remaining = totalSamples
        phase = 0.0
        return true
    }

    fun cancel() { remaining = 0 }

    val isActive: Boolean get() = remaining > 0

    /** Mixes the tone into [buf] in place. Called on the playback thread. */
    fun mixInto(buf: ShortArray) {
        if (remaining <= 0) return
        val step = 2.0 * PI * toneHz / AudioConstants.SAMPLE_RATE
        for (i in buf.indices) {
            if (remaining <= 0) break
            val pos = totalSamples - remaining
            val env = when {
                pos < fadeSamples -> pos.toDouble() / fadeSamples
                remaining < fadeSamples -> remaining.toDouble() / fadeSamples
                else -> 1.0
            }
            val tone = sin(phase) * amplitude * env * Short.MAX_VALUE
            phase += step
            val mixed = buf[i] + tone
            buf[i] = mixed.coerceIn(-32768.0, 32767.0).toInt().toShort()
            remaining--
        }
    }
}
