package com.navigator.app.audio

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.navigator.app.settings.AppSettings

/**
 * Routes call audio to the user's preferred Bluetooth device. Android only
 * lets apps control audio routing for calls and their own playback - not
 * for other apps' media (Spotify, YouTube, etc.), so this is intentionally
 * scoped to calls only.
 */
class CallAudioRouter(private val context: Context) {

    /**
     * Route the active call to the rider's audio device. Priority:
     * 1. A plugged-in wired/USB headset (physical presence IS the preference -
     *    nobody plugs in a headset on a bike by accident).
     * 2. The Bluetooth device picked in Settings.
     * Returns true when the route actually took, so the caller can retry: the
     * bike's own BT SCO grabs the call at connect and a single early
     * setCommunicationDevice gets overridden (R40 field report).
     */
    fun routeCallToPreferredDevice(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false // setCommunicationDevice needs API 31+
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
        val devices = audioManager.availableCommunicationDevices
        val wired = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        // The bike itself can present as a Bluetooth hands-free/headset
        // device even when its own "phone only, no headset" setting is on
        // (field report: the dash auto-enables it anyway) - so "any BT SCO
        // device" is no longer a safe fallback, it could just as easily grab
        // the bike instead of the rider's intercom. We already know the
        // bike's own bonded address from pairing, so explicitly exclude it
        // from consideration: whatever BT SCO candidate is left (that isn't
        // the bike) is the actual intercom.
        val settings = AppSettings(context)
        val bikeAddress = settings.bondedDeviceAddress
        val preferredAddress = settings.preferredCallAudioDeviceAddress
        val allBt = devices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val btCandidates = allBt.filterNot { bikeAddress != null && it.address.equals(bikeAddress, ignoreCase = true) }
        com.navigator.app.logging.AppLogger.log(
            "Call", "BT SCO candidates: ${allBt.map { "${it.productName}/${it.address}" }} bikeAddress=$bikeAddress preferred=$preferredAddress"
        )
        // Prefer an exact match against the Settings pick when Android
        // actually reports a real address (it can come back blank for BT SCO
        // devices on some versions/OEMs without a permission not held at this
        // instant); otherwise take whichever non-bike BT SCO device remains -
        // a rider realistically has exactly one such device: their intercom.
        val bt = btCandidates.firstOrNull { preferredAddress != null && it.address.equals(preferredAddress, ignoreCase = true) }
            ?: btCandidates.firstOrNull()
        val target = wired ?: bt ?: run {
            com.navigator.app.logging.AppLogger.log(
                "Call", "No wired headset and no non-bike Bluetooth SCO device available (candidates=${devices.map { it.type }})"
            )
            return false
        }
        val ok = audioManager.setCommunicationDevice(target)
        com.navigator.app.logging.AppLogger.log(
            "Call", "Route call -> ${target.productName} (type=${target.type}) success=$ok"
        )
        return ok && audioManager.communicationDevice?.id == target.id
    }

    fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AudioManager::class.java)?.clearCommunicationDevice()
        }
    }

    /** Bonded Bluetooth audio devices, for the Settings picker. */
    fun bondedAudioDevices(bondedDevices: Set<BluetoothDevice>): List<BluetoothDevice> {
        return bondedDevices.filter { device ->
            device.bluetoothClass?.hasService(android.bluetooth.BluetoothClass.Service.AUDIO) == true
        }
    }
}
