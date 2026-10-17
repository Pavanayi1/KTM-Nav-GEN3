package com.navigator.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.location.RouteRecorder
import com.navigator.app.ui.components.Eyebrow
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.OpenDashIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/* ------------------------------------------------------------------------- */
/* Ride data model + GPX post-processing                                      */
/* ------------------------------------------------------------------------- */

private data class RidePoint(
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val speedKmh: Float,
    val engineOn: Boolean?, // null = ride recorded without vibration detection
)

private data class RideStop(
    val startMs: Long,
    val durationMs: Long,
    /** true = classified as traffic (engine kept running / short mid-ride halt). */
    val traffic: Boolean,
    val label: String,
    val at: Int, // point index, for drawing the marker
)

private data class RideData(
    val points: List<RidePoint>,
    val distanceKm: Double,
    val totalMs: Long,
    val movingMs: Long,
    val maxKmh: Float,
    val avgMovingKmh: Float,
    val stops: List<RideStop>,
    val trafficMs: Long,
)

/** Speed→color buckets, as specified from the field: fast red, 50-60 band green, cruising blue, crawling black. */
private fun speedColor(kmh: Float): Color = when {
    kmh >= 75f -> Color(0xFFFF4B3A) // high speed - red
    kmh >= 45f -> Color(0xFF39D98A) // the 50-60 sweet spot - green
    kmh >= 5f -> Color(0xFF4B9BFF)  // easy riding - blue
    else -> Color(0xFF15181C)       // stationary/crawl - near-black
}

/** Stationary threshold + how long a halt must last before it counts as a stop. */
private const val STOP_KMH = 3f
private const val STOP_MIN_MS = 45_000L
/** Unknown-engine fallback: mid-ride halts up to this long are "likely traffic". */
private const val UNKNOWN_TRAFFIC_MAX_MS = 3 * 60_000L

private val GPX_TIME = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

/**
 * Parse one recorded GPX (regex-based - our own writer's format) and
 * post-process it: per-point speed (od:speed extension when present, derived
 * from geometry for older files), stop segments, and the traffic verdict.
 *
 * Traffic logic: a stop with the ENGINE RUNNING is the rider waiting in
 * traffic (that's the only reason you sit still with the engine on); a stop
 * with the engine OFF is intentional - fuel, chai, done - and is ignored.
 * Rides recorded before vibration calibration have no engine trace, so there
 * the length decides: short mid-ride halts read as "likely traffic", long
 * ones as breaks.
 */
