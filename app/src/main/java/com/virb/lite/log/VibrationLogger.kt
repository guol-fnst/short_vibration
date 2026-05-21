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
 *   MM-dd HH:mm:ss.SSS VIB pkg=... title="..." cat=... ch=... locked=... reason=...
 *   MM-dd HH:mm:ss.SSS SKP reason=... pkg=...
 *   MM-dd HH:mm:ss.SSS EVT ...
 *
 * File logging is enabled by default so vibration behavior can be diagnosed from the UI.
 */
object VibrationLogger {
    private const val LOG_FILE_NAME = "virb_vib.log"
    private const val MAX_FILE_BYTES = 200_000L
    private const val TRIM_TO_BYTES = 100_000L

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile private var logFile: File? = null
    @Volatile private var fileLoggingEnabled: Boolean = true

    fun init(context: Context) {
        val appContext = context.applicationContext
        logFile = File(appContext.filesDir, LOG_FILE_NAME)
        fileLoggingEnabled = AppPrefs(appContext).fileLoggingEnabled()
    }

    fun setFileLoggingEnabled(enabled: Boolean) {
        fileLoggingEnabled = enabled
    }

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

    fun logSkip(reason: String, pkg: String = "") {
        write("SKP reason=$reason${if (pkg.isNotEmpty()) " pkg=$pkg" else ""}")
    }

    fun logEvent(message: String) {
        write("EVT $message")
    }

    fun readTail(maxLines: Int = 100): String {
        val file = logFile ?: return "(logger not initialised)"
        if (!file.exists()) return "(no log yet - no vibrations recorded)"
        return try {
            val lines = file.readLines()
            if (lines.isEmpty()) "(log is empty)"
            else lines.takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "read error: ${e.message}"
        }
    }

    private fun write(message: String) {
        if (!fileLoggingEnabled) return
        val file = logFile ?: return
        synchronized(lock) {
            try {
                if (file.length() > MAX_FILE_BYTES) trimFile(file)
                FileWriter(file, true).use { w ->
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
            val cut = (text.length - TRIM_TO_BYTES).toInt()
            if (cut <= 0) return
            val nl = text.indexOf('\n', cut)
            if (nl < 0) return
            file.writeText("[...older entries trimmed...]\n" + text.substring(nl + 1))
        } catch (_: Exception) {
            // Best effort only.
        }
    }
}
