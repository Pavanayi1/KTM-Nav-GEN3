package com.navigator.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.analytics.FuelModel
import com.navigator.app.analytics.GpxParser
import com.navigator.app.analytics.ImuLog
import com.navigator.app.analytics.RideEventDetector
import com.navigator.app.analytics.RideSeriesBuilder
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.components.ChartCard
import com.navigator.app.ui.components.ChartSeries
import com.navigator.app.ui.components.CombinedChart
import com.navigator.app.ui.components.Eyebrow
import com.navigator.app.ui.components.KtmPrimaryButton
import com.navigator.app.ui.components.RideChartColors
import com.navigator.app.ui.components.ScreenHeader
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.NavIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.cos

/**
 * Ride Analytics: a recorded GPX on a zoomable map (Google Maps when an API
 * key is configured, the in-app canvas sketch otherwise) with a master time
 * slider scrubbing a marker along the route and a cursor through every chart:
 * speed, acceleration, altitude, riding events, plus a smoothness score and a
 * predictive fuel-cost estimate.
 */
@Composable
fun RideAnalyticsScreen(file: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }

    var model by remember(file) { mutableStateOf<AnalyticsModel?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        val m = withContext(Dispatchers.Default) { buildModel(file) }
        if (m == null) failed = true else model = m
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Ktm.Screen).systemBarsPadding(),
    ) {
        ScreenHeader("Ride analytics", onBack = onBack)
        when {
            failed -> Text(
                "Couldn't read this ride (not enough GPS points).",
                color = Ktm.TextSecondary, fontFamily = Barlow, fontSize = 14.sp,
                modifier = Modifier.padding(18.dp),
            )
            model == null -> Text(
                "Crunching the ride…", color = Ktm.Dim, fontFamily = Barlow, fontSize = 14.sp,
                modifier = Modifier.padding(18.dp),
            )
            else -> AnalyticsBody(model!!, settings)
        }
    }
}

/* ------------------------------------------------------------------------- */
/* Model                                                                      */
/* ------------------------------------------------------------------------- */

private class AnalyticsModel(
    val series: RideSeriesBuilder.RideSeries,
    val eventsResult: RideEventDetector.Result,
    val simplifiedIdx: IntArray,
    val geo: com.navigator.app.ui.components.TrackGeo,
)

private fun buildModel(file: File): AnalyticsModel? {
    val pts = GpxParser.parse(file)
    val series = RideSeriesBuilder.build(pts) ?: return null
    val imu = ImuLog.read(file)
    val events = RideEventDetector.detect(series, imu)
    val idx = RideSeriesBuilder.simplifyIndices(series.points)
    // Map geometry in simplified-index space (incl. crawl ranges computed on it).
    val lats = DoubleArray(idx.size) { series.points[idx[it]].lat }
    val lons = DoubleArray(idx.size) { series.points[idx[it]].lon }
    val speeds = FloatArray(idx.size) { series.speedKmh[idx[it]] }
    val times = LongArray(idx.size) { series.timesMs[idx[it]] }
    val engine = Array<Boolean?>(idx.size) { series.points[idx[it]].engineOn }
    val geo = com.navigator.app.ui.components.TrackGeo(
        lats, lons, speeds,
        trafficRanges = RideEventDetector.trafficRanges(times, speeds, engine),
        timesMs = times,
    )
    return AnalyticsModel(series, events, idx, geo)
}

/* ------------------------------------------------------------------------- */
/* Body                                                                       */
/* ------------------------------------------------------------------------- */

