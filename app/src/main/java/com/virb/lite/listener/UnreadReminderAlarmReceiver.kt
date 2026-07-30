package com.virb.lite.listener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.virb.lite.log.VibrationLogger

class UnreadReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_UNREAD_REMINDER_ALARM) {
            val expectedTriggerElapsedMs =
                intent.getLongExtra(EXTRA_TRIGGER_ELAPSED_MS, 0L)
            val dispatched =
                VibratingNotificationListenerService.dispatchUnreadReminderAlarm(
                    expectedTriggerElapsedMs
                )
            if (!dispatched) {
                VibrationLogger.init(context)
                VibrationLogger.logEvent(
                    "unread_alarm_dropped reason=no_active_listener " +
                            "expected_elapsed_ms=$expectedTriggerElapsedMs"
                )
            }
        }
    }

    companion object {
        const val ACTION_UNREAD_REMINDER_ALARM =
            "com.virb.lite.action.UNREAD_REMINDER_ALARM"
        const val EXTRA_TRIGGER_ELAPSED_MS =
            "com.virb.lite.extra.TRIGGER_ELAPSED_MS"
    }
}
