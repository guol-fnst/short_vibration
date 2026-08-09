package com.virb.lite.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.virb.lite.vibe.VibrationPattern
import java.util.Calendar
import java.util.LinkedHashSet

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun vibrateOnlyWhenLocked(): Boolean = prefs.getBoolean(KEY_LOCKED_ONLY, true)

    fun setVibrateOnlyWhenLocked(lockedOnly: Boolean) {
        prefs.edit { putBoolean(KEY_LOCKED_ONLY, lockedOnly) }
    }

    fun pauseInVibrateMode(): Boolean =
        prefs.getBoolean(KEY_PAUSE_IN_VIBRATE_MODE, true)

    fun setPauseInVibrateMode(pause: Boolean) {
        prefs.edit { putBoolean(KEY_PAUSE_IN_VIBRATE_MODE, pause) }
    }

    fun fileLoggingEnabled(): Boolean = prefs.getBoolean(KEY_FILE_LOGGING, true)

    fun setFileLoggingEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FILE_LOGGING, enabled) }
    }

    fun allowedPackages(): Set<String> {
        return prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())
            ?.let { LinkedHashSet(it) }
            ?: emptySet()
    }

    fun setAllowedPackages(packageNames: Set<String>) {
        prefs.edit {
            putStringSet(KEY_ALLOWED_PACKAGES, LinkedHashSet(packageNames))
        }
    }

    fun isPackageAllowed(packageName: String): Boolean {
        val allowed = allowedPackages()
        return allowed.isNotEmpty() && packageName in allowed
    }

    fun observedNotificationPackages(): Set<String> =
        prefs.getStringSet(KEY_OBSERVED_NOTIFICATION_PACKAGES, emptySet())
            ?.let { LinkedHashSet(it) }
            ?: emptySet()

    fun rememberNotificationPackage(packageName: String) {
        if (packageName.isBlank()) return
        val observed = observedNotificationPackages().toMutableSet()
        if (!observed.add(packageName)) return
        prefs.edit {
            putStringSet(
                KEY_OBSERVED_NOTIFICATION_PACKAGES,
                LinkedHashSet(observed.toList().takeLast(MAX_OBSERVED_PACKAGES))
            )
        }
    }

    fun vibrationPatternFor(packageName: String): VibrationPattern =
        vibrationPatterns()[packageName] ?: VibrationPattern.DEFAULT

    fun setVibrationPattern(packageName: String, pattern: VibrationPattern) {
        val patterns = vibrationPatterns().toMutableMap()
        if (pattern == VibrationPattern.DEFAULT) {
            patterns.remove(packageName)
        } else {
            patterns[packageName] = pattern
        }
        val stored = patterns.entries
            .mapTo(LinkedHashSet()) { "${it.key}=${it.value.storedValue}" }
        prefs.edit { putStringSet(KEY_VIBRATION_PATTERNS, stored) }
    }

    fun customVibrationPatternCount(packageNames: Set<String>): Int =
        vibrationPatterns().count { (packageName, _) -> packageName in packageNames }

    private fun vibrationPatterns(): Map<String, VibrationPattern> =
        prefs.getStringSet(KEY_VIBRATION_PATTERNS, emptySet())
            .orEmpty()
            .mapNotNull { token ->
                val separator = token.lastIndexOf('=')
                if (separator <= 0 || separator == token.lastIndex) return@mapNotNull null
                val packageName = token.substring(0, separator)
                val pattern = VibrationPattern.fromStoredValue(token.substring(separator + 1))
                if (pattern == VibrationPattern.DEFAULT) null else packageName to pattern
            }
            .toMap()

    fun vibrationMs(): Int {
        val raw = prefs.getInt(KEY_VIBRATION_MS, DEFAULT_VIBRATION_MS)
        val clamped = raw.coerceIn(MIN_VIBRATION_MS, MAX_VIBRATION_MS)
        if (raw != clamped) {
            prefs.edit { putInt(KEY_VIBRATION_MS, clamped) }
        }
        return clamped
    }

    fun setVibrationMs(durationMs: Int) {
        val clamped = durationMs.coerceIn(MIN_VIBRATION_MS, MAX_VIBRATION_MS)
        prefs.edit { putInt(KEY_VIBRATION_MS, clamped) }
    }

    fun globalGapMs(): Int {
        val raw = prefs.getInt(KEY_GLOBAL_GAP_MS, DEFAULT_GLOBAL_GAP_MS)
        val clamped = raw.coerceIn(MIN_GLOBAL_GAP_MS, MAX_GLOBAL_GAP_MS)
        if (raw != clamped) {
            prefs.edit { putInt(KEY_GLOBAL_GAP_MS, clamped) }
        }
        return clamped
    }

    fun setGlobalGapMs(gapMs: Int) {
        val clamped = gapMs.coerceIn(MIN_GLOBAL_GAP_MS, MAX_GLOBAL_GAP_MS)
        prefs.edit { putInt(KEY_GLOBAL_GAP_MS, clamped) }
    }

    fun vibrationAmplitude(): Int {
        val raw = prefs.getInt(KEY_VIBRATION_AMPLITUDE, DEFAULT_VIBRATION_AMPLITUDE)
        val stepped = normalizeVibrationAmplitude(raw)
        if (raw != stepped) {
            prefs.edit { putInt(KEY_VIBRATION_AMPLITUDE, stepped) }
        }
        return stepped
    }

    fun setVibrationAmplitude(percent: Int) {
        prefs.edit {
            putInt(KEY_VIBRATION_AMPLITUDE, normalizeVibrationAmplitude(percent))
        }
    }

    fun markUserPresentNow(epochMs: Long) {
        prefs.edit { putLong(KEY_LAST_USER_PRESENT_AT_MS, epochMs) }
    }

    fun lastUserPresentAtMs(): Long = prefs.getLong(KEY_LAST_USER_PRESENT_AT_MS, 0L)

    fun lastVibrationAtMs(): Long = prefs.getLong(KEY_LAST_VIBRATION_AT_MS, 0L)

    fun markVibrationNow(epochMs: Long) {
        prefs.edit { putLong(KEY_LAST_VIBRATION_AT_MS, epochMs) }
    }

    fun repeatReminderEnabled(): Boolean =
        prefs.getBoolean(KEY_REPEAT_REMINDER_ENABLED, false)

    fun setRepeatReminderEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_REPEAT_REMINDER_ENABLED, enabled) }
    }

    fun repeatReminderIntervalMin(): Int {
        val raw = prefs.getInt(KEY_REPEAT_REMINDER_INTERVAL_MIN, DEFAULT_REPEAT_INTERVAL_MIN)
        val clamped = raw.coerceIn(MIN_REPEAT_INTERVAL_MIN, MAX_REPEAT_INTERVAL_MIN)
        if (raw != clamped) {
            prefs.edit { putInt(KEY_REPEAT_REMINDER_INTERVAL_MIN, clamped) }
        }
        return clamped
    }

    fun setRepeatReminderIntervalMin(minutes: Int) {
        prefs.edit {
            putInt(
                KEY_REPEAT_REMINDER_INTERVAL_MIN,
                minutes.coerceIn(MIN_REPEAT_INTERVAL_MIN, MAX_REPEAT_INTERVAL_MIN)
            )
        }
    }

    fun repeatReminderMaxCount(): Int =
        prefs.getInt(KEY_REPEAT_REMINDER_MAX_COUNT, DEFAULT_REPEAT_MAX_COUNT)
            .coerceIn(MIN_REPEAT_MAX_COUNT, MAX_REPEAT_MAX_COUNT)

    fun setRepeatReminderMaxCount(count: Int) {
        prefs.edit {
            putInt(
                KEY_REPEAT_REMINDER_MAX_COUNT,
                count.coerceIn(MIN_REPEAT_MAX_COUNT, MAX_REPEAT_MAX_COUNT)
            )
        }
    }

    fun quietPeriods(): List<QuietPeriod> {
        val raw = prefs.getString(KEY_QUIET_PERIODS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("|").mapNotNull { token ->
            val parts = token.split(":")
            if (parts.size !in 2..4) return@mapNotNull null

            val startMin = parts[0].toIntOrNull() ?: return@mapNotNull null
            val endMin = parts[1].toIntOrNull() ?: return@mapNotNull null
            val dayMask = parts.getOrNull(2)?.toIntOrNull() ?: QuietPeriod.ALL_DAYS_MASK
            val enabled = parts.getOrNull(3)?.toIntOrNull()?.let { it != 0 } ?: true
            QuietPeriod(startMin, endMin, dayMask, enabled).takeIf { it.isValid }
        }.distinct()
    }

    fun setQuietPeriods(periods: List<QuietPeriod>) {
        val raw = periods
            .filter { it.isValid }
            .distinct()
            .sortedWith(compareBy({ it.startMin }, { it.endMin }, { it.dayMask }, { !it.enabled }))
            .joinToString("|") {
                "${it.startMin}:${it.endMin}:${it.dayMask}:${if (it.enabled) 1 else 0}"
            }
        prefs.edit { putString(KEY_QUIET_PERIODS, raw) }
    }

    fun isInQuietHours(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val periods = quietPeriods()
        if (periods.isEmpty()) return false
        return isInQuietHoursAt(nowEpochMs, periods)
    }

    fun millisUntilQuietHoursEnd(
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Long? {
        val periods = quietPeriods()
        if (periods.isEmpty() || !isInQuietHoursAt(nowEpochMs, periods)) return null

        val probe = Calendar.getInstance().apply {
            timeInMillis = nowEpochMs
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, 1)
        }
        repeat(MAX_QUIET_LOOKAHEAD_MINUTES) {
            if (!isInQuietHoursAt(probe, periods)) {
                return (probe.timeInMillis - nowEpochMs).coerceAtLeast(1L)
            }
            probe.add(Calendar.MINUTE, 1)
        }
        return null
    }

    private fun isInQuietHoursAt(
        epochMs: Long,
        periods: List<QuietPeriod>,
    ): Boolean =
        isInQuietHoursAt(
            Calendar.getInstance().apply { timeInMillis = epochMs },
            periods,
        )

    private fun isInQuietHoursAt(
        cal: Calendar,
        periods: List<QuietPeriod>,
    ): Boolean {
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val dayIndex = QuietPeriod.calendarDayToIndex(cal.get(Calendar.DAY_OF_WEEK))
        return periods.any { it.contains(minuteOfDay, dayIndex) }
    }

    companion object {
        private const val PREF_FILE = "virb_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LOCKED_ONLY = "locked_only"
        private const val KEY_PAUSE_IN_VIBRATE_MODE = "pause_in_vibrate_mode"
        private const val KEY_FILE_LOGGING = "file_logging"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val KEY_OBSERVED_NOTIFICATION_PACKAGES = "observed_notification_packages"
        private const val KEY_VIBRATION_PATTERNS = "vibration_patterns"
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
        private const val MAX_OBSERVED_PACKAGES = 100
        private const val MAX_QUIET_LOOKAHEAD_MINUTES = 8 * 24 * 60

        private fun normalizeVibrationAmplitude(percent: Int): Int {
            val clamped = percent.coerceIn(MIN_VIBRATION_AMPLITUDE, MAX_VIBRATION_AMPLITUDE)
            val rounded = ((clamped + VIBRATION_AMPLITUDE_STEP / 2) / VIBRATION_AMPLITUDE_STEP) *
                    VIBRATION_AMPLITUDE_STEP
            return rounded.coerceIn(MIN_VIBRATION_AMPLITUDE, MAX_VIBRATION_AMPLITUDE)
        }
    }
}

