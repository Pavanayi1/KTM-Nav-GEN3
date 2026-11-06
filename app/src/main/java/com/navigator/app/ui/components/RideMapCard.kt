package com.navigator.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.pow

/**
 * Speed-colored ride track on a Google Map (traffic layer on) with a
 * self-degrading canvas fallback when no MAPS_API_KEY is configured. Shared
 * by the ride detail view and the analytics screen. Detected traffic-crawl
 * stretches overlay the track in amber; the optional scrub marker sizes
 * itself to the current zoom so it reads the same at street and city scale.
 */
data class TrackGeo(
    val lats: DoubleArray,
    val lons: DoubleArray,
    val speedsKmh: FloatArray,
    /** Detected traffic stretches (crawl = still creeping, signal = stopped/waiting), drawn over the track. */
    val trafficRanges: List<com.navigator.app.analytics.RideEventDetector.TrafficRange> = emptyList(),
    /** Per-point timestamps - lets the scrub marker interpolate by real elapsed time, not raw index. */
    val timesMs: LongArray = LongArray(0),
)

/** Compass bearing from point 1 to point 2, degrees 0-360 (0 = north). */
internal fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val phi1 = Math.toRadians(lat1); val phi2 = Math.toRadians(lat2)
    val dLambda = Math.toRadians(lon2 - lon1)
    val y = kotlin.math.sin(dLambda) * kotlin.math.cos(phi2)
    val x = kotlin.math.cos(phi1) * kotlin.math.sin(phi2) - kotlin.math.sin(phi1) * kotlin.math.cos(phi2) * kotlin.math.cos(dLambda)
    val deg = Math.toDegrees(kotlin.math.atan2(y, x))
    return ((deg + 360) % 360).toFloat()
}

/** Fractional (index, heading) for a moment in time, interpolating between the two bracketing points. */
private class ScrubPoint(val lat: Double, val lon: Double, val headingDeg: Float, val fraction: Float)

/**
 * Smoothed follower for the raw time-interpolated scrub point: chases the
 * target by a fixed fraction of the remaining distance every animation
 * frame (frame-rate-independent exponential smoothing), instead of a
 * fixed-duration tween. That distinction matters here specifically because
 * the raw target itself updates every single frame during replay - a tween
 * restarted that often never finishes before being told to chase a new,
 * further-away target, and net motion is imperceptible (that was the R47
 * regression: the marker "not moving at all"). A per-frame proportional
 * step can't get stuck that way - it always closes part of the remaining
 * gap, however fast the target is moving.
 */
@Composable
private fun rememberEasedScrub(geo: TrackGeo, scrubTimeMs: Long?): ScrubPoint? {
    val latestTime = rememberUpdatedState(scrubTimeMs)
    val initial = scrubTimeMs?.let { interpolateScrub(geo, it) }
    var lat by remember(geo) { mutableStateOf(initial?.lat ?: geo.lats.firstOrNull() ?: 0.0) }
    var lon by remember(geo) { mutableStateOf(initial?.lon ?: geo.lons.firstOrNull() ?: 0.0) }
    var heading by remember(geo) { mutableFloatStateOf(initial?.headingDeg ?: 0f) }
    var fraction by remember(geo) { mutableFloatStateOf(initial?.fraction ?: 0f) }
    var hasTarget by remember(geo) { mutableStateOf(initial != null) }

    LaunchedEffect(geo) {
        while (isActive) {
            withFrameNanos {
                val target = latestTime.value?.let { interpolateScrub(geo, it) }
                if (target != null) {
                    var delta = target.headingDeg - ((heading % 360 + 360) % 360)
                    if (delta > 180) delta -= 360
                    if (delta < -180) delta += 360
                    // Proportional easing never actually ARRIVES - it just halves
                    // the gap forever - so without this check the loop wrote new
                    // lat/lon/heading values on every single frame for as long as
                    // the screen was open, and everything reading the scrub
                    // position recomposed 60x/second while the ride sat still.
                    // Below the epsilon the difference is under a couple of
                    // centimetres and a twentieth of a degree: snap once, then
                    // stop writing until the target actually moves again.
                    val settled = kotlin.math.abs(target.lat - lat) < SCRUB_EPSILON_DEG &&
                        kotlin.math.abs(target.lon - lon) < SCRUB_EPSILON_DEG &&
                        kotlin.math.abs(delta) < 0.05f
                    if (settled) {
                        if (lat != target.lat) lat = target.lat
                        if (lon != target.lon) lon = target.lon
                        if (heading != target.headingDeg) heading = target.headingDeg
                    } else {
                        val alpha = 0.22f
                        lat += (target.lat - lat) * alpha
                        lon += (target.lon - lon) * alpha
                        heading += delta * alpha
                    }
                    if (fraction != target.fraction) fraction = target.fraction
                    hasTarget = true
                } else {
                    hasTarget = false
                }
            }
        }
    }
    return if (hasTarget) ScrubPoint(lat, lon, ((heading % 360) + 360) % 360, fraction) else null
}

