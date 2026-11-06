package com.navigator.app.ui.screens

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.NavigatorPermissions
import com.navigator.app.ui.components.Eyebrow
import com.navigator.app.ui.components.KtmOutlineButton
import com.navigator.app.ui.components.KtmPrimaryButton
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.NavIcons

/**
 * First-run onboarding: a five-step walkthrough — brand welcome, two feature
 * highlights (dash mirroring, handlebar remote), a name step, and an explained
 * permission checklist. Nothing is requested silently on launch: the rider
 * grants permissions here with context, which also fixes the crash where the
 * pairing screen scanned before the Bluetooth permission existed.
 *
 * The intro steps are skippable straight to setup; every step is individually
 * scrollable so the layout survives small screens and short aspect ratios
 * without clipping the pinned action buttons.
 */
private const val STEP_COUNT = 7
private const val STEP_RIDES = 4
private const val STEP_NAME = 5
private const val STEP_PERMISSIONS = 6

@Composable
fun OnboardingScreen(settings: AppSettings, onComplete: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var userName by remember { mutableStateOf(settings.userName ?: "") }

    // "Skip all": one tap out of the whole wizard (R21 field request - after a
    // reinstall everything is already granted and the walkthrough is friction).
    // Still fires the runtime-permission request so a genuinely fresh install
    // isn't left with a scan that can't run; the system returns instantly when
    // everything is already granted. Completes regardless of the answer, same
    // as the permission step's "Proceed anyway".
    val skipPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        settings.onboardingComplete = true
        onComplete()
    }
    val skipAll = {
        settings.userName = userName
        skipPermLauncher.launch(NavigatorPermissions.runtimePermissions())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ktm.Screen)
            .systemBarsPadding()
            .padding(horizontal = 22.dp)
            .padding(top = 12.dp, bottom = 18.dp),
    ) {
        OnboardingHeader(
            step = step,
            onBack = when (step) {
                0 -> null
                else -> ({ step -= 1 })
            },
            onSkip = if (step >= STEP_NAME) null else skipAll,
        )
        Spacer(Modifier.height(20.dp))

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(280)) { w -> dir * w / 6 } + fadeIn(tween(220)))
                    .togetherWith(
                        slideOutHorizontally(tween(280)) { w -> -dir * w / 6 } + fadeOut(tween(160))
                    )
            },
            label = "onboarding-step",
            modifier = Modifier.weight(1f),
        ) { current ->
            Column(modifier = Modifier.fillMaxSize()) {
                when (current) {
                    0 -> WelcomeStep(
                        onContinue = { step = 1 },
                    )
                    1 -> FeatureStep(
                        step = 1,
                        eyebrow = "Mirror",
                        headline = "See it\nwithout\nlooking down.",
                        body = "Incoming notifications and live turn-by-turn navigation are mirrored " +
                            "straight to your bike's dash — so your phone stays in your pocket.",
                        illustration = { DashPreview() },
                        onContinue = { step = 2 },
                    )
                    2 -> FeatureStep(
                        step = 2,
                        eyebrow = "Handlebar remote",
                        headline = "Control it\nfrom the bar.",
                        body = "Long-press Up on the bars to switch the four buttons between a D-pad for " +
                            "your phone, music control, or handing them back to the bike. Long-press Down " +
                            "opens Google Maps.",
                        illustration = { RemotePreview() },
                        onContinue = { step = 3 },
                    )
                    3 -> RideIntelligenceStep(
                        settings = settings,
                        onContinue = { step = STEP_RIDES },
                    )
                    STEP_RIDES -> FeatureStep(
                        step = STEP_RIDES,
                        eyebrow = "Rides & analytics",
                        headline = "Every ride,\nreplayed.",
                        body = "Rides record themselves once the bike connects. Afterwards, replay the route " +
                            "on a map at 30x with speed, acceleration and altitude tracking underneath — plus " +
                            "a smoothness score, a fuel estimate, and a map of where you always lose time.",
                        illustration = { RidesPreview() },
                        onContinue = { step = STEP_NAME },
                    )
                    STEP_NAME -> NameStep(
                        settings = settings,
                        userName = userName,
                        onNameChange = { userName = it; settings.userName = it },
                        onBack = { step = STEP_RIDES },
                        onContinue = { step = STEP_PERMISSIONS },
                    )
                    else -> PermissionsStep(
                        onBack = { step = STEP_NAME },
                        onProceed = {
                            settings.userName = userName
                            settings.onboardingComplete = true
                            onComplete()
                        },
                    )
                }
            }
        }
    }
}

