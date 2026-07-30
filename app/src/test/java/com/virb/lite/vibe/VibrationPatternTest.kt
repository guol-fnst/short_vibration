package com.virb.lite.vibe

import org.junit.Assert.assertEquals
import org.junit.Test

class VibrationPatternTest {
    @Test
    fun defaultPatternUsesConfiguredDurationWithinBounds() {
        assertEquals(1L, VibrationPattern.DEFAULT.effectDurationMs(0L))
        assertEquals(75L, VibrationPattern.DEFAULT.effectDurationMs(75L))
        assertEquals(1000L, VibrationPattern.DEFAULT.effectDurationMs(5000L))
    }

    @Test
    fun namedPatternsHaveStableDistinctDurations() {
        assertEquals(35L, VibrationPattern.SHORT.effectDurationMs(10L))
        assertEquals(160L, VibrationPattern.DOUBLE.effectDurationMs(10L))
        assertEquals(220L, VibrationPattern.LONG.effectDurationMs(10L))
    }

    @Test
    fun unknownStoredPatternFallsBackToDefault() {
        assertEquals(VibrationPattern.DOUBLE, VibrationPattern.fromStoredValue("double"))
        assertEquals(VibrationPattern.DEFAULT, VibrationPattern.fromStoredValue("unknown"))
    }
}