/** ~1 cm in degrees: below this the eased marker is visibly at its target. */
private const val SCRUB_EPSILON_DEG = 1e-7

private fun interpolateScrub(geo: TrackGeo, timeMs: Long): ScrubPoint? {
    val times = geo.timesMs
    if (times.isEmpty() || geo.lats.size < 2) return null
    var i1 = times.indices.firstOrNull { times[it] >= timeMs } ?: (times.size - 1)
    if (i1 == 0) i1 = 1
    val i0 = i1 - 1
    val span = (times[i1] - times[i0]).coerceAtLeast(1)
    val t = ((timeMs - times[i0]).toFloat() / span).coerceIn(0f, 1f)
    val lat = geo.lats[i0] + (geo.lats[i1] - geo.lats[i0]) * t
    val lon = geo.lons[i0] + (geo.lons[i1] - geo.lons[i0]) * t
    val heading = bearingDeg(geo.lats[i0], geo.lons[i0], geo.lats[i1], geo.lons[i1])
    val fraction = (i0 + t) / (geo.lats.size - 1).coerceAtLeast(1)
    return ScrubPoint(lat, lon, heading, fraction)
}

val TrafficAmber = Color(0xFFFFB020)
val TrafficSignalRed = Color(0xFFE0562B)
val AccelBlue = Color(0xFF2F8CFF)
val PredictedGlowRed = Color(0xFFFF3B30)

private fun trafficColor(kind: com.navigator.app.analytics.RideEventDetector.TrafficKind): Color =
    if (kind == com.navigator.app.analytics.RideEventDetector.TrafficKind.SIGNAL) TrafficSignalRed else TrafficAmber

fun speedTrackColor(kmh: Float): Color = when {
    kmh >= 75f -> Color(0xFFFF4B3A)
    kmh >= 45f -> Color(0xFF39D98A)
    kmh >= 5f -> Color(0xFF4B9BFF)
    else -> Color(0xFF474C55)
}

private fun mapsKeyPresent(): Boolean = com.navigator.app.BuildConfig.MAPS_API_KEY.isNotBlank()

/**
 * Heading-arrow "puck" bitmap: a soft drop shadow (pseudo-3D lift) beneath a
 * white disc and an orange chevron pointing up (north) - the whole marker is
 * then rotated per-frame via Marker's own `rotation`, so this is drawn once
 * and cached. Placeholder styling; see the design brief for the final look.
 */
