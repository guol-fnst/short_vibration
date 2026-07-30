package com.virb.lite

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.virb.lite.databinding.ActivityReminderSettingsBinding
import com.virb.lite.listener.VibratingNotificationListenerService
import com.virb.lite.prefs.AppPrefs
import com.virb.lite.prefs.QuietPeriod
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ReminderSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReminderSettingsBinding
    private lateinit var prefs: AppPrefs
    private var currentToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReminderSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        applySystemBarInsets()
        bindInitialUi()
        bindListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshQuietPeriodsUi()
    }

    private fun bindInitialUi() {
        binding.switchRepeatReminder.isChecked = prefs.repeatReminderEnabled()
        binding.etRepeatInterval.setText(prefs.repeatReminderIntervalMin().toString())
        binding.etRepeatCount.setText(prefs.repeatReminderMaxCount().toString())
        updateRepeatReminderOptions(prefs.repeatReminderEnabled())
        refreshQuietPeriodsUi()
    }

    private fun bindListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.switchRepeatReminder.setOnCheckedChangeListener { _, isChecked ->
            prefs.setRepeatReminderEnabled(isChecked)
            updateRepeatReminderOptions(isChecked)
            dispatchReminderSettingsChanged()
        }
        binding.btnRepeatIntervalMinus.setOnClickListener {
            stepNumber(
                binding.etRepeatInterval.text?.toString(),
                -1,
                AppPrefs.MIN_REPEAT_INTERVAL_MIN,
                AppPrefs.MAX_REPEAT_INTERVAL_MIN,
                AppPrefs.DEFAULT_REPEAT_INTERVAL_MIN,
            ) { binding.etRepeatInterval.setText(it.toString()) }
        }
        binding.btnRepeatIntervalPlus.setOnClickListener {
            stepNumber(
                binding.etRepeatInterval.text?.toString(),
                1,
                AppPrefs.MIN_REPEAT_INTERVAL_MIN,
                AppPrefs.MAX_REPEAT_INTERVAL_MIN,
                AppPrefs.DEFAULT_REPEAT_INTERVAL_MIN,
            ) { binding.etRepeatInterval.setText(it.toString()) }
        }
        binding.btnRepeatCountMinus.setOnClickListener {
            stepNumber(
                binding.etRepeatCount.text?.toString(),
                -1,
                AppPrefs.MIN_REPEAT_MAX_COUNT,
                AppPrefs.MAX_REPEAT_MAX_COUNT,
                AppPrefs.DEFAULT_REPEAT_MAX_COUNT,
            ) { binding.etRepeatCount.setText(it.toString()) }
        }
        binding.btnRepeatCountPlus.setOnClickListener {
            stepNumber(
                binding.etRepeatCount.text?.toString(),
                1,
                AppPrefs.MIN_REPEAT_MAX_COUNT,
                AppPrefs.MAX_REPEAT_MAX_COUNT,
                AppPrefs.DEFAULT_REPEAT_MAX_COUNT,
            ) { binding.etRepeatCount.setText(it.toString()) }
        }
        binding.btnAddQuietPeriod.setOnClickListener { showQuietPeriodTimeDialog() }
        binding.btnSaveReminderSettings.setOnClickListener { saveReminderSettings() }
    }

    private fun saveReminderSettings() {
        val repeatInterval = binding.etRepeatInterval.text.toString().toIntOrNull()
        val repeatCount = binding.etRepeatCount.text.toString().toIntOrNull()
        if (repeatInterval == null || repeatCount == null) {
            toast(getString(R.string.invalid_input))
            return
        }

        prefs.setRepeatReminderIntervalMin(repeatInterval)
        prefs.setRepeatReminderMaxCount(repeatCount)
        binding.etRepeatInterval.setText(prefs.repeatReminderIntervalMin().toString())
        binding.etRepeatCount.setText(prefs.repeatReminderMaxCount().toString())
        dispatchReminderSettingsChanged()
        toast(getString(R.string.saved))
    }

    private fun applySystemBarInsets() {
        val initialTop = binding.root.paddingTop
        val initialBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                top = initialTop + bars.top,
                bottom = initialBottom + bars.bottom,
            )
            insets
        }
    }

    private fun updateRepeatReminderOptions(enabled: Boolean) {
        binding.layoutRepeatReminderOptions.alpha = if (enabled) 1f else 0.45f
        setViewTreeEnabled(binding.layoutRepeatReminderOptions, enabled)
    }

    private fun setViewTreeEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                setViewTreeEnabled(view.getChildAt(index), enabled)
            }
        }
    }

    private fun refreshQuietPeriodsUi() {
        val periods = prefs.quietPeriods()
        binding.tvQuietPeriodsEmpty.isVisible = periods.isEmpty()
        binding.layoutQuietPeriods.removeAllViews()
        updateQuietHoursStatus(periods)

        periods.forEachIndexed { index, period ->
            val row = layoutInflater.inflate(
                R.layout.item_quiet_period,
                binding.layoutQuietPeriods,
                false,
            )
            val enabledSwitch =
                row.findViewById<MaterialSwitch>(R.id.switchQuietPeriodEnabled)
            val label = row.findViewById<TextView>(R.id.tvQuietPeriodLabel)
            val editButton = row.findViewById<AppCompatImageButton>(R.id.btnEditQuietPeriod)
            val deleteButton = row.findViewById<AppCompatImageButton>(R.id.btnDeleteQuietPeriod)

            label.text = formatQuietPeriod(period)
            label.alpha = if (period.enabled) 1f else 0.5f
            enabledSwitch.isChecked = period.enabled
            enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
                val updatedPeriod = period.copy(enabled = isChecked)
                val overlaps = isChecked && periods.withIndex().any { (otherIndex, other) ->
                    otherIndex != index && updatedPeriod.overlaps(other)
                }
                if (overlaps) {
                    enabledSwitch.setOnCheckedChangeListener(null)
                    enabledSwitch.isChecked = false
                    toast(getString(R.string.quiet_hours_overlap))
                    return@setOnCheckedChangeListener
                }
                val updated = periods.toMutableList()
                updated[index] = updatedPeriod
                saveQuietPeriods(updated)
            }

            val editAction = View.OnClickListener {
                showQuietPeriodTimeDialog(index, period)
            }
            label.setOnClickListener(editAction)
            editButton.setOnClickListener(editAction)
            deleteButton.setOnClickListener {
                val updated = periods.toMutableList()
                updated.removeAt(index)
                saveQuietPeriods(updated)
            }
            binding.layoutQuietPeriods.addView(row)
        }
    }

    private fun updateQuietHoursStatus(periods: List<QuietPeriod>) {
        val enabledCount = periods.count { it.enabled }
        val quietEndDelayMs = prefs.millisUntilQuietHoursEnd()
        binding.tvQuietHoursStatus.text = when {
            quietEndDelayMs != null -> {
                val endTime = SimpleDateFormat("HH:mm", Locale.US)
                    .format(Date(System.currentTimeMillis() + quietEndDelayMs))
                getString(R.string.quiet_hours_status_active, endTime)
            }
            enabledCount == 0 ->
                getString(R.string.quiet_hours_status_disabled)
            else ->
                getString(R.string.quiet_hours_status_inactive, enabledCount)
        }
    }

    private fun saveQuietPeriods(periods: List<QuietPeriod>) {
        prefs.setQuietPeriods(periods)
        dispatchReminderSettingsChanged()
        refreshQuietPeriodsUi()
    }

    private fun showQuietPeriodTimeDialog(
        editIndex: Int? = null,
        existing: QuietPeriod? = null,
    ) {
        val calendar = Calendar.getInstance()
        val defaultStartMin =
            existing?.startMin
                ?: (calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE))
        val defaultEndMin = existing?.endMin ?: ((defaultStartMin + 60) % (24 * 60))

        val startPicker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setInputMode(MaterialTimePicker.INPUT_MODE_KEYBOARD)
            .setHour(defaultStartMin / 60)
            .setMinute(defaultStartMin % 60)
            .setTitleText(getString(R.string.quiet_hours_pick_start))
            .build()

        startPicker.addOnPositiveButtonClickListener {
            val startMin = startPicker.hour * 60 + startPicker.minute
            val endPicker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setInputMode(MaterialTimePicker.INPUT_MODE_KEYBOARD)
                .setHour(defaultEndMin / 60)
                .setMinute(defaultEndMin % 60)
                .setTitleText(getString(R.string.quiet_hours_pick_end))
                .build()

            endPicker.addOnPositiveButtonClickListener endTimeSelected@{
                val endMin = endPicker.hour * 60 + endPicker.minute
                if (startMin == endMin) {
                    toast(getString(R.string.quiet_hours_same_time))
                    return@endTimeSelected
                }
                showQuietDaysDialog(
                    startMin = startMin,
                    endMin = endMin,
                    initialDayMask = existing?.dayMask ?: QuietPeriod.ALL_DAYS_MASK,
                    enabled = existing?.enabled ?: true,
                    editIndex = editIndex,
                )
            }
            endPicker.show(supportFragmentManager, "end_time_picker")
        }
        startPicker.show(supportFragmentManager, "start_time_picker")
    }

    private fun showQuietDaysDialog(
        startMin: Int,
        endMin: Int,
        initialDayMask: Int,
        enabled: Boolean,
        editIndex: Int?,
    ) {
        val selectedDays = BooleanArray(QuietPeriod.DAYS_PER_WEEK) { index ->
            initialDayMask and (1 shl index) != 0
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.quiet_hours_pick_days)
            .setMultiChoiceItems(
                R.array.quiet_days_short,
                selectedDays,
            ) { _, which, isChecked ->
                selectedDays[which] = isChecked
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.quiet_hours_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val dayMask = selectedDays.indices.fold(0) { mask, index ->
                    if (selectedDays[index]) mask or (1 shl index) else mask
                }
                if (dayMask == 0) {
                    toast(getString(R.string.quiet_hours_no_days))
                    return@setOnClickListener
                }

                val candidate = QuietPeriod(startMin, endMin, dayMask, enabled)
                val periods = prefs.quietPeriods()
                val overlaps = candidate.enabled && periods.withIndex().any { (index, period) ->
                    index != editIndex && candidate.overlaps(period)
                }
                if (overlaps) {
                    toast(getString(R.string.quiet_hours_overlap))
                    return@setOnClickListener
                }

                val updated = periods.toMutableList()
                if (editIndex == null) {
                    updated.add(candidate)
                } else if (editIndex in updated.indices) {
                    updated[editIndex] = candidate
                }
                saveQuietPeriods(updated)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun formatQuietPeriod(period: QuietPeriod): String {
        fun formatMinute(minute: Int) =
            String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)

        val dayText = when (period.dayMask) {
            QuietPeriod.ALL_DAYS_MASK -> getString(R.string.quiet_days_every_day)
            QuietPeriod.WEEKDAYS_MASK -> getString(R.string.quiet_days_weekdays)
            QuietPeriod.WEEKEND_MASK -> getString(R.string.quiet_days_weekend)
            else -> {
                val dayNames = resources.getStringArray(R.array.quiet_days_short)
                dayNames.indices
                    .filter(period::isDaySelected)
                    .joinToString(getString(R.string.quiet_days_separator)) { dayNames[it] }
            }
        }
        val endText = if (period.crossesMidnight) {
            "${getString(R.string.quiet_hours_next_day)} ${formatMinute(period.endMin)}"
        } else {
            formatMinute(period.endMin)
        }
        return "$dayText  ${formatMinute(period.startMin)} ~ $endText"
    }

    private fun dispatchReminderSettingsChanged() {
        VibratingNotificationListenerService.dispatchReminderSettingsChanged()
    }

    private fun stepNumber(
        currentText: String?,
        delta: Int,
        min: Int,
        max: Int,
        defaultValue: Int,
        applyValue: (Int) -> Unit,
    ) {
        val current = currentText?.toIntOrNull() ?: defaultValue
        applyValue((current + delta).coerceIn(min, max))
    }

    private fun toast(message: String) {
        currentToast?.cancel()
        currentToast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        currentToast?.show()
    }
}
