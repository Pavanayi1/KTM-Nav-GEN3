package com.mensa.riderintercom.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT with precomputed twiddle factors.
 *
 * Sized once and reused, because this runs on the capture thread 62 times a second and
 * must not allocate. At 512 points that is a few tens of microseconds per call on any
 * phone from 2019 onwards.
 */
class Fft(val size: Int) {

    init {
        require(size > 0 && (size and (size - 1)) == 0) { "FFT size must be a power of two" }
    }

    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }
    private val reverse = IntArray(size).also { table ->
        var bits = 0
        while ((1 shl bits) < size) bits++
        for (i in 0 until size) {
            var x = i
            var r = 0
            for (b in 0 until bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            table[i] = r
        }
    }

    /** Forward transform. [re] and [im] are modified in place. */
    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, false)

    /** Inverse transform, scaled by 1/size so a forward-inverse pair is the identity. */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        transform(re, im, true)
        val scale = 1.0 / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, invert: Boolean) {
        require(re.size >= size && im.size >= size)

        for (i in 0 until size) {
            val j = reverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= size) {
            val step = size / len
            val half = len / 2
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosTable[k]
                    val wi = if (invert) sinTable[k] else -sinTable[k]
                    val jh = j + half
                    val tr = re[jh] * wr - im[jh] * wi
                    val ti = re[jh] * wi + im[jh] * wr
                    re[jh] = re[j] - tr
                    im[jh] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
