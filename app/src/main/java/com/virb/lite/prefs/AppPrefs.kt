package com.virb.lite.prefs

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar
import java.util.LinkedHashSet

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun vibrateOnlyWhenLocked(): Boolean = prefs.getBoolean(KEY_LOCKED_ONLY, true)

    fun setVibrateOnlyWhenLocked(lockedOnly: Boolean) {
        prefs.edit().putBoolean(KEY_LOCKED_ONLY, lockedOnly).apply()
    }

    fun fileLoggingEnabled(): Boolean = prefs.getBoolean(KEY_FILE_LOGGING, true)

    fun setFileLoggingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FILE_LOGGING, enabled).apply()
    }

    fun allowedPackages(): Set<String> {
        return prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())
            ?.let { LinkedHashSet(it) }
            ?: emptySet()
    }

    fun setAllowedPackages(packageNames: Set<String>) {
        prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, LinkedHashSet(packageNames)).apply()
    }

    fun isPackageAllowed(packageName: String): Boolean {
        val allowed = allowedPackages()
        return allowed.isNotEmpty() && packageName in allowed
    }

    fun vibrationMs(): Int {
        val raw = prefs.getInt(KEY_VIBRATION_MS, DEFAULT_VIBRATION_MS)
        val clamped = raw.coerceIn(MIN_VIBRATION_MS, MAX_VIBRATION_MS)
        if (raw != clamped) {
            prefs.edit().putInt(KEY_VIBRATION_MS, clamped).apply()
        }
        return clamped
    }

    fun setVibrationMs(durationMs: Int) {
        val clamped = durationMs.coerceIn(MIN_VIBRATION_MS, MAX_VIBRATION_MS)
        prefs.edit().putInt(KEY_VIBRATION_MS, clamped).apply()
    }

    fun globalGapMs(): Int {
        val raw = prefs.getInt(KEY_GLOBAL_GAP_MS, DEFAULT_GLOBAL_GAP_MS)
        val clamped = raw.coerceIn(MIN_GLOBAL_GAP_MS, MAX_GLOBAL_GAP_MS)
        if (raw != clamped) {
            prefs.edit().putInt(KEY_GLOBAL_GAP_MS, clamped).apply()
        }
        return clamped
    }

    fun setGlobalGapMs(gapMs: Int) {
        val clamped = gapMs.coerceIn(MIN_GLOBAL_GAP_MS, MAX_GLOBAL_GAP_MS)
        prefs.edit().putInt(KEY_GLOBAL_GAP_MS, clamped).apply()
    }

    fun vibrationAmplitude(): Int {
        val raw = prefs.getInt(KEY_VIBRATION_AMPLITUDE, DEFAULT_VIBRATION_AMPLITUDE)
        val stepped = normalizeVibrationAmplitude(raw)
        if (raw != stepped) {
            prefs.edit().putInt(KEY_VIBRATION_AMPLITUDE, stepped).apply()
        }
        return stepped
    }

    fun setVibrationAmplitude(percent: Int) {
        prefs.edit().putInt(KEY_VIBRATION_AMPLITUDE, normalizeVibrationAmplitude(percent)).apply()
    }

    fun markUserPresentNow(epochMs: Long) {
        prefs.edit().putLong(KEY_LAST_USER_PRESENT_AT_MS, epochMs).apply()
    }

    fun lastUserPresentAtMs(): Long = prefs.getLong(KEY_LAST_USER_PRESENT_AT_MS, 0L)

    fun lastVibrationAtMs(): Long = prefs.getLong(KEY_LAST_VIBRATION_AT_MS, 0L)

    fun markVibrationNow(epochMs: Long) {
        prefs.edit().putLong(KEY_LAST_VIBRATION_AT_MS, epochMs).apply()
    }

    fun repeatReminderEnabled(): Boolean =
        prefs.getBoolean(KEY_REPEAT_REMINDER_ENABLED, false)

    fun setRepeatReminderEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REPEAT_REMINDER_ENABLED, enabled).apply()
    }

    fun repeatReminderIntervalMin(): Int {
        val raw = prefs.getInt(KEY_REPEAT_REMINDER_INTERVAL_MIN, DEFAULT_REPEAT_INTERVAL_MIN)
        val clamped = raw.coerceIn(MIN_REPEAT_INTERVAL_MIN, MAX_REPEAT_INTERVAL_MIN)
        if (raw != clamped) {
            prefs.edit().putInt(KEY_REPEAT_REMINDER_INTERVAL_MIN, clamped).apply()
        }
        return clamped
    }

    fun setRepeatReminderIntervalMin(minutes: Int) {
        prefs.edit()
            .putInt(
                KEY_REPEAT_REMINDER_INTERVAL_MIN,
                minutes.coerceIn(MIN_REPEAT_INTERVAL_MIN, MAX_REPEAT_INTERVAL_MIN)
            )
            .apply()
    }

    fun repeatReminderMaxCount(): Int =
        prefs.getInt(KEY_REPEAT_REMINDER_MAX_COUNT, DEFAULT_REPEAT_MAX_COUNT)
            .coerceIn(MIN_REPEAT_MAX_COUNT, MAX_REPEAT_MAX_COUNT)

    fun setRepeatReminderMaxCount(count: Int) {
        prefs.edit()
            .putInt(
                KEY_REPEAT_REMINDER_MAX_COUNT,
                count.coerceIn(MIN_REPEAT_MAX_COUNT, MAX_REPEAT_MAX_COUNT)
            )
            .apply()
    }

    fun quietPeriods(): List<QuietPeriod> {
        val raw = prefs.getString(KEY_QUIET_PERIODS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("|").mapNotNull { token ->
            val parts = token.split(":")
            if (parts.size == 2) {
                val s = parts[0].toIntOrNull() ?: return@mapNotNull null
                val e = parts[1].toIntOrNull() ?: return@mapNotNull null
                QuietPeriod(s, e)
            } else {
                null
            }
        }
    }

    fun setQuietPeriods(periods: List<QuietPeriod>) {
        val raw = periods.joinToString("|") { "${it.startMin}:${it.endMin}" }
        prefs.edit().putString(KEY_QUIET_PERIODS, raw).apply()
    }

    fun isInQuietHours(): Boolean {
        val periods = quietPeriods()
        if (periods.isEmpty()) return false
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return periods.any { it.contains(now) }
    }

    companion object {
        private const val PREF_FILE = "virb_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LOCKED_ONLY = "locked_only"
        private const val KEY_FILE_LOGGING = "file_logging"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val KEY_VIBRATION_MS = "vibration_ms"
        private const val KEY_GLOBAL_GAP_MS = "global_gap_ms"
        private const val KEY_VIBRATION_AMPLITUDE = "vibration_amplitude"
        private const val KEY_LAST_USER_PRESENT_AT_MS = "last_user_present_at_ms"
        private const val KEY_LAST_VIBRATION_AT_MS = "last_vibration_at_ms"
        private const val KEY_QUIET_PERIODS = "quiet_periods"
        private const val KEY_REPEAT_REMINDER_ENABLED = "repeat_reminder_enabled"
        private const val KEY_REPEAT_REMINDER_INTERVAL_MIN = "repeat_reminder_interval_min"
        private const val KEY_REPEAT_REMINDER_MAX_COUNT = "repeat_reminder_max_count"

        const val DEFAULT_VIBRATION_MS = 10
        const val MIN_VIBRATION_MS = 1
        const val MAX_VIBRATION_MS = 1000

        const val DEFAULT_VIBRATION_AMPLITUDE = 100
        const val MIN_VIBRATION_AMPLITUDE = 10
        const val MAX_VIBRATION_AMPLITUDE = 100
        const val VIBRATION_AMPLITUDE_STEP = 10

        const val DEFAULT_GLOBAL_GAP_MS = 3000
        const val MIN_GLOBAL_GAP_MS = 500
        const val MAX_GLOBAL_GAP_MS = 99000

        const val DEFAULT_REPEAT_INTERVAL_MIN = 5
        const val MIN_REPEAT_INTERVAL_MIN = 1
        const val MAX_REPEAT_INTERVAL_MIN = 60
        const val DEFAULT_REPEAT_MAX_COUNT = 3
        const val MIN_REPEAT_MAX_COUNT = 1
        const val MAX_REPEAT_MAX_COUNT = 10

        private fun normalizeVibrationAmplitude(percent: Int): Int {
            val clamped = percent.coerceIn(MIN_VIBRATION_AMPLITUDE, MAX_VIBRATION_AMPLITUDE)
            val rounded = ((clamped + VIBRATION_AMPLITUDE_STEP / 2) / VIBRATION_AMPLITUDE_STEP) *
                    VIBRATION_AMPLITUDE_STEP
            return rounded.coerceIn(MIN_VIBRATION_AMPLITUDE, MAX_VIBRATION_AMPLITUDE)
        }
    }
}

/**
 * [startMin] and [endMin] are minutes since midnight (0-1439).
 * Cross-midnight periods are supported, for example 22:00 to 07:00.
 */
data class QuietPeriod(val startMin: Int, val endMin: Int) {
    fun contains(minuteOfDay: Int): Boolean =
        if (startMin <= endMin) minuteOfDay in startMin..endMin
        else minuteOfDay >= startMin || minuteOfDay <= endMin
}
