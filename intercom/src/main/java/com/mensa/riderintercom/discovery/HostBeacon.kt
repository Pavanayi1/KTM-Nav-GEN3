package com.mensa.riderintercom.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Tells nearby phones that this rider is hosting.
 *
 * Discovery and the call itself are deliberately different radios. The voice link needs
 * RFCOMM (which needs the phones bonded) or Wi-Fi Direct; neither can say "I am here" to
 * a stranger's phone without the rider driving it. A BLE advertisement can, costs almost
 * nothing, and needs no pairing - so it carries the invitation, and the call is set up
 * afterwards over whichever transport suits.
 *
 * The 31-byte advertisement is tight: a 128-bit service UUID alone takes 18 of it. So the
 * UUID goes in the advertisement and the rider's name in the scan response, which buys a
 * second 31 bytes.
 */
class HostBeacon(private val adapter: BluetoothAdapter) {

    companion object {
        private const val TAG = "HostBeacon"

        /** Our own service, so a scan only ever matches this app. */
        val SERVICE_UUID: UUID = UUID.fromString("8ce255c1-200a-11e0-ac64-0800200c9a66")
        val SERVICE_PARCEL: ParcelUuid = ParcelUuid(SERVICE_UUID)

        /** Rider names are clipped to this so the scan response always fits. */
        const val MAX_NAME_BYTES = 18
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    @Volatile var isAdvertising: Boolean = false
        private set

    @Volatile var lastError: String? = null
        private set

    /** True when this phone's radio can advertise at all; many budget chipsets cannot. */
    fun isSupported(): Boolean =
        runCatching { adapter.isMultipleAdvertisementSupported }.getOrDefault(false) ||
            runCatching { adapter.bluetoothLeAdvertiser != null }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun start(riderName: String, acceptingJoiners: Boolean = true) {
        if (isAdvertising) return
        val adv = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull()
        if (adv == null) {
            lastError = "This phone cannot advertise over Bluetooth LE"
            Log.w(TAG, lastError!!)
            return
        }

        val settings = AdvertiseSettings.Builder()
            // Low latency so a rider pulling up alongside is found in a second or two,
            // not thirty. The host is on a bike with a charger far more often than not.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(SERVICE_PARCEL)
            .build()

        // The name goes in the scan response: the advertisement itself has no room left.
        val nameBytes = riderName.toByteArray(Charsets.UTF_8).let {
            if (it.size > MAX_NAME_BYTES) it.copyOf(MAX_NAME_BYTES) else it
        }
        val payload = ByteArray(1 + nameBytes.size)
        payload[0] = if (acceptingJoiners) 1 else 0
        nameBytes.copyInto(payload, 1)

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(SERVICE_PARCEL, payload)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                isAdvertising = true
                lastError = null
                Log.i(TAG, "advertising as \"$riderName\"")
            }

            override fun onStartFailure(errorCode: Int) {
                isAdvertising = false
                lastError = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertisement payload too large"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers on this phone"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "Already advertising"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "Bluetooth internal error"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "This phone does not support advertising"
                    else -> "Advertising failed (code $errorCode)"
                }
                Log.w(TAG, lastError!!)
            }
        }

        callback = cb
        advertiser = adv
        runCatching { adv.startAdvertising(settings, data, scanResponse, cb) }
            .onFailure {
                lastError = it.message
                Log.w(TAG, "startAdvertising threw: ${it.message}")
            }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val adv = advertiser
        val cb = callback
        if (adv != null && cb != null) runCatching { adv.stopAdvertising(cb) }
        advertiser = null
        callback = null
        isAdvertising = false
    }
}
