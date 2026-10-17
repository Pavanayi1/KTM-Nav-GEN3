package com.navigator.app.branding

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.navigator.app.logging.AppLogger
import com.navigator.app.ui.theme.Brand

/** Cosmetic only: never reads or writes the bike's connection profile or bond. */
object LauncherBranding {
    fun apply(context: Context, brand: Brand?): Boolean {
        val pm = context.packageManager
        fun component(identity: LauncherIdentity) = ComponentName(
            context.packageName, "${context.packageName}.launcher.${identity.alias}"
        )
        return try {
            val enabled = LauncherIdentity.entries.filter { identity ->
                when (pm.getComponentEnabledSetting(component(identity))) {
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> identity.enabledByDefault
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    else -> false
                }
            }.toSet()
            val changes = LauncherIdentity.changes(LauncherIdentity.forBrand(brand), enabled)
            fun state(enable: Boolean) = if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            if (changes.isNotEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.setComponentEnabledSettings(changes.map { (identity, enable) ->
                        PackageManager.ComponentEnabledSetting(
                            component(identity), state(enable), PackageManager.DONT_KILL_APP
                        )
                    })
                } else {
                    // Stop on failure. Never disable an old icon if enabling its replacement failed.
                    changes.forEach { (identity, enable) ->
                        pm.setComponentEnabledSetting(component(identity), state(enable), PackageManager.DONT_KILL_APP)
                    }
                }
                AppLogger.log("Brand", "Launcher updated to ${brand?.id ?: "default"}")
            }
            true
        } catch (e: RuntimeException) {
            // The saved theme is reapplied next launch, including recovery from a partial update.
            AppLogger.log("Brand", "Launcher update failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}
