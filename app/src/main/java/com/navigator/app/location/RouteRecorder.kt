package com.navigator.app.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.navigator.app.logging.AppLogger
import com.navigator.app.settings.AppSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Auto GPS route recorder. With [AppSettings.powerSaveEnabled] (default) it is
 * gated on CHARGING - the usual state on the bike, phone in a powered mount -
 * at a 1 s / high-precision cadence, and can never drain a pocketed phone.
 * With power-save off it also records on battery, at a relaxed 5 s cadence.
 * Every fix is appended to a GPX track under files/routes/route_<start>.gpx
 * with per-point extensions the ride viewer post-processes:
 *   <od:speed> km/h from the GPS fix, <od:engine> on/off from VibrationMonitor
 * (that pair is what tells "stuck in traffic" apart from "parked for chai").
 * Files are exported through the existing FileProvider via a share intent.
 */
class RouteRecorder(private val context: Context) : LocationListener {

    companion object {
        private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        /** Charging / power-save-off cadence: precise speed graphs need dense points. */
        private const val FAST_INTERVAL_MS = 1000L
        private const val FAST_MIN_DISTANCE_M = 2f
        /** On-battery cadence (power-save off only reaches here when not charging). */
        private const val SLOW_INTERVAL_MS = 5000L
        private const val SLOW_MIN_DISTANCE_M = 10f

        fun routesDir(context: Context): File = File(context.filesDir, "routes").apply { mkdirs() }

        /** A ride being recorded right now (null = none), for the Rides screen's Record button. */
        data class Active(val startedMs: Long, val manual: Boolean, val fileName: String)

        private val _active = kotlinx.coroutines.flow.MutableStateFlow<Active?>(null)
        val active: kotlinx.coroutines.flow.StateFlow<Active?> = _active

        fun listRoutes(context: Context): List<File> =
            routesDir(context).listFiles { f -> f.extension == "gpx" }?.sortedByDescending { it.name } ?: emptyList()
    }

    private val settings = AppSettings(context)
    private val imuRecorder = ImuRecorder(context)
    private var powerReceiver: BroadcastReceiver? = null
    private var recording = false
    /** Rider tapped Record: record regardless of auto-record, power or bike link, until they tap Stop. */
    private var manual = false
    /**
     * Rider tapped Stop on an auto-recorded ride: don't let auto-record reopen
     * a track straight away. Cleared when the bike disconnects, so the next
     * ride records normally.
     */
    private var suppressAuto = false

    /** True while a GPX is open - the idle-shutdown watchdog must not kill a live ride. */
    val isRecording: Boolean get() = recording
    private var charging = false
    private var currentFile: File? = null
    /** Live engine state pushed by the service from VibrationMonitor; null = unknown/uncalibrated. */
    @Volatile private var engineOn: Boolean? = null
    private val gpxTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Engine state changed (from VibrationMonitor via the service): tagged onto every following point. */
    fun onEngineState(on: Boolean?) {
        engineOn = on
    }

    fun start() {
        // Snapshot the candidates BEFORE any recording can begin, then repair
        // off the main thread (service onCreate is the bike-reconnect critical
        // path, and rides accumulate megabytes of GPX over time).
        val candidates = listRoutes(context)
        Thread({ repairUnfinishedTracks(candidates) }, "gpx-repair").start()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = reevaluate()
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(r, f)
        powerReceiver = r
        reevaluate()
    }

    fun stop() {
        powerReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        powerReceiver = null
        manual = false
        stopRecording()
    }

    /** Record button. False when location permission is missing (nothing to record with). */
    fun startManual(): Boolean {
        if (!hasPermission()) return false
        manual = true
        suppressAuto = false
        if (!recording) startRecording()
        else _active.value = _active.value?.copy(manual = true)
        AppLogger.log("Route", "Manual recording requested")
        return true
    }

    /** Stop button: ends the current ride whether it was started by hand or by auto-record. */
    fun stopManual() {
        manual = false
        if (settings.routeAutoRecordEnabled && isBikeConnected()) suppressAuto = true
        AppLogger.log("Route", "Recording stopped by the rider")
        stopRecording()
    }

