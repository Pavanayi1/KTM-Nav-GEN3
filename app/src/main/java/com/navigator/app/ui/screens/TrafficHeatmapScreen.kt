package com.navigator.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.analytics.GpxParser
import com.navigator.app.analytics.RideEventDetector
import com.navigator.app.analytics.RideSeriesBuilder
import com.navigator.app.analytics.TrafficPredictor
import com.navigator.app.location.RouteRecorder
import com.navigator.app.ui.components.MultiRideTrafficMap
import com.navigator.app.ui.components.TrackGeo
import com.navigator.app.ui.components.ScreenHeader
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.NavIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class HeatMode { HISTORY, PREDICT }

/**
 * Every recorded ride drawn together, with detected traffic stretches
 * highlighted on the actual roads (not a floating blob) - and, from the same
 * history, a "predicted now" mode: a frequency model keyed by rough location
 * x weekday/weekend x 4-hour window, learned purely from this rider's own
 * past rides. See [TrafficPredictor] for exactly what it is and isn't.
 */
@Composable
fun TrafficHeatmapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<HeatData?>(null) }
    var mode by remember { mutableStateOf(HeatMode.HISTORY) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        data = withContext(Dispatchers.Default) { loadHeatData(context) }
    }

    Column(modifier = Modifier.fillMaxSize().background(Ktm.Screen).systemBarsPadding()) {
        ScreenHeader("Traffic map", onBack = onBack)

        val d = data
        if (com.navigator.app.BuildConfig.MAPS_API_KEY.isBlank()) {
            Message("The full traffic map needs the Google map - add MAPS_API_KEY to local.properties. A basic sketch still works below.")
        }
        when {
            d == null -> Message("Aggregating all rides…")
            d.rides.isEmpty() -> Message("No traffic detected yet - the map builds itself as recorded rides accumulate.")
            else -> {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ModeChip("HISTORY", mode == HeatMode.HISTORY) { mode = HeatMode.HISTORY }
                    ModeChip("PREDICT NOW", mode == HeatMode.PREDICT) { mode = HeatMode.PREDICT; nowMs = System.currentTimeMillis() }
                }
                if (mode == HeatMode.HISTORY) {
                    Text(
                        "${d.rideCount} rides aggregated - the route is drawn where you actually rode; amber = crawl, red-orange = stopped/signal.",
                        color = Ktm.Dim, fontFamily = Barlow, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    MultiRideTrafficMap(
                        rides = d.rides.map { it.geo }, heatPoints = d.heatPoints,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    val cells = remember(d, nowMs) { d.model.cellsForTime(nowMs) }
                    Text(
                        if (cells.isEmpty())
                            "Not enough ride history yet for ${TrafficPredictor.bucketLabel(nowMs)} - the model needs a few passes through the same spot at a similar time before it can guess. Keep riding."
                        else
                            "${cells.size} familiar spot(s) with enough history for ${TrafficPredictor.bucketLabel(nowMs)} - violet shading = odds of hitting traffic here at this time, from your own past rides.",
                        color = Ktm.Dim, fontFamily = Barlow, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    MultiRideTrafficMap(
                        rides = d.rides.map { it.geo },
                        predictedCells = cells,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Text(
                    "This is a simple pattern-frequency model built entirely on-device from your own rides - not a live traffic feed, and it gets sharper the more you ride the same routes at the same times.",
                    color = Ktm.Dim2, fontFamily = Barlow, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, color = Ktm.TextSecondary, fontFamily = Barlow, fontSize = 14.sp, modifier = Modifier.padding(18.dp))
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Ktm.Orange else Ktm.Surface)
            .border(1.dp, if (selected) Ktm.Orange else Ktm.Border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label, color = if (selected) Ktm.OnAccent else Ktm.TextSecondary,
            fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp,
        )
    }
}

private class RideAgg(val geo: TrackGeo)

private class HeatData(
    val rides: List<RideAgg>,
    val model: TrafficPredictor.Model,
    val rideCount: Int,
    val heatPoints: List<com.google.maps.android.heatmaps.WeightedLatLng>,
)

private fun loadHeatData(context: android.content.Context): HeatData {
    val files = RouteRecorder.listRoutes(context)
    val ridesAgg = ArrayList<RideAgg>()
    val samples = ArrayList<TrafficPredictor.RideSample>()
    val heatPoints = ArrayList<com.google.maps.android.heatmaps.WeightedLatLng>()
    var rideCount = 0

    for (f in files) {
        val pts = GpxParser.parse(f)
        if (pts.size < 20) continue
        rideCount++

        // Full resolution: accurate traffic detection + the predictor's counts.
        val times = LongArray(pts.size) { pts[it].timeMs }
        val lats = DoubleArray(pts.size) { pts[it].lat }
        val lons = DoubleArray(pts.size) { pts[it].lon }
        val speeds = FloatArray(pts.size) { pts[it].speedKmh ?: -1f }
        val engine = Array<Boolean?>(pts.size) { pts[it].engineOn }
        val fullTraffic = RideEventDetector.trafficRanges(times, speeds, engine)
        samples.add(TrafficPredictor.RideSample(times, lats, lons, fullTraffic))

        // Heat density: ONLY the real GPS points inside a detected traffic
        // range contribute - the glow is therefore built from points that ARE
        // on the road actually ridden, never a cloud floating nearby.
        for (tr in fullTraffic) {
            for (i in tr.range) {
                val v = speeds[i]
                if (v < 0f) continue
                val weight = if (tr.kind == RideEventDetector.TrafficKind.SIGNAL) 1.0
                    else ((15f - v) / 15.0).coerceIn(0.2, 1.0)
                heatPoints.add(com.google.maps.android.heatmaps.WeightedLatLng(com.google.android.gms.maps.model.LatLng(lats[i], lons[i]), weight))
            }
        }

        // Decimated: keeps the combined multi-ride map fast to draw. Traffic
        // ranges are recomputed on the thinned series - fine for a map overlay,
        // exact counts already came from the full-resolution pass above.
        val idx = RideSeriesBuilder.simplifyIndices(pts, epsilonM = 6.0)
        val dTimes = LongArray(idx.size) { times[idx[it]] }
        val dLats = DoubleArray(idx.size) { lats[idx[it]] }
        val dLons = DoubleArray(idx.size) { lons[idx[it]] }
        val dSpeeds = FloatArray(idx.size) { speeds[idx[it]] }
        val dEngine = Array<Boolean?>(idx.size) { engine[idx[it]] }
        val dTraffic = RideEventDetector.trafficRanges(dTimes, dSpeeds, dEngine)
        ridesAgg.add(RideAgg(TrackGeo(dLats, dLons, dSpeeds, dTraffic)))
    }

    return HeatData(ridesAgg, TrafficPredictor.build(samples), rideCount, heatPoints)
}
