package com.virb.lite.log

import android.content.Context
import com.virb.lite.prefs.AppPrefs
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight file-based logger for vibration events.
 *
 * Log format (one entry per line):
 *   MM-dd HH:mm:ss.SSS VIB pkg=… title="…" cat=… ch=… locked=… reason=…
 *   MM-dd HH:mm:ss.SSS SKP reason=… pkg=…
 *   MM-dd HH:mm:ss.SSS EVT …
 *
 * Call [init] once in Service.onCreate(). All other calls are safe to make
 * from any thread. IO is synchronised on [lock] to avoid interleaved writes.
 */
object VibrationLogger {

    private const val LOG_FILE_NAME = "virb_vib.log"
    private const val MAX_FILE_BYTES = 200_000L   // rotate when file exceeds 200 KB
    private const val TRIM_TO_BYTES  = 100_000L   // keep newest 100 KB after rotation

    private val fmt  = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile private var logFile: File? = null
    @Volatile private var fileLoggingEnabled: Boolean = true

    // ── lifecycle ────────────────────────────────────────────────────────────

    fun init(context: Context) {
        val appContext = context.applicationContext
        logFile = File(appContext.filesDir, LOG_FILE_NAME)
        fileLoggingEnabled = AppPrefs(appContext).fileLoggingEnabled()
    }

    fun setFileLoggingEnabled(enabled: Boolean) {
        fileLoggingEnabled = enabled
    }

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Record a vibration that actually fired.
     *
     * @param pkg       source package name
     * @param title     notification title (already sanitised / truncated)
     * @param category  notification category string (may be empty)
     * @param channelId notification channel id (may be empty)
     * @param locked    was the screen off at the time
     * @param reason    "notification" | "trailing"
     */
    fun logVibrate(
        pkg: String,
        title: String,
        category: String,
        channelId: String,
        locked: Boolean,
        reason: String,
    ) {
        write("VIB pkg=$pkg title=\"$title\" cat=${category.ifEmpty { "-" }} ch=${channelId.ifEmpty { "-" }} locked=$locked reason=$reason")
    }

    /**
     * Record a notification that was silently skipped.
     *
     * @param reason short description of the skip cause
     * @param pkg    source package name (empty for service-level skips)
     */
    fun logSkip(reason: String, pkg: String = "") {
        write("SKP reason=$reason${if (pkg.isNotEmpty()) " pkg=$pkg" else ""}")
    }

    /** Record a service lifecycle or diagnostic event. */
    fun logEvent(message: String) {
        write("EVT $message")
    }

    /** Return the log [File], or null if [init] has not been called yet. */
    fun getLogFile(): File? = logFile

    /**
     * Read the last [maxLines] lines from the log.
     * Safe to call on the main thread (file is small; UI usage only).
     */
    fun readTail(maxLines: Int = 100): String {
        val file = logFile ?: return "(logger not initialised)"
        if (!file.exists()) return "(no log yet — no vibrations recorded)"
        return try {
            val lines = file.readLines()
            if (lines.isEmpty()) "(log is empty)"
            else lines.takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "read error: ${e.message}"
        }
    }

    // ── internal ─────────────────────────────────────────────────────────────

    private fun write(message: String) {
        if (!fileLoggingEnabled) return
        val file = logFile ?: return
        synchronized(lock) {
            try {
                if (file.length() > MAX_FILE_BYTES) trimFile(file)
                FileWriter(file, /* append= */ true).use { w ->
                    w.write("${fmt.format(Date())} $message\n")
                }
            } catch (_: Exception) {
                // Logging must never crash the app.
            }
        }
    }

    private fun trimFile(file: File) {
        try {
            val text = file.readText()
            val cut  = (text.length - TRIM_TO_BYTES).toInt()
            if (cut <= 0) return
            val nl = text.indexOf('\n', cut)
            if (nl < 0) return
            file.writeText("[...older entries trimmed...]\n" + text.substring(nl + 1))
        } catch (_: Exception) {}
    }
}
