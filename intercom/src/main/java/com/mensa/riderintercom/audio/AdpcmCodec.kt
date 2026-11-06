package com.mensa.riderintercom.audio

/**
 * IMA ADPCM at 4 bits per sample: 320 PCM samples of a 20 ms frame become 160 bytes,
 * plus a 3-byte predictor state, so one voice frame is 163 bytes on the wire and about
 * 65 kbps. That fits inside a single RFCOMM write and well inside the 100-300 kbps a
 * phone-to-phone RFCOMM link carries.
 *
 * Each frame carries the encoder state it started from, so every frame decodes on its
 * own. A frame lost on the radio costs exactly 20 ms of audio instead of desynchronising
 * the decoder for the rest of the call, which is what matters on a link that drops.
 */
object AdpcmCodec {

    const val STATE_BYTES = 3
    val FRAME_PAYLOAD_BYTES = STATE_BYTES + AudioConstants.FRAME_SAMPLES / 2 // 163

    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
        253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
        1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442,
        11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
        32767,
    )

    private val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

    /** Encoder state carried between frames so quality does not reset every 20 ms. */
    class Encoder {
        var predictor = 0
        var index = 0

        fun reset() { predictor = 0; index = 0 }

        /**
         * Encodes one 20 ms frame. The returned array begins with the state this frame
         * started from, which is what makes it independently decodable.
         */
        fun encodeFrame(pcm: ShortArray, count: Int = AudioConstants.FRAME_SAMPLES): ByteArray {
            val out = ByteArray(STATE_BYTES + count / 2)
            // Snapshot the starting state into the frame header.
            out[0] = ((predictor shr 8) and 0xFF).toByte()
            out[1] = (predictor and 0xFF).toByte()
            out[2] = (index and 0xFF).toByte()

            var o = STATE_BYTES
            var i = 0
            while (i < count) {
                val lo = encodeSample(pcm[i].toInt())
                val hi = encodeSample(pcm[i + 1].toInt())
                out[o++] = ((hi shl 4) or lo).toByte()
                i += 2
            }
            return out
        }

        private fun encodeSample(sample: Int): Int {
            val step = STEP_TABLE[index]
            var diff = sample - predictor
            var code = 0
            if (diff < 0) { code = 8; diff = -diff }

            var vpdiff = step shr 3
            var s = step
            if (diff >= s) { code = code or 4; diff -= s; vpdiff += s }
            s = s shr 1
            if (diff >= s) { code = code or 2; diff -= s; vpdiff += s }
            s = s shr 1
            if (diff >= s) { code = code or 1; vpdiff += s }

            predictor = if (code and 8 != 0) predictor - vpdiff else predictor + vpdiff
            predictor = predictor.coerceIn(-32768, 32767)
            index = (index + INDEX_TABLE[code]).coerceIn(0, STEP_TABLE.size - 1)
            return code
        }
    }

    /** Stateless: every frame seeds the decoder from its own 3-byte header. */
    fun decodeFrame(data: ByteArray, out: ShortArray): Int {
        if (data.size <= STATE_BYTES) return 0
        var predictor = ((data[0].toInt() shl 8) or (data[1].toInt() and 0xFF)).toShort().toInt()
        var index = (data[2].toInt() and 0xFF).coerceIn(0, STEP_TABLE.size - 1)

        val samples = (data.size - STATE_BYTES) * 2
        val n = minOf(samples, out.size)
        var o = 0
        var i = STATE_BYTES
        while (o < n) {
            val byte = data[i++].toInt() and 0xFF
            val codes = intArrayOf(byte and 0x0F, (byte shr 4) and 0x0F)
            for (code in codes) {
                if (o >= n) break
                val step = STEP_TABLE[index]
                var vpdiff = step shr 3
                if (code and 4 != 0) vpdiff += step
                if (code and 2 != 0) vpdiff += step shr 1
                if (code and 1 != 0) vpdiff += step shr 2
                predictor = if (code and 8 != 0) predictor - vpdiff else predictor + vpdiff
                predictor = predictor.coerceIn(-32768, 32767)
                index = (index + INDEX_TABLE[code]).coerceIn(0, STEP_TABLE.size - 1)
                out[o++] = predictor.toShort()
            }
        }
        return n
    }
}