@Composable
private fun rememberPuckIcon(): com.google.android.gms.maps.model.BitmapDescriptor {
    return remember {
        val size = 96
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f

        // Drop shadow - offset down, blurred, to lift the puck off the map.
        val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(90, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(6f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(cx - 22f, cy - 14f, cx + 22f, cy + 26f, shadowPaint)

        // White base disc.
        val basePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
        }
        canvas.drawCircle(cx, cy, 26f, basePaint)

        // Orange chevron, pointing up (north) before rotation is applied.
        val arrow = android.graphics.Path().apply {
            moveTo(cx, cy - 20f)
            lineTo(cx + 15f, cy + 14f)
            lineTo(cx, cy + 5f)
            lineTo(cx - 15f, cy + 14f)
            close()
        }
        val arrowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                cx, cy - 20f, cx, cy + 14f,
                android.graphics.Color.parseColor("#FF8A3D"), android.graphics.Color.parseColor("#FF6600"),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(arrow, arrowPaint)

        com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(bmp)
    }
}

/** Same puck construction as [rememberPuckIcon] but blue and slightly smaller - the harsh-acceleration marker. */
@Composable
private fun rememberAccelArrowIcon(): com.google.android.gms.maps.model.BitmapDescriptor {
    return remember {
        val size = 80
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(90, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(5f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(cx - 18f, cy - 12f, cx + 18f, cy + 22f, shadowPaint)
        canvas.drawCircle(cx, cy, 21f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        val arrow = android.graphics.Path().apply {
            moveTo(cx, cy - 16f)
            lineTo(cx + 12f, cy + 11f)
            lineTo(cx, cy + 4f)
            lineTo(cx - 12f, cy + 11f)
            close()
        }
        val arrowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                cx, cy - 16f, cx, cy + 11f,
                android.graphics.Color.parseColor("#5CA7FF"), android.graphics.Color.parseColor("#2F8CFF"),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(arrow, arrowPaint)
        com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(bmp)
    }
}

@Composable
fun SpeedTrackMap(
    geo: TrackGeo,
    modifier: Modifier = Modifier,
    height: Dp = 280.dp,
    /** Absolute ride-time to show the marker at; null = no marker (plain track view). */
    scrubTimeMs: Long? = null,
    /** While true, the camera smoothly tracks the marker, heading-up with a slight tilt. */
    follow: Boolean = false,
    /** Detected harsh-acceleration moments (lat, lon, direction of travel) - shown as a pulsing blue arrow. */
    harshAccelPoints: List<Triple<Double, Double, Float>> = emptyList(),
    showLegend: Boolean = true,
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(Ktm.RadiusCard))
                .border(1.dp, Ktm.Bezel, RoundedCornerShape(Ktm.RadiusCard)),
        ) {
            if (mapsKeyPresent() && isPlayServicesUsable()) GoogleTrackMap(geo, scrubTimeMs, follow, harshAccelPoints)
            else CanvasTrackMap(geo, scrubTimeMs, harshAccelPoints)
        }
        if (!mapsKeyPresent()) {
            Text(
                "Map preview — add MAPS_API_KEY to local.properties for the full Google map with traffic.",
                color = Ktm.Dim, fontFamily = Barlow, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (showLegend) {
            Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(Color(0xFFFF4B3A), "75+")
                LegendDot(Color(0xFF39D98A), "45-75")
                LegendDot(Color(0xFF4B9BFF), "5-45")
                LegendDot(TrafficAmber, "CRAWL")
                LegendDot(TrafficSignalRed, "SIGNAL")
                LegendDot(AccelBlue, "HARSH ACCEL")
            }
        }
    }
}

@Composable
private fun isPlayServicesUsable(): Boolean {
    val context = LocalContext.current
    return remember {
        com.google.android.gms.common.GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text("  $label", color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 10.sp)
    }
}

/** Soft neon glow: a wide faint pass + a mid pass + a crisp bright core, stacked bottom-to-top. */
@Composable
private fun GlowPolyline(points: List<com.google.android.gms.maps.model.LatLng>, color: Color, zIndex: Float = 1f) {
    if (points.size < 2) return
    com.google.maps.android.compose.Polyline(points = points, color = color.copy(alpha = 0.18f), width = 34f, zIndex = zIndex)
    com.google.maps.android.compose.Polyline(points = points, color = color.copy(alpha = 0.40f), width = 20f, zIndex = zIndex + 0.01f)
    com.google.maps.android.compose.Polyline(points = points, color = color.copy(alpha = 0.95f), width = 9f, zIndex = zIndex + 0.02f)
}

@Composable
private fun GoogleTrackMap(
    geo: TrackGeo,
    scrubTimeMs: Long?,
    follow: Boolean,
    harshAccelPoints: List<Triple<Double, Double, Float>>,
) {
    val pts = remember(geo) {
        List(geo.lats.size) { com.google.android.gms.maps.model.LatLng(geo.lats[it], geo.lons[it]) }
    }
    val bounds = remember(geo) {
        val b = com.google.android.gms.maps.model.LatLngBounds.builder()
        pts.forEach { b.include(it) }
        b.build()
    }
    val cameraState = com.google.maps.android.compose.rememberCameraPositionState()
    LaunchedEffect(geo) {
        cameraState.move(com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(bounds, 60))
    }

    val scrub = rememberEasedScrub(geo, scrubTimeMs)
    val displayLat = scrub?.lat ?: 0.0
    val displayLon = scrub?.lon ?: 0.0
    val displayHeading = scrub?.headingDeg ?: 0f

    // Follow mode: every frame the eased position/heading advances, mirror it
    // straight onto the camera (SideEffect, no extra GoogleMap-level
    // animate() layered on top - that was the bug: animate() calls were being
    // cancelled and restarted on almost every frame, so the camera never
    // actually got anywhere and looked frozen while the marker moved on).
    if (follow && scrub != null) {
        SideEffect {
            cameraState.position = com.google.android.gms.maps.model.CameraPosition.Builder()
                .target(com.google.android.gms.maps.model.LatLng(displayLat, displayLon))
                .zoom(maxOf(cameraState.position.zoom, 17f))
                .bearing(displayHeading)
                .tilt(55f)
                .build()
        }
    }

    // Ripple: a ring expanding outward from the marker and fading, looped -
    // reads as "live position", not a static pin. Accel markers pulse on the
    // same clock (breathing alpha) so both feel part of one motion language.
    //
    // Kept as State objects and passed DOWN rather than read here with `by`:
    // these tick 60 times a second, and a read in this function's body would
    // invalidate the whole map content - the speed-run loop over every track
    // point, every traffic glow, every marker - on every frame of the
    // animation. Read inside the small composables below instead, so an
    // animating ripple only ever redraws the ripple.
    val transition = rememberInfiniteTransition(label = "ripple")
    val ripplePhase = transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing)),
        label = "ripplePhase",
    )
    val pulseAlpha = transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = androidx.compose.animation.core.FastOutLinearInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    // The colour-run split depends only on the track, never on the scrub or the
    // animation clock - so compute it once per ride instead of rebuilding every
    // run (and every sublist) on each recomposition.
    val speedRuns = remember(geo, pts) { buildSpeedRuns(pts, geo) }

    com.google.maps.android.compose.GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraState,
        properties = com.google.maps.android.compose.MapProperties(isTrafficEnabled = true),
        uiSettings = com.google.maps.android.compose.MapUiSettings(zoomControlsEnabled = false),
    ) {
        // One polyline per consecutive same-speed-bucket run. The traveled
        // portion (behind the marker) draws at full strength; the stretch
        // still ahead is dimmed, so the completed trail visually stands out.
        SpeedTrail(speedRuns, scrub?.fraction)
        // Traffic stretches: a glowing overlay hugging the exact road segment
        // (amber = crawl, red-orange = stopped/waiting at a likely signal) -
        // built only from real GPS points on this route, never a floating blob.
        for (tr in geo.trafficRanges) {
            val lo = tr.range.first.coerceIn(0, pts.size - 1)
            val hi = tr.range.last.coerceIn(0, pts.size - 1)
            if (hi - lo < 1) continue
            GlowPolyline(pts.subList(lo, hi + 1), trafficColor(tr.kind), zIndex = 1f)
        }
        // Harsh-acceleration moments: a pulsing glow behind a directional blue
        // arrow marker (bigger and higher-zIndex than the old plain dot, which
        // read as invisible against the traffic glow).
        for ((lat, lon, heading) in harshAccelPoints) {
            AccelMoment(
                center = com.google.android.gms.maps.model.LatLng(lat, lon),
                heading = heading,
                cameraState = cameraState,
                pulseAlpha = pulseAlpha,
            )
        }
        // Start / end.
        EndpointDot(pts.first(), Color(0xFF39D98A), cameraState)
        EndpointDot(pts.last(), Color(0xFFFF4B3A), cameraState)
        // Smooth, heading-oriented marker with an expanding ripple, radius
        // scaled to zoom so it reads the same at any map scale.
        if (scrub != null) {
            val pos = com.google.android.gms.maps.model.LatLng(displayLat, displayLon)
            RippleRing(pos, cameraState, ripplePhase)
            val markerState = com.google.maps.android.compose.rememberMarkerState(position = pos)
            markerState.position = pos
            com.google.maps.android.compose.Marker(
                state = markerState,
                icon = rememberPuckIcon(),
                rotation = displayHeading,
                flat = true,
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                zIndex = 2f,
            )
        }
    }
}

