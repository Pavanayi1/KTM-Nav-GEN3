package com.navigator.app.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.navigator.app.R
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.logging.AppLogger
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device turn-icon recogniser, ported from the approach used by the KTM
 * Gen-3 companion app (com.bikeconnect.app): instead of a fragile pixel
 * heuristic, a small TensorFlow Lite CNN looks at the nav app's maneuver-icon
 * bitmap and outputs the dash turn-icon code directly - so Google Maps' icons
 * (including every roundabout exit, which the heuristic could never tell apart)
 * are classified robustly.
 *
 * Pipeline (identical to the reference app so the bundled model behaves the
 * same): composite the icon onto a black background, scale to 96x96, feed RGB
 * floats [0,1], run the model, take the arg-max class; accept it only if its
 * confidence is >= [CONFIDENCE]. The 34 output classes are the dash turn-icon
 * binary codes (plus three named fall-backs), so a numeric label maps straight
 * to [BccuProtocol.TurnIcon.fromBinary].
 */
object ManeuverClassifier {

    private const val INPUT = 96
    private const val CONFIDENCE = 0.4f

    // Output class labels in model order. Loaded at runtime from
    // res/raw/maneuver_labels.txt so a retrained model + labels file drop in
    // with no code change; falls back to this embedded list (the order of the
    // currently-bundled model). A numeric entry is a dash TurnIcon binary code;
    // named entries are Google Maps icon-resource fall-backs.
    private val FALLBACK_LABELS = arrayOf(
        "1", "11", "12", "13", "16", "17", "2", "21", "27", "29", "3", "31",
        "32", "33", "35", "37", "39", "4", "41", "43", "45", "47", "48", "49",
        "51", "53", "55", "57", "6", "7", "8",
        "big_trans_directions_roundabout", "big_trans_directions_roundabout_lhs", "merge",
    )
    @Volatile private var labels: Array<String>? = null

    private fun labels(context: Context): Array<String> {
        labels?.let { return it }
        val loaded = try {
            val id = context.resources.getIdentifier("maneuver_labels", "raw", context.packageName)
            if (id != 0) {
                context.resources.openRawResource(id).bufferedReader().useLines { seq ->
                    seq.map { it.trim() }.filter { it.isNotEmpty() }.toList().toTypedArray()
                }.takeIf { it.isNotEmpty() }
            } else null
        } catch (e: Throwable) {
            AppLogger.log("Maneuver", "!! labels load failed, using embedded: $e"); null
        }
        return (loaded ?: FALLBACK_LABELS).also { labels = it }
    }

    private val NAMED: Map<String, BccuProtocol.TurnIcon> = mapOf(
        "big_trans_directions_roundabout" to BccuProtocol.TurnIcon.RAB_SECT_8_RH,
        "big_trans_directions_roundabout_lhs" to BccuProtocol.TurnIcon.RAB_SECT_8_LH,
        "merge" to BccuProtocol.TurnIcon.ENTER_HIGHWAY_LEFT_LANE,
    )

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var unavailable = false
    // Cache by bitmap-pixel hash: the same maneuver bitmap recurs many times per
    // ride, so we only run the model once per distinct icon.
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, BccuProtocol.TurnIcon>()

    private fun ensureModel(context: Context): Interpreter? {
        interpreter?.let { return it }
        if (unavailable) return null
        synchronized(this) {
            interpreter?.let { return it }
            return try {
                val bytes = context.resources.openRawResource(R.raw.maneuver_model).use { it.readBytes() }
                val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                buf.put(bytes); buf.rewind()
                Interpreter(buf).also { interpreter = it }
            } catch (e: Throwable) {
                AppLogger.log("Maneuver", "!! Failed to load TFLite model: $e")
                unavailable = true
                null
            }
        }
    }

    /** Classify a nav maneuver bitmap into a dash TurnIcon, or null if the model isn't confident. */
    fun classify(context: Context, bitmap: Bitmap): BccuProtocol.TurnIcon? {
        val hash = hashOf(bitmap)
        cache[hash]?.let { return it }
        val model = ensureModel(context) ?: return null
        val lbls = labels(context)
        val result = try {
            val input = preprocess(bitmap)
            val output = Array(1) { FloatArray(lbls.size) }
            model.run(input, output)
            val scores = output[0]
            var best = 0
            for (i in scores.indices) if (scores[i] > scores[best]) best = i
            if (scores[best] < CONFIDENCE) {
                AppLogger.log("Maneuver", "No confident match (best=${lbls[best]} @ ${"%.2f".format(scores[best])})")
                null
            } else {
                labelToIcon(lbls[best])?.also {
                    AppLogger.log("Maneuver", "Model -> ${lbls[best]} = ${it.name} (${"%.2f".format(scores[best])})")
                }
            }
        } catch (e: Throwable) {
            AppLogger.log("Maneuver", "!! inference failed: $e")
            null
        }
        if (result != null) cache[hash] = result
        return result
    }

    private fun labelToIcon(label: String): BccuProtocol.TurnIcon? {
        val n = label.toIntOrNull()
        return if (n != null) BccuProtocol.TurnIcon.fromBinary(n) else NAMED[label]
    }

    /**
     * Composite onto black, scale to 96x96, emit RGB floats [0,1] - matches the
     * reference app byte-for-byte for the usual white arrow.
     *
     * Some Maps versions tint the arrow black (issue #8). Black on the black
     * backdrop is an empty square, and the model guessed at random. The arrow's
     * shape lives in its alpha channel, so a dark arrow is redrawn white
     * (alpha kept) first - which is exactly what the model was trained on.
     */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val onBlack = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(onBlack)
        canvas.drawColor(Color.BLACK)
        val paint = if (isDarkInk(bitmap)) {
            android.graphics.Paint().apply {
                colorFilter = android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            }
        } else null
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        val scaled = Bitmap.createScaledBitmap(onBlack, INPUT, INPUT, true)
        val buf = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until INPUT) {
            for (x in 0 until INPUT) {
                val p = scaled.getPixel(x, y)
                buf.putFloat(((p shr 16) and 0xFF) / 255f)
                buf.putFloat(((p shr 8) and 0xFF) / 255f)
                buf.putFloat((p and 0xFF) / 255f)
            }
        }
        buf.rewind()
        if (scaled != onBlack) scaled.recycle()
        onBlack.recycle()
        return buf
    }

    /**
     * True when the visible pixels are mostly dark, i.e. a black/near-black
     * tinted glyph on a transparent background. Only pixels with real coverage
     * count, so a white arrow's soft anti-aliased edge can't tip it.
     */
    private fun isDarkInk(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return isDarkInk(pixels)
    }

    /** Pixel-array form of [isDarkInk], free of Bitmap so it is JVM-testable. */
    internal fun isDarkInk(argb: IntArray): Boolean {
        var weight = 0L
        var lumaSum = 0L
        for (p in argb) {
            val a = (p ushr 24) and 0xFF
            if (a < 128) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lumaSum += (299 * r + 587 * g + 114 * b) / 1000
            weight++
        }
        if (weight == 0L) return false
        return lumaSum / weight < 80
    }

    private fun hashOf(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.contentHashCode()
    }
}
