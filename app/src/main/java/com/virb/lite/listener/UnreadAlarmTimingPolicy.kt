package com.virb.lite.listener

internal enum class UnreadAlarmAction {
    IGNORE,
    DEFER,
    RUN,
}

internal data class UnreadAlarmTimingDecision(
    val action: UnreadAlarmAction,
    val offsetMs: Long = 0L,
)

internal fun decideUnreadAlarmAction(
    expectedTriggerElapsedMs: Long,
    scheduledTriggerElapsedMs: Long,
    nowElapsedMs: Long,
): UnreadAlarmTimingDecision {
    if (expectedTriggerElapsedMs <= 0L ||
        scheduledTriggerElapsedMs <= 0L ||
        expectedTriggerElapsedMs != scheduledTriggerElapsedMs
    ) {
        return UnreadAlarmTimingDecision(UnreadAlarmAction.IGNORE)
    }

    val elapsedSinceTargetMs = nowElapsedMs - expectedTriggerElapsedMs
    return if (elapsedSinceTargetMs < 0L) {
        UnreadAlarmTimingDecision(
            action = UnreadAlarmAction.DEFER,
            offsetMs = -elapsedSinceTargetMs,
        )
    } else {
        UnreadAlarmTimingDecision(
            action = UnreadAlarmAction.RUN,
            offsetMs = elapsedSinceTargetMs,
        )
    }
}