/**
 * One drawn stretch of the route: consecutive points sharing a speed colour.
 * [endFraction] is where the run ends along the track, which is all the trail
 * needs to decide "already travelled" (full strength) vs "still ahead" (dim).
 */
private class SpeedRun(
    val points: List<com.google.android.gms.maps.model.LatLng>,
    val color: Color,
    val endFraction: Float,
)

private fun buildSpeedRuns(
    pts: List<com.google.android.gms.maps.model.LatLng>,
    geo: TrackGeo,
): List<SpeedRun> {
    if (pts.size < 2) return emptyList()
    val last = (pts.size - 1).coerceAtLeast(1)
    val runs = ArrayList<SpeedRun>()
    var runStart = 0
    var runColor = speedTrackColor(geo.speedsKmh[0])
    for (k in 1 until pts.size) {
        val c = speedTrackColor(geo.speedsKmh[k])
        if (c != runColor || k == pts.size - 1) {
            runs += SpeedRun(pts.subList(runStart, k + 1), runColor, k.toFloat() / last)
            runStart = k
            runColor = c
        }
    }
    return runs
}

/**
 * The route trail. Only the dim/bright boundary moves as the scrub advances,
 * and each [SpeedRun]'s points list is a stable sublist view, so the Polylines
 * whose alpha didn't change skip recomposition entirely.
 */
