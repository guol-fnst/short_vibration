package com.virb.lite.listener

import android.app.AlarmManager
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
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.virb.lite.MainActivity
import com.virb.lite.R
import com.virb.lite.prefs.AppPrefs
import com.virb.lite.reminder.UnreadReminderReceiver
import com.virb.lite.vibe.VibrationHelper
import java.util.LinkedHashSet

class VibratingNotificationListenerService : NotificationListenerService() {
    private lateinit var prefs: AppPrefs
    private val reconnectReplayCandidateKeys = LinkedHashSet<String>()
    private var lastVibrationAtMs: Long = 0L
    private var listenerConnectedAtMs: Long = 0L
    private var anchorNotificationKey: String? = null
    private var anchorReminderFired: Boolean = false

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        lastVibrationAtMs = prefs.lastVibrationAtMs()
        anchorNotificationKey = prefs.anchorNotificationKey()
        anchorReminderFired = prefs.anchorReminderFired()
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

        // Clean up a fired anchor when device is unlocked so a new
        // notification can arm a fresh reminder for the next lock session.
        if (anchorReminderFired && !deviceLocked) {
            clearUnreadReminder()
        }

        // Arm unread reminder BEFORE the global gap check — the gap throttles
        // immediate vibration bursts but should not prevent a new anchor from
        // being established after the previous one was cleared.
        maybeStartUnreadReminder(sbn)

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
        // Sync in-memory anchor against prefs: BootReceiver may have cleared it on unlock
        // without going through this service instance.
        if (anchorNotificationKey != null && prefs.anchorNotificationKey() == null) {
            anchorNotificationKey = null
            anchorReminderFired = false
        }
        if (anchorNotificationKey != null) return

        val delayMs = prefs.unreadReminderDelayMs().toLong()
        anchorNotificationKey = sbn.key
        anchorReminderFired = false
        prefs.setAnchorNotificationKey(sbn.key)
        prefs.setAnchorReminderFired(false)

        val alarmIntent = Intent(this, UnreadReminderReceiver::class.java).apply {
            action = UnreadReminderReceiver.ACTION_REMIND
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            REMINDER_ALARM_REQUEST_CODE,
            alarmIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        try {
            getSystemService(AlarmManager::class.java)
                .setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } catch (e: SecurityException) {
            getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        }
        debugLog("unread reminder armed: key=${sbn.key} delayMs=$delayMs")
    }

    private fun clearUnreadReminder() {
        val cancelIntent = Intent(this, UnreadReminderReceiver::class.java).apply {
            action = UnreadReminderReceiver.ACTION_REMIND
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            REMINDER_ALARM_REQUEST_CODE,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        pendingIntent?.let {
            getSystemService(AlarmManager::class.java).cancel(it)
        }
        anchorNotificationKey = null
        anchorReminderFired = false
        prefs.clearReminderAnchor()
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
        private const val REMINDER_ALARM_REQUEST_CODE = 1001

        @Volatile
        private var lastRebindElapsedMs: Long = 0L

        @Volatile
        private var currentRebindIntervalMs: Long = INITIAL_REBIND_INTERVAL_MS
    }
}
