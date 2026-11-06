package com.navigator.app.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.BuildConfig
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.NavigatorPermissions
import com.navigator.app.ui.components.Eyebrow
import com.navigator.app.ui.components.KtmPrimaryButton
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.NavIcons

private data class FoundDevice(val device: BluetoothDevice, val rssi: Int)

/**
 * Pairing (screen 01). Scans over BLE, lets the rider pick the bike, and
 * critically only advances to Grid on a real [BccuConnectionService.ConnectionState.AUTHENTICATED]
 * status - never on the connect tap itself - showing an "Authenticating..."
 * handshake state in between.
 */
@SuppressLint("MissingPermission") // scan/connect permissions are requested in MainActivity
@Composable
fun PairingScreen(settings: AppSettings, onPaired: () -> Unit, onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val foundDevices = remember { mutableStateListOf<FoundDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var connectingAddress by remember { mutableStateOf<String?>(null) }

    val connectionState by BccuConnectionService.connectionState.collectAsState()

    // Only leave this screen once the handshake genuinely completes.
    androidx.compose.runtime.LaunchedEffect(connectionState, connectingAddress) {
        if (connectingAddress != null &&
            connectionState == BccuConnectionService.ConnectionState.AUTHENTICATED
        ) {
            onPaired()
        }
    }

    val adapter = remember { context.getSystemService(BluetoothManager::class.java).adapter }

    // Whether we may scan; without this the scanner call throws a
    // SecurityException and crashed the app on launch. Re-checked after the
    // permission request returns.
    var btGranted by remember { mutableStateOf(NavigatorPermissions.bluetoothGranted(context)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { btGranted = NavigatorPermissions.bluetoothGranted(context) }

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val existing = foundDevices.indexOfFirst { it.device.address == result.device.address }
                val entry = FoundDevice(result.device, result.rssi)
                if (existing >= 0) foundDevices[existing] = entry else foundDevices.add(entry)
            }
        }
    }

    fun startScan() {
        // Guarded so a missing permission or disabled Bluetooth degrades to a
        // prompt instead of throwing a SecurityException / NPE.
        if (!NavigatorPermissions.bluetoothGranted(context)) {
            btGranted = false
            return
        }
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        foundDevices.clear()
        // Seed the list with devices already bonded to this phone (e.g. the bike
        // paired for media/calls). A bonded dash may not actively advertise BLE
        // when it's connected via classic Bluetooth, so a scan alone can miss it —
        // listing bonds lets the rider pick their bike directly ("use my saved
        // bike"). KTM-named ones sort to the top in the UI.
        try {
            adapter?.bondedDevices?.forEach { dev ->
                if (foundDevices.none { it.device.address == dev.address }) {
                    foundDevices.add(FoundDevice(dev, 0))
                }
            }
        } catch (e: SecurityException) { /* ignore */ }
        scanning = true
        try {
            scanner.startScan(scanCallback)
        } catch (e: SecurityException) {
            scanning = false
            btGranted = false
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { scanner.stopScan(scanCallback) } catch (e: SecurityException) { /* ignore */ }
            scanning = false
        }, 8000)
    }

    // Kick off a scan once we actually have permission (and re-scan when it's granted).
    androidx.compose.runtime.LaunchedEffect(btGranted) { if (btGranted) startScan() }

    val connecting = connectingAddress != null &&
        connectionState != BccuConnectionService.ConnectionState.DISCONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ktm.Screen)
            .systemBarsPadding()
            .padding(horizontal = 22.dp)
            .padding(top = 8.dp, bottom = 18.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Text(
            if (!btGranted) "BLUETOOTH NEEDED" else "PAIR YOUR DASH",
            color = Ktm.White,
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
            fontSize = 30.sp,
            letterSpacing = (-0.3).sp,
        )
        Text(
            if (!btGranted)
                "Navigator Gen3 needs the Nearby devices permission to find your bike's dash."
            else
                "Put the dash into add device mode from its connectivity menu, then confirm on the dash when asked.",
            color = Ktm.Muted,
            fontFamily = Barlow,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(18.dp))
        if (btGranted) {
            ScanStatusBar(scanning = scanning, connecting = connecting, found = foundDevices.size)
        }

        Spacer(Modifier.height(24.dp))
        if (!btGranted) {
            // Permission-needed state: no scanning, just an explained grant action.
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                KtmPrimaryButton("Grant permission") {
                    permLauncher.launch(NavigatorPermissions.runtimePermissions())
                }
            }
        } else {
            // Two sections, as the design splits them: the bike we already know
            // (bonded, or advertising a brand name) above everything else, so
            // the rider isn't hunting for it among neighbours' devices.
            val bonded = settings.bondedDeviceAddress
            val (mine, nearby) = foundDevices.partition { found ->
                found.device.address.equals(bonded, ignoreCase = true) ||
                    found.device.name?.contains("KTM", ignoreCase = true) == true ||
                    found.device.name?.contains("HQV", ignoreCase = true) == true ||
                    found.device.name?.contains("Husqvarna", ignoreCase = true) == true
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (mine.isNotEmpty()) {
                    item {
                        Eyebrow("Your bike", color = Ktm.Dim, fontSize = 11, letterSpacing = 2.4,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
                items(mine) { found ->
                    DeviceCard(
                        name = found.device.name ?: "Unknown device",
                        address = found.device.address,
                        rssi = found.rssi,
                        bonded = found.device.address.equals(bonded, ignoreCase = true),
                        selected = connectingAddress == found.device.address,
                        dimmed = false,
                        isKtm = true,
                        onClick = {
                            if (connectingAddress == null) {
                                settings.bondedDeviceAddress = found.device.address
                                settings.bondedDeviceName = found.device.name
                                connectingAddress = found.device.address
                                BccuConnectionService.start(context, found.device.address)
                            }
                        },
                    )
                }
                if (nearby.isNotEmpty()) {
                    item {
                        Eyebrow("Nearby", color = Ktm.Dim, fontSize = 11, letterSpacing = 2.4,
                            modifier = Modifier.padding(top = 14.dp))
                    }
                }
                items(nearby) { found ->
                    DeviceCard(
                        name = found.device.name ?: "Unknown device",
                        address = found.device.address,
                        rssi = found.rssi,
                        bonded = false,
                        selected = connectingAddress == found.device.address,
                        dimmed = found.device.name == null && connectingAddress != found.device.address,
                        isKtm = false,
                        onClick = {
                            if (connectingAddress == null) {
                                settings.bondedDeviceAddress = found.device.address
                                settings.bondedDeviceName = found.device.name
                                connectingAddress = found.device.address
                                BccuConnectionService.start(context, found.device.address)
                            }
                        },
                    )
                }
                if (foundDevices.isEmpty() && scanning) {
                    item {
                        Text(
                            "Searching...",
                            color = Ktm.Dim,
                            fontFamily = Barlow,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                        )
                    }
                }
            }
        }

        // Footer: the one thing a stuck rider needs is the log, so it sits
        // right above the retry rather than in Settings.
        if (btGranted && !connecting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("Trouble pairing?  ", color = Ktm.Dim, fontFamily = Barlow, fontSize = 13.sp)
                Text(
                    "Open logs", color = Ktm.Orange, fontFamily = Barlow, fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpenLogs),
                )
            }
        }
        if (connecting) {
            AuthenticatingButton()
            Text(
                "HANDSHAKE · deriving session keys",
                color = Ktm.Dim,
                fontFamily = JetBrainsMono,
                fontSize = 10.5.sp,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else if (btGranted) {
            PrimaryButton(
                text = if (scanning) "SCANNING…" else "SCAN AGAIN",
                enabled = !scanning,
                onClick = { startScan() },
            )
            if (BuildConfig.DEBUG) {
                Text(
                    "Skip pairing (debug only)",
                    color = Ktm.Dim,
                    fontFamily = Barlow,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clickable {
                            settings.bondedDeviceAddress = "00:00:00:00:00:00"
                            settings.bondedDeviceName = "Debug device (no real bike)"
                            onPaired()
                        },
                )
            }
        }
    }
}

@Composable
private fun PulseScanTarget() {
    val transition = rememberInfiniteTransition(label = "pulse")
    // Easing and specs are remembered so the ~150ms recomposition driven by
    // MainActivity's state-poll loop can't reallocate them: a fresh spec
    // instance makes InfiniteTransition restart the animation, which is what
    // made the ping visibly stutter. Stable identity = one continuous run.
    val easing = remember { CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }
    val spec1 = remember(easing) {
        infiniteRepeatable<Float>(tween(2400, easing = easing), RepeatMode.Restart)
    }
    val spec2 = remember(easing) {
        infiniteRepeatable<Float>(tween(2400, easing = easing), RepeatMode.Restart, StartOffset(1200))
    }
    val breatheSpec = remember {
        infiniteRepeatable<Float>(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    }
    val p1 by transition.animateFloat(0f, 1f, spec1, label = "ring1")
    val p2 by transition.animateFloat(0f, 1f, spec2, label = "ring2")
    // Gentle breathing on the center disc for a smoother, alive feel.
    val breathe by transition.animateFloat(1f, 1.045f, breatheSpec, label = "breathe")

    Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        listOf(p1, p2).forEach { p ->
            val scale = 0.55f + (1.9f - 0.55f) * p
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = 0.85f * (1f - p) }
                    .border(2.dp, Ktm.Orange, CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer { scaleX = breathe; scaleY = breathe }
                .clip(CircleShape)
                .background(Ktm.Surface)
                .border(1.dp, Ktm.Bezel, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(NavIcons.Bike, contentDescription = null, tint = Ktm.Orange, modifier = Modifier.size(46.dp))
        }
    }
}

@Composable
private fun DeviceCard(
    name: String,
    address: String,
    rssi: Int?,
    bonded: Boolean,
    selected: Boolean,
    dimmed: Boolean,
    isKtm: Boolean,
    onClick: () -> Unit,
) {
    val highlighted = selected || isKtm
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (dimmed) 0.5f else 1f }
            .clip(RoundedCornerShape(Ktm.RadiusRow))
            .background(Ktm.Surface)
            .border(1.dp, if (highlighted) Ktm.Orange else Ktm.Border, RoundedCornerShape(Ktm.RadiusRow))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    name,
                    color = if (highlighted) Ktm.White else Ktm.TextPrimary,
                    fontFamily = Barlow,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    // "bonded" beats a signal number: it tells the rider this is
                    // the one their phone already knows.
                    address + if (bonded) " · bonded" else rssi?.let { " · $it dBm" } ?: "",
                    color = if (highlighted) Ktm.Muted else Ktm.Dim,
                    fontFamily = JetBrainsMono,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            SignalMeter(rssi, highlighted)
        }
    }
}

