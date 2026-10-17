package com.navigator.app.ble

import org.junit.Assert.*
import org.junit.Test

class BccuConnectionProfileTest {
    private val standard = BccuConnectionProfile.STANDARD
    private val husqvarna = BccuConnectionProfile.HUSQVARNA_401_2025

    @Test fun absentOrUnknownSelectionKeepsKtmDefaults() {
        listOf(null, "", "KTM", "Husqvarna", "future_profile").forEach {
            assertEquals(standard, BccuConnectionProfile.fromId(it))
        }
        assertEquals(0, standard.helloReply)
    }

    @Test fun onlyExplicitProfileEnablesHusqvarnaHello() {
        assertEquals(husqvarna, BccuConnectionProfile.fromId("husqvarna_401_2025"))
        assertEquals(1, husqvarna.helloReply)
    }

    @Test fun ktmWritesRemainWithResponseRegardlessOfCapabilities() {
        for (auth in listOf(false, true)) for (supported in listOf(false, true)) {
            assertFalse(standard.useWriteWithoutResponse(auth, supported))
        }
    }

    @Test fun experimentalWriteModeIsLimitedToSupportedAuthReply() {
        assertTrue(husqvarna.useWriteWithoutResponse(isAuthReply = true, supported = true))
        assertFalse(husqvarna.useWriteWithoutResponse(isAuthReply = false, supported = true))
        assertFalse(husqvarna.useWriteWithoutResponse(isAuthReply = true, supported = false))
        assertFalse(husqvarna.useWriteWithoutResponse(isAuthReply = false, supported = false))
    }

    @Test fun missingKeysPauseOnlyTheOptInProfile() {
        assertTrue(husqvarna.needsSessionKeys(null))
        assertFalse(standard.needsSessionKeys(null))
        assertFalse(husqvarna.needsSessionKeys(List(16) { ByteArray(16) }))
        assertTrue(husqvarna.needsSessionKeys(List(15) { ByteArray(16) }))
        assertTrue(husqvarna.needsSessionKeys(List(16) { ByteArray(15) }))
    }

    @Test fun completeSyntheticPoolSurvivesStorageEncoding() {
        val encoded = (0..15).joinToString(",") { "%02x".format(it).repeat(16) }
        val keys = BccuSessionKeyCodec.decode(encoded)!!
        assertEquals(16, keys.size)
        keys.forEachIndexed { i, key -> assertArrayEquals(ByteArray(16) { i.toByte() }, key) }
    }

    @Test fun malformedOrPartialStorageDoesNotBecomeAnAesKey() {
        val key = "01".repeat(16)
        listOf(null, "", key, List(15) { key }.joinToString(","),
            List(17) { key }.joinToString(","), List(16) { "z".repeat(32) }.joinToString(","),
            List(16) { "1".repeat(31) }.joinToString(","), List(16) { "1".repeat(34) }.joinToString(",")
        ).forEach { assertNull(BccuSessionKeyCodec.decode(it)) }
    }
}
