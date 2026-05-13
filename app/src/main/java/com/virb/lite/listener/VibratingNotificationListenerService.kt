package com.virb.lite.listener

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
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
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        debugLog("onListenerConnected — listener is active")
        resetRebindBackoff()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "onListenerDisconnected — listener was killed by system")
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
        if (nowElapsed - lastRebindElapsedMs < intervalMs) {
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

    private fun debugLog(message: String) {
        if (ENABLE_VERBOSE_LOGS) {
            Log.d(TAG, message)
        }
    }

    companion object {
        private const val TAG = "VirbListen"
        private const val ENABLE_VERBOSE_LOGS = false
        private const val INITIAL_REBIND_INTERVAL_MS = 15_000L
        private const val MAX_REBIND_INTERVAL_MS = 5 * 60_000L

        @Volatile
        private var lastRebindElapsedMs: Long = 0L

        @Volatile
        private var currentRebindIntervalMs: Long = INITIAL_REBIND_INTERVAL_MS
    }
}
