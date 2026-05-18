package com.virb.lite.listener

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
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
    private val reminderHandler = Handler(Looper.getMainLooper())
    private val reconnectReplayCandidateKeys = LinkedHashSet<String>()
    private var lastVibrationAtMs: Long = 0L
    private var listenerConnectedAtMs: Long = 0L
    private var anchorNotificationKey: String? = null
    private var anchorReminderFired: Boolean = false
    private var reminderRunnable: Runnable? = null

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
        listenerConnectedAtMs = System.currentTimeMillis()
        rememberCurrentlyActiveNotifications()
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

        if (prefs.vibrateOnlyWhenLocked() && shouldSkipUnlockReplay(now)) {
            debugLog("skip: unlock cooldown")
            clearUnreadReminder()
            return
        }

        if (prefs.vibrateOnlyWhenLocked() && !deviceLocked) {
            debugLog("skip: device unlocked")
            return
        }

        if (shouldSkipReconnectReplay(sbn)) {
            debugLog("skip: reconnect replay key=${sbn.key}")
            clearUnreadReminder()
            return
        }

        if (isCallActive()) {
            debugLog("skip: call is active")
            clearUnreadReminder()
            return
        }

        if (prefs.ignoreSystemPackages() && shouldIgnorePackage(pkg)) {
            debugLog("skip: system package $pkg")
            return
        }

        // Arm unread reminder BEFORE the global gap check — the gap throttles
        // immediate vibration bursts but should not prevent a new anchor from
        // being established after the previous one was cleared.
        maybeStartUnreadReminder(sbn)

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

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == anchorNotificationKey) {
            debugLog("anchor notification removed, cancel unread reminder")
            clearUnreadReminder()
        }
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

    private fun maybeStartUnreadReminder(sbn: StatusBarNotification) {
        if (!prefs.unreadReminderEnabled()) return
        if (anchorNotificationKey != null) return

        val delayMs = prefs.unreadReminderDelayMs().toLong()
        anchorNotificationKey = sbn.key
        anchorReminderFired = false

        val runnable = Runnable {
            fireUnreadReminderIfAnchorStillActive()
        }
        reminderRunnable = runnable
        reminderHandler.postDelayed(runnable, delayMs)
        debugLog("unread reminder armed: key=${sbn.key} delayMs=$delayMs")
    }

    private fun fireUnreadReminderIfAnchorStillActive() {
        val anchorKey = anchorNotificationKey ?: return
        reminderRunnable = null

        if (anchorReminderFired || !prefs.isEnabled() || !prefs.unreadReminderEnabled()) {
            clearUnreadReminder()
            return
        }

        if (prefs.vibrateOnlyWhenLocked() && !isDeviceLocked()) {
            debugLog("skip unread reminder: device unlocked")
            anchorReminderFired = true
            return
        }

        if (isCallActive()) {
            debugLog("skip unread reminder: call is active")
            clearUnreadReminder()
            return
        }

        val anchorStillActive = activeNotifications?.any { it.key == anchorKey } == true
        if (!anchorStillActive) {
            clearUnreadReminder()
            return
        }

        val result = VibrationHelper.vibrateUnreadReminder(this, acquireWakeLock = isDeviceLocked())
        anchorReminderFired = true
        debugLog("unread reminder fired result=$result")
    }

    private fun clearUnreadReminder() {
        reminderRunnable?.let { reminderHandler.removeCallbacks(it) }
        reminderRunnable = null
        anchorNotificationKey = null
        anchorReminderFired = false
    }

    private fun shouldIgnorePackage(packageName: String): Boolean {
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

        @Volatile
        private var lastRebindElapsedMs: Long = 0L

        @Volatile
        private var currentRebindIntervalMs: Long = INITIAL_REBIND_INTERVAL_MS
    }
}
