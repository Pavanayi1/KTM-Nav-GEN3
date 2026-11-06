package com.navigator.app.logging

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-wide logger: logcat + an in-memory buffer (for the in-app Logs screen)
 * + - only when [fileLoggingEnabled] is on - a timestamped file. File logging
 * is OFF by default (Settings > Diagnostics > "Write logs to file"): appending
 * to disk and to a MediaStore stream on every line is real I/O on a hot path,
 * and it is only wanted while actively diagnosing something. The file is written to two places: the app-private
 * external files dir (always works, no permission needed, used by the
 * in-app Share button) and a public Downloads/Navigator copy (so the log can
 * be found with a normal file manager even if the app has crashed and can't
 * be reopened to hit Share). Needs init(context) called once, from
 * NavigatorApplication - also installs a global uncaught-exception handler so
 * a crash's actual stack trace ends up in the log file instead of being lost.
 */
object AppLogger {

    private const val TAG = "Navigator"
    private const val MAX_LINES = 500

    private lateinit var appContext: Context
    private lateinit var logFile: File
    private var publicLogFile: File? = null
    private var initialized = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Whether log lines are persisted to disk. Read from settings at [init] and
     * updated live by the Settings toggle. Volatile: read on the IO thread,
     * written from the UI thread. The private log file is created lazily on the
     * first line actually written, so leaving this off creates no files at all.
     */
    @Volatile
    var fileLoggingEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                log("App", "File logging enabled: ${logFile.absolutePath}")
            } else {
                log("App", "File logging disabled - keeping the in-app buffer only")
            }
        }

    // Disk + MediaStore writes happen here, off the caller's thread. Logging is
    // called from the main thread and from BLE/RCM callbacks (hot paths); doing
    // file I/O and a MediaStore openOutputStream() per line on those threads was
    // a real source of UI jank and callback latency.
    private val ioHandler: Handler by lazy {
        val thread = android.os.HandlerThread("NavigatorLogIO").apply { start() }
        Handler(thread.looper)
    }

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    // The in-app Logs buffer as a real ring buffer. This used to be
    // `_lines.value = (_lines.value + line).takeLast(MAX_LINES)` posted to the
    // main thread per line - two full 500-element copies each time, on the main
    // thread, at whatever rate the BLE/RCM/notification callers log. Now the
    // append is O(1) under a lock and the StateFlow snapshot is published at
    // most once per main-loop pass, so a burst of lines costs one copy, not one
    // per line.
    private val buffer = ArrayDeque<String>()
    private val bufferLock = Any()
    @Volatile private var publishScheduled = false

    // SimpleDateFormat is not thread-safe and log() is called from the main
    // thread AND from BLE binder threads, so one per thread rather than a new
    // one per line.
    private val stamper = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        logFile = File(dir, "navigator_log_$stamp.txt")
        // Read the pref directly rather than through AppSettings: init() runs
        // from Application.onCreate and from broadcast receivers, and must not
        // drag the settings graph (or its migrations) in on that path.
        fileLoggingEnabled = appContext
            .getSharedPreferences("navigator_pairing", Context.MODE_PRIVATE)
            .getBoolean("file_logging_enabled", false)
        publicLogFile = if (fileLoggingEnabled) preparePublicLogFile(stamp) else null
        publicStamp = stamp
        initialized = true
        installCrashHandler()
        log("App", "Navigator logging started (file logging ${if (fileLoggingEnabled) "on" else "off"})")
        publicLogFile?.let { log("App", "Also mirrored to: ${it.absolutePath}") }
    }

    private var publicStamp: String = ""


    /**
     * Pre-API29 only: a plain file under the public Downloads directory,
     * visible to any file manager without needing our app to be running.
     * On API29+ scoped storage blocks direct File access to public
     * directories, so writeToPublicCopy() uses MediaStore instead.
     */
    private fun preparePublicLogFile(stamp: String): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null
        return try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val folder = File(downloads, "Navigator")
            folder.mkdirs()
            File(folder, "navigator_log_$stamp.txt")
        } catch (e: Exception) {
            null
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                log("CRASH", "Uncaught exception on thread ${thread.name}: ${Log.getStackTraceString(throwable)}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log crash", e)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun log(tag: String, message: String) {
        Log.d(TAG, "[$tag] $message")
        if (!initialized) return
        val timestamp = stamper.get()!!.format(Date())
        val line = "[$timestamp] [$tag] $message"
        // Offload all disk/MediaStore I/O to the dedicated log thread - and skip
        // it entirely unless the rider turned file logging on.
        if (fileLoggingEnabled) {
            ioHandler.post {
                try {
                    logFile.appendText(line + "\n")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write private log file", e)
                }
                if (publicLogFile == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    publicLogFile = preparePublicLogFile(publicStamp)
                }
                writeToPublicCopy(line)
            }
        }
        synchronized(bufferLock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        if (!publishScheduled) {
            publishScheduled = true
            mainHandler.post {
                publishScheduled = false
                _lines.value = synchronized(bufferLock) { buffer.toList() }
            }
        }
    }

    private fun writeToPublicCopy(line: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToMediaStoreDownloads(line)
            } else {
                publicLogFile?.appendText(line + "\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write public log copy", e)
        }
    }

    private var mediaStoreUri: android.net.Uri? = null
    private var mediaStoreUriResolved = false

    private fun writeToMediaStoreDownloads(line: String) {
        val resolver = appContext.contentResolver
        if (!mediaStoreUriResolved) {
            mediaStoreUriResolved = true
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, logFile.name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Navigator")
            }
            mediaStoreUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }
        val uri = mediaStoreUri ?: return
        resolver.openOutputStream(uri, "wa")?.use { it.write((line + "\n").toByteArray()) }
    }

    fun currentLogFile(): File? = if (initialized && logFile.exists()) logFile else null

    /**
     * A file to hand to the share sheet. With file logging on that's the live
     * log; with it off there is no file on disk, so dump the in-memory buffer
     * to a throwaway one - SHARE stays useful without paying for disk writes on
     * every line.
     */
    fun snapshotForShare(): File? {
        if (!initialized) return null
        currentLogFile()?.let { return it }
        return try {
            // Same dir as the real log: it's the one the FileProvider exports.
            val out = File(logFile.parentFile, "navigator_log_buffer.txt")
            out.writeText(synchronized(bufferLock) { buffer.joinToString("\n", postfix = "\n") })
            out
        } catch (e: Exception) {
            Log.e(TAG, "Failed to snapshot the log buffer", e)
            null
        }
    }

    fun clear() {
        synchronized(bufferLock) { buffer.clear() }
        _lines.value = emptyList()
    }
}
