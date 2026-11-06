package com.mensa.riderintercom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mensa.riderintercom.audio.MicSource
import com.mensa.riderintercom.audio.NoiseSuppression
import com.mensa.riderintercom.floor.VadSensitivity
import com.mensa.riderintercom.util.Settings as RiderSettings
import com.mensa.riderintercom.util.TalkMode
import com.mensa.riderintercom.util.TransportPreference

/**
 * Everything the rider can change. The wording matters here: each option says what it
 * costs, because most of these are trade-offs rather than preferences.
 */
@Composable
fun SettingsScreen(
    settings: RiderSettings,
    lastKeyCode: Int,
    platformEffects: String = "",
    onChange: ((RiderSettings) -> RiderSettings) -> Unit,
    onBack: () -> Unit,
    onOem: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Brand.Background)
            .verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
    ) {
        ScreenHeader(title = "Settings", onBack = onBack)

        SectionCard(title = "How you talk") {
            Text(
                "An open call is the point of this app: the channel is always up and " +
                    "speaking is all it takes. Push-to-talk is there for helmets too noisy " +
                    "for a detector, or riders who would rather hold a button.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            TalkMode.entries.forEach { mode ->
                ChoiceRow(
                    selected = settings.talkMode == mode,
                    title = mode.label,
                    subtitle = when (mode) {
                        TalkMode.Open -> "Voice-activated. First speaker takes the channel; " +
                            "everyone else is muted until they stop."
                        TalkMode.PushToTalk -> "The detector never opens the channel. Only " +
                            "the on-screen button, the notification or the headset button do."
                    },
                    onClick = { onChange { it.copy(talkMode = mode) } },
                )
            }
        }

        SectionCard(title = "Microphone") {
            Text(
                "The headset mic is the one trap in this design: opening it drops the " +
                    "headset off A2DP and music turns mono for the rest of the ride.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            MicSource.entries.forEach { src ->
                ChoiceRow(
                    selected = settings.micSource == src,
                    title = micTitle(src),
                    subtitle = micSubtitle(src),
                    onClick = { onChange { it.copy(micSource = src) } },
                )
            }
        }

        SectionCard(title = "Noise cancellation") {
            Text(
                "This cleans the voice you send, so your buddy hears you instead of your " +
                    "engine. It cannot cancel noise at your own ear the way an aviation " +
                    "headset does \u2014 that needs a microphone inside the earcup and a " +
                    "sub-millisecond loop, which is a property of the headset, not the phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            NoiseSuppression.entries.forEach { ns ->
                ChoiceRow(
                    selected = settings.noiseSuppression == ns,
                    title = ns.label,
                    subtitle = noiseSubtitle(ns),
                    onClick = { onChange { it.copy(noiseSuppression = ns) } },
                )
            }
            if (platformEffects.isNotEmpty()) {
                Text(
                    "This phone also offers: $platformEffects. Those run in hardware and " +
                        "are used automatically when noise cancellation is on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = IntercomColors.dim,
                )
            }
        }

        SectionCard(title = "Voice detection") {
            Text(
                "How eagerly the channel opens when you speak. Lower sensitivity at " +
                    "highway speed, higher with a boom mic.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            VadSensitivity.entries.forEach { v ->
                ChoiceRow(
                    selected = settings.vadSensitivity == v,
                    title = v.name,
                    subtitle = "${v.thresholdDb.toInt()} dB over the noise floor, " +
                        "${v.attackFrames * 20} ms to open",
                    onClick = { onChange { it.copy(vadSensitivity = v) } },
                )
            }
        }

        SectionCard(title = "Transport") {
            TransportPreference.entries.forEach { t ->
                ChoiceRow(
                    selected = settings.transport == t,
                    title = t.name,
                    subtitle = when (t) {
                        TransportPreference.Auto -> "Bluetooth now; longer-range radios when added"
                        TransportPreference.Bluetooth -> "Bluetooth RFCOMM only, 10 to 30 m"
                        TransportPreference.Range -> "Prefer the long-range radio (phase 2)"
                    },
                    enabled = t != TransportPreference.Range,
                    onClick = { onChange { it.copy(transport = t) } },
                )
            }
            Text(
                "Wi-Fi Direct and Nearby Connections are phase 2. The transport sits " +
                    "behind an interface, so the audio pipeline does not change when they land.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
        }

        SectionCard(title = "Voice level") {
            Text(
                "${settings.voiceLevelDb} dB",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = settings.voiceLevelDb.toFloat(),
                onValueChange = { onChange { s -> s.copy(voiceLevelDb = it.toInt()) } },
                valueRange = -12f..12f,
                steps = 23,
                colors = SliderDefaults.colors(
                    thumbColor = Brand.Accent,
                    activeTrackColor = Brand.Accent,
                    inactiveTrackColor = Brand.SurfaceElevated,
                    activeTickColor = Brand.AccentDeep,
                    inactiveTickColor = Brand.Border,
                ),
            )
            Text(
                "Music stays on the phone's media volume. The headset volume rocker " +
                    "moves both together, because the voice plays as media.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
        }

        SectionCard(title = "Music") {
            ToggleRow(
                checked = settings.takeAudioFocus,
                title = "Duck music while talking",
                subtitle = "Off: the intercom takes no audio focus at all, music keeps " +
                    "playing at full volume and the voice is lifted 6 dB instead. Use this " +
                    "if your music app pauses rather than ducking.",
                onChange = { v -> onChange { it.copy(takeAudioFocus = v) } },
            )
        }

        SectionCard(title = "Mute beep") {
            ToggleRow(
                checked = settings.beepWhenMuted,
                title = "Beep when you talk over your buddy",
                subtitle = "A short local tone, never sent to the other rider, at most " +
                    "one every two seconds.",
                onChange = { v -> onChange { it.copy(beepWhenMuted = v) } },
            )
        }

        SectionCard(title = "Headset button") {
            ToggleRow(
                checked = settings.headsetButtonEnabled,
                title = "Use the headset button",
                subtitle = "Double press toggles push-to-talk, triple or long press ends " +
                    "the call. Single press is left to your music app.",
                onChange = { v -> onChange { it.copy(headsetButtonEnabled = v) } },
            )
            Text(
                if (lastKeyCode == 0)
                    "Calibration: start a call, then press your headset button here to " +
                        "see which key code it sends."
                else "Last key code received: $lastKeyCode",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            Text(
                "Headsets differ, and some map their main button to the voice assistant, " +
                    "which the app cannot change.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
        }

        SectionCard(title = "Keeping the call alive") {
            SecondaryButton("Background and battery steps", onOem)
        }
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = Brand.Accent,
                unselectedColor = Brand.Idle,
                disabledUnselectedColor = Brand.Border,
            ),
        )
        Column(Modifier.padding(start = 4.dp, top = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else IntercomColors.dim,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Brand.TextPrimary,
                checkedTrackColor = Brand.Accent,
                uncheckedThumbColor = Brand.TextDim,
                uncheckedTrackColor = Brand.SurfaceElevated,
                uncheckedBorderColor = Brand.Border,
            ),
        )
    }
}

private fun micTitle(src: MicSource): String = when (src) {
    MicSource.Wired -> "Wired boom mic (best)"
    MicSource.Phone -> "Phone microphone"
    MicSource.LeAudio -> "LE Audio headset"
    MicSource.HeadsetHfp -> "Headset mic (music quality drops)"
}

private fun micSubtitle(src: MicSource): String = when (src) {
    MicSource.Wired -> "USB-C or 3.5 mm, mounted in the helmet. Best wind rejection, " +
        "and the headset stays on A2DP so music stays stereo."
    MicSource.Phone -> "Keeps the headset on A2DP. Usable at town speeds; wind beats it " +
        "on the highway."
    MicSource.LeAudio -> "Android 13+ with an LE Audio headset: mic and high-quality " +
        "output at once. Few helmet headsets support it yet."
    MicSource.HeadsetHfp -> "Convenient, but Android suspends A2DP while the headset mic " +
        "is open, so music turns mono and muffled for the whole call."
}

private fun noiseSubtitle(ns: NoiseSuppression): String = when (ns) {
    NoiseSuppression.Off -> "Only the 150 Hz high-pass and noise gate. Most natural voice, " +
        "but your buddy hears the bike."
    NoiseSuppression.Light -> "Town riding. Takes out the steady hiss and rumble without " +
        "touching the voice."
    NoiseSuppression.Strong -> "Highway. Removes engine and wind roar; the voice sounds " +
        "slightly processed in exchange."
}
