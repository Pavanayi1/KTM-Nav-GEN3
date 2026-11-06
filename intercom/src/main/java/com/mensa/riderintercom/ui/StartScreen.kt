package com.mensa.riderintercom.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mensa.riderintercom.R
import com.mensa.riderintercom.audio.MicSource
import com.mensa.riderintercom.discovery.NearbyHost
import com.mensa.riderintercom.audio.NoiseSuppression
import com.mensa.riderintercom.util.OemDetector
import com.mensa.riderintercom.util.Settings as RiderSettings

/**
 * The screen the ride starts from.
 *
 * One rider hosts and waits; the other picks them from the paired list and joins. The app
 * never triggers pairing itself - it lists what Android has already bonded, because a
 * pairing prompt in the middle of a connect is one of the failure modes this design sets
 * out to avoid.
 */
@SuppressLint("MissingPermission")
@Composable
fun StartScreen(
    settings: RiderSettings,
    permissionsGranted: Boolean,
    bluetoothOn: Boolean,
    bondedDevices: List<BluetoothDevice>,
    nearbyHosts: List<NearbyHost>,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onRenameRider: (String) -> Unit,
    onHost: () -> Unit,
    onJoin: (BluetoothDevice) -> Unit,
    onSettings: () -> Unit,
    onOem: () -> Unit,
) {
    var hostModeState by remember { mutableStateOf(true) }
    // The whole screen recolours with the choice: orange hosting, blue joining.
    WithAccents(host = hostModeState) {
        StartScreenContent(
            settings, permissionsGranted, bluetoothOn, bondedDevices, nearbyHosts,
            onRequestPermissions, onEnableBluetooth, onOpenBluetoothSettings,
            onRenameRider, onHost, onJoin, onSettings, onOem,
            hostMode = hostModeState,
            onHostMode = { hostModeState = it },
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun StartScreenContent(
    settings: RiderSettings,
    permissionsGranted: Boolean,
    bluetoothOn: Boolean,
    bondedDevices: List<BluetoothDevice>,
    nearbyHosts: List<NearbyHost>,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onRenameRider: (String) -> Unit,
    onHost: () -> Unit,
    onJoin: (BluetoothDevice) -> Unit,
    onSettings: () -> Unit,
    onOem: () -> Unit,
    hostMode: Boolean,
    onHostMode: (Boolean) -> Unit,
) {
    var selected by remember(bondedDevices) {
        mutableStateOf(
            bondedDevices.firstOrNull { it.address == settings.buddyAddress }
                ?: bondedDevices.firstOrNull()
        )
    }
    var showBuddies by remember { mutableStateOf(false) }
    var name by remember(settings.riderName) { mutableStateOf(settings.riderName) }

    val ready = permissionsGranted && bluetoothOn
    val musicIcon = ImageVector.vectorResource(R.drawable.ic_music_note)
    val audioIcon = ImageVector.vectorResource(R.drawable.ic_volume_up)
    val canGo = ready && (hostMode || selected != null)

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
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = Brand.TextPrimary,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        )

        // ---- hero ---------------------------------------------------------------
        Image(
            painter = painterResource(R.drawable.hero_riders),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(176.dp),
        )

        Spacer(Modifier.height(6.dp))

        // ---- someone is hosting nearby ------------------------------------------
        val joinable = nearbyHosts.filter { it.acceptingJoiners }
        if (joinable.isNotEmpty()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                BrandCard(Modifier.fillMaxWidth(), selected = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WaveMark(Modifier.size(22.dp, 20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (joinable.size == 1) "A RIDER IS HOSTING"
                            else "${joinable.size} RIDERS ARE HOSTING",
                            fontSize = 11.sp, letterSpacing = 1.6.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LocalAccents.current.base,
                        )
                    }
                    joinable.forEach { host ->
                        val bonded = bondedDevices.firstOrNull { it.address == host.address }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    host.riderName,
                                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                    color = Brand.TextPrimary,
                                )
                                Text(
                                    if (bonded != null) host.proximity
                                    else "${host.proximity} \u00b7 pair in Bluetooth settings first",
                                    fontSize = 12.sp, color = Brand.TextDim,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            if (bonded != null) {
                                Box(Modifier.width(96.dp)) {
                                    PrimaryButton("Join", true, { onJoin(bonded) }, height = 42.dp)
                                }
                            } else {
                                Box(Modifier.width(96.dp)) {
                                    SecondaryButton("Pair", onOpenBluetoothSettings, height = 42.dp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- the two ways to start ----------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionTile(
                icon = Icons.Filled.PlayArrow,
                title = "Host Ride",
                subtitle = "Wait for your buddy",
                selected = hostMode,
                enabled = ready,
                onClick = { onHostMode(true); showBuddies = false },
                modifier = Modifier.weight(1f),
            )
            ActionTile(
                icon = Icons.Filled.Person,
                title = "Join Rider",
                subtitle = "Connect to a paired phone",
                selected = !hostMode,
                enabled = ready,
                onClick = { onHostMode(false); showBuddies = true },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- state --------------------------------------------------------------
        val (headline, detail, dot) = when {
            !permissionsGranted -> Triple(
                "Permissions needed", "Microphone, Bluetooth and notifications", Brand.Danger,
            )
            !bluetoothOn -> Triple("Bluetooth is off", "Turn it on to reach the other phone", Brand.Danger)
            hostMode -> Triple("Ready to host", "Your buddy joins from their phone", Brand.Talking)
            selected != null -> Triple(
                "Ready to join",
                runCatching { selected?.name }.getOrNull() ?: selected?.address ?: "",
                Brand.Talking,
            )
            else -> Triple("No buddy chosen", "Pick a paired phone below", Brand.Buddy)
        }
        Box(Modifier.padding(horizontal = 16.dp)) {
            StatusPill(headline = headline, detail = detail, dotColor = dot)
        }

        // ---- fix-it cards, only when something is actually wrong ------------------
        if (!permissionsGranted) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(horizontal = 16.dp)) {
                BrandCard(Modifier.fillMaxWidth()) {
                    Text(
                        "The call needs the microphone, Bluetooth and the notification that " +
                            "keeps it alive with the screen off.",
                        fontSize = 14.sp, color = Brand.TextDim, lineHeight = 19.sp,
                    )
                    PrimaryButton("Grant permissions", true, onRequestPermissions)
                }
            }
        } else if (!bluetoothOn) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(horizontal = 16.dp)) {
                BrandCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Turn Bluetooth on to reach the other phone.",
                        fontSize = 14.sp, color = Brand.TextDim,
                    )
                    PrimaryButton("Turn on Bluetooth", true, onEnableBluetooth)
                }
            }
        }

        // ---- the dial -----------------------------------------------------------
        Spacer(Modifier.height(22.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable(enabled = canGo) {
                            if (hostMode) onHost() else selected?.let(onJoin)
                        },
                ) {
                    MicDial(
                        color = if (canGo) LocalAccents.current.base else Brand.Idle,
                        icon = MicIcons.Mic,
                        active = canGo,
                    )
                }
                Spacer(Modifier.height(14.dp))
                DotStatus(
                    text = if (canGo) "Intercom Ready" else "Not Ready",
                    color = if (canGo) Brand.Talking else Brand.Idle,
                    subtitle = if (canGo) "Tap to start · speak to talk" else detail,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- start button -------------------------------------------------------
        Box(Modifier.padding(horizontal = 16.dp)) {
            PrimaryButton(
                text = if (hostMode) "Start hosting" else {
                    val label = selected?.let { d ->
                        runCatching { d.name }.getOrNull() ?: d.address
                    }
                    if (label != null) "Join  ·  $label" else "Join"
                },
                enabled = canGo,
                onClick = { if (hostMode) onHost() else selected?.let(onJoin) },
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---- buddy picker -------------------------------------------------------
        Box(Modifier.padding(horizontal = 16.dp)) {
            BrandCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { showBuddies = !showBuddies },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "PAIRED PHONES",
                            fontSize = 11.sp, letterSpacing = 1.6.sp,
                            fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                        )
                        Text(
                            if (bondedDevices.isEmpty()) "None yet"
                            else "${bondedDevices.size} available",
                            fontSize = 15.sp, color = Brand.TextPrimary,
                        )
                    }
                    Text(
                        if (showBuddies) "Hide" else "Show",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = LocalAccents.current.base,
                    )
                }

                if (showBuddies) {
                    if (bondedDevices.isEmpty()) {
                        Text(
                            if (!permissionsGranted) "Grant Bluetooth permission to see paired phones."
                            else "Pair both phones in Android's Bluetooth settings first, then " +
                                "come back.",
                            fontSize = 14.sp, color = Brand.TextDim, lineHeight = 19.sp,
                        )
                    } else {
                        bondedDevices.forEach { device ->
                            val isSel = selected?.address == device.address
                            val label = runCatching { device.name }.getOrNull() ?: device.address
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isSel) Brand.SurfaceSelected else Color.Transparent)
                                    .clickable {
                                        selected = device
                                        onHostMode(false)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        label, fontSize = 16.sp,
                                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                        color = Brand.TextPrimary,
                                    )
                                    Text(device.address, fontSize = 12.sp, color = Brand.TextDim)
                                }
                                if (isSel) {
                                    Icon(
                                        Icons.Filled.Check, contentDescription = "Selected",
                                        tint = LocalAccents.current.base, modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                    SecondaryButton(
                        text = if (bondedDevices.isEmpty()) "Open Bluetooth settings"
                        else "Pair another phone",
                        onClick = onOpenBluetoothSettings,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- your name ----------------------------------------------------------
        Box(Modifier.padding(horizontal = 16.dp)) {
            BrandCard(Modifier.fillMaxWidth()) {
                Text(
                    "YOUR NAME",
                    fontSize = 11.sp, letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(14)
                        onRenameRider(name)
                    },
                    singleLine = true,
                    placeholder = { Text("Shown on your buddy's screen", color = Brand.TextDim) },
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Brand.SurfaceElevated,
                        unfocusedContainerColor = Brand.SurfaceElevated,
                        focusedIndicatorColor = LocalAccents.current.base,
                        unfocusedIndicatorColor = Brand.Border,
                        focusedTextColor = Brand.TextPrimary,
                        unfocusedTextColor = Brand.TextPrimary,
                        cursorColor = LocalAccents.current.base,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- what the three subsystems will do -----------------------------------
        Spacer(Modifier.height(14.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            StatusBar(
                listOf(
                    BarItem(
                        icon = musicIcon,
                        title = "Music",
                        subtitle = if (settings.takeAudioFocus) "Ducks while talking"
                        else "Keeps playing",
                        onClick = onSettings,
                    ),
                    BarItem(
                        icon = MicIcons.Mic,
                        title = "Intercom",
                        subtitle = settings.talkMode.label,
                        active = ready,
                        onClick = {},
                    ),
                    BarItem(
                        icon = audioIcon,
                        title = "Noise",
                        subtitle = settings.noiseSuppression.label,
                        active = settings.noiseSuppression != NoiseSuppression.Off,
                        onClick = onSettings,
                    ),
                )
            )
        }

        // ---- OEM warning --------------------------------------------------------
        if (OemDetector.isAggressiveVendor()) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.padding(horizontal = 16.dp)) {
                BrandCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Lock, contentDescription = null,
                            tint = Brand.Buddy, modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "BEFORE A LONG RIDE",
                            fontSize = 11.sp, letterSpacing = 1.6.sp,
                            fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                        )
                    }
                    Text(
                        "This phone's battery manager can kill the call once the screen is " +
                            "off. Two minutes in settings now saves a dead intercom later.",
                        fontSize = 14.sp, color = Brand.TextDim, lineHeight = 19.sp,
                    )
                    SecondaryButton("Keep the call alive", onOem, color = Brand.Buddy)
                }
            }
        }
    }
}

private fun micLabel(src: MicSource): String = when (src) {
    MicSource.Wired -> "Wired boom mic"
    MicSource.Phone -> "Phone mic"
    MicSource.LeAudio -> "LE Audio"
    MicSource.HeadsetHfp -> "Headset (HFP)"
}
