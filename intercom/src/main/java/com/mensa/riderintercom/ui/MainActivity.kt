package com.mensa.riderintercom.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mensa.riderintercom.RiderIntercomApp
import com.mensa.riderintercom.call.CallPhase
import com.mensa.riderintercom.discovery.HostScanner
import com.mensa.riderintercom.discovery.NearbyHost
import com.mensa.riderintercom.call.IntercomService
import com.mensa.riderintercom.transport.LinkRole
import com.mensa.riderintercom.util.OemDetector
import com.mensa.riderintercom.util.Permissions
import com.mensa.riderintercom.util.Prefs

/** Where in the app the rider is. */
enum class Screen { Start, Call, Settings, Oem }

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private var adapter: BluetoothAdapter? = null

    private var permissionsGranted by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.Start)

    /**
     * Scanning only runs while this screen is in front. A background scan would need its
     * own foreground service and would sit on the radio all ride for a feature the rider
     * only uses at the start of one.
     */
    private var scanner: HostScanner? = null
    private var nearbyHosts by mutableStateOf<List<NearbyHost>>(emptyList())

    private val askPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it } || Permissions.allGranted(this)
    }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* state is re-read when the screen recomposes */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        prefs = RiderIntercomApp.init(this).prefs
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        permissionsGranted = Permissions.allGranted(this)

        setContent {
            RiderIntercomTheme {
                AppRoot(
                    prefs = prefs,
                    screen = screen,
                    onScreen = { screen = it },
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { askPermissions.launch(Permissions.required()) },
                    bluetoothOn = adapter?.isEnabled == true,
                    onEnableBluetooth = {
                        enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                    bondedDevices = { bondedDevices() },
                    nearbyHosts = nearbyHosts,
                    onOpenBluetoothSettings = {
                        runCatching { startActivity(OemDetector.bluetoothSettingsIntent()) }
                    },
                    onStartCall = ::startCall,
                    onStopCall = { IntercomService.stopCall(this) },
                    onOpenAppSettings = {
                        runCatching { startActivity(OemDetector.appDetailsIntent(this)) }
                    },
                    onRequestBatteryExemption = {
                        runCatching { startActivity(OemDetector.batteryOptimisationIntent(this)) }
                    },
                    onOpenOemSettings = { intent ->
                        runCatching { startActivity(intent) }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsGranted = Permissions.allGranted(this)
        startScanning()
        // A call already running means the rider came back to the app mid-ride.
        if (IntercomService.instance.value != null &&
            IntercomService.instance.value?.controller?.status?.value?.phase !in
            listOf(CallPhase.Idle, CallPhase.Ended, null)
        ) {
            screen = Screen.Call
        }
    }

    override fun onPause() {
        super.onPause()
        scanner?.stop()
    }

    private fun startScanning() {
        if (!Permissions.hasBluetooth(this) || adapter?.isEnabled != true) return
        val a = adapter ?: return
        val s = scanner ?: HostScanner(a).also { made ->
            scanner = made
            lifecycleScope.launch {
                made.hosts.collect { nearbyHosts = it }
            }
        }
        s.start()
    }

    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<BluetoothDevice> {
        if (!Permissions.hasBluetooth(this)) return emptyList()
        val a = adapter ?: return emptyList()
        return runCatching { a.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
    }

    /**
     * The microphone foreground service can only be created while the app is visible, so
     * this is the only place a call ever starts.
     */
    // Permissions.allGranted() is checked on the first line, and the one Bluetooth
    // call (device.name) is wrapped in runCatching - lint can't see through the helper.
    @SuppressLint("MissingPermission")
    private fun startCall(role: LinkRole, device: BluetoothDevice?) {
        if (!Permissions.allGranted(this)) {
            askPermissions.launch(Permissions.required())
            return
        }
        if (role == LinkRole.Client) {
            prefs.update {
                it.copy(
                    buddyAddress = device?.address,
                    buddyName = runCatching { device?.name }.getOrNull(),
                )
            }
        }
        IntercomService.startCall(this, role, device)
        screen = Screen.Call
    }
}
