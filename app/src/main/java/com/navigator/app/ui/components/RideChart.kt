package com.navigator.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.app.analytics.RideEventDetector
import com.navigator.app.logging.AppLogger
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ride-analytics chart components: smooth (Catmull-Rom) curves, a glowing
 * highlight along the top edge, peak/dip markers that reveal their value on
 * tap, pinch-to-zoom into a time window (shared across every chart in the
 * stack), and a floating value bubble that follows a touch/drag exactly
 * where it lands - all pure Canvas, no chart library.
 */
object RideChartColors {
    val Speed = Color(0xFF3D8BFF)
    val Accel = Color(0xFFC48310)
    val Altitude = Color(0xFF16B074)
    val Rough = Color(0xFFA05CF0)
    val Brake = Color(0xFFE8483A)
}

/** One series: values vs time. */
class ChartSeries(
    val label: String,
    val unit: String,
    val color: Color,
    val timesMs: LongArray,
    val values: FloatArray,
)

/** 0f..1f fraction of the FULL ride duration currently visible in every chart - shared so all charts zoom together. */
typealias ZoomRange = ClosedFloatingPointRange<Float>
val FULL_ZOOM: ZoomRange = 0f..1f

private data class Extremum(val index: Int, val isPeak: Boolean)

/** Absolute prominence a bump must clear to count as a marker - a fixed 18% of the FULL series' range, independent of zoom. */
private fun seriesProminence(series: ChartSeries): Float {
    var lo = series.values[0]; var hi = series.values[0]
    for (v in series.values) { if (v < lo) lo = v; if (v > hi) hi = v }
    return (hi - lo) * 0.18f
}

/** Minimum sample gap between markers, derived from a fixed TIME gap (20s) so it doesn't collapse when zoomed in. */
private fun seriesMinSeparation(series: ChartSeries, minSeconds: Long = 20L): Int {
    val n = series.timesMs.size
    if (n < 2) return 3
    val avgIntervalMs = (series.timesMs.last() - series.timesMs.first()).toFloat() / (n - 1).coerceAtLeast(1)
    if (avgIntervalMs <= 0f) return 3
    return ((minSeconds * 1000f) / avgIntervalMs).toInt().coerceAtLeast(3)
}

private class CubicSeg(val c1x: Float, val c1y: Float, val c2x: Float, val c2y: Float, val x: Float, val y: Float)

/** Uniform Catmull-Rom -> cubic-bezier control points (tension 1/6, the standard chart-library smoothing) through [points]. */
private fun smoothSegments(points: List<Offset>): List<CubicSeg> {
    if (points.size < 2) return emptyList()
    val out = ArrayList<CubicSeg>(points.size)
    for (i in 0 until points.size - 1) {
        val p0 = points[max(0, i - 1)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[min(points.size - 1, i + 2)]
        out.add(
            CubicSeg(
                p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                p2.x, p2.y,
            )
        )
    }
    return out
}

private fun pathFromSegments(start: Offset, segments: List<CubicSeg>): Path {
    val path = Path()
    path.moveTo(start.x, start.y)
    for (s in segments) path.cubicTo(s.c1x, s.c1y, s.c2x, s.c2y, s.x, s.y)
    return path
}

/** Convenience for the combined chart, which only needs the stroke path. */
private fun smoothPath(points: List<Offset>): Path {
    if (points.isEmpty()) return Path()
    if (points.size < 3) {
        val p = Path(); p.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) p.lineTo(points[i].x, points[i].y)
        return p
    }
    return pathFromSegments(points[0], smoothSegments(points))
}

/**
 * Local maxima/minima with enough vertical prominence to matter, spaced apart
 * so nearby wiggles don't all light up. [prominence] is an ABSOLUTE value
 * (not a fraction of whatever's currently visible) - it must be computed from
 * the FULL series once via [seriesProminence], not the zoomed-in window's own
 * min/max. Using the local visible range made every zoom level relatively
 * noisier the more you zoomed (a 15% wiggle within a narrow zoomed range can
 * be a fraction of a percent of the whole ride), so markers got denser and
 * denser while zooming until nearly every small oscillation qualified -
 * which is why the scrub cursor always seemed to land "near a marker": with
 * this many markers packed in, almost anywhere you tapped was near one.
 */
