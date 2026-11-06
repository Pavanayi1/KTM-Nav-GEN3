package com.navigator.app.analytics

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.floor

/**
 * A lightweight, honest "traffic today" predictor: not a neural net (there
 * isn't remotely enough on-device ride data for one to generalize), but a
 * frequency table over (rough location cell) x (weekday/weekend, 4-hour
 * window) built from every ride ever recorded. For a spot/time combination
 * seen enough times before, it reports "you hit traffic here N% of the
 * times you rode through at this time" - genuinely learned from the
 * rider's own history, with an explicit sample count so a lucky/unlucky
 * one-off ride can't masquerade as a strong signal.
 */
object TrafficPredictor {

    const val CELL_DEG = 0.0018 // ~200 m - city-block-ish granularity
    /** A cell+timeslot needs at least this many recorded passes before it predicts anything. */
    const val MIN_SAMPLES = 4

    data class CellKey(val latIdx: Int, val lonIdx: Int)

    private class Stat {
        var trafficCount = 0
        var totalCount = 0
    }

    /** One ride's raw geometry + already-detected traffic stretches, ready to feed the model. */
    class RideSample(
        val times: LongArray,
        val lats: DoubleArray,
        val lons: DoubleArray,
        val trafficRanges: List<RideEventDetector.TrafficRange>,
    )

    data class Prediction(val probability: Float, val samples: Int)

    /** A cell qualifying for the requested time, with its center for rendering. */
    data class CellPrediction(val lat: Double, val lon: Double, val probability: Float, val samples: Int)

    class Model internal constructor(private val stats: Map<Pair<CellKey, Int>, StatSnapshot>) {

        fun predict(lat: Double, lon: Double, whenMs: Long): Prediction? {
            val s = stats[cellKeyOf(lat, lon) to bucketOf(whenMs)] ?: return null
            if (s.totalCount < MIN_SAMPLES) return null
            return Prediction(s.trafficCount.toFloat() / s.totalCount, s.totalCount)
        }

        /** Every cell with enough history for the given moment, for painting a "predicted now" layer. */
        fun cellsForTime(whenMs: Long): List<CellPrediction> {
            val bucket = bucketOf(whenMs)
            return stats.entries
                .filter { it.key.second == bucket && it.value.totalCount >= MIN_SAMPLES }
                .map { (k, s) ->
                    val (lat, lon) = cellCenter(k.first)
                    CellPrediction(lat, lon, s.trafficCount.toFloat() / s.totalCount, s.totalCount)
                }
        }

        val cellCount: Int get() = stats.keys.map { it.first }.distinct().size
        val ruleCount: Int get() = stats.size
    }

    internal data class StatSnapshot(val trafficCount: Int, val totalCount: Int)

    fun build(rides: List<RideSample>): Model {
        val acc = HashMap<Pair<CellKey, Int>, Stat>()
        for (ride in rides) {
            val inTraffic = BooleanArray(ride.times.size)
            for (tr in ride.trafficRanges) {
                for (i in tr.range) if (i in inTraffic.indices) inTraffic[i] = true
            }
            for (i in ride.times.indices) {
                val key = cellKeyOf(ride.lats[i], ride.lons[i]) to bucketOf(ride.times[i])
                val stat = acc.getOrPut(key) { Stat() }
                stat.totalCount++
                if (inTraffic[i]) stat.trafficCount++
            }
        }
        return Model(acc.mapValues { StatSnapshot(it.value.trafficCount, it.value.totalCount) })
    }

    /** Weekday/weekend x 6 four-hour windows = 12 buckets - coarse enough to accumulate samples fast. */
    fun bucketOf(whenMs: Long): Int {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = whenMs
        val weekend = cal.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
        val hourWindow = cal.get(Calendar.HOUR_OF_DAY) / 4
        return (if (weekend) 1 else 0) * 6 + hourWindow
    }

    fun cellKeyOf(lat: Double, lon: Double): CellKey =
        CellKey(floor(lat / CELL_DEG).toInt(), floor(lon / CELL_DEG).toInt())

    fun cellCenter(key: CellKey): Pair<Double, Double> =
        (key.latIdx + 0.5) * CELL_DEG to (key.lonIdx + 0.5) * CELL_DEG

    /** Human-readable label for the bucket a given moment falls into, e.g. "Weekday, 16:00-20:00". */
    fun bucketLabel(whenMs: Long): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = whenMs
        val weekend = cal.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
        val startH = (cal.get(Calendar.HOUR_OF_DAY) / 4) * 4
        return "${if (weekend) "Weekend" else "Weekday"}, %02d:00-%02d:00".format(startH, startH + 4)
    }
}