/** Wordmark + a segmented progress indicator that widens the current step. */
@Composable
private fun OnboardingHeader(step: Int, onBack: (() -> Unit)?, onSkip: (() -> Unit)?) {
    // Back and Skip live at the top, where the design puts them: they are
    // escape hatches, and putting them under the primary button (the old
    // layout) made "Skip all" compete with "Continue" for the same thumb.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (onBack != null) {
            Text(
                "Back", color = Ktm.Muted, fontFamily = Barlow, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        } else {
            Row {
                Text("NAVIGATOR", color = Ktm.White, fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 20.sp)
                Text("GEN3", color = Ktm.Orange, fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        if (onSkip != null) {
            Text(
                "Skip all", color = Ktm.Dim, fontFamily = Barlow, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSkip)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Segmented progress dots, centred above the primary action - the current step
 * is a wide accent pill so the position reads without counting.
 */
@Composable
private fun StepDots(step: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(STEP_COUNT) { i ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.5.dp)
                    .size(width = if (i == step) 26.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (i == step) Ktm.Orange else Ktm.Bezel),
            )
        }
    }
}

/* ----------------------------------------------------------------------- */
/* Intro steps                                                             */
/* ----------------------------------------------------------------------- */

@Composable
private fun ColumnScope.WelcomeStep(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(4.dp))
        com.navigator.app.ui.components.BikeHeroCard(
            brand = Ktm.current.brand,
            modifier = Modifier.fillMaxWidth().height(190.dp),
        )
        Spacer(Modifier.height(24.dp))
        Eyebrow("Welcome", color = Ktm.Orange, fontSize = 12, letterSpacing = 2.0)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your bike's\ndash, just got\nsmarter.",
            color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic, fontSize = 40.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Navigator Gen3 mirrors your notifications and navigation to the ${Ktm.current.displayName} dash, and turns " +
                "the handlebar remote into a hands-free controller for your phone.",
            color = Ktm.Muted2, fontFamily = Barlow, fontSize = 15.sp, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(18.dp))
        PrivacyRow()
    }
    Spacer(Modifier.height(14.dp))
    StepFooter(step = 0, label = "Get started", onContinue = onContinue)
}

@Composable
private fun ColumnScope.FeatureStep(
    step: Int,
    eyebrow: String,
    headline: String,
    body: String,
    illustration: @Composable () -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
    ) {
        Eyebrow(eyebrow, color = Ktm.Orange, fontSize = 12, letterSpacing = 2.0)
        Spacer(Modifier.height(8.dp))
        Text(
            headline,
            color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic, fontSize = 34.sp, lineHeight = 35.sp, letterSpacing = (-0.4).sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            body,
            color = Ktm.Muted2, fontFamily = Barlow, fontSize = 15.sp, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(22.dp))
        illustration()
    }
    StepFooter(step = step, label = "Next", onContinue = onContinue)
}

/**
 * Feature step 3: the riding brain - approach beeps, spoken turns, engine
 * detection, and the GPX ride log - with the two opt-ins that matter up
 * front (voice prompts, power saver) so the rider decides here rather than
 * discovering them in Settings later.
 */
@Composable
private fun ColumnScope.RideIntelligenceStep(
    settings: AppSettings,
    onContinue: () -> Unit,
) {
    var powerSave by remember { mutableStateOf(settings.powerSaveEnabled) }
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
    ) {
        Eyebrow("Ride intelligence", color = Ktm.Orange, fontSize = 12, letterSpacing = 2.0)
        Spacer(Modifier.height(8.dp))
        Text(
            "It listens,\nbeeps, and\nkeeps the log.",
            color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic, fontSize = 34.sp, lineHeight = 35.sp, letterSpacing = (-0.4).sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Stereo beeps count you down to every turn (left ear = left turn) and go quiet when " +
                "you're stopped. The phone's motion sensor learns your engine's rumble - calibrate " +
                "it later from Settings - and every ride is saved as a GPX with a speed-colored map " +
                "that knows traffic from a chai stop.",
            color = Ktm.Muted2, fontFamily = Barlow, fontSize = 15.sp, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        OnboardToggleCard(
            title = "Power saver",
            desc = "Full-rate GPS + sensors only while the phone is on bike power; gentle on battery otherwise. Turn off for full rate always.",
            checked = powerSave,
            onToggle = { powerSave = it; settings.powerSaveEnabled = it },
        )
    }
    StepFooter(step = 3, label = "Next", onContinue = onContinue)
}

