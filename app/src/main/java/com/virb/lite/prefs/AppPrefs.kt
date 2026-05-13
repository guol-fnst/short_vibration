package com.virb.lite.prefs

import android.content.Context
import android.content.SharedPreferences

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

    fun ignoreSystemPackages(): Boolean = prefs.getBoolean(KEY_IGNORE_SYSTEM, true)

    fun setIgnoreSystemPackages(ignore: Boolean) {
        prefs.edit().putBoolean(KEY_IGNORE_SYSTEM, ignore).apply()
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

    fun globalGapMs(): Int = prefs.getInt(KEY_GLOBAL_GAP_MS, DEFAULT_GLOBAL_GAP_MS)

    fun setGlobalGapMs(gapMs: Int) {
        val clamped = gapMs.coerceIn(MIN_GLOBAL_GAP_MS, MAX_GLOBAL_GAP_MS)
        prefs.edit().putInt(KEY_GLOBAL_GAP_MS, clamped).apply()
    }

    fun markBootNow(epochMs: Long) {
        prefs.edit().putLong(KEY_LAST_BOOT_AT_MS, epochMs).apply()
    }

    fun lastBootAtMs(): Long = prefs.getLong(KEY_LAST_BOOT_AT_MS, 0L)

    fun lastVibrationAtMs(): Long = prefs.getLong(KEY_LAST_VIBRATION_AT_MS, 0L)

    fun markVibrationNow(epochMs: Long) {
        prefs.edit().putLong(KEY_LAST_VIBRATION_AT_MS, epochMs).apply()
    }

    companion object {
        private const val PREF_FILE = "virb_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LOCKED_ONLY = "locked_only"
        private const val KEY_IGNORE_SYSTEM = "ignore_system"
        private const val KEY_VIBRATION_MS = "vibration_ms"
        private const val KEY_GLOBAL_GAP_MS = "global_gap_ms"
        private const val KEY_LAST_BOOT_AT_MS = "last_boot_at_ms"
        private const val KEY_LAST_VIBRATION_AT_MS = "last_vibration_at_ms"

        const val DEFAULT_VIBRATION_MS = 10
        const val MIN_VIBRATION_MS = 1
        const val MAX_VIBRATION_MS = 1000

        const val DEFAULT_GLOBAL_GAP_MS = 3000
        const val MIN_GLOBAL_GAP_MS = 500
        const val MAX_GLOBAL_GAP_MS = 10000
    }
}