private fun findExtrema(values: FloatArray, minSeparation: Int, prominence: Float): List<Extremum> {
    if (values.size < 3 || prominence <= 0f) return emptyList()
    var lo = values[0]; var hi = values[0]
    for (v in values) { if (v < lo) lo = v; if (v > hi) hi = v }
    val candidates = ArrayList<Extremum>()
    for (i in 1 until values.size - 1) {
        val v = values[i]
        if (v >= values[i - 1] && v >= values[i + 1] && v - min(values[i - 1], values[i + 1]) >= prominence) {
            candidates.add(Extremum(i, true))
        } else if (v <= values[i - 1] && v <= values[i + 1] && max(values[i - 1], values[i + 1]) - v >= prominence) {
            candidates.add(Extremum(i, false))
        }
    }
    // Greedy thin: keep the most prominent extremum within each separation window.
    val kept = ArrayList<Extremum>()
    var i = 0
    while (i < candidates.size) {
        var best = candidates[i]
        var bestScore = abs(values[best.index] - (if (best.isPeak) lo else hi))
        var j = i + 1
        while (j < candidates.size && candidates[j].index - candidates[i].index < minSeparation) {
            val score = abs(values[candidates[j].index] - (if (candidates[j].isPeak) lo else hi))
            if (score > bestScore) { best = candidates[j]; bestScore = score }
            j++
        }
        kept.add(best)
        i = j
    }
    return kept
}

/**
 * Chart interaction. Two attempts at hand-rolled direction detection both
 * still let the chart randomly eat the page's vertical scroll ("stuck at
 * particular points" - a diagonal-ish touch or a fast flick could cross the
 * horizontal slop check first even on an intended vertical swipe). This
 * version stops guessing and uses the same primitives Compose's own Slider
 * and HorizontalPager use to resolve exactly this race:
 * [awaitHorizontalTouchSlopOrCancellation] only fires once movement is
 * unambiguously horizontal, and hands back null - untouched, unconsumed -
 * the moment a vertical drag wins, so the parent LazyColumn's own scroll
 * detector (which gets the same event on the same pass) is free to claim it.
 * Two fingers landing at any point switches to pinch-zoom instead.
 */
