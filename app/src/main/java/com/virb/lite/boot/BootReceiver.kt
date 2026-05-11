package com.virb.lite.boot

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log
import com.virb.lite.listener.VibratingNotificationListenerService
import com.virb.lite.prefs.AppPrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                AppPrefs(context).markBootNow(System.currentTimeMillis())
                rebindListener(context)
            }
            Intent.ACTION_USER_PRESENT -> {
                // Phone unlocked — MIUI may have killed the NLS binding while screen was off.
                // requestRebind is instant and does nothing if already bound.
                rebindListener(context)
            }
        }
    }

    private fun rebindListener(context: Context) {
        try {
            val component = ComponentName(
                context.packageName,
                VibratingNotificationListenerService::class.java.name
            )
            NotificationListenerService.requestRebind(component)
            Log.d(TAG, "requestRebind called")
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "VirbBoot"
    }
}
