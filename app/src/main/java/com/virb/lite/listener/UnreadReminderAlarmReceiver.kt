package com.virb.lite.listener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UnreadReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_UNREAD_REMINDER_ALARM) {
            VibratingNotificationListenerService.dispatchUnreadReminderAlarm()
        }
    }

    companion object {
        const val ACTION_UNREAD_REMINDER_ALARM =
            "com.virb.lite.action.UNREAD_REMINDER_ALARM"
    }
}
