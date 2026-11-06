package com.navigator.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Single place for all persisted app configuration: bonded bike address,
 * Gemini API key, notification source apps, nav-app override, and preferred
 * call-audio device. Backed by EncryptedSharedPreferences since the Gemini
 * key is a secret worth protecting at rest.
 */
class AppSettings(context: Context) {

    // LAZY on purpose. Building this costs an Android Keystore round-trip
    // (MasterKey) plus opening an EncryptedSharedPreferences, and the migration
    // check below reads `store.all`, which DECRYPTS EVERY ENTRY. Most callers
    // - the notification listener on every notification, the idle dashboard
    // every 1.5s, the connection watchdogs every 20s - only ever touch
    // `pairingPrefs` below and never read an encrypted value at all, so as an
    // eagerly-initialised field this was pure main-thread cost on every single
    // `AppSettings(context)` construction in the app.
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        fun open(name: String) = EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val store = open(SETTINGS_STORE)
        // Same reasoning as the pairing store: this one holds the brand choice,
        // rider name, Gemini key and every toggle. Carry it over once from the
        // pre-rename store rather than resetting everyone's setup.
        if (store.all.isEmpty()) {
            runCatching { open(LEGACY_SETTINGS_STORE) }.getOrNull()?.let { copyInto(it, store) }
        }
        store
    }

    // Plain (unencrypted) prefs for non-secret pairing state. EncryptedSharedPreferences
    // is deprecated and prone to silent corruption/reset on some devices; the
    // "have we paired this bike before" flag MUST persist reliably, because if it
    // doesn't the app re-triggers the bike's physical "add device" prompt on every
    // reconnect and the handshake stalls at "handshaking". None of this is secret.
    private val pairingPrefs: SharedPreferences =
        context.getSharedPreferences("navigator_pairing", Context.MODE_PRIVATE).also {
            migrateLegacyStore(context, it)
        }

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

    /**
     * Model names the rider typed in by hand, kept so a newly-released Gemini
     * model stays in the picker once it's been entered. Plain store: not
     * secret, and it must survive the encrypted-prefs reset described above.
     */
    var customGeminiModels: Set<String>
        get() = pairingPrefs.getStringSet(KEY_GEMINI_CUSTOM_MODELS, emptySet()) ?: emptySet()
        set(value) = pairingPrefs.edit().putStringSet(KEY_GEMINI_CUSTOM_MODELS, value).apply()

    /** Gemini model used for notification summaries (see [GEMINI_MODELS]). */
    var geminiModel: String
        get() = (prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL)
            .let { if (it in RETIRED_GEMINI_MODELS) DEFAULT_GEMINI_MODEL else it }
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

    /**
     * When on, the idle watchdog never shuts the connection service down, so
     * the app keeps looking for the bike indefinitely instead of giving up
     * after ten minutes. Costs battery on a day with no ride - which is
     * exactly why it is off by default and the rider opts in.
     */
    var keepSessionAlive: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SESSION_ALIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SESSION_ALIVE, value).apply()

    /** Whether the dash notification banner scrolls (marquee) when text overflows. */
    var marqueeEnabled: Boolean
        get() = prefs.getBoolean(KEY_MARQUEE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MARQUEE_ENABLED, value).apply()

    /**
     * When on, the handlebar remote acts as a system-wide gamepad: while any
     * OTHER app is in front, UP/DOWN move focus, SET clicks, BACK goes back -
     * driven through the accessibility service. When Navigator itself is in
     * front, the remote drives Navigator's own UI as before. Requires the user to
     * enable Navigator's accessibility service in Android Settings.
     */
    var gamepadEnabled: Boolean
        get() = pairingPrefs.getBoolean(KEY_GAMEPAD_ENABLED, false)
        set(value) = pairingPrefs.edit().putBoolean(KEY_GAMEPAD_ENABLED, value).apply()

    /**
     * What the handlebar remote drives:
     *  - [MODE_OFF]        : nothing — the buttons control the BIKE's own dash as
     *                        normal (the app doesn't act on them). This is the
     *                        "remote session disabled" state.
     *  - [MODE_CONTROLLER] : a D-pad/gamepad for the phone (up/down/left/right +
     *                        select), driven through the accessibility service.
     *  - [MODE_MUSIC]      : volume ± and track next/previous / play-pause.
     *  - [MODE_INTERCOM]   : talk to the pillion over Rider Intercom - hold SET
     *                        to talk, volume ±, noise level, end call.
     * Long-pressing UP on the remote opens the picker from any screen. Default
     * OFF so a fresh install never hijacks the bike's own button behaviour until
     * the rider explicitly enables it. Legacy stored values are migrated.
     */
    var remoteMode: String
        get() = when (val v = pairingPrefs.getString(KEY_REMOTE_MODE, MODE_OFF)) {
            MODE_MEDIA -> MODE_MUSIC          // legacy
            MODE_GAMEPAD, MODE_DASH -> MODE_CONTROLLER // legacy
            MODE_OFF, MODE_CONTROLLER, MODE_MUSIC, MODE_INTERCOM -> v
            else -> MODE_OFF
        }
        set(value) = pairingPrefs.edit().putString(KEY_REMOTE_MODE, value).apply()

    /**
     * Whether we've ever completed the BCCU handshake with this bike before.
     * The bike decides whether to show its physical "confirm new pairing"
     * prompt based on whether our reply to its cmd=HELLO is "I don't know you"
     * (cmd 0) or "I know you, just refresh keys" (cmd 1) - so this flag, not
     * the in-memory session key pool (which is intentionally re-derived every
     * connection per protocol), is what must persist across app/service
     * restarts to avoid re-prompting on every reconnect.
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
        pairingPrefs.edit().remove(key).commit()
        runCatching { prefs.edit().remove(key).commit() }
    }

    /**
     * The long-term 16-key BCCU pool derived at first pairing, per bike. The
     * BCCU protocol (BCCU_BLE_PROTOCOL.md §2) treats this as the pre-shared
     * secret that proves a KNOWN device on reconnect - both official apps
     * persist it. Storing and re-presenting it is what should let the dash
     * skip its physical "add device" prompt on every cold boot. Plain store
     * (must persist reliably across updates, same reasoning as the bonded
     * address); base64-encoded, comma-separated, 16 entries of 16 bytes each.
     */
    fun saveKeyPool(deviceAddress: String, keys: List<ByteArray>) {
        if (keys.size != 16) return
        val encoded = keys.joinToString(",") { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        pairingPrefs.edit().putString(KEY_KEYPOOL_PREFIX + deviceAddress.uppercase(), encoded).commit()
    }

    fun loadKeyPool(deviceAddress: String): List<ByteArray>? {
        val s = pairingPrefs.getString(KEY_KEYPOOL_PREFIX + deviceAddress.uppercase(), null) ?: return null
        val keys = s.split(",").mapNotNull {
            runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull()
        }
        return keys.takeIf { it.size == 16 && it.all { k -> k.size == 16 } }
    }

    fun clearKeyPool(deviceAddress: String) {
        pairingPrefs.edit().remove(KEY_KEYPOOL_PREFIX + deviceAddress.uppercase()).commit()
    }

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

    /**
     * Write every log line to disk (app-private file + the public
     * Downloads/Navigator copy). OFF by default: the file + MediaStore append
     * runs on every BLE/RCM/notification line, which is a lot of writes for a
     * feature only needed when diagnosing something. The in-app Logs screen and
     * logcat are unaffected - they read the in-memory buffer either way.
     */
    var fileLoggingEnabled: Boolean
        get() = pairingPrefs.getBoolean(KEY_FILE_LOGGING, false)
        set(value) = pairingPrefs.edit().putBoolean(KEY_FILE_LOGGING, value).apply()

    /**
     * Hidden developer mode, unlocked by tapping the home-screen wordmark ten
     * times. Gates the Diagnostics test rows in Settings, the DEV shortcut on
     * the home header, and the notification-audit trail (which records every
     * notification the listener sees, so it stays off for normal riding).
     */
    var devModeEnabled: Boolean
        get() = pairingPrefs.getBoolean(KEY_DEV_MODE, false)
        set(value) = pairingPrefs.edit().putBoolean(KEY_DEV_MODE, value).apply()

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

    /**
     * Home and Work, the two entries the dash shows in its own Favourites
     * menu. Each is a Google Maps navigation target - "lat,lon" when set from
     * the current location, or a typed address.
     *
     * [homeAddress] / [workAddress] are the human labels the dash shows under
     * the title; they fall back to the target itself when the rider never
     * typed one, because a bare "12.971599,77.594566" on the cluster is
     * useless to read at a glance but is still better than an empty line.
     */
    var homeDestination: String?
        get() = pairingPrefs.getString(KEY_HOME_DEST, null)
        set(value) = pairingPrefs.edit().putString(KEY_HOME_DEST, value).apply()

    var homeAddress: String?
        get() = pairingPrefs.getString(KEY_HOME_ADDRESS, null)
        set(value) = pairingPrefs.edit().putString(KEY_HOME_ADDRESS, value).apply()

    var workDestination: String?
        get() = pairingPrefs.getString(KEY_WORK_DEST, null)
        set(value) = pairingPrefs.edit().putString(KEY_WORK_DEST, value).apply()

    var workAddress: String?
        get() = pairingPrefs.getString(KEY_WORK_ADDRESS, null)
        set(value) = pairingPrefs.edit().putString(KEY_WORK_ADDRESS, value).apply()

    /**
     * The rider pressed Exit/Stop. While set, nothing may bring the connection
     * service back in the background (notifications, Bluetooth on, boot) - a
     * quit has to stay quit. Cleared when the rider opens the app or the bike
     * itself shows up over Bluetooth (ignition on). Plain store + commit(): the
     * process is usually killed right after an exit.
     */
    var userQuit: Boolean
        get() = pairingPrefs.getBoolean(KEY_USER_QUIT, false)
        set(value) { pairingPrefs.edit().putBoolean(KEY_USER_QUIT, value).commit() }

    /** Idle dash screen: rotate info cards on the bike's display while not navigating. */
    var idleDashEnabled: Boolean
        get() = pairingPrefs.getBoolean(KEY_IDLE_DASH_ENABLED, false)
        set(value) = pairingPrefs.edit().putBoolean(KEY_IDLE_DASH_ENABLED, value).apply()

    var idleShowClock: Boolean
        get() = pairingPrefs.getBoolean(KEY_IDLE_SHOW_CLOCK, true)
        set(value) = pairingPrefs.edit().putBoolean(KEY_IDLE_SHOW_CLOCK, value).apply()

    var idleShowMusic: Boolean
        get() = pairingPrefs.getBoolean(KEY_IDLE_SHOW_MUSIC, true)
        set(value) = pairingPrefs.edit().putBoolean(KEY_IDLE_SHOW_MUSIC, value).apply()

    var idleShowWeather: Boolean
        get() = pairingPrefs.getBoolean(KEY_IDLE_SHOW_WEATHER, true)
        set(value) = pairingPrefs.edit().putBoolean(KEY_IDLE_SHOW_WEATHER, value).apply()

    /** During navigation: when the next turn is ≥500 m away, show the current track on the center view instead of the road name. */
    var farTurnMusicEnabled: Boolean
        get() = pairingPrefs.getBoolean(KEY_FAR_TURN_MUSIC, false)
        set(value) = pairingPrefs.edit().putBoolean(KEY_FAR_TURN_MUSIC, value).apply()

    /** Seconds each idle card stays on the dash before rotating to the next. */
    var idleRotateSec: Int
        get() = pairingPrefs.getInt(KEY_IDLE_ROTATE_SEC, DEFAULT_IDLE_ROTATE_SEC)
        set(value) = pairingPrefs.edit().putInt(KEY_IDLE_ROTATE_SEC, value.coerceIn(3, 60)).apply()

    /** Auto-record GPS routes to GPX, but only while the phone is charging. */
    var routeAutoRecordEnabled: Boolean
        get() = pairingPrefs.getBoolean(KEY_ROUTE_RECORD, false)
        set(value) = pairingPrefs.edit().putBoolean(KEY_ROUTE_RECORD, value).apply()

    /** Claimed bike mileage (km/l) for the ride fuel estimate. 0 = unset. */
    var fuelMileageKmPerL: Float
        get() = pairingPrefs.getFloat(KEY_FUEL_MILEAGE, 0f)
        set(value) = pairingPrefs.edit().putFloat(KEY_FUEL_MILEAGE, value).apply()

    /** Fuel price per litre. 0 = unset. */
    var fuelPricePerL: Float
        get() = pairingPrefs.getFloat(KEY_FUEL_PRICE, 0f)
        set(value) = pairingPrefs.edit().putFloat(KEY_FUEL_PRICE, value).apply()

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

        /**
         * The plain pairing store was renamed when the code package was
         * renamed. That store holds the bonded bike address, the "paired
         * before" flag and the 16-key session pool - lose it and the rider is
         * thrown back to the pairing screen and has to re-confirm "add device"
         * on the dash. So on first construction, if the current store is empty
         * and a store under the previous name exists, copy every value across
         * (and leave the old file alone, so a downgrade still works).
         */
        private var migrationChecked = false

        private fun migrateLegacyStore(context: Context, current: SharedPreferences) {
            if (migrationChecked) return
            migrationChecked = true
            if (current.all.isNotEmpty()) return
            val legacy = runCatching {
                context.getSharedPreferences(LEGACY_PAIRING_STORE, Context.MODE_PRIVATE)
            }.getOrNull() ?: return
            copyInto(legacy, current)
        }

        /** Copy every entry from [from] into [into]; no-op if [from] is empty/unreadable. */
        private fun copyInto(from: SharedPreferences, into: SharedPreferences) {
            val entries = runCatching { from.all }.getOrNull().orEmpty()
            if (entries.isEmpty()) return
            val editor = into.edit()
            for ((key, value) in entries) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            // commit, not apply: the connection service can read these stores
            // moments later on a boot-triggered auto-connect.
            editor.commit()
        }

        private const val SETTINGS_STORE = "navigator_settings"

        // Store names used by builds before the package rename. These two
        // strings are the only place the old name survives, and deliberately
        // so: without them an updating rider loses their pairing keys and every
        // setting. Safe to delete once nobody is updating from a pre-rename build.
        private const val LEGACY_PAIRING_STORE = "opendash_pairing"
        private const val LEGACY_SETTINGS_STORE = "opendash_settings"

        private const val KEY_BONDED_ADDRESS = "bonded_device_address"
        private const val KEY_BONDED_NAME = "bonded_device_name"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_GEMINI_CUSTOM_MODELS = "gemini_custom_models"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_BRAND = "brand"

        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"

        /**
         * Models Google has since retired. Confirmed against the live API on
         * 2026-09-11: generateContent answers
         * "This model models/gemini-2.0-flash is no longer available" for each
         * of these. A rider who picked one before it was retired would
         * otherwise get a silent 404 and a permanently empty summary, so these
         * are mapped back to the default on read.
         */
        private val RETIRED_GEMINI_MODELS = setOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
        )

        /**
         * Built-in Gemini model choices for notification summaries. Google ships
         * new models faster than this app ships builds, so this list is only a
         * convenience - anything typed into "Custom model…" is stored in
         * [customGeminiModels] and offered here from then on, which means a new
         * model name never needs an app update. The name is passed straight to
         * the REST endpoint, so whatever Google publishes works as-is.
         */
        val GEMINI_MODELS = listOf(
            "gemini-flash-latest",
            "gemini-pro-latest",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
        )

        /** Sentinel row in the model picker that opens the free-text entry. */
        const val GEMINI_MODEL_CUSTOM = "__custom__"
        private const val KEY_NOTIFICATION_APPS = "notification_source_apps"
        private const val KEY_NAV_APP_OVERRIDE = "nav_app_override"
        private const val KEY_CALL_AUDIO_DEVICE = "call_audio_device"
        private const val KEY_MIRROR_ENABLED = "mirror_enabled"
        private const val KEY_MARQUEE_ENABLED = "marquee_enabled"
        private const val KEY_KEEP_SESSION_ALIVE = "keep_session_alive"
        private const val KEY_GAMEPAD_ENABLED = "gamepad_enabled"
        private const val KEY_REMOTE_MODE = "remote_mode"
        // New remote modes.
        const val MODE_OFF = "off"
        const val MODE_CONTROLLER = "controller"
        const val MODE_MUSIC = "music"
        const val MODE_INTERCOM = "intercom"
        // Legacy values, kept only for migration in [remoteMode].
        const val MODE_MEDIA = "media"
        const val MODE_GAMEPAD = "gamepad"
        const val MODE_DASH = "dash"
        private const val KEY_TURN_BEEP = "turn_beep_enabled"
        private const val KEY_BEEP_VOLUME = "beep_volume_percent"
        const val DEFAULT_BEEP_VOLUME = 35
        private const val KEY_POWER_SAVE = "power_save_enabled"
        private const val KEY_FILE_LOGGING = "file_logging_enabled"
        private const val KEY_DEV_MODE = "dev_mode_enabled"
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
        private const val KEY_HOME_DEST = "home_destination"
        private const val KEY_HOME_ADDRESS = "home_address"
        private const val KEY_WORK_DEST = "work_destination"
        private const val KEY_WORK_ADDRESS = "work_address"
        private const val KEY_ROUTE_RECORD = "route_auto_record"
        private const val KEY_IDLE_DASH_ENABLED = "idle_dash_enabled"
        private const val KEY_USER_QUIT = "user_quit"
        private const val KEY_IDLE_SHOW_CLOCK = "idle_show_clock"
        private const val KEY_IDLE_SHOW_MUSIC = "idle_show_music"
        private const val KEY_IDLE_SHOW_WEATHER = "idle_show_weather"
        private const val KEY_IDLE_ROTATE_SEC = "idle_rotate_sec"
        private const val KEY_FAR_TURN_MUSIC = "far_turn_music"
        private const val KEY_FUEL_MILEAGE = "fuel_mileage_kmpl"
        private const val KEY_FUEL_PRICE = "fuel_price_per_l"
        const val DEFAULT_IDLE_ROTATE_SEC = 8
        private const val KEY_PAIRED_PREFIX = "paired_before_"
        private const val KEY_KEYPOOL_PREFIX = "keypool_"
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
