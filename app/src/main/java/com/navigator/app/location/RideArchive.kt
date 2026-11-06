package com.navigator.app.location

import android.content.Context
import android.net.Uri
import com.navigator.app.logging.AppLogger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-history backup and restore for recorded rides, so switching phones
 * doesn't mean losing every ride, the traffic map built from them, and the
 * sensor sidecars that make rough-road detection work.
 *
 * One flat zip: every `.gpx` alongside its `.imu.csv` sidecar, no directories.
 * Deliberately a plain zip a rider can open on a laptop and pick through -
 * this is their data, and a proprietary container would make it less theirs.
 * Nothing else goes in: not the pairing keys (bike-specific and security
 * material), not settings (they take a minute to redo and would silently
 * overwrite a working setup).
 */
object RideArchive {

    /** Result of a restore, so the UI can say what actually happened. */
    data class ImportResult(val imported: Int, val skipped: Int, val failed: Int)

    private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024

    /**
     * Write every recorded ride (and sidecar) into [target].
     * @return the number of ride files written, or -1 on failure.
     */
    fun exportTo(context: Context, target: Uri): Int {
        val rides = RouteRecorder.listRoutes(context)
        if (rides.isEmpty()) return 0
        var written = 0
        return runCatching {
            context.contentResolver.openOutputStream(target)?.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    for (gpx in rides) {
                        addEntry(zip, gpx)
                        written++
                        val sidecar = com.navigator.app.analytics.ImuLog.sidecarFor(gpx)
                        if (sidecar.exists()) addEntry(zip, sidecar)
                    }
                }
            } ?: return -1
            AppLogger.log("Archive", "Exported $written rides to $target")
            written
        }.getOrElse {
            AppLogger.log("Archive", "!! export failed: $it")
            -1
        }
    }

    private fun addEntry(zip: ZipOutputStream, file: File) {
        zip.putNextEntry(ZipEntry(file.name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /**
     * Restore rides from a zip previously produced by [exportTo] (or any zip
     * of loose GPX files). Existing rides with the same filename are left
     * alone rather than overwritten - a restore should never destroy rides
     * recorded on this phone.
     */
    fun importFrom(context: Context, source: Uri): ImportResult {
        val dir = RouteRecorder.routesDir(context)
        var imported = 0
        var skipped = 0
        var failed = 0
        runCatching {
            context.contentResolver.openInputStream(source)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = File(entry.name).name // flatten: ignore any path in the zip
                        if (entry.isDirectory || !isRideFile(name)) {
                            zip.closeEntry(); skipped++; continue
                        }
                        val dest = File(dir, name)
                        if (dest.exists()) {
                            zip.closeEntry(); skipped++; continue
                        }
                        val ok = runCatching {
                            // Copy with a ceiling: a hostile or corrupt zip
                            // shouldn't be able to fill the phone's storage.
                            var total = 0L
                            dest.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zip.read(buf)
                                    if (n <= 0) break
                                    total += n
                                    if (total > MAX_ENTRY_BYTES) throw IllegalStateException("entry too large: $name")
                                    out.write(buf, 0, n)
                                }
                            }
                        }.isSuccess
                        if (ok) imported++ else { failed++; dest.delete() }
                        zip.closeEntry()
                    }
                }
            }
        }.onFailure {
            AppLogger.log("Archive", "!! import failed: $it")
            failed++
        }
        AppLogger.log("Archive", "Imported $imported, skipped $skipped, failed $failed from $source")
        return ImportResult(imported, skipped, failed)
    }

    /** Only ride data is accepted - never anything executable from an untrusted zip. */
    private fun isRideFile(name: String): Boolean =
        !name.startsWith(".") && (name.endsWith(".gpx", true) || name.endsWith(".imu.csv", true))

    /** Suggested filename for a new archive; [stampMs] keeps it sortable. */
    fun suggestedName(stampMs: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        return "navigator-rides-${fmt.format(java.util.Date(stampMs))}.zip"
    }
}
