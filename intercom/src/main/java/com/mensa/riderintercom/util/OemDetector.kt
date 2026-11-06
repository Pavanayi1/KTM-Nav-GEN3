package com.mensa.riderintercom.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Several manufacturers kill long-running foreground services whatever the standard
 * Android rules say, so the rider has to be walked through that OEM's own settings once.
 * This is the difference between a call that survives a ride and one that dies ten
 * minutes after the screen goes off.
 */
object OemDetector {

    data class Guidance(
        val vendor: String,
        val steps: List<String>,
        /** Vendor settings screen to open directly, when one is known. */
        val intent: Intent?,
    )

    private val manufacturer: String get() = Build.MANUFACTURER.lowercase()

    fun isAggressiveVendor(): Boolean = guidanceFor() != null

    fun guidanceFor(): Guidance? = when {
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
            manufacturer.contains("poco") -> Guidance(
            vendor = "Xiaomi / Redmi / POCO",
            steps = listOf(
                "Settings > Apps > Manage apps > Rider Intercom",
                "Turn on Autostart",
                "Battery saver > choose No restrictions",
                "Other permissions > allow Display pop-up windows while running in background",
                "In Recents, pull the app card down and tap the padlock so it is not cleared",
            ),
            intent = tryComponent(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )
        manufacturer.contains("oppo") || manufacturer.contains("realme") -> Guidance(
            vendor = "OPPO / realme",
            steps = listOf(
                "Settings > Battery > App battery management > Rider Intercom",
                "Turn off Smart control, turn on Allow background running",
                "Settings > Apps > Rider Intercom > Allow auto-launch",
            ),
            intent = tryComponent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
        )
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> Guidance(
            vendor = "vivo / iQOO",
            steps = listOf(
                "Settings > Battery > Background power consumption management",
                "Set Rider Intercom to Allow high background power consumption",
                "i Manager > App manager > Autostart manager > turn Rider Intercom on",
            ),
            intent = tryComponent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
        )
        manufacturer.contains("oneplus") -> Guidance(
            vendor = "OnePlus",
            steps = listOf(
                "Settings > Battery > Battery optimisation > Rider Intercom > Don't optimise",
                "Settings > Battery > Advanced > turn off Sleep standby optimisation",
                "In Recents, lock the app card",
            ),
            intent = tryComponent(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            ),
        )
        manufacturer.contains("samsung") -> Guidance(
            vendor = "Samsung",
            steps = listOf(
                "Settings > Battery > Background usage limits",
                "Add Rider Intercom to Never sleeping apps",
                "Make sure it is not in Sleeping apps or Deep sleeping apps",
            ),
            intent = tryComponent(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity",
            ),
        )
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> Guidance(
            vendor = "Huawei / Honor",
            steps = listOf(
                "Settings > Battery > App launch > Rider Intercom",
                "Turn off Manage automatically, then enable all three manual options",
            ),
            intent = tryComponent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        )
        else -> null
    }

    private fun tryComponent(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun canOpen(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        return context.packageManager.resolveActivity(intent, 0) != null
    }

    fun isIgnoringBatteryOptimisations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @Suppress("BatteryLife")
    fun batteryOptimisationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun bluetoothSettingsIntent(): Intent =
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
