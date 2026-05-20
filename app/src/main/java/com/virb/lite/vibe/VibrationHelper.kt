package com.virb.lite.vibe

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object VibrationHelper {
    private const val TAG = "VirbVibe"

    fun vibrate(context: Context, durationMs: Long): Boolean {
        return vibrate(context, durationMs, amplitude = 255, acquireWakeLock = true)
    }

    fun vibrate(context: Context, durationMs: Long, acquireWakeLock: Boolean): Boolean {
        return vibrate(context, durationMs, amplitude = 255, acquireWakeLock = acquireWakeLock)
    }

    fun vibrate(context: Context, durationMs: Long, amplitude: Int, acquireWakeLock: Boolean = true): Boolean {
        val safeDuration = durationMs.coerceIn(1L, 1000L)
        val safeAmplitude = amplitude.coerceIn(1, 255)
        debugLog("vibrate() called: durationMs=$safeDuration amplitude=$safeAmplitude, SDK=${Build.VERSION.SDK_INT}, acquireWakeLock=$acquireWakeLock")

        val audioAttrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        val effect = VibrationEffect.createOneShot(safeDuration, safeAmplitude)
        val appCtx = context.applicationContext
        var wl: PowerManager.WakeLock? = null

        return try {
            wl = acquireVibrationWakeLock(appCtx, safeDuration, acquireWakeLock)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = appCtx.getSystemService(VibratorManager::class.java)
                if (manager == null) {
                    Log.w(TAG, "VibratorManager null, falling back")
                    return legacyVibrate(appCtx, effect, audioAttrs)
                }
                val vibrator = manager.defaultVibrator
                debugLog("hasVibrator=${vibrator.hasVibrator()}, hasFreeformEffect=${vibrator.areEffectsSupported(VibrationEffect.EFFECT_CLICK).any { it == 0 }}")
                if (!vibrator.hasVibrator()) {
                    Log.w(TAG, "hasVibrator=false on API31+, trying legacy")
                    return legacyVibrate(appCtx, effect, audioAttrs)
                }
                vibrator.vibrate(effect, audioAttrs)
                debugLog("vibrate dispatched via VibratorManager+AudioAttrs")
                true
            } else {
                legacyVibrate(appCtx, effect, audioAttrs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "vibrate() exception: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            releaseWakeLock(wl)
        }
    }

    private fun vibrateEffect(
        context: Context,
        effect: VibrationEffect,
        totalDurationMs: Long,
        acquireWakeLock: Boolean
    ): Boolean {
        val audioAttrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        val appCtx = context.applicationContext
        var wl: PowerManager.WakeLock? = null

        return try {
            wl = acquireVibrationWakeLock(appCtx, totalDurationMs, acquireWakeLock)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = appCtx.getSystemService(VibratorManager::class.java)
                if (manager == null) {
                    Log.w(TAG, "VibratorManager null, falling back")
                    return legacyVibrate(appCtx, effect, audioAttrs)
                }
                val vibrator = manager.defaultVibrator
                if (!vibrator.hasVibrator()) {
                    Log.w(TAG, "hasVibrator=false on API31+, trying legacy")
                    return legacyVibrate(appCtx, effect, audioAttrs)
                }
                vibrator.vibrate(effect, audioAttrs)
                true
            } else {
                legacyVibrate(appCtx, effect, audioAttrs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "vibrateEffect() exception: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            releaseWakeLock(wl)
        }
    }

    private fun acquireVibrationWakeLock(
        appCtx: Context,
        durationMs: Long,
        acquireWakeLock: Boolean
    ): PowerManager.WakeLock? {
        if (!acquireWakeLock) return null

        // Keep the CPU awake long enough for MIUI/HyperOS to dispatch vibration while screen-off.
        val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "com.virb.lite:vibrate"
        ).also { it.acquire(durationMs + 500) }
    }

    private fun releaseWakeLock(wl: PowerManager.WakeLock?) {
        try {
            if (wl?.isHeld == true) wl.release()
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyVibrate(appCtx: Context, effect: VibrationEffect, audioAttrs: AudioAttributes): Boolean {
        val vibrator = appCtx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator == null) {
            Log.w(TAG, "legacy Vibrator is null")
            return false
        }
        debugLog("legacy hasVibrator=${vibrator.hasVibrator()}")
        if (!vibrator.hasVibrator()) return false
        vibrator.vibrate(effect, audioAttrs)
        debugLog("vibrate dispatched via legacy Vibrator+AudioAttrs")
        return true
    }

    private fun debugLog(message: String) {
        if (ENABLE_VERBOSE_LOGS) {
            Log.d(TAG, message)
        }
    }

    private const val ENABLE_VERBOSE_LOGS = false
}
