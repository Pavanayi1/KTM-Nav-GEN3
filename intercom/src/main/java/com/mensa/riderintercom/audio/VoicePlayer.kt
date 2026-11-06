package com.mensa.riderintercom.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlin.math.pow

/**
 * Plays the remote rider's voice through the Android mixer as ordinary media.
 *
 * The track is tagged USAGE_MEDIA with CONTENT_TYPE_SPEECH, so it sits next to the music
 * app in the mixer and both reach the headset over A2DP. Nothing here ever touches the
 * communication path, which is what keeps music in stereo for the whole ride.
 *
 * The track is never stopped mid-call. During silence it writes zeroes, because stopping
 * and restarting an A2DP stream makes the headset renegotiate and clips the first word
 * back.
 */
class VoicePlayer(
    private val jitter: JitterBuffer,
    private val beeper: Beeper,
) {
    companion object { private const val TAG = "VoicePlayer" }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    /** Linear gain on the voice. Raised when the rider chooses not to duck the music. */
    @Volatile var gain: Float = 1.0f

    @Volatile var lastOutputRms: Double = 0.0
        private set

    /**
     * Underruns reported by AudioTrack itself. These mean the app did not feed the track
     * fast enough — a scheduling or buffer-size problem — as opposed to a jitter-buffer
     * underrun, which means the frames had not arrived from the radio yet. Telling the
     * two apart is the difference between tuning the buffer and tuning the link.
     */
    val trackUnderruns: Int
        get() = runCatching { track?.underrunCount ?: 0 }.getOrDefault(0)

    fun start(): Boolean {
        if (running) return true

        val minBuf = AudioTrack.getMinBufferSize(
            AudioConstants.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return false
        }
        val bufSize = maxOf(minBuf, AudioConstants.FRAME_BYTES * 4)

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioConstants.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack construction failed", t)
            return false
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialised")
            t.release()
            return false
        }

        track = t
        running = true
        thread = Thread({ loop(t) }, "voice-player").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    private fun loop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ShortArray(AudioConstants.FRAME_SAMPLES)
        try {
            t.play()
        } catch (e: Throwable) {
            Log.e(TAG, "AudioTrack.play failed", e)
            running = false
            return
        }

        while (running) {
            val got = jitter.take(frame)
            if (!got) frame.fill(0)

            val g = gain
            if (g != 1.0f) {
                for (i in frame.indices) {
                    frame[i] = (frame[i] * g).coerceIn(-32768f, 32767f).toInt().toShort()
                }
            }

            // The mute beep is mixed into the same stream, never played on its own track.
            beeper.mixInto(frame)

            var sum = 0.0
            for (s in frame) sum += (s.toInt() * s.toInt()).toDouble()
            lastOutputRms = Math.sqrt(sum / frame.size)

            // A blocking write paces the loop at exactly one frame per 20 ms.
            val written = t.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Log.e(TAG, "AudioTrack.write error $written")
                break
            }
        }

        runCatching { t.stop() }
        runCatching { t.release() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        track = null
    }

    /**
     * Re-opens the track after the system took the headset away, for example for an
     * incoming phone call that moved it to HFP and back.
     */
    fun restart() {
        stop()
        jitter.reset()
        start()
    }

    /** Sets voice gain from a decibel offset, used by the "do not take focus" mode. */
    fun setGainDb(db: Double) {
        gain = 10.0.pow(db / 20.0).toFloat().coerceIn(0.1f, 4.0f)
    }
}
