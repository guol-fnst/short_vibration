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
        return vibrate(context, durationMs, acquireWakeLock = true)
    }

    fun vibrate(context: Context, durationMs: Long, acquireWakeLock: Boolean): Boolean {
        val safeDuration = durationMs.coerceIn(1L, 2000L)
        debugLog("vibrate() called: durationMs=$safeDuration, SDK=${Build.VERSION.SDK_INT}, acquireWakeLock=$acquireWakeLock")

        val audioAttrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        val effect = VibrationEffect.createOneShot(safeDuration, 255)
        val appCtx = context.applicationContext

        // Acquire a short PARTIAL_WAKE_LOCK so that on MIUI/HyperOS when the screen
        // is off the CPU doesn't sleep before the vibrator driver gets the command.
        val wl = if (acquireWakeLock) {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "com.virb.lite:vibrate"
            )
        } else {
            null
        }
        wl?.acquire(safeDuration + 500)

        return try {
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
            if (wl?.isHeld == true) wl.release()
        }
    }

    fun vibrateUnreadReminder(context: Context, acquireWakeLock: Boolean): Boolean {
        val timings = longArrayOf(
            0L,
            1000L,
            500L,
            1000L
        )
        val amplitudes = intArrayOf(
            0,
            255,
            0,
            255
        )
        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
        val totalDurationMs = timings.sum()
        return vibrateEffect(context, effect, totalDurationMs, acquireWakeLock)
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
        val wl = if (acquireWakeLock) {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "com.virb.lite:vibrate"
            )
        } else {
            null
        }
        wl?.acquire(totalDurationMs + 500)

        return try {
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
            if (wl?.isHeld == true) wl.release()
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