private fun parseRide(file: File): RideData? {
    val text = runCatching { file.readText() }.getOrNull() ?: return null
    val ptRegex = Regex(
        """<trkpt lat="([-\d.]+)" lon="([-\d.]+)">.*?<time>([^<]+)</time>(.*?)</trkpt>""",
        RegexOption.DOT_MATCHES_ALL
    )
    val speedRegex = Regex("""<od:speed>([\d.]+)</od:speed>""")
    val engineRegex = Regex("""<od:engine>(on|off)</od:engine>""")

    val raw = ArrayList<RidePoint>(2048)
    for (m in ptRegex.findAll(text)) {
        val lat = m.groupValues[1].toDoubleOrNull() ?: continue
        val lon = m.groupValues[2].toDoubleOrNull() ?: continue
        val time = runCatching { GPX_TIME.parse(m.groupValues[3])?.time }.getOrNull() ?: continue
        val extras = m.groupValues[4]
        val speed = speedRegex.find(extras)?.groupValues?.get(1)?.toFloatOrNull()
        val engine = engineRegex.find(extras)?.groupValues?.get(1)?.let { it == "on" }
        raw.add(RidePoint(lat, lon, time, speed ?: -1f, engine))
    }
    if (raw.size < 2) return null

    // Fill speeds missing an od:speed extension from point-to-point geometry.
    val points = raw.mapIndexed { i, p ->
        if (p.speedKmh >= 0f) p
        else {
            val prev = raw.getOrNull(i - 1) ?: raw[i + 1]
            val dtS = kotlin.math.abs(p.timeMs - prev.timeMs) / 1000.0
            val kmh = if (dtS < 0.5) 0f else (haversineM(prev.lat, prev.lon, p.lat, p.lon) / dtS * 3.6).toFloat()
            p.copy(speedKmh = kmh.coerceAtMost(220f))
        }
    }

    var distM = 0.0
    var movingMs = 0L
    for (i in 1 until points.size) {
        distM += haversineM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        val dt = points[i].timeMs - points[i - 1].timeMs
        if (points[i].speedKmh >= STOP_KMH && dt in 1..60_000) movingMs += dt
    }

    // Stop segmentation + classification.
    val stops = ArrayList<RideStop>()
    var stopStart = -1
    val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    fun closeStop(endIdx: Int) {
        if (stopStart < 0) return
        val startIdx = stopStart; stopStart = -1
        val durMs = points[endIdx].timeMs - points[startIdx].timeMs
        if (durMs < STOP_MIN_MS) return
        val slice = points.subList(startIdx, endIdx + 1)
        val engineSamples = slice.mapNotNull { it.engineOn }
        val nearRideEdge = startIdx < 3 || endIdx > points.size - 4
        val (traffic, label) = when {
            engineSamples.isNotEmpty() && engineSamples.count { it } * 2 >= engineSamples.size ->
                true to "Traffic — engine kept running"
            engineSamples.isNotEmpty() ->
                false to "Stop — engine off"
            nearRideEdge ->
                false to "Ride start/end"
            durMs <= UNKNOWN_TRAFFIC_MAX_MS ->
                true to "Likely traffic — short mid-ride halt"
            else ->
                false to "Break — long halt"
        }
        stops.add(RideStop(points[startIdx].timeMs, durMs, traffic,
            "${timeFmt.format(points[startIdx].timeMs)} · ${durMs / 60_000}m ${(durMs / 1000) % 60}s · $label",
            startIdx))
    }
    for (i in points.indices) {
        if (points[i].speedKmh < STOP_KMH) {
            if (stopStart < 0) stopStart = i
        } else if (stopStart >= 0) closeStop(i - 1)
    }
    closeStop(points.size - 1)

    val maxKmh = points.maxOf { it.speedKmh }
    val avgMoving = if (movingMs > 0) (distM / 1000.0 / (movingMs / 3_600_000.0)).toFloat() else 0f
    return RideData(
        points = points,
        distanceKm = distM / 1000.0,
        totalMs = points.last().timeMs - points.first().timeMs,
        movingMs = movingMs,
        maxKmh = maxKmh,
        avgMovingKmh = avgMoving,
        stops = stops,
        trafficMs = stops.filter { it.traffic }.sumOf { it.durationMs },
    )
}

private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return 2 * r * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
}

/* ------------------------------------------------------------------------- */
/* Screens                                                                    */
/* ------------------------------------------------------------------------- */

/** Ride list → tap → post-processed detail (speed-colored track, speed graph, stops). */
@Composable
fun RidesScreen(onBack: () -> Unit, initialFile: File? = null) {
    val context = LocalContext.current
    // Keyed on initialFile: a GPX opened from another app while this screen is
    // already up (singleTask onNewIntent) swaps the detail view to the new file.
    var selected by remember(initialFile) { mutableStateOf(initialFile) }
    val files = remember(initialFile) { RouteRecorder.listRoutes(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ktm.Screen)
            .systemBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                OpenDashIcons.ChevronLeft, contentDescription = "Back", tint = Ktm.Muted,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { if (selected != null) selected = null else onBack() }
                    .padding(6.dp)
                    .size(24.dp),
            )
            Text(
                if (selected == null) "RIDES" else selected!!.nameWithoutExtension.removePrefix("route_"),
                color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic, fontSize = 20.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(14.dp))

        val sel = selected
        if (sel == null) RideList(files) { selected = it }
        else RideDetail(sel)
    }
}

