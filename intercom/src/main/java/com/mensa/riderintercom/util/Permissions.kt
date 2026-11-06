package com.mensa.riderintercom.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permissions the call needs, in the two stages the design calls for:
 * Bluetooth, microphone and notifications at first launch; location only where an older
 * Android still demands it for Bluetooth work.
 */
object Permissions {

    /** Everything needed before the Start button can do anything. */
    fun required(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_SCAN
            // Needed so other riders can find this phone when it is hosting.
            list += Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            // On Android 10-11 Bluetooth work is gated behind location instead.
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    fun missing(context: Context): List<String> =
        required().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    /**
     * Re-checked before every socket call, because a permission revoked while the app ran
     * turns into a SecurityException from connect() or accept() rather than a prompt.
     */
    fun hasBluetooth(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    fun hasMicrophone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun label(permission: String): String = when (permission) {
        Manifest.permission.RECORD_AUDIO -> "Microphone"
        Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth connect"
        Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth scan"
        Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth advertise (so buddies can find you)"
        Manifest.permission.ACCESS_FINE_LOCATION -> "Location (needed for Bluetooth on this Android)"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        else -> permission.substringAfterLast('.')
    }
}
