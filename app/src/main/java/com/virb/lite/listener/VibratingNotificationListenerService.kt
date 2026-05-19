package com.virb.lite.listener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.virb.lite.MainActivity
import com.virb.lite.R
import com.virb.lite.prefs.AppPrefs
import com.virb.lite.vibe.VibrationHelper
import java.util.LinkedHashSet

class VibratingNotificationListenerService : NotificationListenerService() {
    private lateinit var prefs: AppPrefs
    private val reconnectReplayCandidateKeys = LinkedHashSet<String>()
    private val recentlyVibratedKeys = HashMap<String, Long>()  // key -> postTime
    private var lastVibrationAtMs: Long = 0L
    private var listenerConnectedAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        lastVibrationAtMs = prefs.lastVibrationAtMs()
        debugLog("Service onCreate")
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        recentlyVibratedKeys.remove(sbn.key)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        debugLog("onListenerConnected — listener is active")
        listenerConnectedAtMs = System.currentTimeMillis()
        rememberCurrentlyActiveNotifications()
        resetRebindBackoff()
        startForegroundRuntime()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, "onListenerDisconnected — listener was killed by system")
        stopForeground(STOP_FOREGROUND_REMOVE)
        requestListenerRebind()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val deviceLocked = isDeviceLocked()
        val now = System.currentTimeMillis()
        debugLog("onNotificationPosted: pkg=$pkg id=${sbn.id} locked=$deviceLocked")

        if (pkg == packageName) {
            debugLog("skip: own foreground/service notification")
            return
        }

        if (!prefs.isEnabled()) {
            debugLog("skip: switch disabled")
            return
        }

        if (prefs.isInQuietHours()) {
            debugLog("skip: quiet hours active")
            return
        }

        if (prefs.vibrateOnlyWhenLocked() && shouldSkipUnlockReplay(now)) {
            debugLog("skip: unlock cooldown")
            return
        }

        if (shouldSkipReconnectReplay(sbn)) {
            debugLog("skip: reconnect replay key=${sbn.key}")
            return
        }

        if (recentlyVibratedKeys[sbn.key] == sbn.postTime) {
            debugLog("skip: duplicate repost key=${sbn.key} postTime=${sbn.postTime}")
            return
        }

        if (isCallActive()) {
            debugLog("skip: call is active")
            return
        }

        if (prefs.ignoreSystemPackages() && shouldIgnoreSystemNotification(sbn)) {
            debugLog("skip: system notification pkg=$pkg category=${sbn.notification.category}")
            return
        }

        if (prefs.vibrateOnlyWhenLocked() && !deviceLocked) {
            debugLog("skip: device unlocked")
            return
        }

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
            recentlyVibratedKeys[sbn.key] = sbn.postTime
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
        // 屏幕不亮 = 用户不在使用手机 = 视为锁屏，可以震动
        // PowerManager.isInteractive 比 KeyguardManager.isKeyguardLocked
        // 更可靠：不受 MIUI 锁屏延迟设置影响
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null) return !pm.isInteractive
        return false
    }

    private fun shouldSkipUnlockReplay(now: Long): Boolean {
        val lastUserPresentAt = prefs.lastUserPresentAtMs()
        return lastUserPresentAt > 0L && now - lastUserPresentAt in 0 until UNLOCK_REPLAY_SUPPRESS_MS
    }

    private fun rememberCurrentlyActiveNotifications() {
        reconnectReplayCandidateKeys.clear()
        activeNotifications
            ?.filterNot { it.packageName == packageName }
            ?.forEach { reconnectReplayCandidateKeys.add(it.key) }
        debugLog("reconnect replay candidates=${reconnectReplayCandidateKeys.size}")
    }

    private fun shouldSkipReconnectReplay(sbn: StatusBarNotification): Boolean {
        if (!reconnectReplayCandidateKeys.remove(sbn.key)) return false
        val connectedAt = listenerConnectedAtMs
        return connectedAt > 0L && sbn.postTime <= connectedAt + RECONNECT_REPLAY_GRACE_MS
    }

    private fun isCallActive(): Boolean {
        val audioManager = getSystemService(AudioManager::class.java) ?: return false
        return audioManager.mode == AudioManager.MODE_IN_CALL ||
                audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun shouldIgnoreSystemNotification(sbn: StatusBarNotification): Boolean {
        val packageName = sbn.packageName
        if (isAllowedMessagingPackage(packageName)) return false

        when (sbn.notification.category) {
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_STATUS,
            Notification.CATEGORY_SYSTEM -> return true
        }

        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            // Only ignore true OS core processes (uid < 10000).
            // Do NOT filter by FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP — pre-installed
            // apps such as Feishu on OEM/enterprise devices carry that flag but are
            // regular user-facing messaging apps that should trigger vibration.
            appInfo.uid < 10_000
        } catch (_: Exception) {
            false
        }
    }

    private fun isAllowedMessagingPackage(packageName: String): Boolean {
        return packageName in ALLOWED_MESSAGING_PACKAGES
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
        private const val UNLOCK_REPLAY_SUPPRESS_MS = 8_000L
        private const val RECONNECT_REPLAY_GRACE_MS = 1_000L
        private const val INITIAL_REBIND_INTERVAL_MS = 15_000L
        private const val MAX_REBIND_INTERVAL_MS = 5 * 60_000L
        private val ALLOWED_MESSAGING_PACKAGES = setOf(
            "com.ss.android.lark",
            "com.larksuite.suite",
            "com.bytedance.ee.lark"
        )

        @Volatile
        var isConnected: Boolean = false

        @Volatile
        private var lastRebindElapsedMs: Long = 0L

        @Volatile
        private var currentRebindIntervalMs: Long = INITIAL_REBIND_INTERVAL_MS
    }
}