@Composable
private fun RideList(files: List<File>, onOpen: (File) -> Unit) {
    if (files.isEmpty()) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No rides recorded yet.", color = Ktm.TextSecondary, fontFamily = Barlow, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Turn on \"Auto-record routes\" in Settings → Riding.\nRides record while the phone is on bike power.",
                color = Ktm.Dim, fontFamily = Barlow, fontSize = 13.sp, lineHeight = 18.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        return
    }
    val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    val displayFmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.US)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(files.size, key = { files[it].name }) { i ->
            val f = files[i]
            val stamp = runCatching { nameFmt.parse(f.nameWithoutExtension.removePrefix("route_"))?.let(displayFmt::format) }
                .getOrNull() ?: f.nameWithoutExtension
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Ktm.RadiusRow))
                    .background(Ktm.Surface)
                    .border(1.dp, Ktm.Border, RoundedCornerShape(Ktm.RadiusRow))
                    .clickable { onOpen(f) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(OpenDashIcons.Navigation, contentDescription = null, tint = Ktm.Orange, modifier = Modifier.size(22.dp))
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(stamp, color = Ktm.White, fontFamily = BarlowCondensed,
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("%.0f KB".format(f.length() / 1024.0), color = Ktm.Dim, fontFamily = Barlow, fontSize = 12.sp)
                }
                Text("›", color = Ktm.Muted, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun RideDetail(file: File) {
    val context = LocalContext.current
    var ride by remember(file) { mutableStateOf<RideData?>(null) }
    var parseFailed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        val parsed = withContext(Dispatchers.Default) { parseRide(file) }
        if (parsed == null) parseFailed = true else ride = parsed
    }

    val r = ride
    if (parseFailed) {
        Text("Couldn't read this ride (not enough GPS points).",
            color = Ktm.TextSecondary, fontFamily = Barlow, fontSize = 14.sp,
            modifier = Modifier.padding(top = 30.dp))
        return
    }
    if (r == null) {
        Text("Crunching the ride…", color = Ktm.Dim, fontFamily = Barlow, fontSize = 14.sp,
            modifier = Modifier.padding(top = 30.dp))
        return
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Stat strip.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("DISTANCE", "%.1f".format(r.distanceKm), "km", Modifier.weight(1f))
            StatTile("MOVING", "%d:%02d".format(r.movingMs / 3_600_000, (r.movingMs / 60_000) % 60), "h:m", Modifier.weight(1f))
            StatTile("AVG", "%.0f".format(r.avgMovingKmh), "km/h", Modifier.weight(1f))
            StatTile("MAX", "%.0f".format(r.maxKmh), "km/h", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))

        // Track, colored by speed.
        Eyebrow("Track — colored by speed", color = Ktm.Dim, fontSize = 11, letterSpacing = 1.5)
        Spacer(Modifier.height(6.dp))
        TrackCanvas(r)
        Spacer(Modifier.height(8.dp))
        LegendRow()
        Spacer(Modifier.height(16.dp))

        // Speed graph.
        Eyebrow("Speed over time", color = Ktm.Dim, fontSize = 11, letterSpacing = 1.5)
        Spacer(Modifier.height(6.dp))
        SpeedGraph(r)
        Spacer(Modifier.height(16.dp))

        // Stops / traffic post-processing.
        Eyebrow(
            "Stops — ${r.stops.count { it.traffic }} traffic · ${r.trafficMs / 60_000} min lost",
            color = Ktm.Dim, fontSize = 11, letterSpacing = 1.5,
        )
        Spacer(Modifier.height(6.dp))
        if (r.stops.isEmpty()) {
            Text("Non-stop ride — nice.", color = Ktm.TextSecondary, fontFamily = Barlow, fontSize = 13.sp)
        }
        r.stops.forEach { stop ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Ktm.Surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(9.dp).clip(CircleShape)
                        .background(if (stop.traffic) Color(0xFFFFB020) else Ktm.Dim)
                )
                Text(stop.label, color = Ktm.TextSecondary, fontFamily = Barlow, fontSize = 12.5.sp,
                    modifier = Modifier.padding(start = 10.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        // Share this ride's raw GPX.
        com.navigator.app.ui.components.KtmOutlineButton("Share GPX") {
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("application/gpx+xml")
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(android.content.Intent.createChooser(intent, "Share ride"))
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Ktm.Surface)
            .border(1.dp, Ktm.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(label, color = Ktm.Dim2, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
            fontSize = 10.sp, letterSpacing = 1.2.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(" $unit", color = Ktm.Dim, fontFamily = Barlow, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun LegendRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        LegendChip(Color(0xFFFF4B3A), "75+")
        LegendChip(Color(0xFF39D98A), "45–75")
        LegendChip(Color(0xFF4B9BFF), "5–45")
        LegendChip(Color(0xFF474C55), "stopped")
        LegendChip(Color(0xFFFFB020), "traffic")
    }
}

@Composable
private fun LegendChip(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(" $label", color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 10.sp)
    }
}

/** The ride's path, fit to the canvas, each segment stroked in its speed color. */
@Composable
private fun TrackCanvas(r: RideData) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.ScreenDeep)
            .border(1.dp, Ktm.Bezel, RoundedCornerShape(Ktm.RadiusCard)),
    ) {
        val pts = r.points
        val midLat = Math.toRadians(pts.sumOf { it.lat } / pts.size)
        // Equirectangular projection: x scales by cos(midLat) so the shape isn't squashed.
        val xs = pts.map { it.lon * cos(midLat) }
        val ys = pts.map { it.lat }
        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val pad = 28f
        val spanX = max(maxX - minX, 1e-6)
        val spanY = max(maxY - minY, 1e-6)
        val scale = kotlin.math.min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY).toFloat()
        val offX = (size.width - spanX.toFloat() * scale) / 2f
        val offY = (size.height - spanY.toFloat() * scale) / 2f
        fun toOffset(i: Int) = Offset(
            offX + ((xs[i] - minX) * scale).toFloat(),
            // Screen y grows downward; latitude grows upward.
            size.height - offY - ((ys[i] - minY) * scale).toFloat(),
        )

        for (i in 1 until pts.size) {
            val a = toOffset(i - 1); val b = toOffset(i)
            // Stationary crawl in a visible dark grey rather than true black-on-black.
            val c = if (pts[i].speedKmh < 5f) Color(0xFF474C55) else speedColor(pts[i].speedKmh)
            drawLine(c, a, b, strokeWidth = 5f, cap = StrokeCap.Round)
        }
        // Traffic stops as amber rings.
        r.stops.filter { it.traffic }.forEach { s ->
            drawCircle(Color(0xFFFFB020), radius = 9f, center = toOffset(s.at), style = Stroke(width = 3f))
        }
        // Start / end markers.
        drawCircle(Color(0xFF39D98A), radius = 8f, center = toOffset(0))
        drawCircle(Color(0xFFFF4B3A), radius = 8f, center = toOffset(pts.size - 1))
    }
}

