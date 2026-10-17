package com.navigator.app.branding

import com.navigator.app.ui.theme.Brand

internal enum class LauncherIdentity(val alias: String, val enabledByDefault: Boolean = false) {
    DEFAULT("DefaultLauncher", true),
    KTM("KtmLauncher"),
    HUSQVARNA("HusqvarnaLauncher");

    companion object {
        fun forBrand(brand: Brand?): LauncherIdentity = when (brand) {
            null -> DEFAULT
            Brand.KTM -> KTM
            Brand.HUSQVARNA -> HUSQVARNA
        }

        /** Enable the replacement first: older Android versions apply one change at a time. */
        fun changes(target: LauncherIdentity, enabled: Set<LauncherIdentity>): List<Pair<LauncherIdentity, Boolean>> =
            buildList {
                if (target !in enabled) add(target to true)
                entries.filter { it != target && it in enabled }.forEach { add(it to false) }
            }
    }
}