/**
 * Times are minutes since midnight. Selected days represent the start day, so a
 * Monday 22:00-07:00 period remains active until Tuesday 07:00.
 */
data class QuietPeriod(
    val startMin: Int,
    val endMin: Int,
    val dayMask: Int = ALL_DAYS_MASK,
    val enabled: Boolean = true,
) {
    val isValid: Boolean
        get() = startMin in 0 until MINUTES_PER_DAY &&
                endMin in 0 until MINUTES_PER_DAY &&
                startMin != endMin &&
                dayMask and ALL_DAYS_MASK != 0 &&
                dayMask and ALL_DAYS_MASK == dayMask

    val crossesMidnight: Boolean
        get() = startMin > endMin

    fun contains(minuteOfDay: Int, dayIndex: Int): Boolean {
        if (!enabled || !isValid || minuteOfDay !in 0 until MINUTES_PER_DAY ||
            dayIndex !in 0 until DAYS_PER_WEEK
        ) {
            return false
        }

        return if (!crossesMidnight) {
            isDaySelected(dayIndex) && minuteOfDay >= startMin && minuteOfDay < endMin
        } else {
            when {
                minuteOfDay >= startMin -> isDaySelected(dayIndex)
                minuteOfDay < endMin -> isDaySelected(previousDay(dayIndex))
                else -> false
            }
        }
    }

    fun isDaySelected(dayIndex: Int): Boolean =
        dayIndex in 0 until DAYS_PER_WEEK && dayMask and (1 shl dayIndex) != 0

    fun overlaps(other: QuietPeriod): Boolean {
        if (!enabled || !other.enabled || !isValid || !other.isValid) return false
        for (dayIndex in 0 until DAYS_PER_WEEK) {
            for (minuteOfDay in 0 until MINUTES_PER_DAY) {
                if (contains(minuteOfDay, dayIndex) &&
                    other.contains(minuteOfDay, dayIndex)
                ) {
                    return true
                }
            }
        }
        return false
    }

    companion object {
        const val DAYS_PER_WEEK = 7
        const val ALL_DAYS_MASK = (1 shl DAYS_PER_WEEK) - 1
        const val WEEKDAYS_MASK = 0b0011111
        const val WEEKEND_MASK = 0b1100000
        private const val MINUTES_PER_DAY = 24 * 60

        fun calendarDayToIndex(calendarDay: Int): Int =
            when (calendarDay) {
                Calendar.MONDAY -> 0
                Calendar.TUESDAY -> 1
                Calendar.WEDNESDAY -> 2
                Calendar.THURSDAY -> 3
                Calendar.FRIDAY -> 4
                Calendar.SATURDAY -> 5
                Calendar.SUNDAY -> 6
                else -> -1
            }

        private fun previousDay(dayIndex: Int): Int =
            (dayIndex + DAYS_PER_WEEK - 1) % DAYS_PER_WEEK
    }
}
