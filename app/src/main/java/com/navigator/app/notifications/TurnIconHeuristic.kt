package com.navigator.app.notifications

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.logging.AppLogger

/**
 * Resolves a navigation notification into a dash [BccuProtocol.TurnIcon].
 *
 * Three layers, in order of confidence:
 *
 * 1. **Text maneuver parsing** (new, primary). Most nav apps put the maneuver
 *    in words somewhere in the notification ("Sharp left", "Make a U-turn",
 *    "3rd exit", "Keep right", "Slight left onto…"). Parsing that maps
 *    directly onto the full icon table - including sharp/slight severity,
 *    U-turns, roundabout exits and keep-lane maneuvers - which the old
 *    left/right-ink heuristic could never express. This is what fixes both
 *    "hard turns show as a slight arrow" (a sharp left now maps to HEAVY_LEFT,
 *    not LIGHT_LEFT) and "other symbols never show" (u-turns/roundabouts/keep).
 * 2. **Exact bitmap-hash cache** ([IconHashCache]) for icons a maneuver word
 *    wasn't found for - reliable once a hash has been confirmed.
 * 3. **Graduated ink heuristic** as a last resort: left/right ink dominance
 *    now grades into LIGHT / QUITE / HEAVY (and straight) by how lopsided the
 *    arrow is, instead of always emitting a single LIGHT_* value.
 */
object TurnIconHeuristic {

    private const val SAMPLE_SIZE = 48

    // A Maps maneuver glyph is a white shape on transparency: it covers at
    // most a third of the tile and has no colour at all. Anything that fills
    // the tile, or carries colour, is something else Maps puts in the very
    // same largeIcon slot - a street-view thumbnail, a red traffic-incident
    // badge, a profile avatar.
    //
    // Measured on the 43 icons this app captured as "unknown" on a rider's
    // phone: the 33 genuine maneuvers topped out at 0.388 opaque and 0.000
    // saturation, while the 10 photos and badges started at 0.800 opaque and
    // 0.096 saturation. These cutoffs sit in that gap with better than 2x
    // headroom and separate all 43 correctly.
    private const val MANEUVER_MAX_OPAQUE_FRACTION = 0.5
    private const val MANEUVER_MAX_SATURATION = 0.05

    // Severity bands, calibrated from real Google Maps arrows on hardware
    // (2026-07-06 logs): a straight arrow measured a full-height left/right ink
    // ratio of ~1.00, while a real 90-degree "Turn left" measured only ~1.14 -
    // the tall vertical shaft dilutes the ratio, so the OLD 1.30 cutoff for a
    // "normal" turn mis-called every Maps turn as a slight one. These cutoffs
    // put ~1.14 into QUITE (a proper turn) instead of LIGHT.
    private const val STRAIGHT_MAX = 1.05   // below this -> going straight
    private const val LIGHT_MAX = 1.12      // slight turn
    private const val QUITE_MAX = 1.40      // normal ~90-degree turn; above -> sharp
    // The arrowhead lives in the top of the icon; measuring only the top rows
    // separates sharp turns (head swung far to one side) from gentle ones far
    // better than the shaft-diluted full-height ratio, so it's used to escalate.
    private const val TOP_REGION_FRACTION = 0.45
    private const val TOP_QUITE_RATIO = 1.8
    private const val TOP_HEAVY_RATIO = 3.0
    // A genuinely-straight arrow has a near-1.0 arrowhead (top-region) ratio
    // (measured 1.005 on real Maps). A fork/keep whose full-image ratio still
    // looks straight (shaft dominates) but whose arrowhead clearly leans - e.g.
    // the real "towards A / B" fork measured full=1.024 but top=1.592 - must NOT
    // be called GO_STRAIGHT. Above this top-ratio we treat it as at least a
    // slight turn in the leaning direction. Comfortably above straight's ~1.00
    // and below a real 90-degree turn's ~2.05, so neither is reclassified.
    private const val STRAIGHT_TOP_MAX = 1.35