private fun Modifier.chartGestures(
    visibleRange: ZoomRange,
    onVisibleRangeChange: (ZoomRange) -> Unit,
    onScrub: (Float) -> Unit,
    onTap: (xPx: Float, widthPx: Float) -> Unit = { _, _ -> },
): Modifier = composed {
    val latestRange = rememberUpdatedState(visibleRange)
    pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        var lastUpAtMs = 0L
        var lastUpX = -1f

        fun scrubAt(x: Float, width: Float) {
            val range = latestRange.value
            val windowWidth = range.endInclusive - range.start
            val frac = (x / width).coerceIn(0f, 1f)
            onScrub(range.start + frac * windowWidth)
        }

        fun applyZoom(zoom: Float, centroidX: Float, width: Float) {
            if (abs(zoom - 1f) <= 0.003f) return
            val range = latestRange.value
            val windowWidth = range.endInclusive - range.start
            val touchFrac = (centroidX / width).coerceIn(0f, 1f)
            val centroidTime = range.start + touchFrac * windowWidth
            val newWidth = (windowWidth / zoom).coerceIn(0.02f, 1f)
            var newStart = centroidTime - touchFrac * newWidth
            var newEnd = newStart + newWidth
            if (newStart < 0f) { newEnd -= newStart; newStart = 0f }
            if (newEnd > 1f) { newStart -= (newEnd - 1f); newEnd = 1f }
            onVisibleRangeChange(newStart.coerceAtLeast(0f)..newEnd.coerceAtMost(1f))
        }

        awaitEachGesture {
            try {
                val down = awaitFirstDown(requireUnconsumed = false)
                val width = size.width.toFloat()
                AppLogger.log("Chart", "gesture down")

                // Peek (without consuming) until one of three things resolves:
                // a second finger joins (pinch), the finger lifts before any
                // slop at all (a tap), or SOME slop is crossed (hand off to
                // the dedicated horizontal-vs-vertical arbiter below).
                var pinching = false
                var releasedAsTap = false
                var lastX = down.position.x
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) { pinching = true; break }
                    if (pressed.isEmpty()) { releasedAsTap = true; break }
                    val c = pressed[0]
                    lastX = c.position.x
                    if (abs(c.position.x - down.position.x) > slop || abs(c.position.y - down.position.y) > slop) break
                }

                when {
                    pinching -> {
                        AppLogger.log("Chart", "gesture -> pinch")
                        var remaining: androidx.compose.ui.input.pointer.PointerInputChange? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size < 2) { remaining = pressed.firstOrNull(); break }
                            applyZoom(event.calculateZoom(), event.calculateCentroid().x, width)
                            event.changes.forEach { it.consume() }
                        }
                        // A pinch commonly ends with one finger still down -
                        // keep scrubbing with it instead of going dead until a
                        // full release + fresh tap (the R51 freeze: exactly
                        // this sequence stopped responding to further input).
                        if (remaining != null) {
                            AppLogger.log("Chart", "gesture pinch -> continue as drag with remaining finger")
                            var id = remaining.id
                            while (true) {
                                val event = awaitPointerEvent()
                                val stillPressed = event.changes.firstOrNull { it.id == id && it.pressed }
                                    ?: event.changes.firstOrNull { it.pressed }?.also { id = it.id }
                                    ?: break
                                scrubAt(stillPressed.position.x, width)
                                stillPressed.consume()
                            }
                        }
                        AppLogger.log("Chart", "gesture pinch end")
                    }
                    releasedAsTap -> {
                        AppLogger.log("Chart", "gesture -> tap at x=$lastX")
                        scrubAt(lastX, width)
                        onTap(lastX, width)
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastUpAtMs < 260 && abs(lastX - lastUpX) < slop * 4) {
                            onVisibleRangeChange(FULL_ZOOM)
                            lastUpAtMs = 0L
                        } else {
                            lastUpAtMs = now
                            lastUpX = lastX
                        }
                    }
                    else -> {
                        // Some slop was crossed - defer the horizontal-vs-
                        // vertical call to Compose's own arbiter (same one
                        // Slider/Pager use). It returns null the instant a
                        // scrollable ancestor claims the vertical component
                        // instead, so we never fight the page's own scroll.
                        AppLogger.log("Chart", "gesture -> awaiting horizontal-vs-vertical decision")
                        val horizontalStart = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        if (horizontalStart != null) {
                            AppLogger.log("Chart", "gesture -> horizontal drag claimed")
                            scrubAt(horizontalStart.position.x, width)
                            horizontalDrag(horizontalStart.id) { change ->
                                scrubAt(change.position.x, width)
                                change.consume()
                            }
                            AppLogger.log("Chart", "gesture horizontal drag end")
                        } else {
                            AppLogger.log("Chart", "gesture -> ceded to vertical scroll")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // cooperative cancellation - must propagate, never swallow
            } catch (e: Exception) {
                // Whatever else went wrong, don't let it take the whole
                // detector down with it - awaitEachGesture's loop must reach
                // its next iteration so the NEXT touch on this chart still
                // works. A crash inside one gesture used to permanently kill
                // all future input on that chart until the screen reopened.
                AppLogger.log("Chart", "!! gesture handler threw: $e")
            }
        }
    }
}

/**
 * Horizontal pan control for when a chart is zoomed in: the full ride
 * duration as a thin track with the current [visibleRange] highlighted;
 * drag the highlighted segment to slide the window without changing its
 * width (pinching again still changes the zoom level). Hidden at full zoom.
 */
