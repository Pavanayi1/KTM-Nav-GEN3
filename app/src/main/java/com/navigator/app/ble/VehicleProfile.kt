package com.navigator.app.ble

import android.bluetooth.BluetoothGatt

/**
 * Identifies which KTM/Husqvarna dash we're actually talking to and what it can
 * do, so the app can adapt instead of assuming every bike is the exact Gen-3
 * BCCU unit it was first written against.
 *
 * Two facts drive this:
 *  1. The BCCU family has TWO nav transports (BCCU_BLE_PROTOCOL.md §1, §5):
 *     the *legacy* per-characteristic ETBT service (0700) that this app writes
 *     guidance to byte-exactly, and a *modern* protobuf/PRPC service (0600).
 *     Different model-years / firmware revisions expose different subsets.
 *  2. Older or different bikes (field reports: 1290 / 890 pairing failures,
 *     issue-tracker item #6) may not present 0700 at all - in which case the
 *     old code silently bailed in onServicesDiscovered and the rider just saw
 *     "handshaking" forever with no explanation.
 *
 * This profile is built purely from the discovered GATT table plus the standard
 * Device Information service (0x180A). It never guesses beyond what the radio
 * actually reports, and it's safe on any device: absent services just become
 * `false` capability flags.
 */
data class VehicleProfile(
    /** Advertised BLE name, e.g. "KTM3638" (null if the device never reported one). */
    val advertisedName: String? = null,
    /** DEVICE_INFO_SERVICE 0x2A24 model number string, if the dash exposes it. */
    val modelNumber: String? = null,
    /** DEVICE_INFO_SERVICE 0x2A26 firmware revision string. */
    val firmwareRevision: String? = null,
    /** DEVICE_INFO_SERVICE 0x2A28 software revision string. */
    val softwareRevision: String? = null,
    /** DEVICE_INFO_SERVICE 0x2A27 hardware revision string. */
    val hardwareRevision: String? = null,
    /** DEVICE_INFO_SERVICE 0x2A29 manufacturer name string. */
    val manufacturer: String? = null,

    /** Legacy ETBT nav service (0700) present - the guidance path this app writes. */
    val hasLegacyEtbt: Boolean = false,
    /** Modern protobuf/PRPC service (0600) present - telemetry, and (future) modern nav. */
    val hasModernPrpc: Boolean = false,
    /** Handlebar remote control service (0100). */
    val hasRcm: Boolean = false,
    /** Base service (0000): VIN / firmware info. */
    val hasBase: Boolean = false,
) {

    /** True once the crypto handshake characteristics live where we expect them (in 0700). */
    val supportsAuth: Boolean get() = hasLegacyEtbt

    /** We can only push turn-by-turn guidance over the byte-confirmed legacy path today. */
    val supportsGuidance: Boolean get() = hasLegacyEtbt

    /**
     * A dash we can actually drive. Requires the legacy ETBT service; a bike
     * exposing only 0600 (modern-only firmware) authenticates and reads
     * telemetry but the guidance write path isn't byte-confirmed yet, so we
     * treat it as diagnostic-only rather than pretend nav works.
     */
    val isSupported: Boolean get() = hasLegacyEtbt

    /**
     * Human-facing generation label for the connection banner and logs. Kept
     * deliberately conservative: we only claim "Gen 3" when the model string or
     * the classic Gen-3 service layout backs it up, otherwise we describe what
     * we see rather than inventing a marketing name.
     */
    val generationLabel: String
        get() {
            val model = modelNumber?.trim().orEmpty()
            return when {
                model.contains("BCCU", ignoreCase = true) ||
                    model.contains("Gen 3", ignoreCase = true) ||
                    model.contains("Gen3", ignoreCase = true) -> "Navigator Gen 3 (BCCU)"
                hasLegacyEtbt && hasModernPrpc -> "BCCU dash (Gen 3-class)"
                hasLegacyEtbt -> "BCCU dash (legacy nav)"
                hasModernPrpc -> "BCCU dash (modern/PRPC only)"
                else -> "Unrecognised dash"
            }
        }

    /** One-line capability summary for the diagnostics log. */
    fun describe(): String = buildString {
        append("model=").append(modelNumber ?: "?")
        append(" fw=").append(firmwareRevision ?: "?")
        append(" hw=").append(hardwareRevision ?: "?")
        append(" mfr=").append(manufacturer ?: "?")
        append(" [")
        append(if (hasLegacyEtbt) "ETBT " else "")
        append(if (hasModernPrpc) "PRPC " else "")
        append(if (hasRcm) "RCM " else "")
        append(if (hasBase) "BASE" else "")
        append("] -> ").append(generationLabel)
    }

    companion object {
        // Device Information service characteristic UUIDs (Bluetooth SIG).
        private fun sig(short: String) =
            java.util.UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

        val MANUFACTURER_NAME = sig("2a29")
        val MODEL_NUMBER = sig("2a24")
        val HARDWARE_REVISION = sig("2a27")
        val FIRMWARE_REVISION = sig("2a26")
        val SOFTWARE_REVISION = sig("2a28")

        /**
         * Build the service-level profile from a freshly discovered GATT table.
         * Device-info strings are filled in later as their characteristic reads
         * complete (see [withDeviceInfo]); this captures the transport layout
         * synchronously so the connect path can branch immediately.
         */
        fun fromGatt(gatt: BluetoothGatt, advertisedName: String?): VehicleProfile = VehicleProfile(
            advertisedName = advertisedName,
            hasLegacyEtbt = gatt.getService(BccuProtocol.MAIN_SERVICE) != null,
            hasModernPrpc = gatt.getService(BccuProtocol.PRPC_SERVICE) != null,
            hasRcm = gatt.getService(BccuProtocol.RCM_SERVICE) != null,
            hasBase = gatt.getService(BccuProtocol.BASE_SERVICE) != null,
        )
    }

    /** Return a copy with a device-info characteristic filled in from its decoded string. */
    fun withDeviceInfo(charUuid: java.util.UUID, value: String): VehicleProfile = when (charUuid) {
        MODEL_NUMBER -> copy(modelNumber = value)
        FIRMWARE_REVISION -> copy(firmwareRevision = value)
        SOFTWARE_REVISION -> copy(softwareRevision = value)
        HARDWARE_REVISION -> copy(hardwareRevision = value)
        MANUFACTURER_NAME -> copy(manufacturer = value)
        else -> this
    }
}
