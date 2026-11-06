package com.navigator.app.analytics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Riding-behaviour events from the cleaned series (plus the IMU sidecar when
 * one was recorded): harsh acceleration, hard braking, rough-road spans, and
 * the 0-100 smoothness score that drives the fuel model.
 *
 * Thresholds are typical motorcycle-telematics values: bikes pull 4-6 m/s²
 * flat out, so sustained >+3.0 is "aggressive"; panic braking reaches
 * -6..-9 m/s², so sustained <-3.5 is "hard".
 */
object RideEventDetector {

    enum class EventType { HARSH_ACCEL, HARD_BRAKE }

    data class RideEvent(val type: EventType, val timeMs: Long, val pointIndex: Int, val peakMs2: Float)

    /** A stretch of rough surface (IMU vertical variance), as a time span. */
    data class RoughSpan(val startMs: Long, val endMs: Long)

    data class Result(
        val events: List<RideEvent>,
        val roughSpans: List<RoughSpan>,
        /** 0 = chaos, 100 = butter. */
        val smoothnessScore: Int,
        /** True when accel came from GPS speed only (no IMU sidecar). */
        val gpsOnly: Boolean,
    )

    private const val HARSH_ACCEL_MS2 = 3.0f
    private const val HARD_BRAKE_MS2 = -3.5f
    private const val SUSTAIN_ACCEL_MS = 600L
    private const val SUSTAIN_BRAKE_MS = 800L
    private const val MERGE_MS = 3000L
    private const val ROUGH_RMS_MS2 = 2.5f
    private const val ROUGH_SUSTAIN_MS = 2000L
    private const val ROUGH_MIN_KMH = 15f

    fun detect(series: RideSeriesBuilder.RideSeries, imu: ImuLog.Data?): Result {
        val events = detectAccelEvents(series)
        val rough = imu?.let { detectRoughSpans(series, it) } ?: emptyList()
        val score = smoothnessScore(series, events)
        return Result(events, rough, score, gpsOnly = imu == null)
    }

    private fun detectAccelEvents(s: RideSeriesBuilder.RideSeries): List<RideEvent> {
        val out = ArrayList<RideEvent>()
        var runStart = -1
        var runPeak = 0f
        var runType: EventType? = null
        fun close(endIdx: Int) {
            val type = runType ?: return
            val startIdx = runStart
            runType = null; runStart = -1
            val sustained = s.timesMs[endIdx] - s.timesMs[startIdx]
            val needed = if (type == EventType.HARSH_ACCEL) SUSTAIN_ACCEL_MS else SUSTAIN_BRAKE_MS
            if (sustained < needed) return
            // Merge with the previous event of the same type when close in time.
            val prev = out.lastOrNull()
            if (prev != null && prev.type == type && s.timesMs[startIdx] - prev.timeMs < MERGE_MS) {
                if (abs(runPeak) > abs(prev.peakMs2)) {
                    out[out.size - 1] = prev.copy(peakMs2 = runPeak)
                }
                return
            }
            out.add(RideEvent(type, s.timesMs[startIdx], startIdx, runPeak))
        }
        for (i in s.accelMs2.indices) {
            val a = s.accelMs2[i]
            val type = when {
                a > HARSH_ACCEL_MS2 -> EventType.HARSH_ACCEL
                a < HARD_BRAKE_MS2 -> EventType.HARD_BRAKE
                else -> null
            }
            if (type == runType && type != null) {
                if (abs(a) > abs(runPeak)) runPeak = a
            } else {
                if (runType != null) close(i - 1)
                if (type != null) { runType = type; runStart = i; runPeak = a }
            }
        }
        if (runType != null) close(s.accelMs2.size - 1)
        return out
    }

    /**
     * Rough road from the sidecar's pre-aggregated vertical variance: RMS above
     * [ROUGH_RMS_MS2] for >= [ROUGH_SUSTAIN_MS] while actually moving.
     */
    private fun detectRoughSpans(s: RideSeriesBuilder.RideSeries, imu: ImuLog.Data): List<RoughSpan> {
        val out = ArrayList<RoughSpan>()
        var start = -1L
        var lastT = -1L
        for (i in imu.timesMs.indices) {
            val t = imu.timesMs[i]
            val rms = sqrt(imu.vertVar[i])
            // Speed lookup at this timestamp (binary search on the GPS series).
            val gpsIdx = nearestIndex(s.timesMs, t)
            val moving = s.speedKmh[gpsIdx] > ROUGH_MIN_KMH
            val roughNow = rms > ROUGH_RMS_MS2 && moving
            if (roughNow && start < 0) start = t
            if (!roughNow && start >= 0) {
                if (lastT - start >= ROUGH_SUSTAIN_MS) out.add(RoughSpan(start, lastT))
                start = -1
            }
            lastT = t
        }
        if (start >= 0 && lastT - start >= ROUGH_SUSTAIN_MS) out.add(RoughSpan(start, lastT))
        return out
    }