@Composable
fun ZoomScrollbar(visibleRange: ZoomRange, onVisibleRangeChange: (ZoomRange) -> Unit, modifier: Modifier = Modifier) {
    if (visibleRange == FULL_ZOOM) return
    val latestRange = rememberUpdatedState(visibleRange)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Ktm.Surface)
            .border(1.dp, Ktm.Border, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                var dragStartX = 0f
                var dragStartRange = FULL_ZOOM
                detectDragGesturesForScrollbar(
                    onDragStart = { pos -> dragStartX = pos; dragStartRange = latestRange.value },
                    onDrag = { pos ->
                        val width = latestRange.value.endInclusive - latestRange.value.start
                        val deltaFrac = (pos - dragStartX) / size.width
                        var newStart = (dragStartRange.start + deltaFrac).coerceIn(0f, 1f - width)
                        onVisibleRangeChange(newStart..(newStart + width))
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(22.dp)) {
            val x0 = visibleRange.start * size.width
            val x1 = visibleRange.endInclusive * size.width
            drawRoundRect(
                Ktm.Orange.copy(alpha = 0.85f),
                topLeft = Offset(x0, 3.dp.toPx()),
                size = androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(6.dp.toPx()), size.height - 6.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )
        }
    }
}

/** Minimal horizontal-only drag helper (raw x positions) used by [ZoomScrollbar]. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDragGesturesForScrollbar(
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onDragStart(down.position.x)
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            val c = pressed[0]
            c.consume()
            onDrag(c.position.x)
        }
    }
}

@Composable
fun ChartCard(
    series: ChartSeries,
    scrubT: Float,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    zeroLine: Boolean = false,
    events: List<RideEventDetector.RideEvent> = emptyList(),
    roughSpans: List<RideEventDetector.RoughSpan> = emptyList(),
    cornerTag: String? = null,
    visibleRange: ZoomRange = FULL_ZOOM,
    onVisibleRangeChange: (ZoomRange) -> Unit = {},
) {
    val t0 = series.timesMs.first()
    val t1 = series.timesMs.last()
    // Interpolated, not snapped to the nearest sample - must match the
    // in-chart scrub bubble exactly (a mismatch between this header and the
    // bubble was the earlier "6.5 vs 12.0" bug).
    val current = interpolateSeriesValue(series, (t0 + ((t1 - t0) * scrubT)).toLong())
    var tappedExtremum by remember(series) { mutableStateOf(-1) }
    val bubblePaints = remember { BubblePaints() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.ScreenDeep)
            .border(1.dp, Ktm.Bezel, RoundedCornerShape(Ktm.RadiusCard))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Eyebrow(series.label, color = Ktm.Dim, fontSize = 11)
            if (cornerTag != null) {
                Text("  $cornerTag", color = Ktm.Dim2, fontFamily = JetBrainsMono, fontSize = 9.sp)
            }
            Spacer(Modifier.weight(1f))
            if (visibleRange != FULL_ZOOM) {
                Text(
                    "ZOOMED · DOUBLE-TAP TO RESET", color = Ktm.Orange, fontFamily = JetBrainsMono, fontSize = 8.sp,
                )
                Spacer(Modifier.padding(start = 6.dp))
            }
            Text(formatValue(current), color = series.color, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(" ${series.unit}", color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 10.sp)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .chartGestures(
                    visibleRange, onVisibleRangeChange,
                    onScrub = onScrub,
                    onTap = { x, w ->
                        val idx = nearestVisibleExtremumTo(series, visibleRange, x, w)
                        tappedExtremum = if (tappedExtremum == idx) -1 else idx
                    },
                ),
        ) {
            val laneH = if (events.isNotEmpty() || roughSpans.isNotEmpty()) 18.dp.toPx() else 0f
            val plotH = size.height - laneH
            drawSeriesPlot(series, visibleRange, plotH, zeroLine, scrubT, tappedExtremum, bubblePaints)
            if (laneH > 0f) drawEventLane(t0, t1, visibleRange, laneH, plotH, events, roughSpans)
        }
    }
}

@Composable
fun CombinedChart(
    seriesList: List<ChartSeries>,
    scrubT: Float,
    onScrub: (Float) -> Unit,
    roughSpans: List<RideEventDetector.RoughSpan> = emptyList(),
    modifier: Modifier = Modifier,
    visibleRange: ZoomRange = FULL_ZOOM,
    onVisibleRangeChange: (ZoomRange) -> Unit = {},
) {
    if (seriesList.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.ScreenDeep)
            .border(1.dp, Ktm.Bezel, RoundedCornerShape(Ktm.RadiusCard))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Eyebrow("COMBINED", color = Ktm.Dim, fontSize = 11)
            Spacer(Modifier.weight(1f))
            seriesList.forEach { s ->
                Box(Modifier.padding(start = 10.dp).size(8.dp).clip(CircleShape).background(s.color))
                Text(" ${s.label}", color = Ktm.Dim, fontFamily = JetBrainsMono, fontSize = 10.sp)
            }
        }
        Text("NORMALIZED — SHAPES, NOT UNITS", color = Ktm.Dim2, fontFamily = JetBrainsMono, fontSize = 8.sp)
        Spacer(Modifier.height(6.dp))
        val t0 = seriesList[0].timesMs.first()
        val t1 = seriesList[0].timesMs.last()
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .chartGestures(visibleRange, onVisibleRangeChange, onScrub),
        ) {
            val vs = visibleRange.start; val ve = visibleRange.endInclusive
            val winT0 = t0 + ((t1 - t0) * vs.toDouble()).toLong()
            val winT1 = t0 + ((t1 - t0) * ve.toDouble()).toLong()
            for (span in roughSpans) {
                val xs = xOfTime(span.startMs, winT0, winT1, size.width)
                val xe = xOfTime(span.endMs, winT0, winT1, size.width)
                if (xe < 0f || xs > size.width) continue
                drawRect(
                    RideChartColors.Rough.copy(alpha = 0.15f),
                    topLeft = Offset(xs.coerceAtLeast(0f), 0f),
                    size = androidx.compose.ui.geometry.Size((xe - xs).coerceAtLeast(0f).coerceAtMost(size.width), size.height),
                )
            }
            for (s in seriesList) {
                var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
                for (v in s.values) { if (v < minV) minV = v; if (v > maxV) maxV = v }
                if (maxV - minV < 1e-3f) maxV = minV + 1f
                val visIdx = visibleIndices(s.timesMs, winT0, winT1)
                if (visIdx.count() < 2) continue
                val pts = ArrayList<Offset>(visIdx.count())
                for (i in visIdx) {
                    val x = xOfTime(s.timesMs[i], winT0, winT1, size.width)
                    val y = size.height - (s.values[i] - minV) / (maxV - minV) * size.height
                    pts.add(Offset(x, y))
                }
                drawPath(smoothPath(pts), s.color, style = Stroke(width = 2.dp.toPx()))
            }
            val x = ((scrubT - vs) / (ve - vs).coerceAtLeast(1e-4f)).coerceIn(0f, 1f) * size.width
            if (scrubT in vs..ve) drawLine(Ktm.Orange, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
        }
    }
}

/* ------------------------------------------------------------------------- */
/* Drawing internals                                                         */
/* ------------------------------------------------------------------------- */

