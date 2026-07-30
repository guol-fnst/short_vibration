package com.virb.lite.vibe

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object VibrationHelper {
    private const val TAG = "VirbVibe"

    fun vibrate(
        context: Context,
        durationMs: Long,
        amplitude: Int,
        acquireWakeLock: Boolean = true,
    ): Boolean = vibratePattern(
        context = context,
        pattern = VibrationPattern.DEFAULT,
        defaultDurationMs = durationMs,
        amplitude = amplitude,
        acquireWakeLock = acquireWakeLock,
    )

    fun vibratePattern(
        context: Context,
        pattern: VibrationPattern,
        defaultDurationMs: Long,
        amplitude: Int,
        acquireWakeLock: Boolean = true,
    ): Boolean {
        val safeDuration = defaultDurationMs.coerceIn(1L, 1000L)
        val safeAmplitude = amplitude.coerceIn(1, 255)
        val effectDurationMs = pattern.effectDurationMs(safeDuration)
        debugLog(
            "vibratePattern() called: pattern=${pattern.storedValue} " +
                    "durationMs=$effectDurationMs amplitude=$safeAmplitude, " +
                    "SDK=${Build.VERSION.SDK_INT}, acquireWakeLock=$acquireWakeLock"
        )

        val audioAttrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        val effect = createEffect(pattern, safeDuration, safeAmplitude)
        val appCtx = context.applicationContext
        var wl: PowerManager.WakeLock? = null
        var keepWakeLockUntilTimeout = false

        return try {
            wl = acquireVibrationWakeLock(appCtx, effectDurationMs, acquireWakeLock)
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = appCtx.getSystemService(VibratorManager::class.java)
                if (manager == null) {
                    Log.w(TAG, "VibratorManager null, falling back")
                    legacyVibrate(appCtx, effect, audioAttrs)
                } else {
                    val vibrator = manager.defaultVibrator
                    debugLog("hasVibrator=${vibrator.hasVibrator()}, hasFreeformEffect=${vibrator.areEffectsSupported(VibrationEffect.EFFECT_CLICK).any { it == 0 }}")
                    if (!vibrator.hasVibrator()) {
                        Log.w(TAG, "hasVibrator=false on API31+, trying legacy")
                        legacyVibrate(appCtx, effect, audioAttrs)
                    } else {
                        vibrateWithPlatformAttributes(vibrator, effect, audioAttrs)
                        debugLog("vibrate dispatched via VibratorManager+AudioAttrs")
                        true
                    }
                }
            } else {
                legacyVibrate(appCtx, effect, audioAttrs)
            }
            keepWakeLockUntilTimeout = result
            result
        } catch (e: Exception) {
            Log.e(TAG, "vibrate() exception: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            if (!keepWakeLockUntilTimeout) {
                releaseWakeLock(wl)
            }
        }
    }

    private fun createEffect(
        pattern: VibrationPattern,
        defaultDurationMs: Long,
        amplitude: Int,
    ): VibrationEffect =
        when (pattern) {
            VibrationPattern.DEFAULT ->
                VibrationEffect.createOneShot(defaultDurationMs, amplitude)
            VibrationPattern.SHORT ->
                VibrationEffect.createOneShot(VibrationPattern.SHORT_DURATION_MS, amplitude)
            VibrationPattern.DOUBLE ->
                VibrationEffect.createWaveform(
                    longArrayOf(
                        0L,
                        VibrationPattern.DOUBLE_PULSE_MS,
                        VibrationPattern.DOUBLE_GAP_MS,
                        VibrationPattern.DOUBLE_PULSE_MS,
                    ),
                    intArrayOf(0, amplitude, 0, amplitude),
                    -1,
                )
            VibrationPattern.LONG ->
                VibrationEffect.createOneShot(VibrationPattern.LONG_DURATION_MS, amplitude)
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
        ).also {
            it.setReferenceCounted(false)
            it.acquire(durationMs + 500)
        }
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
        vibrateWithPlatformAttributes(vibrator, effect, audioAttrs)
        debugLog("vibrate dispatched via legacy Vibrator+AudioAttrs")
        return true
    }

    private fun vibrateWithPlatformAttributes(
        vibrator: Vibrator,
        effect: VibrationEffect,
        audioAttrs: AudioAttributes,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val vibrationAttrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()
            vibrator.vibrate(effect, vibrationAttrs)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, audioAttrs)
        }
    }

    private fun debugLog(message: String) {
        if (ENABLE_VERBOSE_LOGS) {
            Log.d(TAG, message)
        }
    }

    private const val ENABLE_VERBOSE_LOGS = false
}
