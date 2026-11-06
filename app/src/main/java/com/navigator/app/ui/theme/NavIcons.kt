package com.navigator.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The app's symbol set, reproduced 1:1 from the design handoff's inline SVGs:
 * 24x24 viewport, stroke-width 1.7 (2.0 for chevrons/arrows), round caps and
 * joins, no fills. Every glyph below is the design's exact path data - if a
 * symbol needs changing, change it in the design first and copy the path here
 * so the two never drift.
 *
 * Drawn as outline paths so [androidx.compose.material3.Icon] can tint them
 * freely; the baked stroke color is a placeholder the Icon tint overrides.
 */
object NavIcons {

    private fun icon(name: String, strokeWidth: Float, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (d in paths) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    /** Filled variant - for the solid transport glyphs (play/pause). */
    private fun filled(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (d in paths) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = SolidColor(Color.White),
            )
        }
        return builder.build()
    }

    // The design writes circles and rects as SVG elements; express them as
    // path data so they can share the builders above.
    private fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0 Z"

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
        "M${x + r},$y h${w - 2 * r} a$r,$r 0 0 1 $r,$r v${h - 2 * r} " +
            "a$r,$r 0 0 1 ${-r},$r h${-(w - 2 * r)} a$r,$r 0 0 1 ${-r},${-r} " +
            "v${-(h - 2 * r)} a$r,$r 0 0 1 $r,${-r} Z"

    // ---- Home-grid tile symbols ----

    /** Notification tile. */
    val Bell: ImageVector by lazy {
        icon(
            "Bell", 1.7f,
            "M18 8a6 6 0 1 0-12 0c0 7-3 8-3 8h18s-3-1-3-8",
            "M13.7 20a2 2 0 0 1-3.4 0",
        )
    }

    /** Direction tile - the design's paper-plane navigation mark. */
    val Navigation: ImageVector by lazy {
        icon("Navigation", 1.7f, "M3 11 21 3l-8 18-2-7-8-3z")
    }

    /** Music tile / mode picker. */
    val Music: ImageVector by lazy {
        icon(
            "Music", 1.7f,
            "M9 18V5l12-2v13",
            circle(6f, 18f, 3f),
            circle(18f, 16f, 3f),
        )
    }

    /** Rides tile - a speed-trace squiggle, not a bike silhouette. */
    val Wave: ImageVector by lazy {
        icon("Wave", 1.7f, "M4 19c3-9 5 3 8-6s5 4 8-4")
    }

    /** Remote tile - the handlebar cluster as a controller body. */
    val Gamepad: ImageVector by lazy {
        icon(
            "Gamepad", 1.7f,
            roundRect(3f, 7f, 18f, 12f, 3f),
            "M7 11v4",
            "M17 11v4",
            "M12 11v4",
        )
    }

    /** Controller mode in the remote overlay - same body, d-pad dots. */
    val GamepadDots: ImageVector by lazy {
        icon(
            "GamepadDots", 2f,
            roundRect(3f, 7f, 18f, 12f, 3f),
            "M8 13h.01",
            "M12 11v4",
            "M16 13h.01",
        )
    }

    /** Home tile - "put me back on the phone", an exit-left arrow to a wall. */
    val Minimize: ImageVector by lazy {
        icon(
            "Minimize", 1.7f,
            "M4 12h10",
            "M10 8l-4 4 4 4",
            "M20 4v16",
        )
    }

    /** Exit tile - stop the service. Always drawn in the danger colour. */
    val Power: ImageVector by lazy {
        icon(
            "Power", 1.7f,
            "M12 3v9",
            "M6.2 6.2a9 9 0 1 0 11.6 0",
        )
    }

    // ---- Navigation / chrome ----

    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight", 2f, "M9 5l7 7-7 7")
    }

    val ChevronLeft: ImageVector by lazy {
        icon("ChevronLeft", 2f, "M15 5l-7 7 7 7")
    }

    val ArrowRight: ImageVector by lazy {
        icon("ArrowRight", 2f, "M4 12h16", "M15 7l5 5-5 5")
    }

    val ArrowLeft: ImageVector by lazy {
        icon("ArrowLeft", 2f, "M20 12h-16", "M8 8l-4 4 4 4")
    }

    /** Settings entry. Text pills carry it in the home header; this is for compact spots. */
    val Settings: ImageVector by lazy {
        icon(
            "Settings", 1.7f,
            "M20 7h-9",
            "M14 17H5",
            circle(17f, 7f, 3f),
            circle(7f, 17f, 3f),
        )
    }

    /** Idle-dash clock card, and any "elapsed/remaining" affordance. */
    val Clock: ImageVector by lazy {
        icon("Clock", 1.7f, circle(12f, 12f, 9f), "M12 8v5l3 2")
    }

    /** "Bike (off)" in the remote mode picker - hand the buttons back. */
    val CircleSlash: ImageVector by lazy {
        icon("CircleSlash", 1.7f, circle(12f, 12f, 9f), "M5 19L19 5")
    }

    val Check: ImageVector by lazy {
        icon("Check", 2.2f, "M20 6L9 17l-5-5")
    }

    val Bluetooth: ImageVector by lazy {
        icon("Bluetooth", 1.8f, "M7 7l10 10-5 5V2l5 5L7 17")
    }

    val Phone: ImageVector by lazy {
        icon(
            "Phone", 1.8f,
            "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z",
        )
    }

    val ShieldCheck: ImageVector by lazy {
        icon(
            "ShieldCheck", 1.8f,
            "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z",
            "M9 12l2 2 4-4",
        )
    }

    /**
     * The dash's turn glyph, as the design draws it: a stem that rises and
     * banks right into an arrowhead. Used on the Direction card and wherever
     * a maneuver stands in for the dash.
     */
    val TurnArrow: ImageVector by lazy {
        icon(
            "TurnArrow", 1.8f,
            "M12 21V9a4 4 0 0 1 4-4h3",
            "M15 2l4 3-4 3",
        )
    }

    /** Mirror of [TurnArrow] - a left maneuver. */
    val TurnArrowLeft: ImageVector by lazy {
        icon(
            "TurnArrowLeft", 1.8f,
            "M12 21V9a4 4 0 0 0-4-4H5",
            "M9 2L5 5l4 3",
        )
    }

    /** Straight-on maneuver. */
    val TurnStraight: ImageVector by lazy {
        icon("TurnStraight", 1.8f, "M12 22V4", "M8 8l4-4 4 4")
    }

    /** Kept for the pairing/vehicle rows that want a bike mark rather than a trace. */
    val Bike: ImageVector by lazy {
        icon(
            "Bike", 1.6f,
            circle(5.5f, 17.5f, 3.5f),
            circle(18.5f, 17.5f, 3.5f),
            "M15 17.5h-6l-3-6 4-1 3 4",
            "M12 6.5h3l1.5 4",
        )
    }

    // ---- Media transport (filled, as the design draws the pause bars) ----

    val Play: ImageVector by lazy { filled("Play", "M7 4l13 8-13 8V4Z") }

    val Pause: ImageVector by lazy {
        filled(
            "Pause",
            roundRect(6f, 4f, 4f, 16f, 1f),
            roundRect(14f, 4f, 4f, 16f, 1f),
        )
    }

    val SkipNext: ImageVector by lazy {
        icon("SkipNext", 1.8f, "M6 4l12 8-12 8V4Z", "M19 5v14")
    }

    val SkipPrevious: ImageVector by lazy {
        icon("SkipPrevious", 1.8f, "M18 4L6 12l12 8V4Z", "M5 5v14")
    }

    /** Legacy alias: the old set called the minimize glyph "House". */
    val House: ImageVector get() = Minimize
}
