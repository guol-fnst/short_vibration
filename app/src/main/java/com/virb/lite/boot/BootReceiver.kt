package com.virb.lite.boot

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import com.virb.lite.listener.VibratingNotificationListenerService
import com.virb.lite.prefs.AppPrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                rebindListener(context, force = true)
            }
            Intent.ACTION_USER_PRESENT -> {
                AppPrefs(context).markUserPresentNow(System.currentTimeMillis())
                rebindListener(context, force = false)
            }
        }
    }

    private fun rebindListener(context: Context, force: Boolean) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!force && nowElapsed - lastRebindElapsedMs < MIN_REBIND_INTERVAL_MS) {
            Log.d(TAG, "skip rebind: throttled")
            return
        }

        try {
            val component = ComponentName(
                context.packageName,
                VibratingNotificationListenerService::class.java.name
            )
            NotificationListenerService.requestRebind(component)
            lastRebindElapsedMs = nowElapsed
            Log.d(TAG, "requestRebind called force=$force")
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "VirbBoot"
        private const val MIN_REBIND_INTERVAL_MS = 15_000L
        @Volatile
        private var lastRebindElapsedMs: Long = 0L
    }
}