    /**
     * A recording that ended in process death (system kill, battery pull) never
     * got its closing tags, leaving invalid XML that GPX importers reject - and
     * export would happily share it. The format is append-only, so repair is a
     * straight footer append on the next service start.
     */
    private fun repairUnfinishedTracks(files: List<File>) {
        for (f in files) {
            runCatching {
                if (f === currentFile || f.name == currentFile?.name) return@runCatching
                // Only the last few bytes matter - don't read whole multi-hour tracks.
                val tail = java.io.RandomAccessFile(f, "r").use { raf ->
                    val n = minOf(raf.length(), 64L).toInt()
                    raf.seek(raf.length() - n)
                    val buf = ByteArray(n)
                    raf.readFully(buf)
                    String(buf)
                }
                if (!tail.trimEnd().endsWith("</gpx>")) {
                    f.appendText("</trkseg></trk>\n</gpx>\n")
                    AppLogger.log("Route", "Repaired unterminated track ${f.name}")
                }
            }
        }
    }

    /**
     * Recording is on iff the setting is enabled, we have location permission,
     * the phone is charging (or the rider opted out of power-save), AND the
     * bike is actually connected. That last check matters: without it, a
     * charging phone recorded GPS on any drive/walk/car trip, polluting the
     * ride list and the traffic heatmap with non-bike data.
     */
    fun reevaluate() {
        val powerOk = isCharging() || !settings.powerSaveEnabled
        val connected = isBikeConnected()
        if (!connected) suppressAuto = false
        val autoOk = !suppressAuto && settings.routeAutoRecordEnabled && powerOk && connected
        val shouldRecord = hasPermission() && (manual || autoOk)
        if (shouldRecord && !recording) startRecording()
        else if (!shouldRecord && recording) stopRecording()
        else if (recording && charging != isCharging()) {
            // Still recording but the power state flipped: re-request updates at
            // the cadence that matches it (dense on power, relaxed on battery).
            requestUpdates()
        }
    }

    private fun isBikeConnected(): Boolean =
        com.navigator.app.ble.BccuConnectionService.connectionState.value ==
            com.navigator.app.ble.BccuConnectionService.ConnectionState.AUTHENTICATED

    private fun isCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        val file = File(routesDir(context), "route_${FILE_STAMP.format(Date())}.gpx")
        try {
            file.writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Navigator Gen3" xmlns="http://www.topografix.com/GPX/1/1" xmlns:od="urn:navigator:gpx">
<trk><name>${file.nameWithoutExtension}</name><trkseg>
"""
            )
            currentFile = file
            recording = true
            _active.value = Active(System.currentTimeMillis(), manual, file.name)
            requestUpdates()
            // Accel/gyro sidecar for ride analytics (rough-road detection etc.);
            // rides on phones without the sensors just won't have one.
            imuRecorder.start(file)
            AppLogger.log("Route", "Recording started -> ${file.name} (${if (isCharging()) "charging, 1s" else "battery, 5s"})")
        } catch (e: Exception) {
            AppLogger.log("Route", "!! Failed to start route recording: $e")
        }
    }

    /** (Re)subscribe GPS at the cadence matching the current power state. */
    private fun requestUpdates() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        charging = isCharging()
        runCatching { lm.removeUpdates(this) }
        val (interval, minDist) =
            if (charging) FAST_INTERVAL_MS to FAST_MIN_DISTANCE_M
            else SLOW_INTERVAL_MS to SLOW_MIN_DISTANCE_M
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, minDist, this)
        } catch (e: Exception) {
            AppLogger.log("Route", "!! requestLocationUpdates failed: $e")
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        _active.value = null
        imuRecorder.stop()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { lm.removeUpdates(this) }
        currentFile?.let { f ->
            runCatching { f.appendText("</trkseg></trk>\n</gpx>\n") }
            AppLogger.log("Route", "Recording stopped -> ${f.name}")
        }
        currentFile = null
    }

    override fun onLocationChanged(location: Location) {
        val f = currentFile ?: return
        runCatching {
            // od:speed straight from the GPS fix (km/h) so the ride viewer gets
            // precise per-point speed without re-deriving it; od:engine is the
            // vibration monitor's live verdict (omitted while unknown). Plain
            // GPX readers ignore the extensions block entirely.
            val ext = buildString {
                if (location.hasSpeed() || engineOn != null) {
                    append("<extensions>")
                    if (location.hasSpeed()) append("<od:speed>%.1f</od:speed>".format(location.speed * 3.6f))
                    engineOn?.let { append("<od:engine>${if (it) "on" else "off"}</od:engine>") }
                    append("</extensions>")
                }
            }
            f.appendText(
                "<trkpt lat=\"${location.latitude}\" lon=\"${location.longitude}\">" +
                    "<ele>${location.altitude}</ele><time>${gpxTime.format(Date(location.time))}</time>$ext</trkpt>\n"
            )
        }
    }

    @Deprecated("Deprecated in API 29 but still called on older devices")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
