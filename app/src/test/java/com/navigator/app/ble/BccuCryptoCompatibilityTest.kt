package com.navigator.app.ble

import org.junit.Assert.*
import org.junit.Test

/** Public synthetic vectors, generated independently with Python cryptography + hashlib.
 * No rider traffic, actual nonces, keys, addresses or route text belongs in these tests.
 */
class BccuCryptoCompatibilityTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun officialStyleChallengeAndNavigationUseTheExistingCrypto() {
        val (iv, secret) = BccuCrypto.computeTempIvAndSecret(ByteArray(16) { it.toByte() }, ByteArray(16) { (it + 16).toByte() })
        assertArrayEquals(hex("08090a0b0c0d0e0f1011121314151617"), iv)
        assertArrayEquals(hex("18191a1b1c1d1e1f0001020304050607"), secret)
        val challenge = BccuCrypto.decryptControl(hex("8537a712942b9fbb9e6f647281e0eed8"), secret, iv)
        assertArrayEquals(hex("20210123ff25012728292a2b2c2d2e2f"), challenge)
        val keys = BccuCrypto.deriveSessionKeys(challenge, BccuCrypto.buildMirrored(challenge), iv, secret)
        assertTrue(BccuSessionKeyCodec.isValid(keys))
        assertArrayEquals(hex("be59e692c0272482d545e5b44143e22b"), keys[6])
        assertArrayEquals(hex("0132"), BccuCrypto.decryptData(
            hex("8a265e617c856ba33470fbe900b7560ba092ab582cdb7b513c15c7ef471bf880"), keys[6], iv))
    }

    @Test fun profileChangesOnlyTheCommandInControlHeader() {
        BccuConnectionProfile.entries.forEach { profile ->
            val message = BccuCrypto.buildControlMessage(profile.helloReply)
            assertEquals(16, message.size)
            assertEquals(255, message[2].toInt() and 255)
            assertEquals(profile.helloReply, message[4].toInt() and 255)
            assertEquals(1, message[6].toInt() and 255)
        }
    }
}
