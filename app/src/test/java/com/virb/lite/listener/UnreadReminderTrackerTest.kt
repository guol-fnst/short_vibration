package com.virb.lite.listener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreadReminderTrackerTest {
    @Test
    fun newNotificationBecomesLatestAndResetsRepeatCount() {
        val tracker = UnreadReminderTracker()
        tracker.track("first", "app.one")
        tracker.markRepeated()

        tracker.track("second", "app.two")

        assertEquals(0, tracker.repeatCount)
        assertEquals("app.two", tracker.latestPackage())
    }

    @Test
    fun updatingExistingNotificationMovesItToLatest() {
        val tracker = UnreadReminderTracker()
        tracker.track("first", "app.one")
        tracker.track("second", "app.two")

        tracker.track("first", "app.one")

        assertEquals("app.one", tracker.latestPackage())
    }

    @Test
    fun retainingActiveNotificationsRemovesDismissedEntries() {
        val tracker = UnreadReminderTracker()
        tracker.track("first", "app.one")
        tracker.track("second", "app.two")

        tracker.retainActive(setOf("first"))

        assertFalse(tracker.isEmpty)
        assertEquals("app.one", tracker.latestPackage())
    }

    @Test
    fun clearRemovesAllNotificationsAndRepeatCount() {
        val tracker = UnreadReminderTracker()
        tracker.track("first", "app.one")
        tracker.markRepeated()

        tracker.clear()

        assertTrue(tracker.isEmpty)
        assertEquals(0, tracker.repeatCount)
        assertNull(tracker.latestPackage())
    }
}
