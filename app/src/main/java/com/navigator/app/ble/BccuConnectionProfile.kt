package com.navigator.app.ble

/** Explicit per-device opt-in. Never infer a wire protocol from the UI brand or device name. */
enum class BccuConnectionProfile(val id: String, val label: String, val helloReply: Int) {
    STANDARD("standard", "Standard (KTM / default)", 0),
    HUSQVARNA_401_2025("husqvarna_401_2025", "Husqvarna 401 (2025) — experimental", 1);

    fun useWriteWithoutResponse(isAuthReply: Boolean, supported: Boolean): Boolean =
        this == HUSQVARNA_401_2025 && isAuthReply && supported

    fun needsSessionKeys(keys: List<ByteArray>?): Boolean =
        this == HUSQVARNA_401_2025 && !BccuSessionKeyCodec.isValid(keys)

    companion object {
        fun fromId(id: String?): BccuConnectionProfile = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

/** Only malformed/missing pools are rejected here; stale but well-formed keys need a bike test. */
object BccuSessionKeyCodec {
    fun isValid(keys: List<ByteArray>?): Boolean = keys?.size == 16 && keys.all { it.size == 16 }

    fun decode(stored: String?): List<ByteArray>? {
        val parts = stored?.split(',') ?: return null
        if (parts.size != 16 || parts.any { !it.matches(Regex("[0-9a-fA-F]{32}")) }) return null
        return parts.map { key -> ByteArray(16) { key.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
    }
}

enum class BccuConnectionIssue(val message: String) {
    MISSING_KEYS("This app has no valid keys for the bike. Complete a fresh pairing with Navigator and confirm it on the dash, then retry. Android pairing alone does not provide these keys."),
    HANDSHAKE_REJECTED("The dash rejected three connection attempts. Automatic retries are paused. Close other bike apps, check the pairing on the dash, then retry."),
    HANDSHAKE_TIMEOUT("The dash did not finish authorization. Automatic retries are paused. Check for a confirmation on the dash, then retry.");

    companion object {
        fun fromId(id: String?): BccuConnectionIssue? = entries.firstOrNull { it.name == id }
    }
}

/** Count only the captured rejection pattern. Ordinary KTM reconnects retain their existing policy. */
class BccuHandshakeRetryPolicy(private val profile: BccuConnectionProfile) {
    private var rejections = 0

    fun reset() { rejections = 0 }

    fun onDisconnected(status: Int, helloReplied: Boolean, authenticated: Boolean): Boolean {
        if (authenticated) reset()
        if (profile == BccuConnectionProfile.STANDARD || authenticated || !helloReplied || status != 19) return false
        rejections++
        return rejections >= 3
    }
}