@Composable
private fun OnboardToggleCard(title: String, desc: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusRow))
            .background(Ktm.Surface)
            .border(1.dp, if (checked) Ktm.ConnBorder else Ktm.Border, RoundedCornerShape(Ktm.RadiusRow))
            .clickable { onToggle(!checked) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontSize = 16.sp, letterSpacing = 0.3.sp)
            Text(desc, color = Ktm.Dim, fontFamily = Barlow, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 2.dp))
        }
        com.navigator.app.ui.components.KtmToggle(checked, onToggle)
    }
}

/** Dots + primary action, the bottom block on every intro step. */
@Composable
private fun StepFooter(step: Int, label: String, enabled: Boolean = true, onContinue: () -> Unit) {
    Spacer(Modifier.height(14.dp))
    StepDots(step)
    KtmPrimaryButton(label, enabled = enabled, onClick = onContinue)
}

/** "No sign-in · no cloud · on-device" reassurance chip row. */
@Composable
private fun PrivacyRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusRow))
            .background(Ktm.ConnBanner)
            .border(1.dp, Ktm.ConnBorder, RoundedCornerShape(Ktm.RadiusRow))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(NavIcons.ShieldCheck, contentDescription = null, tint = Ktm.Green,
            modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text("Private by design", color = Ktm.White, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.3.sp)
            Text("No sign-in, no cloud — everything stays on your phone.",
                color = Ktm.ConnSub, fontFamily = Barlow, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/* ----------------------------------------------------------------------- */
/* Feature illustrations                                                   */
/* ----------------------------------------------------------------------- */

/** Miniature of the bike dash the app drives (turn info + notification banner). */
/**
 * Rides page illustration: a speed-coloured trace with the replay puck part way
 * along it - the closest a static panel gets to showing what replay does.
 */
@Composable
private fun RidesPreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.SurfaceAlt)
            .border(1.dp, Ktm.RowDivider, RoundedCornerShape(Ktm.RadiusCard)),
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(18.dp)) {
            val w = size.width
            val h = size.height
            // A single smooth trace, drawn in three speed bands (slow -> fast)
            // using the same colour language as the ride map.
            val pts = listOf(
                androidx.compose.ui.geometry.Offset(0f, h * 0.82f),
                androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.62f),
                androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.70f),
                androidx.compose.ui.geometry.Offset(w * 0.52f, h * 0.34f),
                androidx.compose.ui.geometry.Offset(w * 0.70f, h * 0.44f),
                androidx.compose.ui.geometry.Offset(w * 0.86f, h * 0.16f),
                androidx.compose.ui.geometry.Offset(w, h * 0.24f),
            )
            val bands = listOf(Ktm.Green, Ktm.Orange, Ktm.Danger)
            for (i in 0 until pts.size - 1) {
                drawLine(
                    color = bands[(i * bands.size) / (pts.size - 1)],
                    start = pts[i], end = pts[i + 1],
                    strokeWidth = 5.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            // The replay puck, two thirds along.
            val puck = pts[4]
            drawCircle(Ktm.Orange.copy(alpha = 0.22f), radius = 17.dp.toPx(), center = puck)
            drawCircle(Ktm.White, radius = 7.dp.toPx(), center = puck)
        }
        Text(
            "REPLAY · 30x",
            color = Ktm.Dim, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
            fontSize = 10.sp, letterSpacing = 2.2.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun DashPreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.ScreenDeep)
            .border(1.dp, Ktm.Bezel, RoundedCornerShape(Ktm.RadiusCard))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(NavIcons.TurnArrow, contentDescription = null, tint = Ktm.Orange,
                modifier = Modifier.size(44.dp))
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("400", color = Ktm.White, fontFamily = BarlowCondensed,
                        fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 40.sp)
                    Text(" m", color = Ktm.Muted, fontFamily = BarlowCondensed,
                        fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
                Text("OUTER RING ROAD", color = Ktm.Orange, fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("ETA", color = Ktm.Dim2, fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp)
                Text("11:09", color = Ktm.White, fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Ktm.RowDivider))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Ktm.DashBanner)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(NavIcons.ShieldCheck, contentDescription = null, tint = Ktm.Orange,
                modifier = Modifier.size(22.dp))
            Text("Messages — running 5 min late", color = Ktm.TextPrimary, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** The four handlebar keys with their idle-mode actions. */
@Composable
private fun RemotePreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.Surface)
            .border(1.dp, Ktm.Border, RoundedCornerShape(Ktm.RadiusCard))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemoteRow("UP", "Next track")
        RemoteRow("DOWN", "Previous track")
        RemoteRow("SET", "Play / pause · answer call")
        RemoteRow("BACK", "Open the menu")
    }
}

