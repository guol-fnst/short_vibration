package com.virb.lite.listener

import android.app.KeyguardManager
import android.app.AlarmManager
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
    private val unreadReminderTracker = UnreadReminderTracker()
    private var lastVibrationAtMs: Long = 0L
    private var listenerConnectedAtMs: Long = 0L
    private var burstStartedAtMs: Long = 0L
    private var burstEndsAtMs: Long = 0L
    private var trailingVibrationCount: Int = 0
    private var hasPendingTrailingVibration = false
    private var pendingTrailingPackage: String? = null
    private var scheduledReminderTriggerElapsedMs: Long = 0L
    private val trailingVibrationRunnable = Runnable { runTrailingVibrationIfNeeded() }
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                prefs.markUserPresentNow(System.currentTimeMillis())
                cancelPendingBurstWindow()
                cancelRepeatReminders("user_present")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        activeInstance = this
        lastVibrationAtMs = prefs.lastVibrationAtMs()
        VibrationLogger.init(this)
        VibrationLogger.logEvent("service_start")
        debugLog("Service onCreate")
        createNotificationChannel()
        registerReceiver(userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    override fun onDestroy() {
        isConnected = false
        if (activeInstance === this) activeInstance = null
        cancelPendingBurstWindow()
        cancelRepeatReminders("service_destroyed")
        try {
            unregisterReceiver(userPresentReceiver)
        } catch (_: Exception) {
            // Receiver may already be unregistered during service teardown.
        }
        super.onDestroy()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        recentlyVibratedKeys.remove(sbn.key)
        if (unreadReminderTracker.remove(sbn.key)) {
            VibrationLogger.logEvent(
                "unread_remove pkg=${sbn.packageName} pending=${unreadReminderTracker.size}"
            )
            if (unreadReminderTracker.isEmpty) {
                cancelRepeatReminders("all_notifications_removed")
                return
            }
        }
        runRepeatReminderIfDueFromNaturalWakeup("notification_removed")
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
        runRepeatReminderIfDueFromNaturalWakeup("listener_connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        cancelPendingBurstWindow()
        cancelRepeatReminders("listener_disconnected")
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
        prefs.rememberNotificationPackage(pkg)
        runRepeatReminderIfDueFromNaturalWakeup("notification_posted")
        if (!prefs.isEnabled()) {
            debugLog("skip: switch disabled")
            return
        }

        if (!prefs.isPackageAllowed(pkg)) {
            debugLog("skip: package not in whitelist pkg=$pkg")
            VibrationLogger.logSkip("not_in_whitelist", pkg)
            return
        }

        if (prefs.vibrateOnlyWhenLocked() && shouldSkipUnlockReplay(sbn, now)) {
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

        // Always skip foreground-service notifications that carry no user-visible content.
        if (isBlankForegroundServiceNotification(sbn.notification)) {
            debugLog("skip: blank foreground-service notification pkg=$pkg")
            VibrationLogger.logSkip("blank_fgs", pkg)
            return
        }

        // Skip notifications posted on channels that MIUI/HyperOS reserves exclusively
        // for internal background services (e.g. hide_foreground, fg_service).
        // This is package-agnostic and remains active even when a user-facing app is whitelisted.
        if (isBackgroundServiceChannel(sbn.notification)) {
            debugLog("skip: background-service channel pkg=$pkg ch=${sbn.notification.channelId}")
            VibrationLogger.logSkip("bkg_channel", pkg)
            return
        }

        if (prefs.vibrateOnlyWhenLocked() && !deviceLocked) {
            debugLog("skip: device unlocked")
            VibrationLogger.logSkip("unlocked", pkg)
            return
        }

        trackRepeatReminder(sbn, deviceLocked)

        if (shouldPauseForVibrateMode()) {
            debugLog("skip immediate vibration: phone is in vibrate mode")
            VibrationLogger.logSkip("phone_vibrate_mode", pkg)
            return
        }

        if (prefs.isInQuietHours()) {
            debugLog("skip immediate vibration: quiet hours active")
            VibrationLogger.logSkip("quiet_hours", pkg)
            return
        }

        if (isCallActive()) {
            debugLog("skip immediate vibration: call is active")
            VibrationLogger.logSkip("call_active", pkg)
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
            pendingTrailingPackage = pkg
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

    private fun trackRepeatReminder(
        sbn: StatusBarNotification,
        deviceLocked: Boolean,
    ) {
        if (!prefs.repeatReminderEnabled()) return
        if (!deviceLocked) {
            VibrationLogger.logEvent(
                "unread_not_tracked reason=unlocked pkg=${sbn.packageName}"
            )
            return
        }
        if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) {
            VibrationLogger.logEvent(
                "unread_not_tracked reason=ongoing pkg=${sbn.packageName}"
            )
            return
        }

        unreadReminderTracker.track(sbn.key, sbn.packageName)
        VibrationLogger.logEvent(
            "unread_track pkg=${sbn.packageName} pending=${unreadReminderTracker.size} " +
                    "interval_min=${prefs.repeatReminderIntervalMin()} " +
                    "max=${prefs.repeatReminderMaxCount()}"
        )
        scheduleRepeatReminder()
    }

    private fun scheduleRepeatReminder() {
        if (unreadReminderTracker.isEmpty || !prefs.repeatReminderEnabled()) return

        if (prefs.isInQuietHours()) {
            scheduleRepeatReminderAfterQuietHours()
            return
        }

        val delayMs = prefs.repeatReminderIntervalMin() * 60_000L
        scheduleRepeatReminderAfter(delayMs)
    }

    private fun scheduleRepeatReminderAfterQuietHours() {
        val delayMs = prefs.millisUntilQuietHoursEnd()
        if (delayMs == null) {
            cancelScheduledReminderAlarm()
            scheduledReminderTriggerElapsedMs = 0L
            VibrationLogger.logEvent(
                "unread_deferred reason=quiet_hours_no_end"
            )
            return
        }

        VibrationLogger.logEvent(
            "unread_deferred reason=quiet_hours delay_ms=$delayMs"
        )
        scheduleRepeatReminderAfter(delayMs)
    }

    private fun scheduleRepeatReminderAfter(delayMs: Long) {
        val triggerAtMs = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(0L)
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            VibrationLogger.logEvent(
                "unread_schedule_failed reason=no_alarm_manager"
            )
            return
        }
        cancelScheduledReminderAlarm(alarmManager)
        scheduledReminderTriggerElapsedMs = 0L
        try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMs,
                repeatReminderPendingIntent(triggerAtMs)
            )
        } catch (e: Exception) {
            scheduledReminderTriggerElapsedMs = 0L
            VibrationLogger.logEvent(
                "unread_schedule_failed reason=${e.javaClass.simpleName}"
            )
            return
        }
        scheduledReminderTriggerElapsedMs = triggerAtMs
        VibrationLogger.logEvent(
            "unread_scheduled delay_ms=${delayMs.coerceAtLeast(0L)} " +
                    "trigger_elapsed_ms=$triggerAtMs " +
                    "repeat_count=${unreadReminderTracker.repeatCount} " +
                    "pending=${unreadReminderTracker.size}"
        )
        debugLog(
            "scheduled unread reminder count=${unreadReminderTracker.repeatCount} delayMs=$delayMs"
        )
    }

    internal fun runRepeatReminderIfNeeded(expectedTriggerElapsedMs: Long) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val scheduledTriggerElapsedMs = scheduledReminderTriggerElapsedMs
        VibrationLogger.logEvent(
            "unread_alarm_received connected=$isConnected " +
                    "now_elapsed_ms=$nowElapsedMs " +
                    "expected_elapsed_ms=$expectedTriggerElapsedMs " +
                    "scheduled_elapsed_ms=$scheduledTriggerElapsedMs " +
                    "pending=${unreadReminderTracker.size} " +
                    "repeat_count=${unreadReminderTracker.repeatCount}"
        )
        val decision = decideUnreadAlarmAction(
            expectedTriggerElapsedMs = expectedTriggerElapsedMs,
            scheduledTriggerElapsedMs = scheduledTriggerElapsedMs,
            nowElapsedMs = nowElapsedMs,
        )
        when (decision.action) {
            UnreadAlarmAction.IGNORE -> {
                VibrationLogger.logEvent(
                    "unread_alarm_ignored reason=stale_or_missing_trigger"
                )
                return
            }

            UnreadAlarmAction.DEFER -> {
                VibrationLogger.logEvent(
                    "unread_alarm_deferred reason=early_delivery " +
                            "remaining_ms=${decision.offsetMs}"
                )
                scheduleRepeatReminderAfter(decision.offsetMs)
                return
            }

            UnreadAlarmAction.RUN -> {
                scheduledReminderTriggerElapsedMs = 0L
                VibrationLogger.logEvent(
                    "unread_alarm_due late_ms=${decision.offsetMs}"
                )
            }
        }
        runDueRepeatReminder()
    }

    private fun runRepeatReminderIfDueFromNaturalWakeup(source: String) {
        val triggerElapsedMs = scheduledReminderTriggerElapsedMs
        if (triggerElapsedMs <= 0L) return

        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (nowElapsedMs < triggerElapsedMs) return

        cancelScheduledReminderAlarm()
        scheduledReminderTriggerElapsedMs = 0L
        VibrationLogger.logEvent(
            "unread_due_from_wakeup source=$source " +
                    "late_ms=${nowElapsedMs - triggerElapsedMs}"
        )
        runDueRepeatReminder()
    }

    private fun runDueRepeatReminder() {
        if (!isConnected) {
            cancelRepeatReminders("listener_not_connected")
            return
        }

        if (!prefs.isEnabled() || !prefs.repeatReminderEnabled()) {
            cancelRepeatReminders("feature_disabled")
            return
        }

        if (!retainActiveReminderNotifications()) {
            debugLog("cancel unread reminder: active notifications unavailable")
            cancelRepeatReminders("active_notifications_unavailable")
            return
        }
        if (unreadReminderTracker.isEmpty) {
            cancelRepeatReminders("no_active_notifications")
            return
        }

        if (!isDeviceLocked()) {
            debugLog("cancel unread reminder: device unlocked")
            cancelRepeatReminders("device_unlocked")
            return
        }

        if (prefs.isInQuietHours()) {
            debugLog("defer unread reminder: quiet hours")
            scheduleRepeatReminderAfterQuietHours()
            return
        }

        if (isCallActive()) {
            debugLog("defer unread reminder: call active")
            VibrationLogger.logEvent("unread_deferred reason=call_active")
            scheduleRepeatReminder()
            return
        }

        if (shouldPauseForVibrateMode()) {
            debugLog("defer unread reminder: phone is in vibrate mode")
            VibrationLogger.logEvent("unread_deferred reason=phone_vibrate_mode")
            scheduleRepeatReminder()
            return
        }

        val maxCount = prefs.repeatReminderMaxCount()
        if (unreadReminderTracker.repeatCount >= maxCount) {
            cancelRepeatReminders("max_count_reached")
            return
        }

        val now = System.currentTimeMillis()
        val minNextVibrationAt = lastVibrationAtMs + prefs.globalGapMs()
        if (now < minNextVibrationAt) {
            VibrationLogger.logEvent(
                "unread_deferred reason=global_gap delay_ms=${minNextVibrationAt - now}"
            )
            scheduleRepeatReminderAfter(minNextVibrationAt - now)
            return
        }

        cancelPendingBurstWindow()
        val latestPackage = unreadReminderTracker.latestPackage()
            ?: return cancelRepeatReminders("source_package_missing")
        val result = vibrateNow(
            now = now,
            deviceLocked = true,
            sbn = null,
            reason = "unread_repeat",
            sourcePackage = latestPackage
        )
        if (!result) {
            cancelRepeatReminders("vibration_failed")
            return
        }

        unreadReminderTracker.markRepeated()
        VibrationLogger.logEvent(
            "unread_repeat_complete count=${unreadReminderTracker.repeatCount} " +
                    "max=$maxCount pending=${unreadReminderTracker.size}"
        )
        if (unreadReminderTracker.repeatCount < maxCount) {
            scheduleRepeatReminder()
        } else {
            cancelRepeatReminders("max_count_completed")
        }
    }

    private fun onReminderSettingsChanged() {
        if (unreadReminderTracker.isEmpty) return
        if (!prefs.isEnabled() || !prefs.repeatReminderEnabled()) {
            cancelRepeatReminders("feature_disabled")
            return
        }
        VibrationLogger.logEvent("reminder_settings_changed rescheduling_unread=true")
        scheduleRepeatReminder()
    }

    private fun retainActiveReminderNotifications(): Boolean {
        val activeKeys = try {
            activeNotifications
                ?.asSequence()
                ?.map { it.key }
                ?.toSet()
                ?: return false
        } catch (e: Exception) {
            Log.w(TAG, "activeNotifications unavailable: ${e.javaClass.simpleName}")
            return false
        }
        unreadReminderTracker.retainActive(activeKeys)
        return true
    }

    private fun cancelRepeatReminders(reason: String) {
        val pending = unreadReminderTracker.size
        val repeatCount = unreadReminderTracker.repeatCount
        val triggerElapsedMs = scheduledReminderTriggerElapsedMs
        cancelScheduledReminderAlarm()
        scheduledReminderTriggerElapsedMs = 0L
        unreadReminderTracker.clear()
        if (pending > 0 || repeatCount > 0 || triggerElapsedMs > 0L) {
            VibrationLogger.logEvent(
                "unread_cancelled reason=$reason pending=$pending repeat_count=$repeatCount"
            )
        }
    }

    private fun cancelScheduledReminderAlarm(
        alarmManager: AlarmManager? = getSystemService(AlarmManager::class.java),
    ) {
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            UNREAD_REMINDER_REQUEST_CODE,
            unreadReminderIntent(),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (alarmManager != null && pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
    }

    private fun repeatReminderPendingIntent(
        triggerElapsedMs: Long? = null,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            UNREAD_REMINDER_REQUEST_CODE,
            unreadReminderIntent(triggerElapsedMs),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun unreadReminderIntent(triggerElapsedMs: Long? = null): Intent =
        Intent(this, UnreadReminderAlarmReceiver::class.java).apply {
            action = UnreadReminderAlarmReceiver.ACTION_UNREAD_REMINDER_ALARM
            if (triggerElapsedMs != null) {
                putExtra(
                    UnreadReminderAlarmReceiver.EXTRA_TRIGGER_ELAPSED_MS,
                    triggerElapsedMs
                )
            }
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
        pendingTrailingPackage = null
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
        if (
            !prefs.isEnabled() ||
            prefs.isInQuietHours() ||
            isCallActive() ||
            shouldPauseForVibrateMode()
        ) {
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
        val sourcePackage = pendingTrailingPackage
        pendingTrailingPackage = null
        val deviceLocked = isDeviceLocked()
        val result = vibrateNow(
            now = now,
            deviceLocked = deviceLocked,
            sbn = null,
            reason = "trailing",
            sourcePackage = sourcePackage,
        )
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
        sourcePackage: String? = null,
    ): Boolean {
        val pkg = sbn?.packageName ?: sourcePackage
        val pattern = pkg
            ?.let(prefs::vibrationPatternFor)
            ?: com.virb.lite.vibe.VibrationPattern.DEFAULT
        val ms = prefs.vibrationMs().toLong()
        val amplitudePercent = prefs.vibrationAmplitude()
        val amplitude = ((amplitudePercent * 255 + 50) / 100).coerceIn(1, 255)
        debugLog(
            "vibrating for $reason pattern=${pattern.storedValue} " +
                    "ms=$ms amplitude=$amplitude"
        )
        val result = VibrationHelper.vibratePattern(
            context = this,
            pattern = pattern,
            defaultDurationMs = ms,
            amplitude = amplitude,
            acquireWakeLock = deviceLocked,
        )
        if (result) {
            lastVibrationAtMs = now
            prefs.markVibrationNow(now)
            val notif = sbn?.notification
            val title = notif?.extras
                ?.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()?.take(30)?.replace('\n', ' ') ?: ""
            val category  = notif?.category ?: ""
            val channelId = notif?.channelId ?: ""
            VibrationLogger.logVibrate(
                pkg = pkg ?: reason,
                title = title,
                category = category,
                channelId = channelId,
                locked = deviceLocked,
                reason = reason,
                pattern = pattern.storedValue,
            )
        }
        return result
    }

    private fun shouldPauseForVibrateMode(): Boolean {
        if (!prefs.pauseInVibrateMode()) return false
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return audioManager?.ringerMode == AudioManager.RINGER_MODE_VIBRATE
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

    private fun shouldSkipUnlockReplay(sbn: StatusBarNotification, now: Long): Boolean {
        val lastUserPresentAt = prefs.lastUserPresentAtMs()
        return shouldSuppressUnlockReplay(
            notificationPostTimeMs = sbn.postTime,
            lastUserPresentAtMs = lastUserPresentAt,
            nowMs = now,
            suppressWindowMs = UNLOCK_REPLAY_SUPPRESS_MS
        )
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
        private const val UNREAD_REMINDER_REQUEST_CODE = 2107
        private val CLOCK_PACKAGES = setOf(
            "com.android.deskclock",
            "com.google.android.deskclock"
        )

        // Channel IDs used by MIUI/HyperOS exclusively for internal background services.
        // Any notification on these channels is never user-facing and must be silenced.
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

        @Volatile
        private var activeInstance: VibratingNotificationListenerService? = null

        internal fun dispatchUnreadReminderAlarm(
            expectedTriggerElapsedMs: Long,
        ): Boolean {
            val instance = activeInstance ?: return false
            instance.runRepeatReminderIfNeeded(expectedTriggerElapsedMs)
            return true
        }

        internal fun dispatchReminderSettingsChanged() {
            activeInstance?.onReminderSettingsChanged()
        }
    }

}
