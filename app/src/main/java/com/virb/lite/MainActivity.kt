package com.virb.lite

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.virb.lite.databinding.ActivityMainBinding
import com.virb.lite.listener.VibratingNotificationListenerService
import com.virb.lite.prefs.AppPrefs
import com.virb.lite.vibe.VibrationHelper

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private var didForceRebind: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        bindInitialUi()
        bindListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun bindInitialUi() {
        binding.switchEnabled.isChecked = prefs.isEnabled()
        binding.switchLockedOnly.isChecked = prefs.vibrateOnlyWhenLocked()
        binding.etDuration.setText(prefs.vibrationMs().toString())
        binding.etGlobalGap.setText(prefs.globalGapMs().toString())
        refreshPermissionState()
    }

    private fun bindListeners() {
        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.setEnabled(isChecked)
        }

        binding.switchLockedOnly.setOnCheckedChangeListener { _, isChecked ->
            prefs.setVibrateOnlyWhenLocked(isChecked)
        }

        binding.btnSave.setOnClickListener {
            val duration = binding.etDuration.text.toString().toIntOrNull()
            val gap = binding.etGlobalGap.text.toString().toIntOrNull()

            if (duration == null || gap == null) {
                toast(getString(R.string.invalid_input))
                return@setOnClickListener
            }

            prefs.setVibrationMs(duration)
            prefs.setGlobalGapMs(gap)

            binding.etDuration.setText(prefs.vibrationMs().toString())
            binding.etGlobalGap.setText(prefs.globalGapMs().toString())
            toast(getString(R.string.saved))
        }

        binding.btnOpenAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnFixHaptic.setOnClickListener {
            // Open Sound & vibration settings so user can enable Haptic feedback
            try {
                startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        binding.btnTestVibration.setOnClickListener {
            val ms = prefs.vibrationMs().toLong()

            // Strategy 1: performHapticFeedback — goes through system haptic channel
            binding.btnTestVibration.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                },
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                        @Suppress("DEPRECATION")
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )

            // Strategy 2: Vibrator via RINGTONE channel (bypasses haptic_feedback_enabled=0 on MIUI)
            val ok = VibrationHelper.vibrate(this, ms)

            val am = getSystemService(AudioManager::class.java)
            val ringerMode = am?.ringerMode ?: -1
            val hapticOn = android.provider.Settings.System.getInt(
                contentResolver, "haptic_feedback_enabled", -1
            )
            Log.d("VirbMain", "testVibration: ms=$ms apiResult=$ok ringerMode=$ringerMode hapticFeedbackEnabled=$hapticOn")

            if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                toast(getString(R.string.silent_mode_warning))
            } else {
                toast(getString(R.string.test_done, ms))
            }
        }
    }

    private fun refreshPermissionState() {
        val enabled = isNotificationListenerEnabled()
        binding.tvAccessStatus.text = if (enabled) {
            getString(R.string.access_enabled)
        } else {
            getString(R.string.access_disabled)
        }

        if (enabled) {
            val component = ComponentName(this, VibratingNotificationListenerService::class.java)
            if (!didForceRebind) {
                forceRebind(component)
                NotificationListenerService.requestRebind(component)
                didForceRebind = true
            }
        }

        val am = getSystemService(AudioManager::class.java)
        val ringerMode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> {
                binding.tvHapticStatus.text = getString(R.string.silent_mode_warning)
                binding.btnFixHaptic.visibility = android.view.View.VISIBLE
            }
            else -> {
                binding.tvHapticStatus.text = getString(R.string.vibration_ok)
                binding.btnFixHaptic.visibility = android.view.View.GONE
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val component = ComponentName(this, VibratingNotificationListenerService::class.java)
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabledListeners?.contains(component.flattenToString()) == true
    }

    private fun forceRebind(component: ComponentName) {
        try {
            packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d("VirbMain", "forceRebind: toggled listener component")
        } catch (e: Exception) {
            Log.w("VirbMain", "forceRebind failed: ${e.message}")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
