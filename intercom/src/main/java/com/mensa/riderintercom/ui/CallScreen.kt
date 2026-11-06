package com.mensa.riderintercom.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.sp
import com.mensa.riderintercom.R
import com.mensa.riderintercom.call.CallPhase
import com.mensa.riderintercom.call.CallStatus
import com.mensa.riderintercom.audio.NoiseSuppression
import com.mensa.riderintercom.floor.FloorState
import com.mensa.riderintercom.util.TalkMode

/**
 * The screen the rider glances at from the saddle.
 *
 * The dial's colour is the whole message: orange means the channel is clear, green means
 * you have it, amber means your buddy does. Everything else on the screen is secondary to
 * reading that in under a second, through a visor, at speed.
 */
@Composable
fun CallScreen(
    status: CallStatus,
    riderName: String,
    micLevel: Float,
    talkMode: TalkMode,
    noiseSuppression: NoiseSuppression,
    onCycleNoise: () -> Unit,
    onPushToTalkDown: () -> Unit,
    onPushToTalkUp: () -> Unit,
    onEnd: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) = WithAccents(host = status.isHost) {
    val accents = LocalAccents.current
    val stateColor = when {
        !status.isConnected -> Brand.Idle
        status.floor == FloorState.Talking -> Brand.Talking
        status.floor == FloorState.Muted -> Brand.Buddy
        else -> accents.base
    }
    val dialColor by animateColorAsState(stateColor, label = "dial")

    val musicIcon = ImageVector.vectorResource(R.drawable.ic_music_note)
    val audioIcon = ImageVector.vectorResource(R.drawable.ic_volume_up)

    Column(
        Modifier
            .fillMaxSize()
            .background(Brand.Background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 26.dp)
    ) {
        BrandHeader(
            trailing = {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Brand.Surface)
                        .clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Settings, contentDescription = "Settings",
                        tint = Brand.TextPrimary, modifier = Modifier.size(21.dp),
                    )
                }
            }
        )

        // ---- who you are connected to -------------------------------------------
        Box(Modifier.padding(horizontal = 16.dp)) {
            StatusPill(
                headline = when {
                    status.isConnected -> status.peerName?.let { "Linked with $it" } ?: "Linked"
                    status.isHost -> "Hosting"
                    else -> "Joining"
                },
                detail = status.statusText,
                dotColor = if (status.isConnected) Brand.Talking else Brand.Buddy,
            )
        }

        // ---- the dial -----------------------------------------------------------
        Spacer(Modifier.height(26.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MicDial(
                    color = dialColor,
                    icon = if (status.floor == FloorState.Muted) MicIcons.MicOff else MicIcons.Mic,
                    active = status.floor != FloorState.Idle,
                    size = 210.dp,
                )
                Spacer(Modifier.height(16.dp))
                DotStatus(
                    text = when {
                        !status.isConnected -> phaseLabel(status)
                        status.floor == FloorState.Talking -> "You are talking"
                        status.floor == FloorState.Muted ->
                            "${status.peerName ?: "Buddy"} is talking"
                        else -> "Channel clear"
                    },
                    color = dialColor,
                    subtitle = when {
                        !status.isConnected ->
                            if (status.isHost) "Your buddy joins from their phone"
                            else "Reaching the other phone"
                        status.floor == FloorState.Muted -> "Your mic is muted until they stop"
                        else -> "Speak to talk · pause to release"
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ---- push to talk, only when that is the chosen mode ---------------------
        if (talkMode == TalkMode.PushToTalk) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(92.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .let {
                                if (status.pushToTalk) it.background(Brand.Talking)
                                else if (status.isConnected) it.background(accents.gradient)
                                else it.background(Brand.SurfaceElevated)
                            }
                            .pointerInput(status.isConnected) {
                                detectTapGestures(
                                    onPress = {
                                        if (!status.isConnected) return@detectTapGestures
                                        onPushToTalkDown()
                                        tryAwaitRelease()
                                        onPushToTalkUp()
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (status.pushToTalk) "TALKING" else "HOLD TO TALK",
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            color = if (status.isConnected) Color(0xFF13240F) else Brand.Idle,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Push-to-talk mode \u2014 the voice detector will not open the channel " +
                            "on its own.",
                        fontSize = 12.sp, color = Brand.TextDim, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            // Open call: speaking is enough. The button stays as a smaller override for
            // cutting in, or for when the detector struggles at speed.
            Box(Modifier.padding(horizontal = 16.dp)) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (status.pushToTalk) Brand.Talking else Brand.SurfaceElevated
                            )
                            .pointerInput(status.isConnected) {
                                detectTapGestures(
                                    onPress = {
                                        if (!status.isConnected) return@detectTapGestures
                                        onPushToTalkDown()
                                        tryAwaitRelease()
                                        onPushToTalkUp()
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (status.pushToTalk) "TALKING" else "Hold to cut in",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (status.pushToTalk) Color(0xFF13240F) else Brand.TextDim,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Open call \u2014 just speak. Hold only to override the detector.",
                        fontSize = 12.sp, color = Brand.TextDim, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- live mic level ------------------------------------------------------
        Box(Modifier.padding(horizontal = 16.dp)) {
            BrandCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "YOUR MIC",
                        fontSize = 11.sp, letterSpacing = 1.6.sp,
                        fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                    )
                    Spacer(Modifier.width(14.dp))
                    LevelBar(
                        level = micLevel,
                        color = if (status.floor == FloorState.Muted) Brand.Buddy else Brand.Talking,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    if (status.floor == FloorState.Muted)
                        "Still listening, but nothing is being sent"
                    else "Speak normally; the detector opens the channel for you",
                    fontSize = 12.sp, color = Brand.TextDim,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- what the three subsystems are doing ---------------------------------
        Box(Modifier.padding(horizontal = 16.dp)) {
            StatusBar(
                listOf(
                    BarItem(
                        icon = musicIcon,
                        title = "Music",
                        subtitle = if (status.focusHeld) "Ducked" else "Keep playing",
                        onClick = onSettings,
                    ),
                    BarItem(
                        icon = MicIcons.Mic,
                        title = "Intercom",
                        subtitle = if (status.isConnected) "Rider to rider" else "Offline",
                        active = status.isConnected,
                        onClick = {},
                    ),
                    BarItem(
                        icon = audioIcon,
                        title = "Noise",
                        subtitle = noiseSuppression.label,
                        active = noiseSuppression != NoiseSuppression.Off,
                        onClick = onCycleNoise,
                    ),
                )
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---- link detail ---------------------------------------------------------
        Box(Modifier.padding(horizontal = 16.dp)) {
            BrandCard(Modifier.fillMaxWidth()) {
                Text(
                    "LINK",
                    fontSize = 11.sp, letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                )
                KeyValueRow("State", status.link.name)
                KeyValueRow(
                    "Round trip",
                    if (status.rttMs < 0) "measuring" else "${status.rttMs} ms",
                )
                KeyValueRow(
                    "Frame loss",
                    "%.1f %%".format(status.lossPercent),
                    valueColor = if (status.lossPercent > 5) Brand.Buddy else Brand.TextPrimary,
                )
                KeyValueRow("Jitter buffer", "${status.jitterDepth} frames")
                KeyValueRow("Role", if (status.isHost) "Host" else "Joined")
                KeyValueRow("Call time", formatElapsed(status.elapsedSeconds))
            }
        }

        if (status.error != null) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.padding(horizontal = 16.dp)) {
                BrandCard(Modifier.fillMaxWidth()) {
                    Text("PROBLEM", fontSize = 11.sp, letterSpacing = 1.6.sp, color = Brand.Danger)
                    Text(status.error, fontSize = 14.sp, color = Brand.Danger)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Box(Modifier.padding(horizontal = 16.dp)) {
            Column {
                SecondaryButton("End call", onEnd, color = Brand.Danger, height = 54.dp)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Back", onBack, color = Brand.TextDim, height = 46.dp)
            }
        }
    }
}

private fun phaseLabel(s: CallStatus): String = when (s.phase) {
    CallPhase.Idle -> "Not started"
    CallPhase.Starting -> "Starting"
    CallPhase.Connecting -> if (s.isHost) "Waiting for buddy" else "Connecting"
    CallPhase.Active -> "Connected"
    CallPhase.Reconnecting -> "Reconnecting"
    CallPhase.Ended -> "Call ended"
    CallPhase.Error -> "Error"
}

private fun formatElapsed(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
