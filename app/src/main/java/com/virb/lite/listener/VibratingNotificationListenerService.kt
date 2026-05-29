package com.virb.lite.listener

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.virb.lite.MainActivity
import com.virb.lite.R
import com.virb.lite.log.VibrationLogger
import com.virb.lite.prefs.AppPrefs
import com.virb.lite.vibe.VibrationHelper
import java.util.LinkedHashMap
import java.util.LinkedHashSet

class VibratingNotificationListenerService : NotificationListenerService() {
    private lateinit var prefs: AppPrefs
    private val reconnectReplayCandidateKeys = LinkedHashSet<String>()
    private val recentlyVibratedKeys = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > MAX_RECENTLY_VIBRATED_KEYS
        }
    }
    private val trailingVibrationHandler = Handler(Looper.getMainLooper())
    private var lastVibrationAtMs: Long = 0L
    private var listenerConnectedAtMs: Long = 0L
    private var burstStartedAtMs: Long = 0L
    private var burstEndsAtMs: Long = 0L
    private var trailingVibrationCount: Int = 0
    private var hasPendingTrailingVibration = false
    private val trailingVibrationRunnable = Runnable { runTrailingVibrationIfNeeded() }
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                cancelPendingBurstWindow()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        lastVibrationAtMs = prefs.lastVibrationAtMs()
        VibrationLogger.init(this)
        VibrationLogger.logEvent("service_start")
        debugLog("Service onCreate")
        createNotificationChannel()
        registerReceiver(userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    override fun onDestroy() {
        isConnected = false
        cancelPendingBurstWindow()
        try {
            unregisterReceiver(userPresentReceiver)
        } catch (_: Exception) {
            // Receiver may already be unregistered during service teardown.
        }
        super.onDestroy()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        recentlyVibratedKeys.remove(sbn.key)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        debugLog("onListenerConnected — listener is active")
        VibrationLogger.logEvent("listener_connected")
        listenerConnectedAtMs = System.currentTimeMillis()
        rememberCurrentlyActiveNotifications()
        resetRebindBackoff()
        startForegroundRuntime()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, "onListenerDisconnected — listener was killed by system")
        VibrationLogger.logEvent("listener_disconnected")
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
            VibrationLogger.logSkip("quiet_hours", pkg)
            return
        }

        if (prefs.vibrateOnlyWhenLocked() && shouldSkipUnlockReplay(now)) {
            debugLog("skip: unlock cooldown")
            VibrationLogger.logSkip("unlock_cooldown", pkg)
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
            VibrationLogger.logSkip("call_active", pkg)
            return
        }

        if (isMediaTransportNotification(sbn.notification)) {
            debugLog("skip: media transport notification pkg=$pkg")
            VibrationLogger.logSkip("media_transport", pkg)
            return
        }

        if (isClockAlarmNotification(sbn)) {
            debugLog("skip: clock alarm notification pkg=$pkg ch=${sbn.notification.channelId}")
            VibrationLogger.logSkip("clock_alarm", pkg)
            return
        }

        // Always skip foreground-service notifications that carry no user-visible content
        // (e.g. Xiaomi AICR, various OEM background workers). This is independent of the
        // "ignore system packages" toggle because such notifications are never user-facing.
        if (isBlankForegroundServiceNotification(sbn.notification)) {
            debugLog("skip: blank foreground-service notification pkg=$pkg")
            VibrationLogger.logSkip("blank_fgs", pkg)
            return
        }

        // Skip notifications posted on channels that MIUI/HyperOS reserves exclusively
        // for internal background services (e.g. hide_foreground, fg_service).
        // This is package-agnostic: it catches any OEM service using these channel IDs
        // without requiring a per-package entry in IGNORED_SYSTEM_NOISE_PACKAGES.
        if (isBackgroundServiceChannel(sbn.notification)) {
            debugLog("skip: background-service channel pkg=$pkg ch=${sbn.notification.channelId}")
            VibrationLogger.logSkip("bkg_channel", pkg)
            return
        }

        if (prefs.ignoreSystemPackages() && shouldIgnoreSystemNotification(sbn)) {
            debugLog("skip: system notification pkg=$pkg category=${sbn.notification.category}")
            VibrationLogger.logSkip("system_noise", pkg)
            return
        }

        if (prefs.vibrateOnlyWhenLocked() && !deviceLocked) {
            debugLog("skip: device unlocked")
            VibrationLogger.logSkip("unlocked", pkg)
            return
        }

        val gapMs = prefs.globalGapMs().toLong()
        restoreActiveBurstWindowFromLastVibration(now, gapMs)
        clearExpiredBurstWindow(now)
        if (burstEndsAtMs > now) {
            val delta = now - burstStartedAtMs
            debugLog("skip: within burst window, delta=$delta gap=$gapMs")
            VibrationLogger.logSkip("gap_${delta}ms", pkg)
            recentlyVibratedKeys[sbn.key] = sbn.postTime
            scheduleTrailingVibration(burstEndsAtMs - now)
            return
        }

        val result = vibrateNow(now, deviceLocked, sbn, "notification")
        if (result) {
            recentlyVibratedKeys[sbn.key] = sbn.postTime
            burstStartedAtMs = now
            burstEndsAtMs = now + gapMs
            trailingVibrationCount = 0
            hasPendingTrailingVibration = false
            trailingVibrationHandler.removeCallbacks(trailingVibrationRunnable)
        }
        debugLog("vibrate result=$result")
    }

    private fun scheduleTrailingVibration(delayMs: Long) {
        if (burstEndsAtMs <= System.currentTimeMillis()) return
        hasPendingTrailingVibration = true
        trailingVibrationHandler.removeCallbacks(trailingVibrationRunnable)
        trailingVibrationHandler.postDelayed(
            trailingVibrationRunnable,
            delayMs.coerceAtLeast(0L)
        )
        debugLog("scheduled trailing vibration delayMs=$delayMs")
    }

    private fun nextTrailingDelayMs(gapMs: Long): Long {
        val multiplier = (trailingVibrationCount + 1).coerceAtMost(TRAILING_BACKOFF_MAX_MULTIPLIER)
        val maxDelayMs = TRAILING_BACKOFF_MAX_DELAY_MS.coerceAtLeast(gapMs)
        return (gapMs * multiplier.toLong()).coerceAtMost(maxDelayMs)
    }

    private fun cancelPendingBurstWindow() {
        hasPendingTrailingVibration = false
        burstStartedAtMs = 0L
        burstEndsAtMs = 0L
        trailingVibrationCount = 0
        trailingVibrationHandler.removeCallbacks(trailingVibrationRunnable)
    }

    private fun clearExpiredBurstWindow(now: Long) {
        if (burstEndsAtMs > 0L && now >= burstEndsAtMs && !hasPendingTrailingVibration) {
            burstStartedAtMs = 0L
            burstEndsAtMs = 0L
            trailingVibrationCount = 0
        }
    }

    private fun restoreActiveBurstWindowFromLastVibration(now: Long, gapMs: Long) {
        if (burstEndsAtMs > now) return

        val lastVibrationAt = lastVibrationAtMs
        if (lastVibrationAt <= 0L) return
        if (prefs.lastUserPresentAtMs() > lastVibrationAt) return

        val restoredBurstEndsAt = lastVibrationAt + gapMs
        if (restoredBurstEndsAt > now) {
            burstStartedAtMs = lastVibrationAt
            burstEndsAtMs = restoredBurstEndsAt
        }
    }

    private fun runTrailingVibrationIfNeeded() {
        if (!hasPendingTrailingVibration) return

        val now = System.currentTimeMillis()
        if (!prefs.isEnabled() || prefs.isInQuietHours() || isCallActive()) {
            cancelPendingBurstWindow()
            return
        }

        if (prefs.vibrateOnlyWhenLocked() && !isDeviceLocked()) {
            debugLog("cancel trailing: device unlocked")
            cancelPendingBurstWindow()
            return
        }

        val remainingDelayMs = burstEndsAtMs - now
        if (remainingDelayMs > 0L) {
            scheduleTrailingVibration(remainingDelayMs)
            return
        }

        hasPendingTrailingVibration = false
        val deviceLocked = isDeviceLocked()
        val result = vibrateNow(now, deviceLocked, null, "trailing")
        if (result) {
            val gapMs = prefs.globalGapMs().toLong()
            trailingVibrationCount += 1
            burstStartedAtMs = now
            burstEndsAtMs = now + nextTrailingDelayMs(gapMs)
        } else {
            burstStartedAtMs = 0L
            burstEndsAtMs = 0L
            trailingVibrationCount = 0
        }
        debugLog("trailing vibrate result=$result")
    }

    private fun vibrateNow(
        now: Long,
        deviceLocked: Boolean,
        sbn: StatusBarNotification?,
        reason: String,
    ): Boolean {
        val ms = prefs.vibrationMs().toLong()
        val amplitudePercent = prefs.vibrationAmplitude()
        val amplitude = ((amplitudePercent * 255 + 50) / 100).coerceIn(1, 255)
        debugLog("vibrating for $reason ms=$ms amplitude=$amplitude")
        val result = VibrationHelper.vibrate(this, ms, amplitude, acquireWakeLock = deviceLocked)
        if (result) {
            lastVibrationAtMs = now
            prefs.markVibrationNow(now)
            val notif = sbn?.notification
            val title = notif?.extras
                ?.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()?.take(30)?.replace('\n', ' ') ?: ""
            val category  = notif?.category ?: ""
            val channelId = notif?.channelId ?: ""
            val pkg       = sbn?.packageName ?: reason
            VibrationLogger.logVibrate(pkg, title, category, channelId, deviceLocked, reason)
        }
        return result
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
        // Notifications may wake the lock screen; it is still locked until USER_PRESENT.
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard != null) return keyguard.isKeyguardLocked
        val pm = getSystemService(PowerManager::class.java)
        return pm?.isInteractive == false
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
        if (packageName in IGNORED_SYSTEM_NOISE_PACKAGES) return true

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

    private fun isBlankForegroundServiceNotification(notification: Notification): Boolean {
        if (notification.flags and Notification.FLAG_FOREGROUND_SERVICE == 0) return false

        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        return title.isBlank() && text.isBlank() && subText.isBlank()
    }

    private fun isMediaTransportNotification(notification: Notification): Boolean {
        return notification.category == Notification.CATEGORY_TRANSPORT
    }

    private fun isClockAlarmNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName !in CLOCK_PACKAGES) return false

        val notification = sbn.notification
        val channelId = notification.channelId?.lowercase(java.util.Locale.ROOT).orEmpty()
        return notification.category == Notification.CATEGORY_ALARM ||
                channelId.contains("alarm") ||
                channelId.contains("timer")
    }

    private fun isBackgroundServiceChannel(notification: Notification): Boolean {
        val ch = notification.channelId?.lowercase(java.util.Locale.ROOT) ?: return false
        return ch in BACKGROUND_SERVICE_CHANNELS
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
        private const val MAX_RECENTLY_VIBRATED_KEYS = 256
        private const val TRAILING_BACKOFF_MAX_DELAY_MS = 60_000L
        private const val TRAILING_BACKOFF_MAX_MULTIPLIER = 4
        private val ALLOWED_MESSAGING_PACKAGES = setOf(
            "com.ss.android.lark",
            "com.larksuite.suite",
            "com.bytedance.ee.lark"
        )
        private val IGNORED_SYSTEM_NOISE_PACKAGES = setOf(
            "com.xiaomi.aicr"
        )
        private val CLOCK_PACKAGES = setOf(
            "com.android.deskclock",
            "com.google.android.deskclock"
        )

        // Channel IDs used by MIUI/HyperOS exclusively for internal background services.
        // Any notification on these channels is never user-facing and must be silenced
        // regardless of the package name or the "ignore system packages" toggle.
        private val BACKGROUND_SERVICE_CHANNELS = setOf(
            "channel_foreground_service",
            "com.miui.gallery.hide",
            "hide_foreground",
            "fgs_hide",
            "fg_service",
            "foreground_service",
            "foreground",
            "notification_channel_foreground_service"
        )

        @Volatile
        var isConnected: Boolean = false

        @Volatile
        private var lastRebindElapsedMs: Long = 0L

        @Volatile
        private var currentRebindIntervalMs: Long = INITIAL_REBIND_INTERVAL_MS
    }
}