private fun formatValue(v: Float): String = if (abs(v) >= 100f) "%.0f".format(v) else "%.1f".format(v)

private fun xOfTime(t: Long, winT0: Long, winT1: Long, width: Float): Float =
    ((t - winT0).toFloat() / (winT1 - winT0).coerceAtLeast(1)) * width

/** Indices of [times] whose timestamp falls in [winT0, winT1], inclusive, at least the two bracketing points. */
private fun visibleIndices(times: LongArray, winT0: Long, winT1: Long): IntRange {
    var lo = RideEventDetector.nearestIndex(times, winT0)
    var hi = RideEventDetector.nearestIndex(times, winT1)
    if (lo > 0 && times[lo] > winT0) lo--
    if (hi < times.size - 1 && times[hi] < winT1) hi++
    return lo.coerceIn(0, times.size - 1)..hi.coerceIn(0, times.size - 1)
}

private fun nearestVisibleExtremumTo(series: ChartSeries, visibleRange: ZoomRange, touchX: Float, width: Float): Int {
    val t0 = series.timesMs.first(); val t1 = series.timesMs.last()
    val winT0 = t0 + ((t1 - t0) * visibleRange.start).toLong()
    val winT1 = t0 + ((t1 - t0) * visibleRange.endInclusive).toLong()
    val visIdx = visibleIndices(series.timesMs, winT0, winT1)
    val visibleValues = FloatArray(visIdx.count()) { series.values[visIdx.first + it] }
    val extrema = findExtrema(visibleValues, seriesMinSeparation(series), seriesProminence(series))
    if (extrema.isEmpty()) return -1
    var best = -1; var bestDist = Float.MAX_VALUE
    for (e in extrema) {
        val globalIdx = visIdx.first + e.index
        val x = xOfTime(series.timesMs[globalIdx], winT0, winT1, width)
        val d = abs(x - touchX)
        if (d < bestDist) { bestDist = d; best = globalIdx }
    }
    return if (bestDist < 28f * 3f) best else -1 // ~28dp-ish touch slop in px-agnostic terms (called with raw px, generous)
}

