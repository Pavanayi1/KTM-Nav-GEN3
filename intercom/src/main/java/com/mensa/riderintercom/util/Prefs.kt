package com.mensa.riderintercom.util

import android.content.Context
import android.content.SharedPreferences
import com.mensa.riderintercom.audio.MicSource
import com.mensa.riderintercom.audio.NoiseSuppression
import com.mensa.riderintercom.floor.VadSensitivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How the rider takes the floor.
 *
 * [Open] is the default and the point of the product: a continuous call where speaking
 * is all it takes. [PushToTalk] is for riders who would rather hold a button than trust
 * a detector at speed, or whose helmet is too noisy for one.
 */
enum class TalkMode(val label: String) {
    Open("Open call"),
    PushToTalk("Push to talk"),
}

/** Which transport the rider wants the link to use. */
enum class TransportPreference { Auto, Bluetooth, Range }

/** Everything the rider can change, in one place. */
data class Settings(
    val riderName: String = "Rider",
    val buddyAddress: String? = null,
    val buddyName: String? = null,
    val transport: TransportPreference = TransportPreference.Auto,
    val talkMode: TalkMode = TalkMode.Open,
    val micSource: MicSource = MicSource.Phone,
    val noiseSuppression: NoiseSuppression = NoiseSuppression.Light,
    val vadSensitivity: VadSensitivity = VadSensitivity.Medium,
    /** Voice level in dB, -12..+12, applied on top of the stream. */
    val voiceLevelDb: Int = 0,
    val beepWhenMuted: Boolean = true,
    val takeAudioFocus: Boolean = true,
    val headsetButtonEnabled: Boolean = true,
)

/** Thin SharedPreferences wrapper that publishes the whole [Settings] as a flow. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("rider_intercom", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings

    val current: Settings get() = _settings.value

    private fun load(): Settings = Settings(
        riderName = sp.getString(K_NAME, "Rider") ?: "Rider",
        buddyAddress = sp.getString(K_BUDDY_ADDR, null),
        buddyName = sp.getString(K_BUDDY_NAME, null),
        transport = enumOf(sp.getString(K_TRANSPORT, null), TransportPreference.Auto),
        talkMode = enumOf(sp.getString(K_TALK, null), TalkMode.Open),
        micSource = enumOf(sp.getString(K_MIC, null), MicSource.Phone),
        noiseSuppression = enumOf(sp.getString(K_NS, null), NoiseSuppression.Light),
        vadSensitivity = enumOf(sp.getString(K_VAD, null), VadSensitivity.Medium),
        voiceLevelDb = sp.getInt(K_VOICE_DB, 0),
        beepWhenMuted = sp.getBoolean(K_BEEP, true),
        takeAudioFocus = sp.getBoolean(K_FOCUS, true),
        headsetButtonEnabled = sp.getBoolean(K_BUTTON, true),
    )

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    fun update(block: (Settings) -> Settings) {
        val next = block(_settings.value)
        sp.edit().apply {
            putString(K_NAME, next.riderName)
            putString(K_BUDDY_ADDR, next.buddyAddress)
            putString(K_BUDDY_NAME, next.buddyName)
            putString(K_TRANSPORT, next.transport.name)
            putString(K_TALK, next.talkMode.name)
            putString(K_MIC, next.micSource.name)
            putString(K_NS, next.noiseSuppression.name)
            putString(K_VAD, next.vadSensitivity.name)
            putInt(K_VOICE_DB, next.voiceLevelDb)
            putBoolean(K_BEEP, next.beepWhenMuted)
            putBoolean(K_FOCUS, next.takeAudioFocus)
            putBoolean(K_BUTTON, next.headsetButtonEnabled)
        }.apply()
        _settings.value = next
    }

    /** Set once, after the rider has been shown the OEM background-permission steps. */
    var oemGuidanceShown: Boolean
        get() = sp.getBoolean(K_OEM_SHOWN, false)
        set(v) { sp.edit().putBoolean(K_OEM_SHOWN, v).apply() }

    private companion object {
        const val K_NAME = "rider_name"
        const val K_BUDDY_ADDR = "buddy_addr"
        const val K_BUDDY_NAME = "buddy_name"
        const val K_TRANSPORT = "transport"
        const val K_TALK = "talk_mode"
        const val K_MIC = "mic_source"
        const val K_NS = "noise_suppression"
        const val K_VAD = "vad"
        const val K_VOICE_DB = "voice_db"
        const val K_BEEP = "beep"
        const val K_FOCUS = "focus"
        const val K_BUTTON = "button"
        const val K_OEM_SHOWN = "oem_shown"
    }
}
