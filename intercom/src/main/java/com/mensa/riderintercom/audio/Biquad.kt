package com.mensa.riderintercom.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Second-order IIR section, direct form 1. Used for the transmit high-pass that removes
 * wind rumble and for the 300-3400 Hz band the voice detector listens on.
 */
class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    /** Filters in place, so the caller's frame buffer is reused every 20 ms. */
    fun processInPlace(buf: ShortArray, count: Int = buf.size) {
        for (i in 0 until count) {
            val y = process(buf[i].toDouble())
            buf[i] = y.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    companion object {
        private const val Q = 0.7071067811865476 // Butterworth

        fun highPass(sampleRate: Int, cutoffHz: Double, q: Double = Q): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = ((1.0 + cosW) / 2.0) / a0,
                b1 = (-(1.0 + cosW)) / a0,
                b2 = ((1.0 + cosW) / 2.0) / a0,
                a1 = (-2.0 * cosW) / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }

        fun lowPass(sampleRate: Int, cutoffHz: Double, q: Double = Q): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = ((1.0 - cosW) / 2.0) / a0,
                b1 = (1.0 - cosW) / a0,
                b2 = ((1.0 - cosW) / 2.0) / a0,
                a1 = (-2.0 * cosW) / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }
    }
}
