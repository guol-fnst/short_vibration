package com.virb.lite.listener

import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadAlarmTimingPolicyTest {
    @Test
    fun missingTriggerIsIgnored() {
        val decision = decideUnreadAlarmAction(
            expectedTriggerElapsedMs = 0L,
            scheduledTriggerElapsedMs = 10_000L,
            nowElapsedMs = 10_000L,
        )

        assertEquals(UnreadAlarmAction.IGNORE, decision.action)
    }

    @Test
    fun staleTriggerIsIgnored() {
        val decision = decideUnreadAlarmAction(
            expectedTriggerElapsedMs = 8_000L,
            scheduledTriggerElapsedMs = 10_000L,
            nowElapsedMs = 12_000L,
        )

        assertEquals(UnreadAlarmAction.IGNORE, decision.action)
    }

    @Test
    fun earlyDeliveryIsDeferredForRemainingTime() {
        val decision = decideUnreadAlarmAction(
            expectedTriggerElapsedMs = 10_000L,
            scheduledTriggerElapsedMs = 10_000L,
            nowElapsedMs = 7_500L,
        )

        assertEquals(UnreadAlarmAction.DEFER, decision.action)
        assertEquals(2_500L, decision.offsetMs)
    }

    @Test
    fun dueDeliveryRunsAndReportsLateness() {
        val decision = decideUnreadAlarmAction(
            expectedTriggerElapsedMs = 10_000L,
            scheduledTriggerElapsedMs = 10_000L,
            nowElapsedMs = 12_500L,
        )

        assertEquals(UnreadAlarmAction.RUN, decision.action)
        assertEquals(2_500L, decision.offsetMs)
    }
}