@Composable
private fun SpeedTrail(runs: List<SpeedRun>, fraction: Float?) {
    for (run in runs) {
        val alpha = if (fraction == null || run.endFraction <= fraction) 1f else 0.32f
        com.google.maps.android.compose.Polyline(
            points = run.points, color = run.color.copy(alpha = alpha), width = 10f,
        )
    }
}

/**
 * A harsh-acceleration moment. Reads the pulse clock and the camera zoom in
 * ITS own scope, so neither the breathing glow nor a camera move invalidates
 * the rest of the map content.
 */
@Composable
private fun AccelMoment(
    center: com.google.android.gms.maps.model.LatLng,
    heading: Float,
    cameraState: com.google.maps.android.compose.CameraPositionState,
    pulseAlpha: androidx.compose.runtime.State<Float>,
) {
    val r = zoomRadiusM(cameraState.position.zoom, 1.6f)
    com.google.maps.android.compose.Circle(
        center = center, radius = r * 2.2, fillColor = AccelBlue.copy(alpha = pulseAlpha.value * 0.30f),
        strokeColor = Color.Transparent, zIndex = 2.3f,
    )
    com.google.maps.android.compose.Marker(
        state = com.google.maps.android.compose.rememberMarkerState(position = center),
        icon = rememberAccelArrowIcon(),
        rotation = heading,
        flat = true,
        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
        zIndex = 2.4f,
    )
}

/** Start/end dot, sized to zoom - the zoom read stays in here. */
@Composable
private fun EndpointDot(
    center: com.google.android.gms.maps.model.LatLng,
    color: Color,
    cameraState: com.google.maps.android.compose.CameraPositionState,
) {
    com.google.maps.android.compose.Circle(
        center = center, radius = zoomRadiusM(cameraState.position.zoom, 0.6f),
        fillColor = color, strokeColor = Color.White, strokeWidth = 2f,
    )
}

/**
 * The expanding "live position" ring. This is the one thing on the map that
 * genuinely animates every frame, and now it is the only thing that recomposes
 * every frame.
 */
@Composable
private fun RippleRing(
    center: com.google.android.gms.maps.model.LatLng,
    cameraState: com.google.maps.android.compose.CameraPositionState,
    ripplePhase: androidx.compose.runtime.State<Float>,
) {
    val phase = ripplePhase.value
    com.google.maps.android.compose.Circle(
        center = center, radius = zoomRadiusM(cameraState.position.zoom, 1f) * (1.0 + phase * 1.6),
        fillColor = Ktm.Orange.copy(alpha = (1f - phase) * 0.35f),
        strokeColor = Color.Transparent, zIndex = 1.5f,
    )
}

/** Meters that render as a constant ~10 px dot at any zoom (halves per zoom level). */
private fun zoomRadiusM(zoom: Float, scale: Float): Double =
    (10.0 * 2.0.pow((16f - zoom).toDouble()) * scale).coerceIn(2.0, 400.0)

