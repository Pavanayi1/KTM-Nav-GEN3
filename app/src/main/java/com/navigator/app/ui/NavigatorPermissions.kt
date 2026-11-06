package com.navigator.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Single source of truth for the runtime permissions Navigator needs and
 * whether they're currently granted. Centralized so onboarding, the pairing
 * screen, and MainActivity all agree — and so scanning can be gated on the
 * Bluetooth permission instead of crashing when it's missing.
 */
object NavigatorPermissions {

    /** Runtime permissions to request together (varies by SDK level). */
    fun runtimePermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        // GPS features (overspeed, waypoint, route recording) on every SDK; on
        // pre-Android-12 this also covers the BLE scan requirement. FINE must be
        // requested TOGETHER WITH COARSE: on Android 12+ a request containing
        // FINE alone is silently ignored (no dialog, nothing granted).
        perms += Manifest.permission.ACCESS_FINE_LOCATION
        perms += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        perms += Manifest.permission.READ_PHONE_STATE
        perms += Manifest.permission.ANSWER_PHONE_CALLS
        return perms.toTypedArray()
    }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** True when the app may scan/connect over BLE (the permission that, if missing, crashed the scan). */
    fun bluetoothGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(context, Manifest.permission.BLUETOOTH_SCAN) &&
                isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun notificationsPostGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else true

    fun phoneGranted(context: Context): Boolean =
        isGranted(context, Manifest.permission.READ_PHONE_STATE) &&
            isGranted(context, Manifest.permission.ANSWER_PHONE_CALLS)

    /** Special access (not a runtime permission): notification listener, granted from system settings. */
    fun notificationAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}
