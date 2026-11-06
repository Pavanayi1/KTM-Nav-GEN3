package com.navigator.app.analytics

import java.io.File

/**
 * Reader for the IMU sidecar recorded next to each GPX
 * (route_<stamp>.imu.csv). Format, one row per 100 ms window:
 *   tMs,ax,ay,az,gx,gy,gz,vertVar
 * where ax..gz are window means (m/s², rad/s, raw device frame) and vertVar
 * is the in-window variance of the accel magnitude - the rough-road signal,
 * pre-computed at source so files stay small. Header line starts with '#'.
 */
object ImuLog {

    const val HEADER = "# odimu v1: tMs,ax,ay,az,gx,gy,gz,vertVar"

    class Data(
        val timesMs: LongArray,
        val accelMag: FloatArray,
        val gyroMag: FloatArray,
        val vertVar: FloatArray,
    )

    fun sidecarFor(gpx: File): File = File(gpx.parentFile, gpx.nameWithoutExtension + ".imu.csv")

    fun read(gpx: File): Data? {
        val f = sidecarFor(gpx)
        if (!f.exists() || f.length() < 32) return null
        val times = ArrayList<Long>(8192)
        val amag = ArrayList<Float>(8192)
        val gmag = ArrayList<Float>(8192)
        val vvar = ArrayList<Float>(8192)
        runCatching {
            f.forEachLine { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachLine
                val p = line.split(',')
                if (p.size < 8) return@forEachLine
                val t = p[0].toLongOrNull() ?: return@forEachLine
                val ax = p[1].toFloatOrNull() ?: return@forEachLine
                val ay = p[2].toFloatOrNull() ?: return@forEachLine
                val az = p[3].toFloatOrNull() ?: return@forEachLine
                val gx = p[4].toFloatOrNull() ?: return@forEachLine
                val gy = p[5].toFloatOrNull() ?: return@forEachLine
                val gz = p[6].toFloatOrNull() ?: return@forEachLine
                val vv = p[7].toFloatOrNull() ?: return@forEachLine
                times.add(t)
                amag.add(kotlin.math.sqrt(ax * ax + ay * ay + az * az))
                gmag.add(kotlin.math.sqrt(gx * gx + gy * gy + gz * gz))
                vvar.add(vv)
            }
        }.onFailure { return null }
        if (times.size < 10) return null
        return Data(
            timesMs = times.toLongArray(),
            accelMag = amag.toFloatArray(),
            gyroMag = gmag.toFloatArray(),
            vertVar = vvar.toFloatArray(),
        )
    }
}