@Composable
private fun RemoteRow(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(58.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Ktm.Orange)
                .padding(vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(key, color = Ktm.Screen, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
        }
        Text(action, color = Ktm.TextSecondary, fontFamily = Barlow, fontSize = 14.sp,
            modifier = Modifier.padding(start = 14.dp))
    }
}

/* ----------------------------------------------------------------------- */
/* Name + permission steps                                                 */
/* ----------------------------------------------------------------------- */

@Composable
private fun ColumnScope.NameStep(
    settings: AppSettings,
    userName: String,
    onNameChange: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Eyebrow("Almost there", color = Ktm.Orange, fontSize = 12, letterSpacing = 2.0)
        Spacer(Modifier.height(8.dp))
        Text(
            "What should\nwe call you?",
            color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic, fontSize = 34.sp, lineHeight = 35.sp, letterSpacing = (-0.4).sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Used to personalize the dash greeting and test messages. This never leaves your phone.",
            color = Ktm.Muted2, fontFamily = Barlow, fontSize = 15.sp, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(26.dp))
        Eyebrow("Your name", color = Ktm.Dim, fontSize = 11, letterSpacing = 1.5)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = userName,
            onValueChange = onNameChange,
            singleLine = true,
            placeholder = { Text("e.g. Rider", color = Ktm.Dim, fontFamily = Barlow) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = ktmFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        // Which KTM: picks the home card's art ("READY TO RACE" for the Duke).
        // Husqvarna has one artwork, so the question is KTM-only.
        if (Ktm.current.brand == com.navigator.app.ui.theme.Brand.KTM) {
            Spacer(Modifier.height(26.dp))
            Eyebrow("Which KTM do you ride?", color = Ktm.Dim, fontSize = 11, letterSpacing = 1.5)
            Spacer(Modifier.height(10.dp))
            BikeModelPicker(settings)
        }
    }
    StepFooter(step = STEP_NAME, label = "Continue", enabled = userName.isNotBlank(), onContinue = onContinue)
}

/**
 * Two picture cards, Duke and Adventure. The choice is saved the moment it's
 * tapped (like the name field above), so "Back" or leaving mid-wizard keeps it.
 * Also used by Settings, so riders past onboarding can change it.
 */