    fun guessDirection(context: Context, sbn: StatusBarNotification): BccuProtocol.TurnIcon? {
        // The parsed text is logged alongside the verdict: when this mapping
        // goes wrong on a rider's phone, the log has to show WHAT was parsed,
        // not just what it decided. Diagnosing the "destination flag for a
        // whole ride" report needed exactly this line and it wasn't there.
        val maneuverText = collectText(sbn)
        parseManeuverFromText(maneuverText)?.let {
            AppLogger.log("TurnIcon", "Maneuver from text \"${maneuverText.trim().take(80)}\" -> ${it.name}")
            return it
        }

        val bitmap = loadIconBitmap(context, sbn) ?: return null

        // 1. User's manual calibration (exact per-bitmap override) wins.
        IconHashCache.lookup(bitmap, context)?.let { return it }

        // 1b. Is this even a maneuver icon? Maps posts street-view photos,
        // traffic-incident badges and avatars through the same slot, and the
        // ink heuristic below cannot say "no" - a photograph has ink on both
        // halves, so it always resolved to SOME turn and put a confident,
        // completely wrong arrow on the dash. Leaving the arrow untouched is
        // the right answer for a notification that isn't a maneuver.
        if (!looksLikeManeuverIcon(bitmap)) return null

        // 2. On-device TFLite classifier (ported from the KTM Gen-3 companion
        //    app). Purpose-trained on nav maneuver icons, so it recognises the
        //    exact maneuver - including every roundabout exit - which the pixel
        //    heuristic below cannot. This is the real fix for Google Maps icons.
        ManeuverClassifier.classify(context, bitmap)?.let {
            AppLogger.log("TurnIcon", "Maneuver from model -> ${it.name}")
            return it
        }

        // 3. Last-resort ink heuristic (only if the model isn't confident / absent).
        val (leftInk, rightInk) = measureLeftRightInk(bitmap, 0.0)
        val (topLeft, topRight) = measureLeftRightInk(bitmap, TOP_REGION_FRACTION)
        if (leftInk + rightInk <= 0) return null

        // Direction from the arrowhead (top region) - more reliable than the
        // full image, whose bottom shaft is centred for every maneuver.
        val leftDominant = if (topLeft + topRight > 0) topLeft >= topRight else leftInk >= rightInk
        val fullRatio = ratio(leftInk, rightInk)
        val topRatio = ratio(topLeft, topRight)
        AppLogger.log("TurnIcon", "Icon heuristic: full L=$leftInk R=$rightInk (r=${"%.3f".format(fullRatio)}), top L=$topLeft R=$topRight (r=${"%.3f".format(topRatio)})")

        val severity = when {
            // Straight only when BOTH the full image and the arrowhead are
            // near-symmetric - a leaning arrowhead (STRAIGHT_TOP_MAX) means a
            // fork/keep/slight turn, never straight, even if the shaft-diluted
            // full ratio looks straight.
            fullRatio < STRAIGHT_MAX && topRatio < STRAIGHT_TOP_MAX -> 0 // straight
            fullRatio >= QUITE_MAX || topRatio >= TOP_HEAVY_RATIO -> 3  // heavy/sharp
            fullRatio >= LIGHT_MAX || topRatio >= TOP_QUITE_RATIO -> 2  // quite/normal
            else -> 1 // light/slight
        }
        return when (severity) {
            0 -> BccuProtocol.TurnIcon.GO_STRAIGHT
            1 -> if (leftDominant) BccuProtocol.TurnIcon.LIGHT_LEFT else BccuProtocol.TurnIcon.LIGHT_RIGHT
            2 -> if (leftDominant) BccuProtocol.TurnIcon.QUITE_LEFT else BccuProtocol.TurnIcon.QUITE_RIGHT
            else -> if (leftDominant) BccuProtocol.TurnIcon.HEAVY_LEFT else BccuProtocol.TurnIcon.HEAVY_RIGHT
        }
    }

    /** See [MANEUVER_MAX_OPAQUE_FRACTION]: white-on-transparent glyph, or something else entirely? */
    private fun looksLikeManeuverIcon(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var opaque = 0L
        var saturationSum = 0.0
        for (pixel in pixels) {
            if (((pixel ushr 24) and 0xFF) <= 32) continue
            opaque++
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            saturationSum += (maxOf(r, g, b) - minOf(r, g, b)) / 255.0
        }
        if (opaque == 0L) return false
        val opaqueFraction = opaque.toDouble() / pixels.size
        val saturation = saturationSum / opaque
        val maneuver = opaqueFraction < MANEUVER_MAX_OPAQUE_FRACTION && saturation < MANEUVER_MAX_SATURATION
        if (!maneuver) {
            AppLogger.log(
                "TurnIcon",
                "Not a maneuver icon (opaque=${"%.2f".format(opaqueFraction)} sat=${"%.2f".format(saturation)}) - leaving the dash arrow alone",
            )
        }
        return maneuver
    }