/** Speed-vs-time line, stroked per segment in the same speed colors, with traffic bands shaded. */
@Composable
private fun SpeedGraph(r: RideData) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.ScreenDeep)
            .border(1.dp, Ktm.Bezel, RoundedCornerShape(Ktm.RadiusCard)),
    ) {
        val pts = r.points
        val t0 = pts.first().timeMs
        val span = max(1L, pts.last().timeMs - t0).toFloat()
        val vMax = max(60f, r.maxKmh * 1.1f)
        val padX = 8f; val padTop = 10f; val padBottom = 6f
        val w = size.width - 2 * padX
        val h = size.height - padTop - padBottom
        fun x(ms: Long) = padX + w * ((ms - t0) / span)
        fun y(kmh: Float) = padTop + h * (1f - (kmh / vMax).coerceIn(0f, 1f))

        // Traffic bands behind the curve.
        r.stops.filter { it.traffic }.forEach { s ->
            drawRect(
                Color(0x33FFB020),
                topLeft = Offset(x(s.startMs), padTop),
                size = androidx.compose.ui.geometry.Size(max(3f, x(s.startMs + s.durationMs) - x(s.startMs)), h),
            )
        }
        // Reference gridline at the 50-60 green band edges.
        listOf(45f, 75f).forEach { v ->
            drawLine(Color(0x22FFFFFF), Offset(padX, y(v)), Offset(padX + w, y(v)), strokeWidth = 1f)
        }
        for (i in 1 until pts.size) {
            drawLine(
                speedColor(pts[i].speedKmh).let { if (pts[i].speedKmh < 5f) Color(0xFF474C55) else it },
                Offset(x(pts[i - 1].timeMs), y(pts[i - 1].speedKmh)),
                Offset(x(pts[i].timeMs), y(pts[i].speedKmh)),
                strokeWidth = 2.5f,
            )
        }
    }
}