/**
 * Every recorded ride's route drawn together (thin base line) with detected
 * traffic stretches highlighted on top - "the actual roads where you hit
 * traffic", not just a floating heat cloud. An optional violet layer paints
 * cells the [com.navigator.app.analytics.TrafficPredictor] rates likely for
 * the current day/time, from the rider's own history.
 */
@Composable
fun MultiRideTrafficMap(
    rides: List<TrackGeo>,
    predictedCells: List<com.navigator.app.analytics.TrafficPredictor.CellPrediction> = emptyList(),
    /** Historical density, built ONLY from real GPS points that were on a detected traffic stretch - hugs the road, not a floating cloud. */
    heatPoints: List<com.google.maps.android.heatmaps.WeightedLatLng> = emptyList(),
    modifier: Modifier = Modifier,
    height: Dp = 340.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .border(1.dp, Ktm.Bezel, RoundedCornerShape(Ktm.RadiusCard)),
    ) {
        if (mapsKeyPresent() && rides.isNotEmpty() && isPlayServicesUsable()) GoogleMultiRideMap(rides, predictedCells, heatPoints)
        else CanvasMultiRideMap(rides, predictedCells)
    }
}

private val RouteBase = Color(0xFF4B9BFF)

@Composable
private fun GoogleMultiRideMap(
    rides: List<TrackGeo>,
    predictedCells: List<com.navigator.app.analytics.TrafficPredictor.CellPrediction>,
    heatPoints: List<com.google.maps.android.heatmaps.WeightedLatLng>,
) {
    val bounds = remember(rides) {
        val b = com.google.android.gms.maps.model.LatLngBounds.builder()
        var any = false
        rides.forEach { r -> for (i in r.lats.indices) { b.include(com.google.android.gms.maps.model.LatLng(r.lats[i], r.lons[i])); any = true } }
        if (any) b.build() else null
    }
    val cameraState = com.google.maps.android.compose.rememberCameraPositionState()
    LaunchedEffect(rides) {
        bounds?.let { cameraState.move(com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(it, 50)) }
    }
    // Tight radius so the glow hugs the road it was measured on instead of
    // bleeding into the next block over - built only from points inside a
    // detected traffic range, so "on the path" is true by construction.
    val heatProvider = remember(heatPoints) {
        if (heatPoints.isEmpty()) null
        else com.google.maps.android.heatmaps.HeatmapTileProvider.Builder()
            .weightedData(heatPoints).radius(25).opacity(0.7)
            .build()
    }
    // Every ride's LatLng list, built once. This used to be allocated inside
    // the map content below - which reads the pulsing glow clock - so every
    // point of every recorded ride was re-allocated 60 times a second.
    val ridePts = remember(rides) {
        rides.map { r -> List(r.lats.size) { com.google.android.gms.maps.model.LatLng(r.lats[it], r.lons[it]) } }
    }
    // State, not `by`: read it down in PredictedCell so the pulse doesn't
    // invalidate the whole map content (see GoogleTrackMap for the same fix).
    val transition = rememberInfiniteTransition(label = "predict-glow")
    val glowPulse = transition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "glowPulse",
    )
    com.google.maps.android.compose.GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraState,
        uiSettings = com.google.maps.android.compose.MapUiSettings(zoomControlsEnabled = false),
    ) {
        heatProvider?.let { com.google.maps.android.compose.TileOverlay(tileProvider = it) }
        for ((rideIndex, ride) in rides.withIndex()) {
            val pts = ridePts[rideIndex]
            if (pts.size < 2) continue
            com.google.maps.android.compose.Polyline(points = pts, color = RouteBase.copy(alpha = 0.35f), width = 6f, zIndex = 0.5f)
            for (tr in ride.trafficRanges) {
                val lo = tr.range.first.coerceIn(0, pts.size - 1)
                val hi = tr.range.last.coerceIn(0, pts.size - 1)
                if (hi - lo < 1) continue
                GlowPolyline(pts.subList(lo, hi + 1), trafficColor(tr.kind), zIndex = 1f)
            }
        }
        // Predicted-now: a red glow (soft outer halo + brighter core), pulsing
        // gently, sized/shaded by confidence - never a plain flat dot.
        val cellMeters = com.navigator.app.analytics.TrafficPredictor.CELL_DEG * 111_000.0 / 2
        for (c in predictedCells) {
            PredictedCell(c, cellMeters, glowPulse)
        }
    }
}