    private fun ratio(a: Long, b: Long): Double {
        val hi = maxOf(a, b); val lo = minOf(a, b)
        return hi.toDouble() / lo.coerceAtLeast(1)
    }

    /**
     * Gather the fields that carry the MANEUVER instruction.
     *
     * Deliberately NOT sub/info/summary text. Those hold the trip-status row
     * ("9 min - 2.4 km - Arrive 16:51"), and folding it into the same string
     * made every update match the arrival rule below: a rider's whole ride
     * showed the chequered destination flag on the dash instead of turns
     * (real log, 2026-10-12: title="Head toward 6th A Cross Rd", eta=16:51,
     * "Maneuver from text -> END" on every single notification repost).
     * [AppNotificationListener] reads the status row separately for ETA and
     * remaining distance, which is the only thing it is good for.
     */
    private fun collectText(sbn: StatusBarNotification): String {
        val e = sbn.notification.extras
        val keys = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
        )
        val sb = StringBuilder()
        for (k in keys) {
            e.getCharSequence(k)?.let { sb.append(' ').append(it) }
        }
        sbn.notification.tickerText?.let { sb.append(' ').append(it) }
        return sb.toString().lowercase()
    }

    /**
     * Phrases that mean "you are there", as opposed to the bare words
     * "arrive"/"destination" that also appear in an ETA line. Matching the
     * bare words is what put the dash on the destination flag for an entire
     * ride, so arrival now needs an actual arrival sentence.
     */
    private val ARRIVAL_PHRASES = listOf(
        "you have arrived", "you've arrived", "you are arriving", "you're arriving",
        "arriving at your destination", "arrive at your destination",
        "reached your destination", "destination reached",
        "your destination is", "destination is on", "destination will be on",
        "destination ahead", "destination on the",
    )
    /** "Arrived" as a whole word - Maps' own final instruction - but never "arrive 16:51". */
    private val ARRIVED_WORD = Regex("""\barrived\b""")
    /**
     * Maps' last step reads "Arrive at <place>", which IS an arrival - while
     * "Arrive at 16:51" is an ETA. The negative look-ahead for a clock time is
     * what tells them apart.
     */
    private val ARRIVE_AT_PLACE = Regex("""\barrive\s+(?:at|in|on)\s+(?!\d{1,2}\s*[:.]\s*\d{2})""")

    private fun isArrival(t: String): Boolean =
        ARRIVAL_PHRASES.any { t.contains(it) } ||
            ARRIVED_WORD.containsMatchIn(t) ||
            ARRIVE_AT_PLACE.containsMatchIn(t)

    /**
     * Map an instruction string to an icon. Order matters - the most specific
     * phrases (sharp/slight/u-turn/roundabout/keep) are checked before the
     * generic "turn left/right", so a "sharp left" isn't swallowed by "left".
     * Returns null when no maneuver word is present (falls through to bitmap).
     */
    private fun parseManeuverFromText(t: String): BccuProtocol.TurnIcon? {
        if (t.isBlank()) return null
        val left = t.contains("left")
        val right = t.contains("right")

        // Roundabouts: "roundabout" / "rotary" + an exit ordinal.
        if (t.contains("roundabout") || t.contains("rotary")) {
            val exit = roundaboutExit(t)
            if (exit != null) {
                val idx = exit.coerceIn(1, 16)
                return BccuProtocol.TurnIcon.entries.first { it.name == "RAB_SECT_${idx}_RH" }
            }
            return BccuProtocol.TurnIcon.RAB_SECT_2_RH
        }

        // U-turn.
        if (t.contains("u-turn") || t.contains("u turn") || t.contains("uturn") || t.contains("make a u")) {
            return if (right) BccuProtocol.TurnIcon.UTURN_RIGHT else BccuProtocol.TurnIcon.UTURN_LEFT
        }

        // Sharp / hard.
        if ((t.contains("sharp") || t.contains("hard")) && (left || right)) {
            return if (left) BccuProtocol.TurnIcon.HEAVY_LEFT else BccuProtocol.TurnIcon.HEAVY_RIGHT
        }
        // Slight / soft / bear.
        if ((t.contains("slight") || t.contains("soft") || t.contains("bear")) && (left || right)) {
            return if (left) BccuProtocol.TurnIcon.LIGHT_LEFT else BccuProtocol.TurnIcon.LIGHT_RIGHT
        }
        // Keep / stay in lane.
        if ((t.contains("keep") || t.contains("stay")) && (left || right)) {
            return if (left) BccuProtocol.TurnIcon.KEEP_LEFT else BccuProtocol.TurnIcon.KEEP_RIGHT
        }
        // Merge onto a highway.
        if (t.contains("merge")) {
            return if (left) BccuProtocol.TurnIcon.ENTER_HIGHWAY_LEFT_LANE else BccuProtocol.TurnIcon.ENTER_HIGHWAY_RIGHT_LANE
        }
        // Take a motorway exit / off-ramp.
        if ((t.contains("exit") || t.contains("ramp") || t.contains("off-ramp")) && (left || right)) {
            return if (left) BccuProtocol.TurnIcon.LEAVE_HIGHWAY_LEFT_LANE else BccuProtocol.TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE
        }
        // Arrival - a real arrival sentence only, never an ETA's "Arrive 16:51".
        if (isArrival(t)) {
            return BccuProtocol.TurnIcon.END
        }
        // Plain turn: this is a normal ~90° turn, so QUITE_* (not LIGHT_*).
        if (t.contains("turn left") || (left && t.contains("turn"))) return BccuProtocol.TurnIcon.QUITE_LEFT
        if (t.contains("turn right") || (right && t.contains("turn"))) return BccuProtocol.TurnIcon.QUITE_RIGHT
        // Straight / continue / head.
        if (t.contains("straight") || t.contains("continue") || t.contains("head ") || t.contains("proceed")) {
            return BccuProtocol.TurnIcon.GO_STRAIGHT
        }
        // A bare directional word with no other maneuver context - treat as a turn.
        if (left && !right) return BccuProtocol.TurnIcon.QUITE_LEFT
        if (right && !left) return BccuProtocol.TurnIcon.QUITE_RIGHT
        return null
    }

    /** Pull a roundabout exit number from phrases like "3rd exit" or "exit 2". */
    private fun roundaboutExit(t: String): Int? {
        Regex("""(\d+)(?:st|nd|rd|th)?\s+exit""").find(t)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("""exit\s+(\d+)""").find(t)?.let { return it.groupValues[1].toIntOrNull() }
        listOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5).forEach { (w, n) ->
            if (t.contains("$w exit")) return n
        }
        return null
    }

    /** Public wrapper: render a nav notification's large maneuver icon to the sampled bitmap (for debug capture). */
    fun loadManeuverBitmap(context: Context, sbn: StatusBarNotification): Bitmap? = loadIconBitmap(context, sbn)

    private fun loadIconBitmap(context: Context, sbn: StatusBarNotification): Bitmap? {
        val icon = sbn.notification.getLargeIcon() ?: return null
        val drawable = try {
            icon.loadDrawable(context)
        } catch (e: Exception) {
            AppLogger.log("TurnIcon", "!! Failed to load notification icon: $e")
            null
        } ?: return null
        return drawableToBitmap(drawable)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            AppLogger.log("TurnIcon", "!! Failed to render icon to bitmap: $e")
            null
        }
    }

    /**
     * Sum left-half vs right-half ink alpha. [fromYFraction] restricts the scan
     * to the rows starting at that fraction of the height downward from the top
     * (0.0 = whole image; 0.55 = top 45% only, where the arrowhead sits).
     */
    private fun measureLeftRightInk(bitmap: Bitmap, fromYFraction: Double): Pair<Long, Long> {
        var leftInk = 0L
        var rightInk = 0L
        val halfWidth = bitmap.width / 2
        val startY = 0
        val endY = if (fromYFraction <= 0.0) bitmap.height
        else (bitmap.height * fromYFraction).toInt().coerceIn(1, bitmap.height)
        for (y in startY until endY) {
            for (x in 0 until bitmap.width) {
                val alpha = (bitmap.getPixel(x, y) ushr 24) and 0xFF
                if (x < halfWidth) leftInk += alpha else rightInk += alpha
            }
        }
        return leftInk to rightInk
    }
}
