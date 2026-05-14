package com.virb.lite.listener

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.virb.lite.MainActivity
import com.virb.lite.R
import com.virb.lite.prefs.AppPrefs
import com.virb.lite.vibe.VibrationHelper

class VibratingNotificationListenerService : NotificationListenerService() {
    private lateinit var prefs: AppPrefs
    private var lastVibrationAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        lastVibrationAtMs = prefs.lastVibrationAtMs()
        debugLog("Service onCreate")
        createNotificationChannel()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        debugLog("onListenerConnected — listener is active")
        resetRebindBackoff()
        startForegroundRuntime()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "onListenerDisconnected — listener was killed by system")
        stopForeground(STOP_FOREGROUND_REMOVE)
        requestListenerRebind()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val deviceLocked = isDeviceLocked()
        debugLog("onNotificationPosted: pkg=$pkg id=${sbn.id} locked=$deviceLocked")

        if (!prefs.isEnabled()) {
            debugLog("skip: switch disabled")
            return
        }

        if (prefs.vibrateOnlyWhenLocked() && !deviceLocked) {
            debugLog("skip: device unlocked")
            return
        }

        if (prefs.ignoreSystemPackages() && shouldIgnorePackage(pkg)) {
            debugLog("skip: system package $pkg")
            return
        }

        val now = System.currentTimeMillis()
        val gapMs = prefs.globalGapMs().toLong()
        val lastVibrationAt = lastVibrationAtMs
        if (lastVibrationAt > 0L && now - lastVibrationAt < gapMs) {
            debugLog("skip: within global gap, delta=${now - lastVibrationAt} gap=$gapMs")
            return
        }

        val ms = prefs.vibrationMs().toLong()
        debugLog("vibrating for pkg=$pkg ms=$ms")
        val result = VibrationHelper.vibrate(this, ms, acquireWakeLock = deviceLocked)
        if (result) {
            lastVibrationAtMs = now
            prefs.markVibrationNow(now)
        }
        debugLog("vibrate result=$result")
    }

    private fun requestListenerRebind() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val intervalMs = currentRebindIntervalMs
        if (lastRebindElapsedMs > 0L && nowElapsed - lastRebindElapsedMs < intervalMs) {
            debugLog("skip rebind: backoff active interval=$intervalMs")
            return
        }

        try {
            val component = ComponentName(this, VibratingNotificationListenerService::class.java)
            requestRebind(component)
            lastRebindElapsedMs = nowElapsed
            currentRebindIntervalMs = (intervalMs * 2).coerceAtMost(MAX_REBIND_INTERVAL_MS)
            Log.d(TAG, "requestRebind called after listener disconnect, nextIntervalMs=$currentRebindIntervalMs")
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind failed: ${e.message}")
        }
    }

    private fun resetRebindBackoff() {
        lastRebindElapsedMs = 0L
        currentRebindIntervalMs = INITIAL_REBIND_INTERVAL_MS
    }

    private fun isDeviceLocked(): Boolean {
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        return keyguardManager?.isKeyguardLocked == true
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

    private fun startForegroundRuntime() {
        try {
            startForeground(FOREGROUND_NOTIF_ID, buildForegroundNotification())
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
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

    private fun debugLog(message: String) {
        if (ENABLE_VERBOSE_LOGS) {
            Log.d(TAG, message)
        }
    }

    companion object {
        private const val TAG = "VirbListen"
        private const val CHANNEL_ID = "virb_fg_channel"
        private const val FOREGROUND_NOTIF_ID = 1
        private const val ENABLE_VERBOSE_LOGS = false
        private const val INITIAL_REBIND_INTERVAL_MS = 15_000L
        private const val MAX_REBIND_INTERVAL_MS = 5 * 60_000L

        @Volatile
        private var lastRebindElapsedMs: Long = 0L

        @Volatile
        private var currentRebindIntervalMs: Long = INITIAL_REBIND_INTERVAL_MS
    }
}
