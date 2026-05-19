package com.virb.lite

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.chip.Chip
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.virb.lite.databinding.ActivityMainBinding
import com.virb.lite.listener.VibratingNotificationListenerService
import com.virb.lite.prefs.AppPrefs
import com.virb.lite.prefs.QuietPeriod
import com.virb.lite.vibe.VibrationHelper
import java.util.Calendar

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private var didForceRebind: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        applySystemBarInsets()
        bindInitialUi()
        bindListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        // 给 onListenerConnected 留出时间，1.5 秒后再刻新一次状态
        handler.postDelayed({ refreshPermissionState() }, 1500)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    private fun bindInitialUi() {
        binding.switchEnabled.isChecked = prefs.isEnabled()
        binding.switchLockedOnly.isChecked = prefs.vibrateOnlyWhenLocked()
        binding.switchIgnoreSystem.isChecked = prefs.ignoreSystemPackages()
        binding.etDuration.setText(prefs.vibrationMs().toString())
        binding.etGlobalGap.setText(msToSeconds(prefs.globalGapMs()).toString())
        refreshQuietPeriodsUi()
        refreshPermissionState()
    }

    private fun bindListeners() {
        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.setEnabled(isChecked)
        }

        binding.switchLockedOnly.setOnCheckedChangeListener { _, isChecked ->
            prefs.setVibrateOnlyWhenLocked(isChecked)
        }

        binding.switchIgnoreSystem.setOnCheckedChangeListener { _, isChecked ->
            prefs.setIgnoreSystemPackages(isChecked)
        }

        binding.btnDurationMinus.setOnClickListener {
            stepNumber(
                currentText = binding.etDuration.text?.toString(),
                delta = -1,
                min = AppPrefs.MIN_VIBRATION_MS,
                max = AppPrefs.MAX_VIBRATION_MS,
                defaultValue = AppPrefs.DEFAULT_VIBRATION_MS
            ) { binding.etDuration.setText(it.toString()) }
        }

        binding.btnDurationPlus.setOnClickListener {
            stepNumber(
                currentText = binding.etDuration.text?.toString(),
                delta = 1,
                min = AppPrefs.MIN_VIBRATION_MS,
                max = AppPrefs.MAX_VIBRATION_MS,
                defaultValue = AppPrefs.DEFAULT_VIBRATION_MS
            ) { binding.etDuration.setText(it.toString()) }
        }

        binding.btnGlobalGapMinus.setOnClickListener {
            stepNumber(
                currentText = binding.etGlobalGap.text?.toString(),
                delta = -1,
                min = secondsFromMs(AppPrefs.MIN_GLOBAL_GAP_MS),
                max = secondsFromMs(AppPrefs.MAX_GLOBAL_GAP_MS),
                defaultValue = secondsFromMs(AppPrefs.DEFAULT_GLOBAL_GAP_MS)
            ) { binding.etGlobalGap.setText(it.toString()) }
        }

        binding.btnGlobalGapPlus.setOnClickListener {
            stepNumber(
                currentText = binding.etGlobalGap.text?.toString(),
                delta = 1,
                min = secondsFromMs(AppPrefs.MIN_GLOBAL_GAP_MS),
                max = secondsFromMs(AppPrefs.MAX_GLOBAL_GAP_MS),
                defaultValue = secondsFromMs(AppPrefs.DEFAULT_GLOBAL_GAP_MS)
            ) { binding.etGlobalGap.setText(it.toString()) }
        }

        binding.btnSave.setOnClickListener {
            val duration = binding.etDuration.text.toString().toIntOrNull()
            val gapSeconds = binding.etGlobalGap.text.toString().toIntOrNull()

            if (duration == null || gapSeconds == null) {
                toast(getString(R.string.invalid_input))
                return@setOnClickListener
            }

            prefs.setVibrationMs(duration)
            prefs.setGlobalGapMs(secondsToMs(gapSeconds))

            binding.etDuration.setText(prefs.vibrationMs().toString())
            binding.etGlobalGap.setText(msToSeconds(prefs.globalGapMs()).toString())
            toast(getString(R.string.saved))
        }

        binding.btnAutoStart.setOnClickListener { openAutoStartSettings() }
        binding.btnBatteryOpt.setOnClickListener { openBatteryOptSettings() }
        binding.btnAddQuietPeriod.setOnClickListener { showAddQuietPeriodDialog() }

        binding.btnOpenAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnFixHaptic.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        binding.btnTestVibration.setOnClickListener {
            val ms = (binding.etDuration.text.toString().toIntOrNull() ?: prefs.vibrationMs()).toLong()

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

    private fun applySystemBarInsets() {
        val initialLeft = binding.rootScroll.paddingLeft
        val initialTop = binding.rootScroll.paddingTop
        val initialRight = binding.rootScroll.paddingRight
        val initialBottom = binding.rootScroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootScroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = initialLeft + bars.left,
                top = initialTop + bars.top,
                right = initialRight + bars.right,
                bottom = initialBottom + bars.bottom
            )
            insets
        }
    }

    private fun refreshPermissionState() {
        val enabled = isNotificationListenerEnabled()
        binding.tvAccessStatus.text = if (enabled) {
            getString(R.string.access_enabled)
        } else {
            getString(R.string.access_disabled)
        }

        // 已开通权限但服务未连接（被 MIUI 杀掉）→ 显示警告卡
        val serviceDead = enabled && !VibratingNotificationListenerService.isConnected
        binding.cardServiceDead.visibility =
            if (serviceDead) android.view.View.VISIBLE else android.view.View.GONE

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

    private fun openAutoStartSettings() {
        // 尝试打开 MIUI 自启动管理页，失败则降级到应用详情
        val miuiIntent = Intent().apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        }
        try {
            startActivity(miuiIntent)
            return
        } catch (_: Exception) {}
        // 通用应用详情页
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openBatteryOptSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val component = ComponentName(this, VibratingNotificationListenerService::class.java)
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabledListeners
            ?.split(':')
            ?.mapNotNull { ComponentName.unflattenFromString(it) }
            ?.any { it == component } == true
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

    private fun stepNumber(
        currentText: String?,
        delta: Int,
        min: Int,
        max: Int,
        defaultValue: Int,
        applyValue: (Int) -> Unit
    ) {
        val current = currentText?.toIntOrNull() ?: defaultValue
        applyValue((current + delta).coerceIn(min, max))
    }

    private fun msToSeconds(ms: Int): Int = (ms / 1000).coerceAtLeast(1)

    private fun secondsFromMs(ms: Int): Int = msToSeconds(ms)

    private fun secondsToMs(seconds: Int): Int = seconds.coerceAtLeast(1) * 1000

    private fun refreshQuietPeriodsUi() {
        val periods = prefs.quietPeriods()
        binding.tvQuietPeriodsEmpty.visibility =
            if (periods.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.chipGroupQuietPeriods.removeAllViews()
        periods.forEachIndexed { index, period ->
            val chip = Chip(this).apply {
                text = formatQuietPeriod(period)
                isCloseIconVisible = true
                isCheckable = false
                setOnCloseIconClickListener {
                    val updated = prefs.quietPeriods().toMutableList()
                    updated.removeAt(index)
                    prefs.setQuietPeriods(updated)
                    refreshQuietPeriodsUi()
                }
            }
            binding.chipGroupQuietPeriods.addView(chip)
        }
    }

    private fun showAddQuietPeriodDialog() {
        val cal = Calendar.getInstance()

        val startPicker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setInputMode(MaterialTimePicker.INPUT_MODE_KEYBOARD)
            .setHour(cal.get(Calendar.HOUR_OF_DAY))
            .setMinute(cal.get(Calendar.MINUTE))
            .setTitleText(getString(R.string.quiet_hours_pick_start))
            .build()

        startPicker.addOnPositiveButtonClickListener {
            val startMin = startPicker.hour * 60 + startPicker.minute

            val endPicker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setInputMode(MaterialTimePicker.INPUT_MODE_KEYBOARD)
                .setHour(startPicker.hour)
                .setMinute(startPicker.minute)
                .setTitleText(getString(R.string.quiet_hours_pick_end))
                .build()

            endPicker.addOnPositiveButtonClickListener {
                val period = QuietPeriod(startMin, endPicker.hour * 60 + endPicker.minute)
                val updated = prefs.quietPeriods().toMutableList()
                updated.add(period)
                prefs.setQuietPeriods(updated)
                refreshQuietPeriodsUi()
            }

            endPicker.show(supportFragmentManager, "end_time_picker")
        }

        startPicker.show(supportFragmentManager, "start_time_picker")
    }

    private fun formatQuietPeriod(period: QuietPeriod): String {
        fun fmt(min: Int) = String.format("%02d:%02d", min / 60, min % 60)
        return "${fmt(period.startMin)} ~ ${fmt(period.endMin)}"
    }
}
