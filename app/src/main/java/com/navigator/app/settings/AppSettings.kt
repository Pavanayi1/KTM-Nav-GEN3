package com.navigator.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.annotation.SuppressLint
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.navigator.app.ble.BccuConnectionIssue
import com.navigator.app.ble.BccuConnectionProfile
import com.navigator.app.ble.BccuSessionKeyCodec
import java.util.Locale

/**
 * Single place for all persisted app configuration: bonded bike address,
 * Gemini API key, notification source apps, nav-app override, and preferred
 * call-audio device. Backed by EncryptedSharedPreferences since the Gemini
 * key is a secret worth protecting at rest.
 */
class AppSettings(context: Context) {

    /** Protocol choices and a paused retry reason belong to one bike, never the theme/brand. */
    fun connectionProfile(address: String): BccuConnectionProfile =
        BccuConnectionProfile.fromId(
            pairingPrefs.getString("connection_profile_" + address.uppercase(Locale.ROOT), null)
        )

    // The service may start immediately after this UI action; persist the profile first.
    @SuppressLint("ApplySharedPref")
    fun setConnectionProfile(address: String, profile: BccuConnectionProfile) {
        pairingPrefs.edit()
            .putString("connection_profile_" + address.uppercase(Locale.ROOT), profile.id)
            .remove("connection_issue_" + address.uppercase(Locale.ROOT))
            .commit()
    }

    fun connectionIssue(address: String): BccuConnectionIssue? =
        BccuConnectionIssue.fromId(
            pairingPrefs.getString("connection_issue_" + address.uppercase(Locale.ROOT), null)
        )

    // A terminal auth failure must remain paused even if the process is stopped next.
    @SuppressLint("ApplySharedPref")
    fun setConnectionIssue(address: String, issue: BccuConnectionIssue?) {
        pairingPrefs.edit().putString("connection_issue_" + address.uppercase(Locale.ROOT), issue?.name).commit()
    }

    /** Strict loading is opt-in so the standard profile keeps its existing key handling. */
    fun loadValidatedSessionKeys(address: String): List<ByteArray>? =
        BccuSessionKeyCodec.decode(
            pairingPrefs.getString(KEY_SESSION_KEYS_PREFIX + address.uppercase(), null)
        )

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "opendash_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Plain (unencrypted) prefs for non-secret pairing state. EncryptedSharedPreferences
    // is deprecated and prone to silent corruption/reset on some devices; the
    // "have we paired this bike before" flag MUST persist reliably, because if it
    // doesn't the app re-triggers the bike's physical "add device" prompt on every
    // reconnect and the handshake stalls at "handshaking". None of this is secret.
    private val pairingPrefs: SharedPreferences =
        context.getSharedPreferences("opendash_pairing", Context.MODE_PRIVATE)

    // Bonded bike identity lives in the PLAIN store: EncryptedSharedPreferences
    // silently resets on some devices/updates (documented on pairingPrefs), and
    // every reset threw the rider back to the pairing screen ("always asking to
    // re-pair", R21/R22 field reports - observed directly after the R21 update).
    // A BLE MAC isn't a secret. Reads fall back to the legacy encrypted store
    // once, migrating bikes paired by older builds; writes clear the legacy copy
    // so a stale encrypted value can't resurrect after "Re-pair vehicle".
    var bondedDeviceAddress: String?
        get() = pairingPrefs.getString(KEY_BONDED_ADDRESS, null)
            ?: runCatching { prefs.getString(KEY_BONDED_ADDRESS, null) }.getOrNull()?.also {
                pairingPrefs.edit().putString(KEY_BONDED_ADDRESS, it).apply()
            }
        set(value) {
            pairingPrefs.edit().putString(KEY_BONDED_ADDRESS, value).commit()
            runCatching { prefs.edit().remove(KEY_BONDED_ADDRESS).apply() }
        }

    var bondedDeviceName: String?
        get() = pairingPrefs.getString(KEY_BONDED_NAME, null)
            ?: runCatching { prefs.getString(KEY_BONDED_NAME, null) }.getOrNull()?.also {
                pairingPrefs.edit().putString(KEY_BONDED_NAME, it).apply()
            }
        set(value) {
            pairingPrefs.edit().putString(KEY_BONDED_NAME, value).commit()
            runCatching { prefs.edit().remove(KEY_BONDED_NAME).apply() }
        }