@Composable
private fun AnalyticsBody(m: AnalyticsModel, settings: AppSettings) {
    val s = m.series
    var scrubT by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var showFuelDialog by remember { mutableStateOf(false) }
    // Shared pinch-zoom window (fraction of full ride duration) - all charts
    // zoom together, matching the master scrub they already share.
    var zoomRange by remember { mutableStateOf(com.navigator.app.ui.components.FULL_ZOOM) }

    // Auto-follow: once zoomed in, continuously keep the cursor centered in
    // the window (replay or manual scrub) instead of only correcting once it
    // nears an edge - a threshold jump reads as a stutter/snap, so this
    // eases the window's start toward "cursor centered" a little every
    // frame instead, the same smoothing technique the map's follow mode
    // uses. Keyed on Unit (never restarts) so it can run forever without
    // per-frame relaunch overhead; skips the state write once converged so
    // it goes idle rather than recomposing every frame for no visual change.
    LaunchedEffect(Unit) {
        var lastScrubT = scrubT
        // Frames left to keep correcting after the last time scrubT changed.
        // A single master-slider release is ONE frame of change, not
        // continuous motion - checking only "did it change THIS frame" gave
        // it exactly one 18%-of-the-way nudge and then gave up, leaving the
        // window mostly unconverged (the marker pinned near the window's
        // edge, showing a stale clamped value that didn't match the header -
        // the concrete bug a screen recording caught). Keep correcting for a
        // short settle window after any change instead, so it actually
        // reaches "centered" rather than taking one step toward it.
        var settleFrames = 0
        while (isActive) {
            withFrameNanos {
                val range = zoomRange
                if (scrubT != lastScrubT) settleFrames = 30 // ~0.5s at 60fps
                lastScrubT = scrubT
                if (settleFrames > 0 && range != com.navigator.app.ui.components.FULL_ZOOM) {
                    settleFrames--
                    val width = range.endInclusive - range.start
                    val idealStart = (scrubT - width / 2f).coerceIn(0f, 1f - width)
                    val newStart = range.start + (idealStart - range.start) * 0.18f
                    if (abs(newStart - range.start) > 0.00005f) {
                        zoomRange = newStart..(newStart + width)
                    } else {
                        settleFrames = 0 // converged - stop early so a manual pan right after isn't fought
                    }
                }
            }
        }
    }

    // Replay: advance the scrub at 30x real time until the end.
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var lastNs = 0L
        while (playing && scrubT < 1f) {
            withFrameNanos { now ->
                if (lastNs != 0L) {
                    val dtRealMs = (now - lastNs) / 1_000_000.0 * 30.0
                    scrubT = (scrubT + (dtRealMs / s.durationMs)).toFloat().coerceAtMost(1f)
                }
                lastNs = now
            }
        }
        playing = false
    }

    val scrubTimeMs = s.timesMs.first() + ((s.timesMs.last() - s.timesMs.first()) * scrubT).toLong()

    val speedSeries = remember(s) { ChartSeries("SPEED", "km/h", RideChartColors.Speed, s.timesMs, s.speedKmh) }
    val accelSeries = remember(s) { ChartSeries("ACCELERATION", "m/s²", RideChartColors.Accel, s.timesMs, s.accelMs2) }
    val altSeries = remember(s) {
        s.altitudeM?.let { ChartSeries("ALTITUDE", "m", RideChartColors.Altitude, s.timesMs, it) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            var follow by remember { mutableStateOf(true) }
            val accelPoints = remember(m) {
                val pts = m.series.points
                m.eventsResult.events
                    .filter { it.type == RideEventDetector.EventType.HARSH_ACCEL }
                    .map { e ->
                        val p = pts[e.pointIndex]
                        val next = pts.getOrNull(e.pointIndex + 1) ?: pts[e.pointIndex]
                        val heading = com.navigator.app.ui.components.bearingDeg(p.lat, p.lon, next.lat, next.lon)
                        Triple(p.lat, p.lon, heading)
                    }
            }
            Column {
                com.navigator.app.ui.components.SpeedTrackMap(
                    geo = m.geo, scrubTimeMs = scrubTimeMs, follow = follow,
                    harshAccelPoints = accelPoints,
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (follow) "FOLLOWING · TAP TO FREE" else "FOLLOW",
                        color = if (follow) Ktm.Orange else Ktm.Dim, fontFamily = BarlowCondensed,
                        fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (follow) Ktm.Orange.copy(alpha = 0.15f) else Ktm.Surface)
                            .border(1.dp, if (follow) Ktm.Orange else Ktm.Border, RoundedCornerShape(6.dp))
                            .clickable { follow = !follow }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        item {
            ScrubConsole(
                s, scrubT, scrubTimeMs, playing,
                onScrub = { scrubT = it; playing = false },
                onTogglePlay = { if (scrubT >= 1f) scrubT = 0f; playing = !playing },
            )
        }
        item { StatsHeader(m, settings, onFuelSetup = { showFuelDialog = true }) }
        item { Eyebrow("Charts", color = Ktm.Orange, fontSize = 11) }
        item {
            com.navigator.app.ui.components.ZoomScrollbar(
                visibleRange = zoomRange,
                onVisibleRangeChange = { zoomRange = it },
            )
        }
        item {
            CombinedChart(
                seriesList = listOfNotNull(speedSeries, accelSeries, altSeries),
                scrubT = scrubT,
                onScrub = { scrubT = it; playing = false },
                roughSpans = m.eventsResult.roughSpans,
                visibleRange = zoomRange,
                onVisibleRangeChange = { zoomRange = it },
            )
        }
        item {
            ChartCard(
                speedSeries, scrubT, { scrubT = it; playing = false },
                events = m.eventsResult.events, roughSpans = m.eventsResult.roughSpans,
                visibleRange = zoomRange, onVisibleRangeChange = { zoomRange = it },
            )
        }
        item {
            ChartCard(
                accelSeries, scrubT, { scrubT = it; playing = false },
                zeroLine = true,
                events = m.eventsResult.events,
                cornerTag = if (m.eventsResult.gpsOnly) "DERIVED FROM GPS" else "GPS+IMU",
                visibleRange = zoomRange, onVisibleRangeChange = { zoomRange = it },
            )
        }
        item {
            if (altSeries != null) {
                ChartCard(
                    altSeries, scrubT, { scrubT = it; playing = false },
                    visibleRange = zoomRange, onVisibleRangeChange = { zoomRange = it },
                )
            } else {
                DegradedCard("ALTITUDE", "No elevation in this recording.")
            }
        }
        item { EventsSummary(m.eventsResult) }
    }

    if (showFuelDialog) {
        FuelSetupDialog(settings, onDismiss = { showFuelDialog = false })
    }
}

/* ------------------------------------------------------------------------- */
/* Scrub console                                                              */
/* ------------------------------------------------------------------------- */

@Composable
private fun ScrubConsole(
    s: RideSeriesBuilder.RideSeries,
    scrubT: Float,
    scrubTimeMs: Long,
    playing: Boolean,
    onScrub: (Float) -> Unit,
    onTogglePlay: () -> Unit,
) {
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    // Hoisted once (not per-frame) - see the interpolation note below.
    val cumDistFloat = remember(s) { FloatArray(s.cumDistM.size) { i -> s.cumDistM[i].toFloat() } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusRow))
            .background(Ktm.Surface)
            .border(1.dp, Ktm.Border, RoundedCornerShape(Ktm.RadiusRow))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(clock.format(scrubTimeMs), color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "+%.1f km".format(com.navigator.app.ui.components.interpolateAt(s.timesMs, cumDistFloat, scrubTimeMs) / 1000.0),
                color = Ktm.White, fontFamily = JetBrainsMono, fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (playing) "❚❚" else "▶  30x",
                color = Ktm.Orange, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onTogglePlay).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Slider(
            value = scrubT,
            onValueChange = onScrub,
            colors = SliderDefaults.colors(
                thumbColor = Ktm.Orange,
                activeTrackColor = Ktm.Orange,
                inactiveTrackColor = Ktm.Border,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LiveValue(RideChartColors.Speed, "%.0f km/h".format(com.navigator.app.ui.components.interpolateAt(s.timesMs, s.speedKmh, scrubTimeMs)))
            LiveValue(RideChartColors.Accel, "%+.1f m/s²".format(com.navigator.app.ui.components.interpolateAt(s.timesMs, s.accelMs2, scrubTimeMs)))
            s.altitudeM?.let { LiveValue(RideChartColors.Altitude, "%.0f m".format(com.navigator.app.ui.components.interpolateAt(s.timesMs, it, scrubTimeMs))) }
        }
    }
}