@Composable
private fun CanvasMultiRideMap(
    rides: List<TrackGeo>,
    predictedCells: List<com.navigator.app.analytics.TrafficPredictor.CellPrediction>,
) {
    Canvas(modifier = Modifier.fillMaxSize().background(Ktm.ScreenDeep)) {
        val allLats = rides.flatMap { it.lats.toList() }
        val allLons = rides.flatMap { it.lons.toList() }
        if (allLats.size < 2) return@Canvas
        val minLat = allLats.min(); val maxLat = allLats.max()
        val minLon = allLons.min(); val maxLon = allLons.max()
        val kx = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = ((maxLon - minLon) * kx).coerceAtLeast(1e-6)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-6)
        val scale = minOf(size.width * 0.9 / spanX, size.height * 0.9 / spanY)
        val ox = (size.width - spanX * scale) / 2
        val oy = (size.height - spanY * scale) / 2
        fun proj(lat: Double, lon: Double) = Offset(
            (ox + (lon * kx - minLon * kx) * scale).toFloat(),
            (oy + (maxLat - lat) * scale).toFloat(),
        )
        for (c in predictedCells) {
            drawCircle(
                PredictedGlowRed.copy(alpha = (0.10f + c.probability * 0.2f)), radius = 18.dp.toPx(), center = proj(c.lat, c.lon),
            )
            drawCircle(
                PredictedGlowRed.copy(alpha = (0.25f + c.probability * 0.45f)), radius = 9.dp.toPx(), center = proj(c.lat, c.lon),
            )
        }
        for (ride in rides) {
            for (k in 1 until ride.lats.size) {
                drawLine(
                    RouteBase.copy(alpha = 0.3f),
                    proj(ride.lats[k - 1], ride.lons[k - 1]), proj(ride.lats[k], ride.lons[k]),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            for (tr in ride.trafficRanges) {
                val color = trafficColor(tr.kind)
                for (k in (tr.range.first + 1)..tr.range.last) {
                    if (k in 1 until ride.lats.size) drawGlowLine(
                        proj(ride.lats[k - 1], ride.lons[k - 1]), proj(ride.lats[k], ride.lons[k]), color, 4.dp.toPx(),
                    )
                }
            }
        }
    }
}

/**
 * One predicted-traffic cell: soft outer halo plus a brighter core, breathing
 * on the shared glow clock. The clock is read here and nowhere above, so the
 * pulse redraws these circles and nothing else.
 */
@Composable
private fun PredictedCell(
    cell: com.navigator.app.analytics.TrafficPredictor.CellPrediction,
    cellMeters: Double,
    glowPulse: androidx.compose.runtime.State<Float>,
) {
    val pulse = glowPulse.value
    val center = com.google.android.gms.maps.model.LatLng(cell.lat, cell.lon)
    com.google.maps.android.compose.Circle(
        center = center, radius = cellMeters * 1.8,
        fillColor = PredictedGlowRed.copy(alpha = (0.10f + cell.probability * 0.25f) * pulse),
        strokeColor = Color.Transparent, zIndex = 1.9f,
    )
    com.google.maps.android.compose.Circle(
        center = center, radius = cellMeters,
        fillColor = PredictedGlowRed.copy(alpha = (0.25f + cell.probability * 0.55f) * pulse),
        strokeColor = PredictedGlowRed.copy(alpha = 0.7f), strokeWidth = 1f, zIndex = 2f,
    )
}

/** Blurred wide pass beneath a crisp core - the canvas-fallback equivalent of [GlowPolyline]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlowLine(
    p1: Offset, p2: Offset, color: Color, coreWidthPx: Float,
) {
    drawContext.canvas.nativeCanvas.let { nc ->
        val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.copy(alpha = 0.6f).toArgb()
            strokeWidth = coreWidthPx * 3.2f
            strokeCap = android.graphics.Paint.Cap.ROUND
            maskFilter = android.graphics.BlurMaskFilter(coreWidthPx * 1.3f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        nc.drawLine(p1.x, p1.y, p2.x, p2.y, glow)
    }
    drawLine(color, p1, p2, strokeWidth = coreWidthPx, cap = StrokeCap.Round)
}

@Composable
private fun CanvasTrackMap(geo: TrackGeo, scrubTimeMs: Long?, harshAccelPoints: List<Triple<Double, Double, Float>> = emptyList()) {
    val transition = rememberInfiniteTransition(label = "ripple-canvas")
    val ripplePhase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing)),
        label = "ripplePhaseCanvas",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = androidx.compose.animation.core.FastOutLinearInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "pulseAlphaCanvas",
    )
    val scrub = rememberEasedScrub(geo, scrubTimeMs)

    Canvas(modifier = Modifier.fillMaxSize().background(Ktm.ScreenDeep)) {
        val n = geo.lats.size
        if (n < 2) return@Canvas
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (i in 0 until n) {
            if (geo.lats[i] < minLat) minLat = geo.lats[i]
            if (geo.lats[i] > maxLat) maxLat = geo.lats[i]
            if (geo.lons[i] < minLon) minLon = geo.lons[i]
            if (geo.lons[i] > maxLon) maxLon = geo.lons[i]
        }
        val kx = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = ((maxLon - minLon) * kx).coerceAtLeast(1e-6)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-6)
        val scale = minOf(size.width * 0.9 / spanX, size.height * 0.9 / spanY)
        val ox = (size.width - spanX * scale) / 2
        val oy = (size.height - spanY * scale) / 2
        fun projLL(lat: Double, lon: Double) = Offset(
            (ox + (lon * kx - minLon * kx) * scale).toFloat(),
            (oy + (maxLat - lat) * scale).toFloat(),
        )
        fun proj(i: Int) = projLL(geo.lats[i], geo.lons[i])

        for (k in 1 until n) {
            val alpha = if (scrub == null || k.toFloat() / (n - 1).coerceAtLeast(1) <= scrub.fraction) 1f else 0.32f
            drawLine(speedTrackColor(geo.speedsKmh[k]).copy(alpha = alpha), proj(k - 1), proj(k), strokeWidth = 3.dp.toPx())
        }
        for (tr in geo.trafficRanges) {
            val color = trafficColor(tr.kind)
            for (k in (tr.range.first + 1)..tr.range.last) {
                if (k in 1 until n) drawGlowLine(proj(k - 1), proj(k), color, 5.dp.toPx())
            }
        }
        for ((lat, lon, heading) in harshAccelPoints) {
            val m = projLL(lat, lon)
            drawCircle(AccelBlue.copy(alpha = pulseAlpha * 0.35f), 16.dp.toPx(), m)
            rotate(heading, m) {
                val path = Path().apply {
                    moveTo(m.x, m.y - 8.dp.toPx())
                    lineTo(m.x + 6.dp.toPx(), m.y + 5.dp.toPx())
                    lineTo(m.x, m.y + 1.dp.toPx())
                    lineTo(m.x - 6.dp.toPx(), m.y + 5.dp.toPx())
                    close()
                }
                drawCircle(Color.White, 8.dp.toPx(), m)
                drawPath(path, AccelBlue.copy(alpha = pulseAlpha))
            }
        }
        drawCircle(Color(0xFF39D98A), 5.dp.toPx(), proj(0))
        drawCircle(Color(0xFFFF4B3A), 5.dp.toPx(), proj(n - 1))
        if (scrub != null) {
            val m = projLL(scrub.lat, scrub.lon)
            val heading = scrub.headingDeg
            // Ripple ring.
            drawCircle(Ktm.Orange.copy(alpha = (1f - ripplePhase) * 0.4f), 6.dp.toPx() + 14.dp.toPx() * ripplePhase, m)
            // Heading chevron, rotated to bearing (0 = pointing up/north).
            rotate(heading, m) {
                val path = Path().apply {
                    moveTo(m.x, m.y - 9.dp.toPx())
                    lineTo(m.x + 7.dp.toPx(), m.y + 6.dp.toPx())
                    lineTo(m.x, m.y + 2.dp.toPx())
                    lineTo(m.x - 7.dp.toPx(), m.y + 6.dp.toPx())
                    close()
                }
                drawCircle(Color.White, 9.dp.toPx(), m)
                drawPath(path, Ktm.Orange)
            }
        }
    }
}
