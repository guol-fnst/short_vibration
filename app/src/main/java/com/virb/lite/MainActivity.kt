package com.virb.lite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.chip.Chip
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.virb.lite.databinding.ActivityMainBinding
import com.virb.lite.listener.VibratingNotificationListenerService
import com.virb.lite.log.VibrationLogger
import com.virb.lite.prefs.AppPrefs
import com.virb.lite.prefs.QuietPeriod
import com.virb.lite.vibe.VibrationHelper
import java.util.Calendar
import java.util.LinkedHashSet
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private var installedAppsCache: List<WhitelistApp>? = null
    private var lastForceRebindElapsedMs: Long = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val permissionRefreshRunnable = Runnable { refreshPermissionState() }
    private var currentToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        VibrationLogger.init(this)
        applySystemBarInsets()
        bindInitialUi()
        bindListeners()
    }

    override fun onResume() {
        super.onResume()
        installedAppsCache = null
        lastForceRebindElapsedMs = 0L
        refreshPermissionState()
        refreshWhitelistUi()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    private fun bindInitialUi() {
        binding.switchEnabled.isChecked = prefs.isEnabled()
        binding.switchLockedOnly.isChecked = prefs.vibrateOnlyWhenLocked()
        binding.switchFileLogging.isChecked = prefs.fileLoggingEnabled()
        binding.etDuration.setText(prefs.vibrationMs().toString())
        binding.etGlobalGap.setText(msToSeconds(prefs.globalGapMs()).toString())
        binding.sliderAmplitude.value = prefs.vibrationAmplitude().toFloat()
        updateAmplitudeLabel(prefs.vibrationAmplitude())
        refreshWhitelistUi()
        refreshQuietPeriodsUi()
    }

    private fun bindListeners() {
        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.setEnabled(isChecked)
        }

        binding.switchLockedOnly.setOnCheckedChangeListener { _, isChecked ->
            prefs.setVibrateOnlyWhenLocked(isChecked)
        }

        binding.switchFileLogging.setOnCheckedChangeListener { _, isChecked ->
            prefs.setFileLoggingEnabled(isChecked)
            VibrationLogger.setFileLoggingEnabled(isChecked)
        }

        binding.btnManageWhitelist.setOnClickListener {
            showWhitelistDialog()
        }

        binding.sliderAmplitude.addOnChangeListener { _, value, _ ->
            updateAmplitudeLabel(value.toInt())
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
            prefs.setVibrationAmplitude(binding.sliderAmplitude.value.toInt())

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
            val amplitudePercent = binding.sliderAmplitude.value.toInt()
            val amplitude = ((amplitudePercent * 255 + 50) / 100).coerceIn(1, 255)

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

            val ok = VibrationHelper.vibrate(this, ms, amplitude)

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

        binding.btnViewLog.setOnClickListener { showVibrationLogDialog() }
    }

    private fun showVibrationLogDialog() {
        val logText = VibrationLogger.readTail(100).ifBlank { getString(R.string.log_empty) }

        val tv = TextView(this).apply {
            text = logText
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.log_dialog_title))
            .setView(scroll)
            .setNeutralButton(R.string.copy_log) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("virb_log", logText))
                toast(getString(R.string.log_copied))
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
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

        // 权限未开启时在 header 中显示警告；已开启则不显示任何状态文字
        if (enabled) {
            binding.tvAccessStatus.visibility = android.view.View.GONE
        } else {
            binding.tvAccessStatus.text = getString(R.string.access_disabled)
            binding.tvAccessStatus.visibility = android.view.View.VISIBLE
        }

        // 访问按钮：权限未开启时显示，已开启时隐藏
        binding.btnOpenAccess.visibility =
            if (enabled) android.view.View.GONE else android.view.View.VISIBLE

        // 已开通权限但服务未连接（被 MIUI 杀掉）→ 显示警告卡
        val serviceDead = enabled && !VibratingNotificationListenerService.isConnected
        binding.cardServiceDead.visibility =
            if (serviceDead) android.view.View.VISIBLE else android.view.View.GONE

        if (serviceDead) {
            requestListenerRebindIfNeeded()
            schedulePermissionRefresh()
        } else {
            handler.removeCallbacks(permissionRefreshRunnable)
        }

        val am = getSystemService(AudioManager::class.java)
        val ringerMode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> {
                binding.tvHapticStatus.text = getString(R.string.silent_mode_warning)
                binding.tvHapticStatus.visibility = android.view.View.VISIBLE
                binding.btnFixHaptic.visibility = android.view.View.VISIBLE
            }
            else -> {
                binding.tvHapticStatus.visibility = android.view.View.GONE
                binding.btnFixHaptic.visibility = android.view.View.GONE
            }
        }

        // 整个访问按钮行：两个按钮都隐藏时隐藏整行
        binding.rowAccessButtons.visibility =
            if (binding.btnOpenAccess.visibility == android.view.View.VISIBLE
                || binding.btnFixHaptic.visibility == android.view.View.VISIBLE
            ) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun requestListenerRebindIfNeeded() {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastForceRebindElapsedMs > 0L &&
            nowElapsed - lastForceRebindElapsedMs < FORCE_REBIND_INTERVAL_MS
        ) {
            return
        }

        try {
            val component = ComponentName(this, VibratingNotificationListenerService::class.java)
            NotificationListenerService.requestRebind(component)
            lastForceRebindElapsedMs = nowElapsed
        } catch (e: Exception) {
            Log.w("VirbMain", "requestRebind failed: ${e.message}")
        }
    }

    private fun schedulePermissionRefresh() {
        handler.removeCallbacks(permissionRefreshRunnable)
        handler.postDelayed(permissionRefreshRunnable, SERVICE_STATE_REFRESH_MS)
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

    private fun toast(message: String) {
        currentToast?.cancel()
        currentToast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        currentToast?.show()
    }

    private fun updateAmplitudeLabel(percent: Int) {
        binding.tvAmplitudeValue.text = getString(R.string.amplitude_percent, percent)
    }

    private fun refreshWhitelistUi() {
        val selectedPackages = prefs.allowedPackages()
        binding.tvWhitelistSummary.text = if (selectedPackages.isEmpty()) {
            getString(R.string.whitelist_summary_empty)
        } else {
            getString(R.string.whitelist_summary, selectedPackages.size)
        }
    }

    private fun showWhitelistDialog() {
        val apps = loadInstalledApps()
        if (apps.isEmpty()) {
            toast(getString(R.string.whitelist_no_apps))
            return
        }

        val originalSelectedPackages = prefs.allowedPackages()
        val selectedPackages = prefs.allowedPackages().toMutableSet()
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }

        val searchInput = EditText(this).apply {
            hint = getString(R.string.whitelist_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        dialogContent.addView(
            searchInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val listView = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_NONE
            isVerticalScrollBarEnabled = true
            dividerHeight = 0
        }
        val rowItems = ArrayList<WhitelistDialogRow>()
        val inflater = LayoutInflater.from(this)
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = rowItems.size

            override fun getItem(position: Int): Any = rowItems[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getViewTypeCount(): Int = 2

            override fun getItemViewType(position: Int): Int =
                when (rowItems[position]) {
                    is WhitelistDialogRow.Header -> 0
                    is WhitelistDialogRow.AppItem -> 1
                }

            override fun isEnabled(position: Int): Boolean =
                rowItems[position] is WhitelistDialogRow.AppItem

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return when (val row = rowItems[position]) {
                    is WhitelistDialogRow.Header -> {
                        val view = (convertView as? TextView)
                            ?: inflater.inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
                        view.text = row.title
                        view.setTextAppearance(android.R.style.TextAppearance_Medium)
                        view.setTypeface(view.typeface, android.graphics.Typeface.BOLD)
                        view.alpha = 0.7f
                        view.setPadding(view.paddingLeft, view.paddingTop + 12, view.paddingRight, view.paddingBottom)
                        view
                    }

                    is WhitelistDialogRow.AppItem -> {
                        val view = (convertView as? CheckedTextView)
                            ?: inflater.inflate(
                                android.R.layout.simple_list_item_multiple_choice,
                                parent,
                                false
                            ) as CheckedTextView
                        view.text = row.app.displayText
                        view.isChecked = row.app.packageName in selectedPackages
                        view
                    }
                }
            }
        }
        listView.adapter = adapter
        dialogContent.addView(
            listView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (360 * resources.displayMetrics.density).toInt()
            )
        )

        fun sortedApps(source: List<WhitelistApp>): Pair<List<WhitelistApp>, List<WhitelistApp>> {
            val selected = source
                .filter { it.packageName in selectedPackages }
                .sortedWith(compareBy({ it.label.lowercase(Locale.getDefault()) }, { it.packageName }))
            val unselected = source
                .filter { it.packageName !in selectedPackages }
                .sortedWith(compareBy({ it.label.lowercase(Locale.getDefault()) }, { it.packageName }))
            return selected to unselected
        }

        fun applyFilter(query: String) {
            val normalized = query.trim().lowercase(Locale.getDefault())
            val filtered = if (normalized.isEmpty()) {
                apps
            } else {
                apps.filter { app ->
                    app.label.lowercase(Locale.getDefault()).contains(normalized) ||
                    app.packageName.lowercase(Locale.getDefault()).contains(normalized)
                }
            }
            val (selectedApps, unselectedApps) = sortedApps(filtered)
            rowItems.clear()
            if (selectedApps.isNotEmpty()) {
                rowItems.add(WhitelistDialogRow.Header(getString(R.string.whitelist_selected_section)))
                rowItems.addAll(selectedApps.map { WhitelistDialogRow.AppItem(it) })
            }
            if (unselectedApps.isNotEmpty()) {
                rowItems.add(WhitelistDialogRow.Header(getString(R.string.whitelist_other_section)))
                rowItems.addAll(unselectedApps.map { WhitelistDialogRow.AppItem(it) })
            }
            adapter.notifyDataSetChanged()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val row = rowItems[position]
            if (row is WhitelistDialogRow.AppItem) {
                val pkg = row.app.packageName
                if (pkg in selectedPackages) {
                    selectedPackages.remove(pkg)
                } else {
                    selectedPackages.add(pkg)
                }
                prefs.setAllowedPackages(selectedPackages)
                refreshWhitelistUi()
                applyFilter(searchInput.text.toString())
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        applyFilter("")

        AlertDialog.Builder(this)
            .setTitle(R.string.whitelist_dialog_title)
            .setView(dialogContent)
            .setNeutralButton(R.string.whitelist_clear) { _, _ ->
                prefs.setAllowedPackages(emptySet())
                refreshWhitelistUi()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                prefs.setAllowedPackages(originalSelectedPackages)
                refreshWhitelistUi()
            }
            .setPositiveButton(R.string.whitelist_done) { _, _ ->
                refreshWhitelistUi()
            }
            .show()
    }

    private fun loadInstalledApps(): List<WhitelistApp> {
        installedAppsCache?.let { return it }

        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        val seenPackages = LinkedHashSet<String>()
        val apps = installedApps.mapNotNull { appInfo ->
            val pkg = appInfo.packageName
            if (pkg == packageName || !seenPackages.add(pkg)) {
                return@mapNotNull null
            }
            val label = packageManager.getApplicationLabel(appInfo).toString().trim()
            if (label.isEmpty()) return@mapNotNull null
            WhitelistApp(pkg, label)
        }.toMutableList()

        prefs.allowedPackages().forEach { pkg ->
            if (pkg != packageName && seenPackages.add(pkg)) {
                apps.add(resolveWhitelistApp(pkg))
            }
        }

        apps.sortWith(compareBy({ it.label.lowercase(Locale.getDefault()) }, { it.packageName }))
        installedAppsCache = apps
        return apps
    }

    private fun resolveWhitelistApp(packageName: String): WhitelistApp {
        val label = try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(appInfo).toString().trim()
        } catch (_: Exception) {
            ""
        }
        return WhitelistApp(packageName, label.ifEmpty { packageName })
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

        // 自适应高度：无时段时紧凑，有时段时铺满剩余空间（内部可滚动）
        val hasPeriods = periods.isNotEmpty()
        val cardLp = binding.cardQuietHours.layoutParams as android.widget.LinearLayout.LayoutParams
        cardLp.height = if (hasPeriods) 0 else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        cardLp.weight = if (hasPeriods) 1f else 0f
        binding.cardQuietHours.layoutParams = cardLp
        binding.layoutQuietHoursContent.layoutParams.height =
            if (hasPeriods) android.view.ViewGroup.LayoutParams.MATCH_PARENT
            else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        val scrollLp = binding.scrollQuietPeriods.layoutParams as android.widget.LinearLayout.LayoutParams
        scrollLp.height = if (hasPeriods) 0 else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        scrollLp.weight = if (hasPeriods) 1f else 0f
        binding.scrollQuietPeriods.layoutParams = scrollLp
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
        fun fmt(min: Int) = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)
        return "${fmt(period.startMin)} ~ ${fmt(period.endMin)}"
    }

    companion object {
        private const val SERVICE_STATE_REFRESH_MS = 1500L
        private const val FORCE_REBIND_INTERVAL_MS = 15_000L
    }
}

private data class WhitelistApp(
    val packageName: String,
    val label: String,
) {
    val displayText: String
        get() = "$label\n$packageName"
}

private sealed interface WhitelistDialogRow {
    data class Header(val title: String) : WhitelistDialogRow
    data class AppItem(val app: WhitelistApp) : WhitelistDialogRow
}