private fun DrawScope.niceRange(values: FloatArray, includeZero: Boolean): Pair<Float, Float> {
    var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
    for (v in values) { if (v < minV) minV = v; if (v > maxV) maxV = v }
    if (includeZero) { if (minV > 0f) minV = 0f; if (maxV < 0f) maxV = 0f }
    if (maxV - minV < 1e-3f) maxV = minV + 1f
    val pad = (maxV - minV) * 0.12f
    return (minV - pad) to (maxV + pad)
}

private fun DrawScope.drawSeriesPlot(
    series: ChartSeries,
    visibleRange: ZoomRange,
    plotH: Float,
    zeroLine: Boolean,
    scrubT: Float,
    tappedExtremumIdx: Int,
    bubblePaints: BubblePaints,
) {
    val t0 = series.timesMs.first(); val t1 = series.timesMs.last()
    val winT0 = t0 + ((t1 - t0) * visibleRange.start).toLong()
    val winT1 = t0 + ((t1 - t0) * visibleRange.endInclusive).toLong()
    val visIdx = visibleIndices(series.timesMs, winT0, winT1)
    if (visIdx.count() < 2) return
    val visibleValues = FloatArray(visIdx.count()) { series.values[visIdx.first + it] }
    val (minV, maxV) = niceRange(visibleValues, zeroLine)
    fun xOf(i: Int) = xOfTime(series.timesMs[i], winT0, winT1, size.width)
    fun yOf(v: Float) = plotH - (v - minV) / (maxV - minV) * plotH

    for (frac in listOf(0.33f, 0.66f)) {
        drawLine(Ktm.Border, Offset(0f, plotH * frac), Offset(size.width, plotH * frac), strokeWidth = 1f)
    }
    if (zeroLine && minV < 0f && maxV > 0f) {
        drawLine(
            Ktm.Dim3, Offset(0f, yOf(0f)), Offset(size.width, yOf(0f)), strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
        )
    }

    // Draw literally every real point in the visible window - no stride, no
    // cap, no inclusion mask. This mirrors CombinedChart's point-building
    // loop just below in this same file (which draws all points with zero
    // decimation and is confirmed to look/perform fine): a typical ride is
    // ~1Hz GPX, so even a multi-hour ride is at most a few thousand points
    // in view. Decimating here was the actual bug - smoothing a Catmull-Rom
    // curve through a stride-sampled subset draws an interpolated shape
    // between the surviving points, not the recorded data, which reads as
    // "skipping/misrepresenting" real peaks and dips even when those peaks
    // were force-included as markers (the marker sat in the right place; the
    // LINE through it didn't, because its neighbors were fabricated).
    val extremaIdx = findExtrema(visibleValues, seriesMinSeparation(series), seriesProminence(series)).map { it.index }
    val n = visIdx.count()
    val pts = ArrayList<Offset>(n)
    for (localIdx in 0 until n) {
        val gi = visIdx.first + localIdx
        pts.add(Offset(xOf(gi), yOf(series.values[gi])))
    }
    val segments = smoothSegments(pts)
    val line = pathFromSegments(pts.first(), segments)

    // Fill: same smoothed curve (identical cubic segments, not a re-trace),
    // closed down to the baseline, gradient fade.
    val fill = Path()
    fill.moveTo(pts.first().x, plotH)
    fill.lineTo(pts.first().x, pts.first().y)
    for (s in segments) fill.cubicTo(s.c1x, s.c1y, s.c2x, s.c2y, s.x, s.y)
    fill.lineTo(pts.last().x, plotH)
    fill.close()
    drawPath(
        fill,
        Brush.verticalGradient(
            colors = listOf(series.color.copy(alpha = 0.35f), series.color.copy(alpha = 0.03f)),
            startY = 0f, endY = plotH,
        ),
    )

    // Highlight-on-top: a brighter, thinner sheen riding the crest of the
    // main stroke - a lighter tint of the series color, slight upward offset,
    // so the curve reads as glossy/lit rather than a flat colored line.
    val highlight = androidx.compose.ui.graphics.lerp(series.color, Color.White, 0.55f)
    drawPath(line, highlight.copy(alpha = 0.5f), style = Stroke(width = 5.dp.toPx()))
    drawPath(line, series.color, style = Stroke(width = 2.6.dp.toPx()))

    // Peak/dip markers: always-visible small rings (same indices forced into
    // the line above, so the ring always sits exactly on the curve); tapped
    // one gets a value bubble.
    for (localIdx in extremaIdx) {
        val globalIdx = visIdx.first + localIdx
        val center = Offset(xOf(globalIdx), yOf(series.values[globalIdx]))
        drawCircle(Ktm.ScreenDeep, radius = 5.dp.toPx(), center = center)
        drawCircle(series.color, radius = 5.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
        if (globalIdx == tappedExtremumIdx) {
            drawValueBubble(center, series.values[globalIdx], series.unit, series.color, plotH, bubblePaints)
        }
    }

    // Live scrub cursor + its own value bubble, following the touch/drag
    // exactly - CONTINUOUSLY, not snapped to the nearest recorded sample.
    // Recorded GPS points are only ~1Hz; snapping the cursor to the nearest
    // one meant that once zoomed in enough to show just a handful of real
    // samples, the cursor could only ever occupy that handful of x-positions
    // - it LOOKED like it kept landing on "some points only" no matter where
    // you dragged, because it genuinely could only land on those points.
    // Interpolating between the two bracketing samples (same technique the
    // map's follow-marker uses) gives a smooth cursor at the exact tapped
    // position, with an interpolated value in the bubble.
    if (scrubT in visibleRange) {
        val scrubTimeMs = (t0 + ((t1 - t0) * scrubT)).toLong()
        val x = xOfTime(scrubTimeMs, winT0, winT1, size.width)
        val value = interpolateSeriesValue(series, scrubTimeMs)
        val y = yOf(value)
        drawLine(Ktm.Orange.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, plotH), strokeWidth = 1.5.dp.toPx())
        drawCircle(Ktm.Orange, radius = 4.5.dp.toPx(), center = Offset(x, y))
        drawValueBubble(Offset(x, y), value, series.unit, Ktm.Orange, plotH, bubblePaints)
    }
}

