package com.mensa.riderintercom.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A rider who is hosting nearby, as seen over BLE. */
data class NearbyHost(
    val address: String,
    val riderName: String,
    val rssi: Int,
    val acceptingJoiners: Boolean,
    val lastSeenAtMs: Long,
    /** Set when this phone has already bonded with them, so RFCOMM can be used at once. */
    val bonded: Boolean,
) {
    /** Rough distance band, which is more honest than converting RSSI to metres. */
    val proximity: String get() = when {
        rssi > -60 -> "very close"
        rssi > -75 -> "nearby"
        else -> "in range"
    }
}

/**
 * Watches for riders who are hosting, so joining is a tap on a name rather than a hunt
 * through the paired-devices list.
 *
 * Entries are dropped after [STALE_AFTER_MS] without a sighting, so a rider who has
 * ridden off disappears from the list instead of lingering and failing to connect.
 */
class HostScanner(private val adapter: BluetoothAdapter) {

    companion object {
        private const val TAG = "HostScanner"
        /** A host unseen for this long has gone. BLE adverts come every second or so. */
        const val STALE_AFTER_MS = 12_000L
        private const val SWEEP_MS = 3_000L
    }

    private val _hosts = MutableStateFlow<List<NearbyHost>>(emptyList())
    val hosts: StateFlow<List<NearbyHost>> = _hosts.asStateFlow()

    @Volatile var isScanning: Boolean = false
        private set

    @Volatile var lastError: String? = null
        private set

    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private val seen = mutableMapOf<String, NearbyHost>()
    private val handler = Handler(Looper.getMainLooper())
    private var sweeper: Runnable? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (isScanning) return
        val s = runCatching { adapter.bluetoothLeScanner }.getOrNull()
        if (s == null) {
            lastError = "Bluetooth LE scanning is not available"
            return
        }

        // Filtering on our own service UUID in the scan filter means the radio wakes this
        // app only for adverts that can possibly matter.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(HostBeacon.SERVICE_PARCEL).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result ?: return
                record(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { record(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning = false
                lastError = "Scan failed (code $errorCode)"
                Log.w(TAG, lastError!!)
            }
        }

        callback = cb
        scanner = s
        runCatching { s.startScan(filters, settings, cb) }
            .onSuccess {
                isScanning = true
                lastError = null
                startSweeper()
            }
            .onFailure {
                lastError = it.message
                Log.w(TAG, "startScan threw: ${it.message}")
            }
    }

    @SuppressLint("MissingPermission")
    private fun record(result: ScanResult) {
        val device: BluetoothDevice = result.device ?: return
        val data = result.scanRecord?.getServiceData(HostBeacon.SERVICE_PARCEL)

        val accepting = data?.firstOrNull()?.toInt() == 1
        val name = if (data != null && data.size > 1) {
            String(data, 1, data.size - 1, Charsets.UTF_8)
        } else {
            runCatching { device.name }.getOrNull() ?: device.address
        }

        val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }
            .getOrDefault(false)

        synchronized(seen) {
            seen[device.address] = NearbyHost(
                address = device.address,
                riderName = name.ifBlank { device.address },
                rssi = result.rssi,
                acceptingJoiners = accepting,
                lastSeenAtMs = System.currentTimeMillis(),
                bonded = bonded,
            )
        }
        publish()
    }

    private fun startSweeper() {
        sweeper?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                var changed = false
                synchronized(seen) {
                    val it = seen.entries.iterator()
                    while (it.hasNext()) {
                        if (now - it.next().value.lastSeenAtMs > STALE_AFTER_MS) {
                            it.remove()
                            changed = true
                        }
                    }
                }
                if (changed) publish()
                if (isScanning) handler.postDelayed(this, SWEEP_MS)
            }
        }
        sweeper = r
        handler.postDelayed(r, SWEEP_MS)
    }

    private fun publish() {
        val list = synchronized(seen) { seen.values.toList() }
        // Strongest signal first: the rider you are next to should be top of the list.
        _hosts.value = list.sortedByDescending { it.rssi }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val s = scanner
        val cb = callback
        if (s != null && cb != null) runCatching { s.stopScan(cb) }
        sweeper?.let { handler.removeCallbacks(it) }
        sweeper = null
        scanner = null
        callback = null
        isScanning = false
        synchronized(seen) { seen.clear() }
        publish()
    }
}