/**
 * Scan status strip: a pulsing accent dot while the radio is actually
 * scanning, and a live count. Without the count, "scanning" and "scanning but
 * finding nothing" look identical.
 */
@Composable
private fun ScanStatusBar(scanning: Boolean, connecting: Boolean, found: Int) {
    val label = when {
        connecting -> "CONNECTING"
        scanning -> "SCANNING"
        else -> "IDLE"
    }
    val dotColor = if (scanning || connecting) Ktm.Orange else Ktm.Dim3
    val alpha = if (scanning || connecting) {
        val transition = rememberInfiniteTransition(label = "scanPulse")
        val a by transition.animateFloat(
            0.35f, 1f,
            infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "scanAlpha",
        )
        a
    } else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusRow))
            .background(Ktm.SurfaceAlt)
            .border(1.dp, Ktm.RowDivider, RoundedCornerShape(Ktm.RadiusRow))
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                label, color = Ktm.TextPrimary, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, letterSpacing = 2.2.sp, modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            if (found == 1) "1 found" else "$found found",
            color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 12.sp,
        )
    }
}

@Composable
private fun SignalMeter(rssi: Int?, highlighted: Boolean) {
    val lit = when {
        rssi == null -> 0
        rssi >= -60 -> 4
        rssi >= -72 -> 3
        rssi >= -85 -> 2
        else -> 1
    }
    val onColor = if (highlighted) Ktm.Green else Ktm.Muted
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(6, 10, 14, 18).forEachIndexed { index, h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (index < lit) onColor else Ktm.Bezel),
            )
        }
    }
}

@Composable
private fun AuthenticatingButton() {
    val transition = rememberInfiniteTransition(label = "spin")
    // Remembered so recomposition doesn't reallocate the spec and restart the
    // spin (same stutter cause as the pulse rings above).
    val spinSpec = remember {
        infiniteRepeatable<Float>(tween(800, easing = LinearEasing), RepeatMode.Restart)
    }
    val angle by transition.animateFloat(0f, 360f, spinSpec, label = "angle")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusButton))
            .background(Ktm.Orange)
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.size(15.dp).graphicsLayer { rotationZ = angle },
        ) {
            val stroke = 2.dp.toPx()
            drawArc(
                color = Ktm.Screen,
                startAngle = 0f,
                sweepAngle = 300f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            )
        }
        Spacer(Modifier.width(11.dp))
        Text(
            "AUTHENTICATING…",
            color = Ktm.Screen,
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 1.5.sp,
        )
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusButton))
            .background(if (enabled) Ktm.Orange else Ktm.SurfaceDisabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(15.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            color = if (enabled) Ktm.Screen else Ktm.Dim,
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 1.5.sp,
        )
    }
}
