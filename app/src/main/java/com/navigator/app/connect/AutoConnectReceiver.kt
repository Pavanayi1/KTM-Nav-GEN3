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
                val savedAddress = AppSettings(context).bondedDeviceAddress ?: return
                AppLogger.log("AutoConnect", "ACL_CONNECTED from ${device.address}, saved=$savedAddress")
                if (device.address.equals(savedAddress, ignoreCase = true)) {
                    AppLogger.log("AutoConnect", "Match - starting BccuConnectionService")
                    BccuConnectionService.start(context, savedAddress, userInitiated = false)
                }
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    val savedAddress = AppSettings(context).bondedDeviceAddress ?: return
                    AppLogger.log("AutoConnect", "Bluetooth turned on - starting service for $savedAddress")
                    BccuConnectionService.start(context, savedAddress, userInitiated = false)
                }
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                val savedAddress = AppSettings(context).bondedDeviceAddress ?: return
                AppLogger.log("AutoConnect", "Boot completed, starting service for $savedAddress")
                BccuConnectionService.start(context, savedAddress, userInitiated = false)
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
