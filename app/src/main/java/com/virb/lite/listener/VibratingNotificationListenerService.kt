package com.virb.lite.listener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.virb.lite.MainActivity
import com.virb.lite.R
import com.virb.lite.prefs.AppPrefs
import com.virb.lite.vibe.VibrationHelper

class VibratingNotificationListenerService : NotificationListenerService() {
    private lateinit var prefs: AppPrefs

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "onListenerConnected — listener is active")
        // Promote to foreground service so MIUI/HyperOS cannot freeze this process.
        // Without this, MIUI's process-freezing kills the binder connection silently.
        startForeground(FOREGROUND_NOTIF_ID, buildForegroundNotification())
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "onListenerDisconnected — listener was killed by system")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        Log.d(TAG, "onNotificationPosted: pkg=$pkg id=${sbn.id} enabled=${prefs.isEnabled()}")

        if (!prefs.isEnabled()) {
            Log.d(TAG, "skip: switch disabled")
            return
        }

        if (shouldIgnorePackage(pkg)) {
            Log.d(TAG, "skip: system package $pkg")
            return
        }

        val ms = prefs.vibrationMs().toLong()
        Log.d(TAG, "vibrating for pkg=$pkg ms=$ms")
        val result = VibrationHelper.vibrate(this, ms)
        Log.d(TAG, "vibrate result=$result")
    }

    private fun shouldIgnorePackage(packageName: String): Boolean {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 ||
                    appInfo.uid < 10_000
        } catch (_: Exception) {
            false
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_MIN  // No sound, no popup, collapsed by default
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notif_fg_title))
            .setContentText(getString(R.string.notif_fg_text))
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "VirbListen"
        private const val CHANNEL_ID = "virb_fg_channel"
        private const val FOREGROUND_NOTIF_ID = 1
    }
}