@Composable
private fun LiveValue(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text("  $text", color = Ktm.TextPrimary, fontFamily = JetBrainsMono, fontSize = 13.sp)
    }
}

/* ------------------------------------------------------------------------- */
/* Stats header + fuel                                                        */
/* ------------------------------------------------------------------------- */

@Composable
private fun StatsHeader(m: AnalyticsModel, settings: AppSettings, onFuelSetup: () -> Unit) {
    val s = m.series
    val score = m.eventsResult.smoothnessScore
    val fuel = FuelModel.estimate(
        s.distanceKm, settings.fuelMileageKmPerL.toDouble(), settings.fuelPricePerL.toDouble(), score,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("DISTANCE", "%.1f".format(s.distanceKm), "km", Modifier.weight(1f))
            StatTile("DURATION", formatDuration(s.durationMs), "h:m", Modifier.weight(1f))
            StatTile("AVG", "%.0f".format(s.avgMovingKmh), "km/h", Modifier.weight(1f))
            StatTile("MAX", "%.0f".format(s.maxKmh), "km/h", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "ELEV GAIN",
                s.elevGainM?.let { "+%.0f".format(it) } ?: "--",
                "m", Modifier.weight(1f),
            )
            SmoothnessTile(score, m.eventsResult, Modifier.weight(2f))
            FuelTile(fuel, settings, onFuelSetup, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Ktm.Surface)
            .border(1.dp, Ktm.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, color = Ktm.Dim2, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
        Text(value, color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(unit, color = Ktm.Dim, fontFamily = Barlow, fontSize = 11.sp)
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 80 -> Ktm.Green
    score >= 50 -> Ktm.Orange
    else -> Ktm.Danger
}

@Composable
private fun SmoothnessTile(score: Int, events: RideEventDetector.Result, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Ktm.Surface)
            .border(1.dp, Ktm.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text("SMOOTHNESS", color = Ktm.Dim2, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
        Text("$score", color = scoreColor(score), fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Ktm.SurfaceDisabled),
        ) {
            Box(
                Modifier.fillMaxWidth(score / 100f).height(6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(scoreColor(score)),
            )
        }
        val accel = events.events.count { it.type == RideEventDetector.EventType.HARSH_ACCEL }
        val brake = events.events.count { it.type == RideEventDetector.EventType.HARD_BRAKE }
        Text(
            "$accel harsh · $brake brakes · ${events.roughSpans.size} rough" +
                if (events.gpsOnly) " · GPS-only" else "",
            color = Ktm.Dim, fontFamily = Barlow, fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FuelTile(fuel: FuelModel.Estimate?, settings: AppSettings, onSetup: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Ktm.Surface)
            .border(1.dp, Ktm.Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onSetup)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text("FUEL", color = Ktm.Dim2, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
        if (fuel == null) {
            Text("--", color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text("SET UP ›", color = Ktm.Orange, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
        } else {
            Text("%.1f L".format(fuel.litres), color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(
                "≈ ₹%.0f · %.1f km/L".format(fuel.cost, fuel.effectiveKmPerL),
                color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 10.sp,
            )
        }
    }
}

/* ------------------------------------------------------------------------- */
/* Events summary + degraded card + fuel dialog                               */
/* ------------------------------------------------------------------------- */

@Composable
private fun EventsSummary(r: RideEventDetector.Result) {
    val accel = r.events.count { it.type == RideEventDetector.EventType.HARSH_ACCEL }
    val brake = r.events.count { it.type == RideEventDetector.EventType.HARD_BRAKE }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.ScreenDeep)
            .border(1.dp, Ktm.Bezel, RoundedCornerShape(Ktm.RadiusCard))
            .padding(12.dp),
    ) {
        Eyebrow("EVENTS", color = Ktm.Dim, fontSize = 11)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LiveValue(RideChartColors.Accel, "$accel harsh accel")
            LiveValue(RideChartColors.Brake, "$brake hard brakes")
            LiveValue(RideChartColors.Rough, "${r.roughSpans.size} rough")
        }
        if (r.gpsOnly) {
            Text(
                "Recorded without motion sensors — events derived from GPS speed only; rough-road detection needs a ride recorded on this app version.",
                color = Ktm.Dim, fontFamily = Barlow, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun DegradedCard(title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.ScreenDeep)
            .border(1.dp, Ktm.Bezel, RoundedCornerShape(Ktm.RadiusCard))
            .padding(12.dp),
    ) {
        Eyebrow(title, color = Ktm.Dim, fontSize = 11)
        Text(message, color = Ktm.Dim, fontFamily = Barlow, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun FuelSetupDialog(settings: AppSettings, onDismiss: () -> Unit) {
    var mileage by remember { mutableStateOf(settings.fuelMileageKmPerL.takeIf { it > 0 }?.toString() ?: "") }
    var price by remember { mutableStateOf(settings.fuelPricePerL.takeIf { it > 0 }?.toString() ?: "") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(Ktm.RadiusCard))
                .background(Ktm.Surface)
                .border(1.dp, Ktm.Border, RoundedCornerShape(Ktm.RadiusCard))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow("FUEL & COSTS", color = Ktm.Orange, fontSize = 12)
            Text(
                "Enter your bike's claimed mileage — real-world usage is estimated from it (a claimed 20 km/L rides like ~16.5-17) and nudged by how smoothly each ride was ridden.",
                color = Ktm.Dim, fontFamily = Barlow, fontSize = 12.sp,
            )
            FuelField("Mileage (km/L)", mileage) { mileage = it }
            FuelField("Fuel price (per litre)", price) { price = it }
            KtmPrimaryButton("SAVE") {
                settings.fuelMileageKmPerL = mileage.toFloatOrNull()?.coerceIn(5f, 80f) ?: 0f
                settings.fuelPricePerL = price.toFloatOrNull()?.coerceIn(1f, 500f) ?: 0f
                onDismiss()
            }
            Text(
                "CANCEL", color = Ktm.Dim, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss).padding(4.dp),
            )
        }
    }
}

@Composable
private fun FuelField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Eyebrow(label, color = Ktm.Dim, fontSize = 10)
        BasicTextField(
            value = value,
            onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' }) },
            textStyle = TextStyle(color = Ktm.White, fontFamily = JetBrainsMono, fontSize = 20.sp),
            cursorBrush = SolidColor(Ktm.Orange),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Ktm.Screen)
                .border(1.dp, Ktm.Border, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    return "%d:%02d".format(totalMin / 60, totalMin % 60)
}