private fun interpolateSeriesValue(series: ChartSeries, timeMs: Long): Float =
    interpolateAt(series.timesMs, series.values, timeMs)

/**
 * Linearly interpolated value at an arbitrary time between its two
 * bracketing recorded samples - reusable outside [ChartSeries] (e.g. the
 * master scrub console's live speed/accel/altitude readout, which had the
 * same nearest-sample-snap issue as the in-chart cursor).
 */
fun interpolateAt(timesMs: LongArray, values: FloatArray, timeMs: Long): Float {
    if (timesMs.isEmpty()) return 0f
    if (timesMs.size == 1 || timeMs <= timesMs[0]) return values[0]
    if (timeMs >= timesMs.last()) return values.last()
    var i1 = RideEventDetector.nearestIndex(timesMs, timeMs)
    if (timesMs[i1] < timeMs) i1 = (i1 + 1).coerceAtMost(timesMs.size - 1)
    if (i1 == 0) i1 = 1
    val i0 = i1 - 1
    val span = (timesMs[i1] - timesMs[i0]).coerceAtLeast(1)
    val t = ((timeMs - timesMs[i0]).toFloat() / span).coerceIn(0f, 1f)
    return values[i0] + (values[i1] - values[i0]) * t
}

/**
 * Cached native Paint objects for [drawValueBubble] - created ONCE per chart
 * (via `remember`) instead of allocated fresh on every redraw. Bubbles can
 * redraw many times a second during a drag; repeated Paint/shader allocation
 * there was real, measurable per-frame cost stacking on top of the sampling
 * work, and very likely what made a drag feel like it stalled on a real
 * device even though nothing was actually deadlocked.
 */
