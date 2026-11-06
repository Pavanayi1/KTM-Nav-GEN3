package com.navigator.app.notifications

import android.content.Context
import android.graphics.Bitmap
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.logging.AppLogger
import com.navigator.app.settings.AppSettings
import java.io.File
import java.io.FileOutputStream

/**
 * Exact-match icon cache. A decompiled third-party app for this same dash
 * (BikeConnect, com.bikeconnect.app) solves turn-icon detection this exact
 * way: Google Maps and similar apps reuse a small, fixed set of bitmap
 * assets per maneuver type rather than rendering a continuously-varying
 * angle (confirmed independently in our own logs - the same maneuver
 * produced byte-identical pixel data across 15+ separate notification
 * updates), so hashing the icon bitmap and looking up a previously-confirmed
 * mapping is far more reliable than a continuous pixel-density heuristic,
 * once a given hash has actually been seen and confirmed once.
 *
 * This ships with an empty table - we have no way to read BikeConnect's own
 * hash table (it lives inside a method JADX could not decompile) - so it
 * starts by collecting real data: any unrecognized icon gets its hash logged
 * and its bitmap saved to disk, the same "please help us label this" flow
 * BikeConnect's own UI uses. Once a saved icon is confirmed against what the
 * phone's screen actually showed at that moment, add its hash to
 * knownHashes below and it becomes an exact, no-guessing match from then on.
 */
object IconHashCache {

    // Confirmed hash -> icon mappings. Empty until real rides confirm some -
    // check Download/Navigator/unknown_turn_icons (or the app-private
    // equivalent) for saved PNGs, cross-reference against what the phone
    // screen showed at that log timestamp, then add entries here.
    private val knownHashes: Map<Int, BccuProtocol.TurnIcon> = mapOf(
        // example once confirmed: -123456789 to BccuProtocol.TurnIcon.LIGHT_LEFT,
    )

    private val seenHashesThisSession = mutableSetOf<Int>()

    fun lookup(bitmap: Bitmap, context: Context): BccuProtocol.TurnIcon? {
        val hash = hashBitmap(bitmap)
        val known = knownHashes[hash]
        if (known != null) {
            AppLogger.log("IconCache", "Exact hash match: $hash -> ${known.name}")
            return known
        }
        // User calibration from the Turn-icon calibration screen - exact and
        // permanent for this bike/Maps version once labeled.
        AppSettings(context).getTurnCalibration(hash)?.let { name ->
            val icon = BccuProtocol.TurnIcon.entries.firstOrNull { it.name == name }
            if (icon != null) {
                AppLogger.log("IconCache", "Calibrated hash match: $hash -> ${icon.name}")
                return icon
            }
        }
        // Always keep the latest bitmap on disk for this hash so the calibration
        // screen has something to show (overwrite is fine - same hash, same pixels).
        saveUnknownIcon(bitmap, hash, context)
        if (seenHashesThisSession.add(hash)) {
            AppLogger.log("IconCache", "Unrecognized icon hash=$hash - saved for calibration")
        }
        return null
    }

    /** Directory holding captured, not-yet-labeled turn-icon bitmaps. */
    fun savedIconsDir(context: Context): File = File(context.getExternalFilesDir(null), "unknown_turn_icons")

    /** All captured turn-icon PNGs (newest first), paired with the bitmap hash parsed from the filename. */
    fun savedIcons(context: Context): List<Pair<File, Int>> {
        val dir = savedIconsDir(context)
        val files = dir.listFiles { f -> f.name.startsWith("icon_") && f.name.endsWith(".png") } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.mapNotNull { f ->
            val hash = f.name.removePrefix("icon_").removeSuffix(".png").toIntOrNull() ?: return@mapNotNull null
            f to hash
        }
    }

    private fun hashBitmap(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.contentHashCode()
    }

    private fun saveUnknownIcon(bitmap: Bitmap, hash: Int, context: Context) {
        try {
            val dir = savedIconsDir(context)
            dir.mkdirs()
            val file = File(dir, "icon_$hash.png")
            if (file.exists()) return // same hash = same pixels, no need to rewrite
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            AppLogger.log("IconCache", "Saved unknown icon to ${file.absolutePath}")
        } catch (e: Exception) {
            AppLogger.log("IconCache", "!! Failed to save unknown icon: $e")
        }
    }
}
