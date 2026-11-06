package com.navigator.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.logging.AppLogger
import com.navigator.app.sensors.VibrationMonitor
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.components.Eyebrow
import com.navigator.app.ui.components.KtmOutlineButton
import com.navigator.app.ui.components.KtmPrimaryButton
import com.navigator.app.ui.components.ScreenHeader
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.NavIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Guided engine-vibration calibration. Two 10-second captures with the phone
 * exactly where the rider usually keeps it: first with the bike fully OFF
 * (the mount/pocket noise floor), then with the engine idling. The threshold
 * between those two levels is what [VibrationMonitor] uses to tell "engine
 * running" from "parked", and it keeps refining itself gradually on real rides.
 */
private enum class CalPhase { INTRO, BASELINE, START_BIKE, ENGINE, RESULT }

private const val CAPTURE_SECONDS = 10
private const val START_BIKE_SECONDS = 10

@Composable
fun VibrationCalibrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var phase by remember { mutableStateOf(CalPhase.INTRO) }
    var countdown by remember { mutableIntStateOf(CAPTURE_SECONDS) }
    var idleRms by remember { mutableFloatStateOf(Float.NaN) }
    var engineRms by remember { mutableFloatStateOf(Float.NaN) }
    var failed by remember { mutableStateOf<String?>(null) }

    // Phase driver: each capture phase runs its timer + sensor capture, then
    // advances automatically - the rider's hands stay on the bike, not the phone.
    LaunchedEffect(phase) {
        when (phase) {
            CalPhase.BASELINE -> {
                countdown = CAPTURE_SECONDS
                val ticker = launch {
                    while (countdown > 0) { delay(1000); countdown-- }
                }
                val rms = VibrationMonitor.captureRms(context, CAPTURE_SECONDS * 1000L)
                ticker.cancel()
                if (rms == null) {
                    failed = "Couldn't read the accelerometer."
                    phase = CalPhase.RESULT
                } else {
                    idleRms = rms
                    AppLogger.log("Vibe", "Calibration baseline rms=%.4f".format(rms))
                    phase = CalPhase.START_BIKE
                }
            }
            CalPhase.START_BIKE -> {
                countdown = START_BIKE_SECONDS
                while (countdown > 0) { delay(1000); countdown-- }
                phase = CalPhase.ENGINE
            }
            CalPhase.ENGINE -> {
                countdown = CAPTURE_SECONDS
                val ticker = launch {
                    while (countdown > 0) { delay(1000); countdown-- }
                }
                val rms = VibrationMonitor.captureRms(context, CAPTURE_SECONDS * 1000L)
                ticker.cancel()
                if (rms == null) {
                    failed = "Couldn't read the accelerometer."
                } else {
                    engineRms = rms
                    AppLogger.log("Vibe", "Calibration engine rms=%.4f".format(rms))
                    if (engineRms > idleRms * 1.5f) {
                        settings.vibrationIdleRms = idleRms
                        settings.vibrationEngineRms = engineRms
                        settings.engineDetectEnabled = true
                        com.navigator.app.ble.BccuConnectionService.reevaluateEngineDetectIfRunning()
                    } else {
                        failed = "The engine vibration wasn't clearly above the noise floor. " +
                            "Try again with the phone firmly in its usual spot (a solid mount works best)."
                    }
                }
                phase = CalPhase.RESULT
            }
            else -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ktm.Screen)
            .systemBarsPadding()
            .padding(horizontal = 22.dp)
            .padding(top = 12.dp, bottom = 18.dp),
    ) {
        // Header: back chevron + title (matches the other sub-screens).
        ScreenHeader("Engine calibration", onBack = onBack)
        Spacer(Modifier.height(24.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (phase) {
                CalPhase.INTRO -> {
                    Eyebrow("Set up", color = Ktm.Orange, fontSize = 12, letterSpacing = 2.0)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Teach it your\nbike's rumble.",
                        color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic, fontSize = 36.sp, lineHeight = 34.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "The phone's motion sensor learns the difference between \"bike off\" and " +
                            "\"engine running\", so beeps go quiet when you kill the engine and ride " +
                            "logs can tell traffic from a chai stop.",
                        color = Ktm.Muted2, fontFamily = Barlow, fontSize = 15.sp, lineHeight = 22.sp,
                    )
                    Spacer(Modifier.height(20.dp))
                    StepCard(1, "Put the phone where it usually rides — mount or pocket. Bike completely OFF.")
                    Spacer(Modifier.height(10.dp))
                    StepCard(2, "Tap Start. Don't touch the phone for 10 seconds.")
                    Spacer(Modifier.height(10.dp))
                    StepCard(3, "When asked, start the engine and let it idle for 10 more seconds. Done.")
                }
                CalPhase.BASELINE -> CapturePane(
                    eyebrow = "Step 1 of 2 — bike OFF",
                    big = "$countdown",
                    caption = "Measuring the quiet floor.\nHands off the phone, engine off.",
                    accent = Ktm.Orange,
                )
                CalPhase.START_BIKE -> CapturePane(
                    eyebrow = "Get ready",
                    big = "$countdown",
                    caption = "START THE ENGINE NOW.\nLeave it idling — don't rev, don't touch the phone.",
                    accent = Ktm.Green,
                )
                CalPhase.ENGINE -> CapturePane(
                    eyebrow = "Step 2 of 2 — engine idling",
                    big = "$countdown",
                    caption = "Listening to the engine.\nKeep it idling, hands off the phone.",
                    accent = Ktm.Green,
                )
                CalPhase.RESULT -> ResultPane(
                    failed = failed,
                    idleRms = idleRms,
                    engineRms = engineRms,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        when (phase) {
            CalPhase.INTRO -> KtmPrimaryButton("Start — bike is OFF") { phase = CalPhase.BASELINE }
            CalPhase.RESULT -> {
                if (failed == null) {
                    Text(
                        "You can switch the bike off and pick up the phone.",
                        color = Ktm.Dim, fontFamily = Barlow, fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    )
                    KtmPrimaryButton("Done", onClick = onBack)
                } else {
                    KtmPrimaryButton("Try again") { failed = null; phase = CalPhase.INTRO }
                    Spacer(Modifier.height(10.dp))
                    KtmOutlineButton("Not now", onClick = onBack)
                }
            }
            else -> Text(
                "Calibrating… keep hands off the phone.",
                color = Ktm.Dim, fontFamily = Barlow, fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StepCard(n: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusRow))
            .background(Ktm.Surface)
            .border(1.dp, Ktm.Border, RoundedCornerShape(Ktm.RadiusRow))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(26.dp).clip(CircleShape).background(Ktm.Orange),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", color = Ktm.Screen, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Text(text, color = Ktm.TextSecondary, fontFamily = Barlow, fontSize = 14.sp,
            lineHeight = 19.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Big countdown number with a phase caption - readable from the saddle. */
@Composable
private fun CapturePane(eyebrow: String, big: String, caption: String, accent: androidx.compose.ui.graphics.Color) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Eyebrow(eyebrow, color = accent, fontSize = 12, letterSpacing = 2.0)
        Text(
            big,
            color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic, fontSize = 120.sp,
        )
        Text(
            caption,
            color = Ktm.Muted2, fontFamily = Barlow, fontSize = 16.sp, lineHeight = 23.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ResultPane(failed: String?, idleRms: Float, engineRms: Float) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        if (failed != null) {
            Eyebrow("Didn't take", color = Ktm.Danger, fontSize = 12, letterSpacing = 2.0)
            Spacer(Modifier.height(8.dp))
            Text("Let's try that\nagain.", color = Ktm.White, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 34.sp, lineHeight = 32.sp)
            Spacer(Modifier.height(12.dp))
            Text(failed, color = Ktm.Muted2, fontFamily = Barlow, fontSize = 15.sp, lineHeight = 22.sp)
            return@Column
        }
        val ratio = engineRms / idleRms
        Eyebrow("Calibrated", color = Ktm.Green, fontSize = 12, letterSpacing = 2.0)
        Spacer(Modifier.height(8.dp))
        Text("Engine detect\nis live.", color = Ktm.White, fontFamily = BarlowCondensed,
            fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 34.sp, lineHeight = 32.sp)
        Spacer(Modifier.height(16.dp))
        MetricRow("Quiet floor", "%.4f".format(idleRms))
        MetricRow("Engine idle", "%.4f".format(engineRms))
        MetricRow("Separation", "%.1f×  %s".format(ratio, if (ratio >= 3f) "· strong" else "· usable"))
        Spacer(Modifier.height(12.dp))
        Text(
            "It keeps tuning itself as you ride. If it misreads, nudge the sensitivity in Settings → Riding.",
            color = Ktm.Dim, fontFamily = Barlow, fontSize = 13.sp, lineHeight = 18.sp,
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Ktm.TextSecondary, fontFamily = Barlow, fontSize = 14.sp)
        Text(value, color = Ktm.White, fontFamily = JetBrainsMono, fontSize = 14.sp)
    }
}
