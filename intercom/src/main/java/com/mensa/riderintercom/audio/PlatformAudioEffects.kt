package com.mensa.riderintercom.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * The phone's own capture-side effects, where the hardware offers them.
 *
 * These sit in the audio HAL ahead of anything this app can do, cost no CPU, and are
 * tuned by the manufacturer for that specific microphone. They are worth taking whenever
 * they exist, with [SpectralDenoiser] running afterwards to deal with what is left.
 *
 * Availability is per device and frequently false on budget hardware, so every one of
 * these is best-effort and the pipeline must sound correct without them.
 */
class PlatformAudioEffects {

    companion object { private const val TAG = "PlatformAudioFx" }

    private var suppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null

    /** What actually got enabled, for the log and the settings screen. */
    var report: String = "none"
        private set

    /**
     * Attaches to an [android.media.AudioRecord] session.
     *
     * The echo canceller is included because with in-helmet speakers close to the ear
     * there is a real acoustic path back into a boom mic. It only engages on some
     * devices, and the design deliberately avoids communication mode where Android's own
     * canceller would run, so taking it here when offered costs nothing.
     */
    fun attach(audioSessionId: Int, wantNoiseSuppression: Boolean) {
        release()
        val active = mutableListOf<String>()

        if (wantNoiseSuppression && NoiseSuppressor.isAvailable()) {
            runCatching {
                suppressor = NoiseSuppressor.create(audioSessionId)?.also { it.enabled = true }
            }.onFailure { Log.w(TAG, "NoiseSuppressor failed: ${it.message}") }
            if (suppressor?.enabled == true) active += "NS"
        }

        if (AutomaticGainControl.isAvailable()) {
            runCatching {
                agc = AutomaticGainControl.create(audioSessionId)?.also { it.enabled = true }
            }.onFailure { Log.w(TAG, "AGC failed: ${it.message}") }
            if (agc?.enabled == true) active += "AGC"
        }

        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                aec = AcousticEchoCanceler.create(audioSessionId)?.also { it.enabled = true }
            }.onFailure { Log.w(TAG, "AEC failed: ${it.message}") }
            if (aec?.enabled == true) active += "AEC"
        }

        report = if (active.isEmpty()) "none" else active.joinToString("+")
        Log.i(TAG, "platform effects enabled: $report")
    }

    fun release() {
        runCatching { suppressor?.release() }
        runCatching { agc?.release() }
        runCatching { aec?.release() }
        suppressor = null
        agc = null
        aec = null
        report = "none"
    }

    /** For the settings screen, so the rider can see what their phone actually offers. */
    fun availability(): String = buildList {
        if (NoiseSuppressor.isAvailable()) add("noise suppressor")
        if (AutomaticGainControl.isAvailable()) add("auto gain")
        if (AcousticEchoCanceler.isAvailable()) add("echo canceller")
    }.let { if (it.isEmpty()) "none" else it.joinToString(", ") }
}
