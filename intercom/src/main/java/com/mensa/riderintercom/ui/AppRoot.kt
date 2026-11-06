package com.mensa.riderintercom.ui

import android.bluetooth.BluetoothDevice
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mensa.riderintercom.audio.PlatformAudioEffects
import com.mensa.riderintercom.call.CallStatus
import com.mensa.riderintercom.discovery.NearbyHost
import com.mensa.riderintercom.call.IntercomService
import com.mensa.riderintercom.transport.LinkRole
import com.mensa.riderintercom.util.Prefs

@Composable
fun AppRoot(
    prefs: Prefs,
    screen: Screen,
    onScreen: (Screen) -> Unit,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    bluetoothOn: Boolean,
    onEnableBluetooth: () -> Unit,
    bondedDevices: () -> List<BluetoothDevice>,
    nearbyHosts: List<NearbyHost>,
    onOpenBluetoothSettings: () -> Unit,
    onStartCall: (LinkRole, BluetoothDevice?) -> Unit,
    onStopCall: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenOemSettings: (Intent) -> Unit,
) {
    val settings by prefs.settings.collectAsState()
    val service by IntercomService.instance.collectAsState()
    val status: CallStatus = service?.controller?.status?.collectAsState()?.value ?: CallStatus()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // Android 15+ forces edge-to-edge and ignores setDecorFitsSystemWindows, so the
        // insets are applied here or the header draws under the status bar.
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (screen) {
                Screen.Start -> StartScreen(
                    settings = settings,
                    permissionsGranted = permissionsGranted,
                    bluetoothOn = bluetoothOn,
                    bondedDevices = remember(permissionsGranted, bluetoothOn) { bondedDevices() },
                    nearbyHosts = nearbyHosts,
                    onRequestPermissions = onRequestPermissions,
                    onEnableBluetooth = onEnableBluetooth,
                    onOpenBluetoothSettings = onOpenBluetoothSettings,
                    onRenameRider = { name -> prefs.update { it.copy(riderName = name) } },
                    onHost = { onStartCall(LinkRole.Host, null) },
                    onJoin = { device -> onStartCall(LinkRole.Client, device) },
                    onSettings = { onScreen(Screen.Settings) },
                    onOem = { onScreen(Screen.Oem) },
                )

                Screen.Call -> CallScreen(
                    status = status,
                    riderName = settings.riderName,
                    micLevel = service?.controller?.micLevel?.collectAsState()?.value ?: 0f,
                    talkMode = settings.talkMode,
                    noiseSuppression = settings.noiseSuppression,
                    onCycleNoise = { service?.controller?.cycleNoiseSuppression() },
                    onPushToTalkDown = { service?.controller?.setPushToTalk(true) },
                    onPushToTalkUp = { service?.controller?.setPushToTalk(false) },
                    onEnd = {
                        onStopCall()
                        onScreen(Screen.Start)
                    },
                    onBack = { onScreen(Screen.Start) },
                    onSettings = { onScreen(Screen.Settings) },
                )

                Screen.Settings -> SettingsScreen(
                    settings = settings,
                    lastKeyCode = service?.lastKeyCode?.collectAsState()?.value ?: 0,
                    platformEffects = remember { PlatformAudioEffects().availability() },
                    onChange = { transform ->
                        prefs.update(transform)
                        service?.controller?.applySettingsChange()
                    },
                    onBack = {
                        onScreen(if (status.isConnected) Screen.Call else Screen.Start)
                    },
                    onOem = { onScreen(Screen.Oem) },
                )

                Screen.Oem -> OemScreen(
                    onBack = { onScreen(Screen.Start) },
                    onOpenAppSettings = onOpenAppSettings,
                    onRequestBatteryExemption = onRequestBatteryExemption,
                    onOpenOemSettings = onOpenOemSettings,
                )
            }
        }
    }
}
