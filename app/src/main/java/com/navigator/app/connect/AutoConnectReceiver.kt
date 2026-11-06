package com.navigator.app.connect

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.logging.AppLogger
import com.navigator.app.settings.AppSettings

/**
 * Starts BccuConnectionService automatically whenever the bike's Bluetooth
 * connects (ACL_CONNECTED for the previously-bonded MAC address saved during
 * pairing), when Bluetooth is turned back on, and on boot in case the bike is
 * already in range. This is what makes the app "just work" without the rider
 * ever opening it.
 *
 * Note it deliberately does NOT stop the service on ACL_DISCONNECTED: the
 * service owns its own auto-reconnect (connectGatt(autoConnect=true)), and
 * tearing it down on every brief disconnect was exactly what prevented the app
 * from reconnecting on its own.
 */
class AutoConnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.init(context) // safe no-op if Application.onCreate already ran
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device = getDeviceExtra(intent) ?: return
                val settings = AppSettings(context)
                val savedAddress = settings.bondedDeviceAddress ?: return
                val savedName = settings.bondedDeviceName
                val deviceName = try { device.name } catch (e: SecurityException) { null }
                AppLogger.log("AutoConnect", "ACL_CONNECTED from ${device.address} (${deviceName ?: "unnamed"}), saved=$savedAddress")
                // Match on the NAME as well as the MAC: a dash that also pairs
                // for media shows up over Bluetooth Classic under a different
                // address than the BLE one we bonded with, so an address-only
                // check silently misses the single most useful "the rider just
                // switched the ignition on" signal we get.
                // Name match is lenient: the same cluster advertises
                // "KTM3638" over BLE but a Classic media link can report it
                // with a space, a dash or different case. An exact compare
                // threw away the single most useful "the rider just switched
                // the ignition on" signal we get.
                val isBike = device.address.equals(savedAddress, ignoreCase = true) ||
                    BikePresence.namesMatch(savedName, deviceName)
                if (isBike) {
                    AppLogger.log("AutoConnect", "Match - starting BccuConnectionService")
                    // Ignition on is the rider asking to ride: it overrides an earlier Stop.
                    settings.userQuit = false
                    BccuConnectionService.start(context, savedAddress)
                } else {
                    AppLogger.log("AutoConnect", "Not our bike (saved name \"${savedName ?: "-"}\") - ignoring")
                }
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    val s = AppSettings(context)
                    if (s.userQuit) { AppLogger.log("AutoConnect", "Bluetooth on, but the rider quit - staying down"); return }
                    val savedAddress = s.bondedDeviceAddress ?: return
                    AppLogger.log("AutoConnect", "Bluetooth turned on - starting service for $savedAddress")
                    BccuConnectionService.start(context, savedAddress)
                }
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                val s = AppSettings(context)
                if (s.userQuit) { AppLogger.log("AutoConnect", "Boot, but the rider quit - staying down"); return }
                val savedAddress = s.bondedDeviceAddress ?: return
                AppLogger.log("AutoConnect", "Boot completed, starting service for $savedAddress")
                BccuConnectionService.start(context, savedAddress)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getDeviceExtra(intent: Intent): BluetoothDevice? {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }
}
