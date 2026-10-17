package com.navigator.app.ble

import org.junit.Assert.*
import org.junit.Test

class BccuHandshakeRetryPolicyTest {
    @Test fun nineCapturedStyleRejectionsNeverLimitStandardProfile() {
        val policy = BccuHandshakeRetryPolicy(BccuConnectionProfile.STANDARD)
        repeat(9) { assertFalse(policy.onDisconnected(19, helloReplied = true, authenticated = false)) }
    }

    @Test fun thirdExperimentalRejectionPausesInsteadOfLooping() {
        val policy = BccuHandshakeRetryPolicy(BccuConnectionProfile.HUSQVARNA_401_2025)
        assertFalse(policy.onDisconnected(19, true, false))
        assertFalse(policy.onDisconnected(19, true, false))
        assertTrue(policy.onDisconnected(19, true, false))
    }

    @Test fun radioTimeoutAndCancelledPreHandshakeAttemptDoNotConsumeBudget() {
        val policy = BccuHandshakeRetryPolicy(BccuConnectionProfile.HUSQVARNA_401_2025)
        repeat(5) {
            assertFalse(policy.onDisconnected(8, true, false))
            assertFalse(policy.onDisconnected(22, false, false))
            assertFalse(policy.onDisconnected(19, false, false))
        }
        assertFalse(policy.onDisconnected(19, true, false))
        assertFalse(policy.onDisconnected(19, true, false))
        assertTrue(policy.onDisconnected(19, true, false))
    }

    @Test fun completedSessionResetsPreviousFailures() {
        val policy = BccuHandshakeRetryPolicy(BccuConnectionProfile.HUSQVARNA_401_2025)
        repeat(2) { policy.onDisconnected(19, true, false) }
        assertFalse(policy.onDisconnected(19, true, true))
        assertFalse(policy.onDisconnected(19, true, false))
        assertFalse(policy.onDisconnected(19, true, false))
    }

    @Test fun explicitRetryStartsANewBudget() {
        val policy = BccuHandshakeRetryPolicy(BccuConnectionProfile.HUSQVARNA_401_2025)
        repeat(3) { policy.onDisconnected(19, true, false) }
        policy.reset()
        assertFalse(policy.onDisconnected(19, true, false))
    }
}
