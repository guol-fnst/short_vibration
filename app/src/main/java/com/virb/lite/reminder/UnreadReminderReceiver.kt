package com.virb.lite.reminder

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import com.virb.lite.prefs.AppPrefs
import com.virb.lite.vibe.VibrationHelper

class UnreadReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) return

        val prefs = AppPrefs(context)

        if (!prefs.isEnabled() || !prefs.unreadReminderEnabled()) {
            Log.d(TAG, "Unread reminder skipped: disabled after scheduling")
            prefs.clearReminderAnchor()
            return
        }

        if (prefs.anchorReminderFired() || prefs.anchorNotificationKey() == null) {
            Log.d(TAG, "Unread reminder skipped: already fired or anchor cleared")
            prefs.clearReminderAnchor()
            return
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null && (am.mode == AudioManager.MODE_IN_CALL ||
                    am.mode == AudioManager.MODE_IN_COMMUNICATION)) {
            Log.d(TAG, "Unread reminder skipped: call active")
            return
        }

        if (prefs.vibrateOnlyWhenLocked()) {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (km?.isKeyguardLocked != true) {
                Log.d(TAG, "Unread reminder skipped: device unlocked")
                prefs.clearReminderAnchor()
                return
            }
        }

        Log.d(TAG, "Firing unread reminder vibration")
        VibrationHelper.vibrateUnreadReminder(context, acquireWakeLock = true)
        prefs.setAnchorReminderFired(true)
    }

    companion object {
        const val TAG = "VirbReminder"
        const val ACTION_REMIND = "com.virb.lite.action.UNREAD_REMINDER"
        private const val ALARM_REQUEST_CODE = 1001

        fun cancelPendingAlarm(context: Context) {
            val intent = Intent(context, UnreadReminderReceiver::class.java).apply {
                action = ACTION_REMIND
            }
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            )
            if (pi != null) {
                context.getSystemService(AlarmManager::class.java)?.cancel(pi)
            }
        }
    }
}
