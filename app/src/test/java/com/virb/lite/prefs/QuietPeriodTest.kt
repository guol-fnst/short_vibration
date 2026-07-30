package com.virb.lite.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietPeriodTest {
    @Test
    fun endTimeIsExcluded() {
        val period = QuietPeriod(
            startMin = 9 * 60,
            endMin = 17 * 60,
        )

        assertTrue(period.contains(16 * 60 + 59, MONDAY))
        assertFalse(period.contains(17 * 60, MONDAY))
    }

    @Test
    fun equalStartAndEndIsInvalid() {
        val period = QuietPeriod(
            startMin = 8 * 60,
            endMin = 8 * 60,
        )

        assertFalse(period.isValid)
        assertFalse(period.contains(8 * 60, MONDAY))
    }

    @Test
    fun weekdayPeriodDoesNotApplyOnWeekend() {
        val period = QuietPeriod(
            startMin = 9 * 60,
            endMin = 17 * 60,
            dayMask = QuietPeriod.WEEKDAYS_MASK,
        )

        assertTrue(period.contains(12 * 60, MONDAY))
        assertFalse(period.contains(12 * 60, SATURDAY))
    }

    @Test
    fun crossMidnightPeriodUsesStartDay() {
        val mondayOnly = 1 shl MONDAY
        val period = QuietPeriod(
            startMin = 22 * 60,
            endMin = 7 * 60,
            dayMask = mondayOnly,
        )

        assertTrue(period.contains(23 * 60, MONDAY))
        assertTrue(period.contains(6 * 60 + 59, TUESDAY))
        assertFalse(period.contains(7 * 60, TUESDAY))
        assertFalse(period.contains(23 * 60, TUESDAY))
    }

    @Test
    fun adjacentPeriodsDoNotOverlap() {
        val morning = QuietPeriod(9 * 60, 12 * 60)
        val afternoon = QuietPeriod(12 * 60, 17 * 60)

        assertFalse(morning.overlaps(afternoon))
    }

    @Test
    fun crossMidnightOverlapIsDetectedOnFollowingDay() {
        val mondayNight = QuietPeriod(
            startMin = 22 * 60,
            endMin = 7 * 60,
            dayMask = 1 shl MONDAY,
        )
        val tuesdayMorning = QuietPeriod(
            startMin = 6 * 60,
            endMin = 8 * 60,
            dayMask = 1 shl TUESDAY,
        )

        assertTrue(mondayNight.overlaps(tuesdayMorning))
    }

    @Test
    fun disabledPeriodDoesNotContainOrOverlap() {
        val disabled = QuietPeriod(9 * 60, 17 * 60, enabled = false)
        val enabled = QuietPeriod(10 * 60, 11 * 60)

        assertFalse(disabled.contains(10 * 60, MONDAY))
        assertFalse(disabled.overlaps(enabled))
    }

    private companion object {
        const val MONDAY = 0
        const val TUESDAY = 1
        const val SATURDAY = 5
    }
}
