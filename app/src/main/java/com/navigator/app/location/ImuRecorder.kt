package com.navigator.app.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.navigator.app.analytics.ImuLog
import com.navigator.app.logging.AppLogger
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Accelerometer + gyroscope sidecar recorder, owned by RouteRecorder: starts
 * and stops with each GPX so every ride gets a matching .imu.csv. Sensors run
 * at SENSOR_DELAY_GAME (~50 Hz) but rows are pre-aggregated 100 ms windows
 * (means + in-window accel-magnitude variance), so an hour of riding is
 * ~2 MB. Cost is negligible next to the 1 Hz GPS already running.
 */
class ImuRecorder(private val context: Context) : SensorEventListener {

    private var writer: BufferedWriter? = null
    private var manager: SensorManager? = null

    // Current 100 ms aggregation window (accessed only on the sensor thread).
    private var windowStartMs = 0L
    private var n = 0
    private var ax = 0f; private var ay = 0f; private var az = 0f
    private var gx = 0f; private var gy = 0f; private var gz = 0f
    private var magSum = 0f; private var magSqSum = 0f
    private var lastFlushMs = 0L

    fun start(gpxFile: File) {
        stop()
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val file = ImuLog.sidecarFor(gpxFile)
        writer = runCatching {
            BufferedWriter(FileWriter(file, true)).also { it.write(ImuLog.HEADER); it.newLine() }
        }.getOrNull() ?: return
        manager = sm
        sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        // Gyro is optional - some budget phones don't have one; accel alone
        // still powers rough-road detection (gyro columns stay 0).
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        AppLogger.log("Route", "IMU sidecar recording -> ${file.name}")
    }

    fun stop() {
        manager?.unregisterListener(this)
        manager = null
        runCatching { writer?.flush(); writer?.close() }
        writer = null
        n = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (windowStartMs == 0L) windowStartMs = now
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                ax += event.values[0]; ay += event.values[1]; az += event.values[2]
                val mag = kotlin.math.sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                magSum += mag; magSqSum += mag * mag
                n++
            }
            Sensor.TYPE_GYROSCOPE -> {
                gx += event.values[0]; gy += event.values[1]; gz += event.values[2]
            }
        }
        if (now - windowStartMs >= 100 && n > 0) {
            val inv = 1f / n
            val mean = magSum * inv
            val vertVar = (magSqSum * inv - mean * mean).coerceAtLeast(0f)
            val row = "%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.4f".format(
                java.util.Locale.US, windowStartMs,
                ax * inv, ay * inv, az * inv, gx * inv, gy * inv, gz * inv, vertVar,
            )
            runCatching {
                writer?.write(row); writer?.newLine()
                if (now - lastFlushMs > 5000) { writer?.flush(); lastFlushMs = now }
            }
            windowStartMs = now
            n = 0; ax = 0f; ay = 0f; az = 0f; gx = 0f; gy = 0f; gz = 0f
            magSum = 0f; magSqSum = 0f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
