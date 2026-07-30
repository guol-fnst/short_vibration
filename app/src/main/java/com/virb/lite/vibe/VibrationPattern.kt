package com.virb.lite.vibe

enum class VibrationPattern(val storedValue: String) {
    DEFAULT("default"),
    SHORT("short"),
    DOUBLE("double"),
    LONG("long");

    fun effectDurationMs(defaultDurationMs: Long): Long =
        when (this) {
            DEFAULT -> defaultDurationMs.coerceIn(1L, 1000L)
            SHORT -> SHORT_DURATION_MS
            DOUBLE -> DOUBLE_PULSE_MS * 2 + DOUBLE_GAP_MS
            LONG -> LONG_DURATION_MS
        }

    companion object {
        const val SHORT_DURATION_MS = 35L
        const val DOUBLE_PULSE_MS = 35L
        const val DOUBLE_GAP_MS = 90L
        const val LONG_DURATION_MS = 220L

        fun fromStoredValue(value: String?): VibrationPattern =
            entries.firstOrNull { it.storedValue == value } ?: DEFAULT
    }
}
