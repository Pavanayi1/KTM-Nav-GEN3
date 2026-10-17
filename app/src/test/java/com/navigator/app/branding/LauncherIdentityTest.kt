package com.navigator.app.branding

import com.navigator.app.ui.theme.Brand
import org.junit.Assert.*
import org.junit.Test

class LauncherIdentityTest {
    @Test fun unselectedInstallKeepsDefaultAndBothBrandsHaveTheirOwnIdentity() {
        assertEquals(LauncherIdentity.DEFAULT, LauncherIdentity.forBrand(null))
        assertEquals(LauncherIdentity.KTM, LauncherIdentity.forBrand(Brand.KTM))
        assertEquals(LauncherIdentity.HUSQVARNA, LauncherIdentity.forBrand(Brand.HUSQVARNA))
        assertEquals(listOf(LauncherIdentity.DEFAULT), LauncherIdentity.entries.filter { it.enabledByDefault })
    }

    @Test fun everySwitchKeepsAnEntryPointAndEndsWithExactlyOneIcon() {
        for (enabled in allStates().filter { it.isNotEmpty() }) {
            for (target in LauncherIdentity.entries) {
                val current = enabled.toMutableSet()
                for ((identity, enable) in LauncherIdentity.changes(target, current)) {
                    if (enable) current.add(identity) else current.remove(identity)
                    assertTrue("An icon must survive each pre-33 update", current.isNotEmpty())
                }
                assertEquals(setOf(target), current)
            }
        }
    }

    @Test fun retryRecoversAfterAnyInterruptedSequentialSwitch() {
        for (enabled in allStates().filter { it.isNotEmpty() }) {
            for (target in LauncherIdentity.entries) {
                val changes = LauncherIdentity.changes(target, enabled)
                for (completed in 0..changes.size) {
                    val current = enabled.toMutableSet()
                    changes.take(completed).forEach { (identity, enable) ->
                        if (enable) current.add(identity) else current.remove(identity)
                    }
                    LauncherIdentity.changes(target, current).forEach { (identity, enable) ->
                        if (enable) current.add(identity) else current.remove(identity)
                        assertTrue(current.isNotEmpty())
                    }
                    assertEquals(setOf(target), current)
                }
            }
        }
    }

    @Test fun openingAgainDoesNotRewriteComponentStates() {
        for (target in LauncherIdentity.entries) {
            assertTrue(LauncherIdentity.changes(target, setOf(target)).isEmpty())
        }
    }

    @Test fun activityOpenedFromNotificationCanRepairMissingIcons() {
        for (target in LauncherIdentity.entries) {
            assertEquals(listOf(target to true), LauncherIdentity.changes(target, emptySet()))
        }
    }

    private fun allStates(): List<Set<LauncherIdentity>> = (0 until 8).map { mask ->
        LauncherIdentity.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
    }
}
