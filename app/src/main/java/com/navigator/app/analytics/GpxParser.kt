package com.navigator.app.analytics

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * GPX track parser for ride analytics. Handles our own RouteRecorder output
 * (lat/lon + <ele> + <time> + od:speed/od:engine extensions) and, best-effort,
 * imported GPX from other apps (any tag order inside <trkpt>, missing ele or
 * extensions). Regex-based like the RidesScreen parser - our files are
 * machine-written and imported files only need the common shape.
 */
object GpxParser {

    data class TrackPoint(
        val lat: Double,
        val lon: Double,
        val timeMs: Long,
        val eleM: Double?,
        val speedKmh: Float?,
        val engineOn: Boolean?,
    )

    private val PT = Regex("""<trkpt\s+lat="([-\d.]+)"\s+lon="([-\d.]+)"\s*>(.*?)</trkpt>""", RegexOption.DOT_MATCHES_ALL)
    private val TIME = Regex("""<time>([^<]+)</time>""")
    private val ELE = Regex("""<ele>([-\d.]+)</ele>""")
    private val SPEED = Regex("""<od:speed>([\d.]+)</od:speed>""")
    private val ENGINE = Regex("""<od:engine>(on|off)</od:engine>""")

    // Two accepted time shapes: our writer's "yyyy-MM-dd'T'HH:mm:ss'Z'" and the
    // common millisecond variant other recorders produce.
    private fun timeFormats() = listOf(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
    ).onEach { it.timeZone = TimeZone.getTimeZone("UTC") }

    fun parse(file: File): List<TrackPoint> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val formats = timeFormats()
        val out = ArrayList<TrackPoint>(4096)
        for (m in PT.findAll(text)) {
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            val body = m.groupValues[3]
            val timeStr = TIME.find(body)?.groupValues?.get(1) ?: continue
            val timeMs = formats.firstNotNullOfOrNull { f -> runCatching { f.parse(timeStr)?.time }.getOrNull() } ?: continue
            out.add(
                TrackPoint(
                    lat = lat,
                    lon = lon,
                    timeMs = timeMs,
                    eleM = ELE.find(body)?.groupValues?.get(1)?.toDoubleOrNull(),
                    speedKmh = SPEED.find(body)?.groupValues?.get(1)?.toFloatOrNull(),
                    engineOn = ENGINE.find(body)?.groupValues?.get(1)?.let { it == "on" },
                )
            )
        }
        return out
    }
}