    /**
     * score = 100 - 18*eventsPerKm - 25*(jerkPenalty/1.5), clamped 0..100.
     * Jerk (|da/dt| mean) punishes constant on-off throttle that never quite
     * crosses the event thresholds.
     */
    private fun smoothnessScore(s: RideSeriesBuilder.RideSeries, events: List<RideEvent>): Int {
        if (s.distanceKm < 0.3) return 100
        val eventsPerKm = events.size / s.distanceKm
        var jerkAcc = 0.0; var jerkN = 0
        for (i in 1 until s.accelMs2.size) {
            val dtS = (s.timesMs[i] - s.timesMs[i - 1]) / 1000.0
            if (dtS in 0.2..30.0) {
                jerkAcc += abs(s.accelMs2[i] - s.accelMs2[i - 1]) / dtS
                jerkN++
            }
        }
        val jerkPenalty = (if (jerkN > 0) jerkAcc / jerkN else 0.0).coerceIn(0.0, 1.5)
        val score = 100.0 - 18.0 * eventsPerKm - 25.0 * (jerkPenalty / 1.5)
        return score.coerceIn(0.0, 100.0).toInt()
    }

    enum class TrafficKind { SIGNAL, CRAWL }

    /** [range] indexes into the times/speeds arrays passed to [trafficRanges]. */
    data class TrafficRange(val range: IntRange, val kind: TrafficKind)

    private const val CRAWL_MAX_KMH = 15f
    private const val TRAFFIC_STOP_KMH = 3f
    private const val TRAFFIC_MIN_MS = 15_000L
    /** Beyond this a stop reads as a genuine break/parking, not a jam - unless the engine confirms otherwise. */
    private const val SIGNAL_MAX_UNKNOWN_MS = 3 * 60_000L

    /**
     * Traffic detection combining speed with engine state (from VibrationMonitor,
     * persisted per-point as GPX's od:engine):
     *  - Slow but still moving, engine running -> CRAWL (creeping in a jam).
     *  - Essentially stopped, engine running -> SIGNAL (waiting - a light, a jam
     *    that's fully halted). No time cap: the engine confirms it's not parked.
     *  - Essentially stopped, engine state unknown (older recordings/no
     *    calibration) -> SIGNAL only for short-to-medium halts (<=3 min) - a
     *    "possible traffic signal" heuristic; longer unknown stops read as a
     *    real break, same reasoning the ride-list stop classifier already uses.
     *  - Any stretch where the engine is confirmed OFF is excluded entirely -
     *    that is an intentional stop, never traffic.
     */
    fun trafficRanges(
        times: LongArray,
        speedsKmh: FloatArray,
        engineOn: Array<Boolean?>? = null,
    ): List<TrafficRange> {
        val out = ArrayList<TrafficRange>()
        var start = -1
        fun close(endExclusive: Int) {
            val s = start; start = -1
            if (s < 0) return
            val e = endExclusive - 1
            if (times[e] - times[s] < TRAFFIC_MIN_MS) return
            var speedSum = 0f
            for (i in s..e) speedSum += speedsKmh[i]
            val avgSpeed = speedSum / (e - s + 1)
            val engineSamples = engineOn?.let { arr -> (s..e).mapNotNull { arr[it] } } ?: emptyList()
            val engineOnMajority = engineSamples.isNotEmpty() && engineSamples.count { it } * 2 >= engineSamples.size
            val engineOffMajority = engineSamples.isNotEmpty() && !engineOnMajority
            if (engineOffMajority) return // confirmed intentional stop
            val stopped = avgSpeed < TRAFFIC_STOP_KMH
            val durationMs = times[e] - times[s]
            when {
                !stopped -> out.add(TrafficRange(s..e, TrafficKind.CRAWL))
                engineOnMajority -> out.add(TrafficRange(s..e, TrafficKind.SIGNAL))
                durationMs <= SIGNAL_MAX_UNKNOWN_MS -> out.add(TrafficRange(s..e, TrafficKind.SIGNAL))
                // else: stopped, engine unknown, long halt -> treated as a break, excluded.
            }
        }
        for (i in speedsKmh.indices) {
            if (speedsKmh[i] < CRAWL_MAX_KMH) {
                if (start < 0) start = i
            } else if (start >= 0) close(i)
        }
        if (start >= 0) close(speedsKmh.size)
        return out
    }

    fun nearestIndex(times: LongArray, t: Long): Int {
        var lo = 0; var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (times[mid] < t) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && t - times[lo - 1] < times[lo] - t) lo - 1 else lo
    }
}