private class BubblePaints {
    val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        typeface = android.graphics.Typeface.MONOSPACE
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(235, 18, 19, 22)
    }
    val border = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
    }
    val connector = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val rect = android.graphics.RectF()
}

/** Rounded value chip drawn with cached native Paints (crisp text + fill at any density). */
private fun DrawScope.drawValueBubble(anchor: Offset, value: Float, unit: String, accent: Color, plotH: Float, paints: BubblePaints) {
    val text = "${formatValue(value)} $unit"
    val nc = drawContext.canvas.nativeCanvas
    paints.text.textSize = 11.sp.toPx()
    val padH = 10.dp.toPx()
    val textWidth = paints.text.measureText(text)
    val bubbleW = textWidth + padH * 2
    val bubbleH = 22.dp.toPx()
    val half = bubbleW / 2
    val cx = anchor.x.coerceIn(half, size.width - half)
    val top = (anchor.y - bubbleH - 10.dp.toPx()).coerceAtLeast(2.dp.toPx())
    paints.rect.set(cx - half, top, cx + half, top + bubbleH)
    paints.border.color = accent.toArgbInt()
    paints.border.strokeWidth = 1.5.dp.toPx()
    paints.connector.color = accent.toArgbInt()
    paints.connector.strokeWidth = 1.5.dp.toPx()
    nc.drawRoundRect(paints.rect, 8.dp.toPx(), 8.dp.toPx(), paints.bg)
    nc.drawRoundRect(paints.rect, 8.dp.toPx(), 8.dp.toPx(), paints.border)
    nc.drawLine(cx, paints.rect.bottom, anchor.x.coerceIn(2f, size.width - 2f), min(anchor.y, plotH), paints.connector)
    nc.drawText(text, cx, paints.rect.centerY() - (paints.text.descent() + paints.text.ascent()) / 2, paints.text)
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

private fun DrawScope.drawEventLane(
    t0: Long, t1: Long, visibleRange: ZoomRange, laneH: Float, plotH: Float,
    events: List<RideEventDetector.RideEvent>, roughSpans: List<RideEventDetector.RoughSpan>,
) {
    val winT0 = t0 + ((t1 - t0) * visibleRange.start).toLong()
    val winT1 = t0 + ((t1 - t0) * visibleRange.endInclusive).toLong()
    drawLine(Ktm.RowDivider, Offset(0f, plotH + 1), Offset(size.width, plotH + 1), strokeWidth = 1f)
    val cy = plotH + laneH / 2f
    for (span in roughSpans) {
        val xs = xOfTime(span.startMs, winT0, winT1, size.width)
        val xe = xOfTime(span.endMs, winT0, winT1, size.width)
        if (xe < 0f || xs > size.width) continue
        drawRect(
            RideChartColors.Rough.copy(alpha = 0.35f),
            topLeft = Offset(xs.coerceAtLeast(0f), plotH + 3),
            size = androidx.compose.ui.geometry.Size((xe - xs).coerceAtLeast(2f).coerceAtMost(size.width), laneH - 6),
        )
    }
    val r = 4.dp.toPx()
    for (e in events) {
        if (e.timeMs < winT0 || e.timeMs > winT1) continue
        val x = xOfTime(e.timeMs, winT0, winT1, size.width)
        val up = e.type == RideEventDetector.EventType.HARSH_ACCEL
        val color = if (up) RideChartColors.Accel else RideChartColors.Brake
        val path = Path().apply {
            if (up) { moveTo(x, cy - r); lineTo(x - r, cy + r); lineTo(x + r, cy + r) }
            else { moveTo(x, cy + r); lineTo(x - r, cy - r); lineTo(x + r, cy - r) }
            close()
        }
        drawPath(path, color)
    }
}
