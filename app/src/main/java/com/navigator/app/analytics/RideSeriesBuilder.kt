package com.navigator.app.analytics

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns raw GPX track points into clean, chart-ready series: GPS outliers
 * rejected, speed smoothed, acceleration derived (central difference on the
 * smoothed speed), altitude EMA-smoothed, cumulative distance computed.
 * Everything here is pure Kotlin so the numbers are unit-testable.
 */
object RideSeriesBuilder {

    /** Chart-ready model for one ride. Arrays are index-aligned with [points]. */
    data class RideSeries(
        val points: List<GpxParser.TrackPoint>,
        val timesMs: LongArray,
        val speedKmh: FloatArray,
        /** m/s², + = accelerating, - = braking. */
        val accelMs2: FloatArray,
        /** Smoothed altitude, null when the GPX had no <ele>. */
        val altitudeM: FloatArray?,
        val cumDistM: DoubleArray,
        val distanceKm: Double,
        val durationMs: Long,
        val movingMs: Long,
        val maxKmh: Float,
        val avgMovingKmh: Float,
        val elevGainM: Double?,
    )

    private const val STOP_KMH = 3f

    fun build(raw: List<GpxParser.TrackPoint>): RideSeries? {
        if (raw.size < 2) return null
        val pts = rejectOutliers(raw.sortedBy { it.timeMs })
        if (pts.size < 2) return null

        val n = pts.size
        val times = LongArray(n) { pts[it].timeMs }

        // Raw speed: od:speed when recorded, else from geometry.
        val vRaw = FloatArray(n)
        for (i in 0 until n) {
            val rec = pts[i].speedKmh
            vRaw[i] = if (rec != null && rec >= 0f) rec else {
                val j = if (i == 0) 1 else i
                val dtS = (times[j] - times[j - 1]) / 1000.0
                if (dtS < 0.5) 0f
                else (haversineM(pts[j - 1].lat, pts[j - 1].lon, pts[j].lat, pts[j].lon) / dtS * 3.6)
                    .toFloat().coerceAtMost(220f)
            }
        }
        // Hampel-style spike repair, then a 5-sample moving average.
        hampel(vRaw)
        val v = movingAverage(vRaw, 5)

        // Acceleration: central difference on the smoothed speed (m/s per s),
        // then a light 3-sample average so single-sample jitter dies.
        val aRaw = FloatArray(n)
        for (i in 0 until n) {
            val lo = maxOf(0, i - 1)
            val hi = minOf(n - 1, i + 1)
            val dtS = (times[hi] - times[lo]) / 1000f
            aRaw[i] = if (dtS < 0.5f) 0f else ((v[hi] - v[lo]) / 3.6f) / dtS
        }
        val a = movingAverage(aRaw, 3)

        // Altitude: EMA (GPS ele is ±10 m noisy). Present only if most points carry it.
        val eleCount = pts.count { it.eleM != null }
        val alt: FloatArray? = if (eleCount < n / 2) null else {
            val out = FloatArray(n)
            var ema = pts.firstNotNullOf { it.eleM }.toFloat()
            for (i in 0 until n) {
                pts[i].eleM?.let { ema = 0.15f * it.toFloat() + 0.85f * ema }
                out[i] = ema
            }
            out
        }

        val cum = DoubleArray(n)
        var movingMs = 0L
        for (i in 1 until n) {
            cum[i] = cum[i - 1] + haversineM(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
            val dt = times[i] - times[i - 1]
            if (v[i] >= STOP_KMH && dt in 1..60_000) movingMs += dt
        }
        val elevGain: Double? = alt?.let { al ->
            var gain = 0.0
            for (i in 1 until n) if (al[i] > al[i - 1]) gain += (al[i] - al[i - 1]).toDouble()
            gain
        }
        val distKm = cum.last() / 1000.0
        return RideSeries(
            points = pts,
            timesMs = times,
            speedKmh = v,
            accelMs2 = a,
            altitudeM = alt,
            cumDistM = cum,
            distanceKm = distKm,
            durationMs = times.last() - times.first(),
            movingMs = movingMs,
            maxKmh = v.max(),
            avgMovingKmh = if (movingMs > 0) (distKm / (movingMs / 3_600_000.0)).toFloat() else 0f,
            elevGainM = elevGain,
        )
    }

    /**
     * Drop GPS teleports: a jump implying >290 km/h, or a >300 m leap inside
     * 5 s. Walks the track keeping a "last good" anchor so one bad fix doesn't
     * cascade into rejecting everything after it.
     */
    private fun rejectOutliers(pts: List<GpxParser.TrackPoint>): List<GpxParser.TrackPoint> {
        val out = ArrayList<GpxParser.TrackPoint>(pts.size)
        var last: GpxParser.TrackPoint? = null
        for (p in pts) {
            val l = last
            if (l == null) { out.add(p); last = p; continue }
            val dtS = (p.timeMs - l.timeMs) / 1000.0
            if (dtS <= 0.0) continue
            val d = haversineM(l.lat, l.lon, p.lat, p.lon)
            val implied = d / dtS * 3.6
            if (implied > 290.0 || (d > 300.0 && dtS <= 5.0)) continue
            out.add(p); last = p
        }
        return out
    }

    /** Replace values deviating >3×MAD from the 7-point running median. */
    private fun hampel(v: FloatArray) {
        if (v.size < 7) return
        val window = FloatArray(7)
        for (i in 3 until v.size - 3) {
            for (k in 0 until 7) window[k] = v[i - 3 + k]
            window.sort()
            val med = window[3]
            var madAcc = 0f
            for (k in 0 until 7) madAcc += abs(window[k] - med)
            val mad = madAcc / 7f
            if (abs(v[i] - med) > 3f * (mad * 1.4826f) + 2f) v[i] = med
        }
    }

    fun movingAverage(v: FloatArray, window: Int): FloatArray {
        if (v.size < window) return v.copyOf()
        val half = window / 2
        val out = FloatArray(v.size)
        for (i in v.indices) {
            var sum = 0f; var count = 0
            for (k in maxOf(0, i - half)..minOf(v.size - 1, i + half)) { sum += v[k]; count++ }
            out[i] = sum / count
        }
        return out
    }

    /**
     * Douglas-Peucker simplification returning the KEPT indices into the
     * original point list (so scrub time -> position mapping survives).
     */
    fun simplifyIndices(pts: List<GpxParser.TrackPoint>, epsilonM: Double = 4.0): IntArray {
        if (pts.size <= 2) return IntArray(pts.size) { it }
        val keep = BooleanArray(pts.size)
        keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to pts.size - 1)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            if (e - s < 2) continue
            var maxD = 0.0; var maxI = -1
            for (i in s + 1 until e) {
                val d = perpDistM(pts[i], pts[s], pts[e])
                if (d > maxD) { maxD = d; maxI = i }
            }
            if (maxD > epsilonM && maxI > 0) {
                keep[maxI] = true
                stack.addLast(s to maxI)
                stack.addLast(maxI to e)
            }
        }
        return keep.indices.filter { keep[it] }.toIntArray()
    }

    private fun perpDistM(p: GpxParser.TrackPoint, a: GpxParser.TrackPoint, b: GpxParser.TrackPoint): Double {
        // Local equirectangular projection - fine at ride scale.
        val kx = 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
        val ky = 110_540.0
        val ax = a.lon * kx; val ay = a.lat * ky
        val bx = b.lon * kx; val by = b.lat * ky
        val px = p.lon * kx; val py = p.lat * ky
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