    var geminiApiKey: String?
        get() = prefs.getString(KEY_GEMINI_KEY, null)
        set(value) = prefs.edit().putString(KEY_GEMINI_KEY, value).apply()

    /** Gemini model used for notification summaries (see [GEMINI_MODELS]). */
    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value).apply()

    /** Whether the first-run onboarding (name + permissions) has been completed. */
    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    /**
     * Selected motorcycle brand — drives the whole app's theme (KTM dark/orange,
     * Husqvarna light/blue). Plain store so it survives the encrypted-prefs reset
     * that also wiped the bonded bike (see [bondedDeviceAddress]).
     */
    var brand: com.navigator.app.ui.theme.Brand
        get() = com.navigator.app.ui.theme.Brand.fromId(pairingPrefs.getString(KEY_BRAND, null))
        set(value) = pairingPrefs.edit().putString(KEY_BRAND, value.id).apply()

    /** True once the rider has explicitly picked a brand (so first-run shows brand select). */
    var brandChosen: Boolean
        get() = pairingPrefs.contains(KEY_BRAND)
        set(value) { if (!value) pairingPrefs.edit().remove(KEY_BRAND).apply() }

    /** Used to personalize the test notification and greeting text ("Hey <name>"). */
    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    /** Package names of apps whose notifications should be mirrored to the dash. */
    var notificationSourceApps: Set<String>
        get() = prefs.getStringSet(KEY_NOTIFICATION_APPS, DEFAULT_NOTIFICATION_APPS) ?: DEFAULT_NOTIFICATION_APPS
        set(value) = prefs.edit().putStringSet(KEY_NOTIFICATION_APPS, value).apply()

    /** Null = auto-detect via CATEGORY_NAVIGATION notifications. Non-null = manual override package name. */
    var navAppOverride: String?
        get() = prefs.getString(KEY_NAV_APP_OVERRIDE, null)
        set(value) = prefs.edit().putString(KEY_NAV_APP_OVERRIDE, value).apply()

    var preferredCallAudioDeviceAddress: String?
        get() = prefs.getString(KEY_CALL_AUDIO_DEVICE, null)
        set(value) = prefs.edit().putString(KEY_CALL_AUDIO_DEVICE, value).apply()

    /** Quick-mute: when false, non-navigation notifications are not pushed to the dash. */
    var mirrorEnabled: Boolean
        get() = prefs.getBoolean(KEY_MIRROR_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MIRROR_ENABLED, value).apply()

    /** Whether the dash notification banner scrolls (marquee) when text overflows. */
    var marqueeEnabled: Boolean
        get() = prefs.getBoolean(KEY_MARQUEE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MARQUEE_ENABLED, value).apply()

    /**
     * When on, the handlebar remote acts as a system-wide gamepad: while any
     * OTHER app is in front, UP/DOWN move focus, SET clicks, BACK goes back -
     * driven through the accessibility service. When OpenDash itself is in
     * front, the remote drives OpenDash's own UI as before. Requires the user to
     * enable OpenDash's accessibility service in Android Settings.
     */
    var gamepadEnabled: Boolean
        get() = pairingPrefs.getBoolean(KEY_GAMEPAD_ENABLED, false)
        set(value) = pairingPrefs.edit().putBoolean(KEY_GAMEPAD_ENABLED, value).apply()

    /**
     * What the handlebar remote does with its buttons: [MODE_MEDIA] (play/pause,
     * next/prev), [MODE_GAMEPAD] (navigate other phone apps via accessibility),
     * or [MODE_DASH] (open/drive Navigator Gen3's own menu). Triple-press Up on
     * the remote pops an overlay to switch. Default: media.
     */
    var remoteMode: String
        get() = pairingPrefs.getString(KEY_REMOTE_MODE, MODE_MEDIA) ?: MODE_MEDIA
        set(value) = pairingPrefs.edit().putString(KEY_REMOTE_MODE, value).apply()

    /**
     * Whether we've ever completed the BCCU handshake with this bike before.
     * The bike decides whether to show its physical "confirm new pairing"
     * prompt from its own bond memory (see the HELLO handling in
     * BccuConnectionService). This flag persists that "known bike" state across
     * app/service restarts so we don't re-prompt on every reconnect.
     *
     * NOTE: contrary to an earlier assumption, the session-key pool is NOT
     * re-derived on every connection. Field logs on a Gen-3 dash show it keeps
     * the pool derived at pairing time across ignition cycles and resumes later
     * sessions by key-select alone (cmd 16..31) with no GENERATE_KEYS. So the
     * pool must persist too - see [storeSessionKeys]/[loadSessionKeys] - or the
     * dash's key-select lands on an empty app-side pool and every reconnect
     * stalls. That was the root cause of the "reconnect after an ignition cycle
     * is unreliable" bug.
     */
    fun hasPairedBefore(deviceAddress: String): Boolean {
        val key = KEY_PAIRED_PREFIX + deviceAddress.uppercase()
        // Check the reliable plain store first, then fall back to the legacy
        // encrypted store so bikes paired by older builds aren't re-prompted.
        return pairingPrefs.getBoolean(key, false) ||
            runCatching { prefs.getBoolean(key, false) }.getOrDefault(false)
    }

    fun markPairedBefore(deviceAddress: String) {
        // commit() (synchronous) so the flag is on disk before the service can be
        // torn down on the disconnect that often follows authentication.
        pairingPrefs.edit().putBoolean(KEY_PAIRED_PREFIX + deviceAddress.uppercase(), true).commit()
    }

    /**
     * Forget that we've paired this bike, so the next handshake replies HELLO
     * (cmd 0) and the dash shows its physical "add device" prompt to establish
     * fresh keys. Required when the dash has lost its side of the pairing:
     * otherwise we keep replying GENERATE_KEYS against a dash that no longer
     * knows us and the handshake stalls forever at "handshaking". Clears both
     * the plain and legacy-encrypted stores.
     */
    fun clearPairedBefore(deviceAddress: String) {
        val key = KEY_PAIRED_PREFIX + deviceAddress.uppercase()
        // Forgetting a pairing also drops its session-key pool: the pool is only
        // meaningful together with the dash-side pairing record, and both callers
        // (user "forget", handshake self-heal after a genuine dash-side key loss)
        // want a from-scratch HELLO + GENERATE_KEYS next time.
        pairingPrefs.edit()
            .remove(key)
            .remove(KEY_SESSION_KEYS_PREFIX + deviceAddress.uppercase())
            .commit()
        runCatching { prefs.edit().remove(key).commit() }
    }

    /**
     * Persist the session-key pool derived during the pairing handshake, keyed
     * by device MAC. The dash keeps its copy of this pool across ignition cycles
     * and resumes each later session by selecting a key from it (walking the
     * pool downward) instead of re-deriving, so the app-side copy MUST survive
     * disconnects and process death or a silent reconnect is impossible.
     *
     * Stored in the plain pairing store (like [markPairedBefore]) so it is as
     * durable as the "paired before" flag it partners, and so [clearPairedBefore]
     * drops both atomically. commit() for the same reason markPairedBefore uses
     * it: the pool must reach disk before the service can be torn down on the
     * disconnect that often follows authentication.
     */
    fun storeSessionKeys(deviceAddress: String, keys: List<ByteArray>) {
        val joined = keys.joinToString(",") { hex(it) }
        pairingPrefs.edit()
            .putString(KEY_SESSION_KEYS_PREFIX + deviceAddress.uppercase(), joined)
            .commit()
    }

    /** The persisted session-key pool for this bike, or null if none stored. */
    fun loadSessionKeys(deviceAddress: String): List<ByteArray>? {
        val stored = pairingPrefs.getString(KEY_SESSION_KEYS_PREFIX + deviceAddress.uppercase(), null)
        if (stored.isNullOrEmpty()) return null
        return stored.split(",").map { unhex(it) }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Stereo beep pattern as a turn approaches (left ear = left turn). */
    var turnBeepEnabled: Boolean
        get() = pairingPrefs.getBoolean(KEY_TURN_BEEP, true)
        set(value) = pairingPrefs.edit().putBoolean(KEY_TURN_BEEP, value).apply()

    /** Turn-beep loudness, 5..100 (%). Default deliberately gentle - the R20 field test found full volume grating. */
    var beepVolumePercent: Int
        get() = pairingPrefs.getInt(KEY_BEEP_VOLUME, DEFAULT_BEEP_VOLUME)
        set(value) = pairingPrefs.edit().putInt(KEY_BEEP_VOLUME, value.coerceIn(5, 100)).apply()

    /**
     * Adaptive power: high-refresh sensors/GPS only while the phone is charging
     * (the usual state on the bike mount); duty-cycled/off on battery. Turning
     * this OFF keeps everything high-refresh regardless of charge state.
     */
    var powerSaveEnabled: Boolean
        get() = pairingPrefs.getBoolean(KEY_POWER_SAVE, true)
        set(value) = pairingPrefs.edit().putBoolean(KEY_POWER_SAVE, value).apply()

    /** Detect the engine running from the phone's accelerometer (needs calibration). */
    var engineDetectEnabled: Boolean
        get() = pairingPrefs.getBoolean(KEY_ENGINE_DETECT, false)
        set(value) = pairingPrefs.edit().putBoolean(KEY_ENGINE_DETECT, value).apply()

    /** Calibrated vibration RMS with the bike off / idling, from the guided calibration. NaN = uncalibrated. */
    var vibrationIdleRms: Float
        get() = pairingPrefs.getFloat(KEY_VIB_IDLE_RMS, Float.NaN)
        set(value) = pairingPrefs.edit().putFloat(KEY_VIB_IDLE_RMS, value).apply()

    var vibrationEngineRms: Float
        get() = pairingPrefs.getFloat(KEY_VIB_ENGINE_RMS, Float.NaN)
        set(value) = pairingPrefs.edit().putFloat(KEY_VIB_ENGINE_RMS, value).apply()

    /**
     * Engine-detection sensitivity trim, -50..+50 (%). Negative shifts the
     * on/off threshold DOWN (more sensitive - detects a gentler idle), positive
     * shifts it up (fewer false "engine on" from road rumble). 0 = calibrated midpoint.
     */
    var vibrationSensitivity: Int
        get() = pairingPrefs.getInt(KEY_VIB_SENSITIVITY, 0)
        set(value) = pairingPrefs.edit().putInt(KEY_VIB_SENSITIVITY, value.coerceIn(-50, 50)).apply()

    /** Swap the beep left/right channels (default on - matched the rider's headset in the field). */
    var swapBeepChannels: Boolean
        get() = pairingPrefs.getBoolean(KEY_SWAP_BEEP_CHANNELS, true)
        set(value) = pairingPrefs.edit().putBoolean(KEY_SWAP_BEEP_CHANNELS, value).apply()

    /** GPS overspeed alert on/off. */
    var overspeedEnabled: Boolean
        get() = pairingPrefs.getBoolean(KEY_OVERSPEED_ENABLED, true)
        set(value) = pairingPrefs.edit().putBoolean(KEY_OVERSPEED_ENABLED, value).apply()

    /** Speed (km/h) above which the overspeed alert fires. Alert clears at limit − 10. */
    var overspeedLimitKmh: Int
        get() = pairingPrefs.getInt(KEY_OVERSPEED_LIMIT, DEFAULT_OVERSPEED_LIMIT_KMH)
        set(value) = pairingPrefs.edit().putInt(KEY_OVERSPEED_LIMIT, value).apply()

    /** Saved waypoint ("lat,lon"), usable as a Google Maps navigation target. Null = none set. */
    var waypoint: String?
        get() = pairingPrefs.getString(KEY_WAYPOINT, null)
        set(value) = pairingPrefs.edit().putString(KEY_WAYPOINT, value).apply()

    var waypointName: String?
        get() = pairingPrefs.getString(KEY_WAYPOINT_NAME, null)
        set(value) = pairingPrefs.edit().putString(KEY_WAYPOINT_NAME, value).apply()

    /** Auto-record GPS routes to GPX, but only while the phone is charging. */
    var routeAutoRecordEnabled: Boolean
        get() = pairingPrefs.getBoolean(KEY_ROUTE_RECORD, false)
        set(value) = pairingPrefs.edit().putBoolean(KEY_ROUTE_RECORD, value).apply()

    /** Manual hardware-calibration result for one icon, from the Symbol Testing screen. "works", "wrong", or null (untested). */
    fun getIconTestResult(key: String): String? = prefs.getString(KEY_ICON_TEST_PREFIX + key, null)

    fun setIconTestResult(key: String, result: String) {
        prefs.edit().putString(KEY_ICON_TEST_PREFIX + key, result).apply()
    }

    /**
     * User-supplied turn-icon calibration: maps a captured notification-icon
     * bitmap hash to the correct dash TurnIcon name. Because Google Maps reuses
     * byte-identical bitmaps per maneuver, one label makes that maneuver exact
     * forever (U-turns, roundabouts, etc. that the pixel heuristic can't infer).
     * Stored in plain prefs - not secret, and must persist reliably.
     */
    fun getTurnCalibration(hash: Int): String? = pairingPrefs.getString(KEY_TURN_CAL_PREFIX + hash, null)

    fun setTurnCalibration(hash: Int, iconName: String) {
        pairingPrefs.edit().putString(KEY_TURN_CAL_PREFIX + hash, iconName).apply()
    }

    fun clearTurnCalibration(hash: Int) {
        pairingPrefs.edit().remove(KEY_TURN_CAL_PREFIX + hash).apply()
    }

    companion object {
        private const val KEY_BONDED_ADDRESS = "bonded_device_address"
        private const val KEY_BONDED_NAME = "bonded_device_name"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_BRAND = "brand"

        const val DEFAULT_GEMINI_MODEL = "gemini-2.0-flash"

        /** Selectable Gemini models for notification summaries. */
        val GEMINI_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
        )
        private const val KEY_NOTIFICATION_APPS = "notification_source_apps"
        private const val KEY_NAV_APP_OVERRIDE = "nav_app_override"
        private const val KEY_CALL_AUDIO_DEVICE = "call_audio_device"
        private const val KEY_MIRROR_ENABLED = "mirror_enabled"
        private const val KEY_MARQUEE_ENABLED = "marquee_enabled"
        private const val KEY_GAMEPAD_ENABLED = "gamepad_enabled"
        private const val KEY_REMOTE_MODE = "remote_mode"
        const val MODE_MEDIA = "media"
        const val MODE_GAMEPAD = "gamepad"
        const val MODE_DASH = "dash"
        private const val KEY_TURN_BEEP = "turn_beep_enabled"
        private const val KEY_BEEP_VOLUME = "beep_volume_percent"
        const val DEFAULT_BEEP_VOLUME = 35
        private const val KEY_POWER_SAVE = "power_save_enabled"
        private const val KEY_ENGINE_DETECT = "engine_detect_enabled"
        private const val KEY_VIB_IDLE_RMS = "vibration_idle_rms"
        private const val KEY_VIB_ENGINE_RMS = "vibration_engine_rms"
        private const val KEY_VIB_SENSITIVITY = "vibration_sensitivity"
        private const val KEY_SWAP_BEEP_CHANNELS = "swap_beep_channels"
        private const val KEY_OVERSPEED_ENABLED = "overspeed_enabled"
        private const val KEY_OVERSPEED_LIMIT = "overspeed_limit_kmh"
        const val DEFAULT_OVERSPEED_LIMIT_KMH = 80
        private const val KEY_WAYPOINT = "waypoint_latlon"
        private const val KEY_WAYPOINT_NAME = "waypoint_name"
        private const val KEY_ROUTE_RECORD = "route_auto_record"
        private const val KEY_PAIRED_PREFIX = "paired_before_"
        private const val KEY_SESSION_KEYS_PREFIX = "session_keys_"
        private const val KEY_ICON_TEST_PREFIX = "icon_test_"
        private const val KEY_TURN_CAL_PREFIX = "turncal_"

        val DEFAULT_NOTIFICATION_APPS = setOf(
            "com.whatsapp",
            "com.google.android.apps.messaging"
        )

        val KNOWN_NAV_APPS = setOf(
            "com.google.android.apps.maps",
            "com.waze",
            "com.here.app.maps",
            "com.sygic.aura",
            "net.osmand",
            "com.kurviger.app"
        )
    }
}
