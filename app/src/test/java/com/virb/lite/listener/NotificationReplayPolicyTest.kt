package com.virb.lite.listener

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationReplayPolicyTest {
    @Test
    fun oldNotificationIsSuppressedImmediatelyAfterUnlock() {
        assertTrue(
            shouldSuppressUnlockReplay(
                notificationPostTimeMs = 9_000L,
                lastUserPresentAtMs = 10_000L,
                nowMs = 12_000L,
                suppressWindowMs = 8_000L
            )
        )
    }

    @Test
    fun newNotificationAfterRelockIsNotSuppressed() {
        assertFalse(
            shouldSuppressUnlockReplay(
                notificationPostTimeMs = 12_000L,
                lastUserPresentAtMs = 10_000L,
                nowMs = 12_100L,
                suppressWindowMs = 8_000L
            )
        )
    }

    @Test
    fun oldNotificationOutsideSuppressWindowIsNotSuppressed() {
        assertFalse(
            shouldSuppressUnlockReplay(
                notificationPostTimeMs = 9_000L,
                lastUserPresentAtMs = 10_000L,
                nowMs = 18_000L,
                suppressWindowMs = 8_000L
            )
        )
    }
}