@Composable
fun BikeModelPicker(settings: AppSettings, onChanged: (com.navigator.app.ui.components.BikeModel) -> Unit = {}) {
    var model by remember { mutableStateOf(settings.bikeModel) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(
            com.navigator.app.ui.components.BikeModel.DUKE,
            com.navigator.app.ui.components.BikeModel.ADVENTURE,
        ).forEach { option ->
            val selected = option == model
            val art = com.navigator.app.ui.components.bikeArtFor(com.navigator.app.ui.theme.Brand.KTM, option)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(art.cardColor)
                    .border(
                        if (selected) 2.dp else 1.dp,
                        if (selected) Ktm.Orange else Ktm.Border,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable {
                        model = option
                        settings.bikeModel = option
                        onChanged(option)
                    }
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.Image(
                    bitmap = com.navigator.app.ui.components.BikeBitmaps.get(
                        androidx.compose.ui.platform.LocalContext.current, art.drawable,
                    ),
                    contentDescription = "KTM ${option.label}",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(86.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    option.label.uppercase(),
                    color = if (selected) Ktm.Orange else androidx.compose.ui.graphics.Color.White,
                    fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic, fontSize = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.PermissionsStep(
    onBack: () -> Unit,
    onProceed: () -> Unit,
) {
    val context = LocalContext.current

    // Re-checked on resume so grants made in system settings reflect here.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }

    // Read live status (tick forces recomposition after grants).
    val btGranted = tick.let { NavigatorPermissions.bluetoothGranted(context) }
    val notifPost = tick.let { NavigatorPermissions.notificationsPostGranted(context) }
    val phone = tick.let { NavigatorPermissions.phoneGranted(context) }
    val notifAccess = tick.let { NavigatorPermissions.notificationAccessGranted(context) }
    val runtimeAllGranted = btGranted && notifPost && phone

    Eyebrow("Permissions", color = Ktm.Orange, fontSize = 12, letterSpacing = 2.0)
    Spacer(Modifier.height(8.dp))
    Text(
        "A few permissions.",
        color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic, fontSize = 34.sp, letterSpacing = (-0.4).sp,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Navigator Gen3 needs these to talk to your bike. Nothing leaves your phone.",
        color = Ktm.Muted2, fontFamily = Barlow, fontSize = 14.sp, lineHeight = 20.sp,
    )

    Spacer(Modifier.height(18.dp))
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PermissionCard(
            icon = NavIcons.Bluetooth,
            title = "Nearby devices",
            desc = "Find and connect to your bike's dash over Bluetooth. Required to pair.",
            granted = btGranted,
            onGrant = { permLauncher.launch(NavigatorPermissions.runtimePermissions()) },
        )
        PermissionCard(
            icon = NavIcons.Bell,
            title = "Notification access",
            desc = "Read incoming notifications so they can be mirrored to the dash.",
            granted = notifAccess,
            onGrant = { context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        )
        PermissionCard(
            icon = NavIcons.Phone,
            title = "Phone & calls",
            desc = "Answer and silence calls from the handlebar remote while riding.",
            granted = phone,
            onGrant = { permLauncher.launch(NavigatorPermissions.runtimePermissions()) },
        )
        PermissionCard(
            icon = NavIcons.Navigation,
            title = "Post notifications",
            desc = "Show the persistent connection status and dash-mirroring service.",
            granted = notifPost,
            onGrant = { permLauncher.launch(NavigatorPermissions.runtimePermissions()) },
        )
    }

    Spacer(Modifier.height(12.dp))
    if (!runtimeAllGranted) {
        KtmPrimaryButton("Grant permissions") {
            permLauncher.launch(NavigatorPermissions.runtimePermissions())
        }
        Spacer(Modifier.height(10.dp))
        KtmOutlineButton(
            if (btGranted) "Proceed" else "Proceed anyway",
            onClick = onProceed,
        )
    } else {
        StepDots(STEP_PERMISSIONS)
        KtmPrimaryButton("Proceed", onClick = onProceed)
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    desc: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusRow))
            .background(Ktm.Surface)
            .border(1.dp, if (granted) Ktm.ConnBorder else Ktm.Border, RoundedCornerShape(Ktm.RadiusRow))
            .clickable(enabled = !granted, onClick = onGrant)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (granted) Ktm.Green else Ktm.Orange,
            modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontSize = 17.sp, letterSpacing = 0.3.sp)
            Text(desc, color = Ktm.Dim, fontFamily = Barlow, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 2.dp))
        }
        if (granted) {
            Icon(NavIcons.Check, contentDescription = "Granted", tint = Ktm.Green, modifier = Modifier.size(20.dp))
        } else {
            Text("GRANT", color = Ktm.Orange, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
internal fun ktmFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Ktm.Orange,
    unfocusedBorderColor = Ktm.Border,
    focusedTextColor = Ktm.White,
    unfocusedTextColor = Ktm.TextPrimary,
    cursorColor = Ktm.Orange,
    focusedContainerColor = Ktm.SurfaceAlt,
    unfocusedContainerColor = Ktm.SurfaceAlt,
)
