package com.wellness.overlay3d.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.descendants
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wellness.overlay3d.R
import com.wellness.overlay3d.databinding.ActivityMainBinding
import com.wellness.overlay3d.databinding.ItemTaskReminderBinding
import com.wellness.overlay3d.overlay.HumanCompanionView
import com.wellness.overlay3d.service.CompanionRuntime
import com.wellness.overlay3d.service.CompanionStatus
import com.wellness.overlay3d.service.OverlayService
import com.wellness.overlay3d.util.CompanionPreferences
import com.wellness.overlay3d.util.NotificationPermissionFlow
import com.wellness.overlay3d.util.TaskManager
import com.wellness.overlay3d.util.TaskStorageException
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var syncing = false
    private var pendingPhoneVisit = false
    private var tapCount = 0
    private val stopTalking = Runnable { binding.liveDashboardCompanion.isTalking = false }
    private val taskListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> renderTasks() }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> loadSettings() }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            if (pendingPhoneVisit) showPhoneVisit() else enableCompanion()
        }
        pendingPhoneVisit = false
        loadSettings()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        loadSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CompanionPreferences.clearRetiredReaderSettings(this)
        pendingPhoneVisit = savedInstanceState?.getBoolean("pendingPhoneVisit") == true
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.scanCredit.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        binding.dashboardContent.descendants.filter { it.tag == "heading" }
            .forEach { ViewCompat.setAccessibilityHeading(it, true) }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val maxWidth = (720 * resources.displayMetrics.density).toInt()
        val padding = resources.getDimensionPixelSize(R.dimen.dashboard_padding)
        binding.root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val side = maxOf(padding, (view.width - maxWidth) / 2)
            if (binding.dashboardContent.paddingLeft != side) {
                binding.dashboardContent.setPadding(side, padding, side, padding)
            }
        }
        setupListeners()
        loadSettings()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CompanionRuntime.status.collect { renderStatus(it) } }
                launch {
                    while (isActive) {
                        renderCountdowns(CompanionRuntime.status.value)
                        delay(1_000L)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TaskManager.preferences(this).registerOnSharedPreferenceChangeListener(taskListener)
        CompanionPreferences.getPrefs(this).registerOnSharedPreferenceChangeListener(settingsListener)
        renderTasks()
    }

    override fun onResume() {
        super.onResume()
        binding.liveDashboardCompanion.animationEnabled = true
        if (Settings.canDrawOverlays(this)) {
            if (!CompanionPreferences.isCompanionEnabled(this)) {
                enableCompanion()
            } else if (!CompanionRuntime.status.value.running) {
                OverlayService.updateConfiguration(this)
            }
        }
        loadSettings()
    }

    override fun onPause() {
        binding.liveDashboardCompanion.animationEnabled = false
        binding.liveDashboardCompanion.removeCallbacks(stopTalking)
        binding.liveDashboardCompanion.isTalking = false
        super.onPause()
    }

    override fun onStop() {
        TaskManager.preferences(this).unregisterOnSharedPreferenceChangeListener(taskListener)
        CompanionPreferences.getPrefs(this).unregisterOnSharedPreferenceChangeListener(settingsListener)
        super.onStop()
    }

    private fun setupListeners() = with(binding) {
        btnMandatoryGrant.setOnClickListener { requestOverlay() }

        tvEngineStatusBadge.setOnClickListener {
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                requestOverlay()
            } else if (CompanionPreferences.isCompanionEnabled(this@MainActivity)) {
                OverlayService.pause(this@MainActivity)
                loadSettings()
            } else {
                enableCompanion()
                loadSettings()
            }
        }

        btnTestCompanionNow.setOnClickListener {
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                pendingPhoneVisit = true
                requestOverlay()
            } else showPhoneVisit()
        }

        btnFocusToggle.setOnClickListener {
            val saved = CompanionPreferences.getFocusState(this@MainActivity)
            val onBreak = CompanionPreferences.breakEnd(this@MainActivity) > System.currentTimeMillis()
            if (saved.active || onBreak || OverlayService.startFocusSession(this@MainActivity)) {
                startActivity(Intent(this@MainActivity, FocusRoomActivity::class.java))
            }

        }

        btnFocusDuration.setOnClickListener {
            if (CompanionPreferences.getFocusState(this@MainActivity).active) {
                OverlayService.togglePauseFocusSession(this@MainActivity)
            } else {
                val next = if (CompanionPreferences.getFocusDurationMinutes(this@MainActivity) == 25) 50 else 25
                CompanionPreferences.setFocusDurationMinutes(this@MainActivity, next)
                loadSettings()
            }
        }

        btnStartBreakNow.setOnClickListener {
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                requestOverlay()
            } else {
                if (!CompanionPreferences.isCompanionEnabled(this@MainActivity)) enableCompanion()
                CompanionPreferences.setScreenBreakEnabled(this@MainActivity, true)
                OverlayService.triggerScreenBreakNow(this@MainActivity)
            }
        }

        btnSipWaterNow.setOnClickListener {
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                requestOverlay()
            } else {
                if (!CompanionPreferences.isCompanionEnabled(this@MainActivity)) enableCompanion()
                CompanionPreferences.setWaterBreakEnabled(this@MainActivity, true)
                OverlayService.triggerWaterNow(this@MainActivity)
            }
        }

        switchScreenBreak.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            CompanionPreferences.setScreenBreakEnabled(this@MainActivity, checked)
            loadSettings()
        }

        switchWaterBreak.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            CompanionPreferences.setWaterBreakEnabled(this@MainActivity, checked)
            loadSettings()
        }

        switchVoice.setOnCheckedChangeListener { _, checked ->
            if (!syncing) CompanionPreferences.setVoiceEnabled(this@MainActivity, checked)
        }

        btnHeadphonesOnly.setOnClickListener {
            val next = !CompanionPreferences.isHeadphonesOnlyVoice(this@MainActivity)
            CompanionPreferences.setHeadphonesOnlyVoice(this@MainActivity, next)
            loadSettings()
        }

        switchWindDown.setOnCheckedChangeListener { _, checked ->
            if (!syncing) CompanionPreferences.setWindDownEnabled(this@MainActivity, checked)
        }

        btnTriggerWindDown.setOnClickListener {
            val pending = try {
                TaskManager.getPendingReminders(this@MainActivity)
            } catch (_: TaskStorageException) {
                emptyList()
            }
            if (pending.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.wind_down_empty, Toast.LENGTH_SHORT).show()
            } else if (!Settings.canDrawOverlays(this@MainActivity)) {
                requestOverlay()
            } else {
                if (!CompanionPreferences.isCompanionEnabled(this@MainActivity)) enableCompanion()
                OverlayService.triggerWindDownNow(this@MainActivity)
            }
        }

        eyeChoices().forEach { (button, value) ->
            button.setOnClickListener {
                CompanionPreferences.setEyeStrainMinutes(this@MainActivity, value)
                if (!CompanionPreferences.isScreenBreakEnabled(this@MainActivity)) {
                    CompanionPreferences.setScreenBreakEnabled(this@MainActivity, true)
                }
            }
        }

        waterChoices().forEach { (button, value) ->
            button.setOnClickListener {
                CompanionPreferences.setWaterIntervalMinutes(this@MainActivity, value)
                if (!CompanionPreferences.isWaterBreakEnabled(this@MainActivity)) {
                    CompanionPreferences.setWaterBreakEnabled(this@MainActivity, true)
                }
            }
        }

        liveDashboardCompanion.dragToRotate = true
        liveDashboardCompanion.setOnClickListener {
            val expressions = listOf(
                HumanCompanionView.Expression.WINK_PLAYFUL to R.string.demo_cheer,
                HumanCompanionView.Expression.HAPPY_WATER to R.string.water_message,
                HumanCompanionView.Expression.TIRED_EYES to R.string.eye_message,
                HumanCompanionView.Expression.NORMAL to R.string.demo_message
            )
            val (exp, msg) = expressions[tapCount++ % expressions.size]
            liveDashboardCompanion.displayMode = HumanCompanionView.DisplayMode.FULL_BODY
            liveDashboardCompanion.walkDirection = if (tapCount % 8 == 1) 1 else -1
            liveDashboardCompanion.animationPhase = if (tapCount % 4 == 1)
                HumanCompanionView.PHASE_WALK_IN else HumanCompanionView.PHASE_IDLE
            liveDashboardCompanion.currentExpression = exp
            liveDashboardCompanion.isTalking = true
            if (exp == HumanCompanionView.Expression.NORMAL) liveDashboardCompanion.triggerTapReaction()
            liveDashboardCompanion.removeCallbacks(stopTalking)
            liveDashboardCompanion.postDelayed(stopTalking, 2_400L)
            tvLiveCompanionSpeech.setText(msg)
        }

        btnAddNewTask.setOnClickListener {
            TaskEditorDialog.create().show(supportFragmentManager, "task-editor")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("pendingPhoneVisit", pendingPhoneVisit)
    }

    private fun showPhoneVisit() {
        CompanionPreferences.setCompanionEnabled(this, true)
        if (OverlayService.startWithInitialDemo(this)) moveTaskToBack(true)
    }

    private fun requestOverlay() {
        try {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (error: ActivityNotFoundException) {
            Log.e("MainActivity", "Overlay settings unavailable", error)
            Toast.makeText(this, R.string.permission_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun enableCompanion() {
        CompanionPreferences.setCompanionEnabled(this, true)
        OverlayService.updateConfiguration(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            NotificationPermissionFlow.nextAction(
                permissionGranted = false,
                alreadyRequested = CompanionPreferences.wasNotificationPermissionRequested(this),
                shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) == NotificationPermissionFlow.Action.SHOW_SYSTEM_PROMPT
        ) {
            requestNotificationPermission()
        }
    }

    @RequiresApi(33)
    private fun requestNotificationPermission() {
        CompanionPreferences.markNotificationPermissionRequested(this)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun eyeChoices() = with(binding) {
        listOf(chipEye15 to 15, chipEye20 to 20, chipEye30 to 30, chipEye45 to 45)
    }

    private fun waterChoices() = with(binding) {
        listOf(chipWater15 to 15, chipWater30 to 30, chipWater45 to 45, chipWater60 to 60)
    }

    private fun select(button: Button, selected: Boolean) {
        button.isSelected = selected
        ViewCompat.setStateDescription(button, getString(if (selected) R.string.selected else R.string.not_selected))
    }

    private fun loadSettings() = with(binding) {
        syncing = true
        switchScreenBreak.isChecked = CompanionPreferences.isScreenBreakEnabled(this@MainActivity)
        switchWaterBreak.isChecked = CompanionPreferences.isWaterBreakEnabled(this@MainActivity)
        switchVoice.isChecked = CompanionPreferences.isVoiceEnabled(this@MainActivity)
        switchWindDown.isChecked = CompanionPreferences.isWindDownEnabled(this@MainActivity)
        liveDashboardCompanion.reducedMotion = CompanionPreferences.isReducedMotion(this@MainActivity)
        syncing = false

        select(btnHeadphonesOnly, CompanionPreferences.isHeadphonesOnlyVoice(this@MainActivity))
        eyeChoices().forEach { select(it.first, it.second == CompanionPreferences.getEyeStrainMinutes(this@MainActivity)) }
        waterChoices().forEach { select(it.first, it.second == CompanionPreferences.getWaterIntervalMinutes(this@MainActivity)) }

        val focusMin = CompanionPreferences.getFocusDurationMinutes(this@MainActivity)
        btnFocusDuration.text = getString(if (focusMin == 50) R.string.focus_50m else R.string.focus_25m)

        val breaksToday = CompanionPreferences.getBreaksToday(this@MainActivity)
        val waterToday = CompanionPreferences.getWaterToday(this@MainActivity)
        tvStreakBadge.text = getString(R.string.streak_summary, breaksToday, waterToday)

        llMandatoryOverlayBanner.isVisible = !Settings.canDrawOverlays(this@MainActivity)
        renderStatus(CompanionRuntime.status.value)
    }

    private fun renderStatus(status: CompanionStatus) = with(binding) {
        val hasOverlay = Settings.canDrawOverlays(this@MainActivity)
        val enabled = CompanionPreferences.isCompanionEnabled(this@MainActivity)
        val isActive = hasOverlay && enabled && status.running
        tvEngineStatusBadge.setText(
            when {
                !hasOverlay -> R.string.setup_required
                !enabled -> R.string.status_paused
                status.error != null -> R.string.status_problem
                status.running -> R.string.status_active
                else -> R.string.status_starting
            }
        )
        tvEngineStatusBadge.setBackgroundResource(if (isActive) R.drawable.bg_pill_active else R.drawable.bg_pill_inactive)
        tvEngineStatusBadge.setTextColor(getColor(if (isActive) R.color.on_primary else R.color.primary))
        tvRuntimeError.isVisible = status.error != null
        status.error?.let { tvRuntimeError.setText(it) }
        renderCountdowns(status)
    }

    private fun renderCountdowns(status: CompanionStatus) {
        fun renderTimer(
            view: TextView,
            progress: ProgressBar,
            enabled: Boolean,
            requireScreenActive: Boolean,
            deadline: Long?,
            minutes: Int,
            labelRes: Int
        ) {
            val remaining = ((deadline ?: 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            val seconds = (remaining + 999) / 1000
            val text = when {
                !enabled || !status.running -> getString(R.string.countdown_paused)
                requireScreenActive && !status.screenActive -> getString(R.string.countdown_unlock)
                seconds == 0L -> getString(R.string.countdown_waiting)
                else -> getString(R.string.countdown, seconds / 60, seconds % 60)
            }
            if (view.text != text) {
                view.text = text
                view.contentDescription = getString(R.string.countdown_accessibility, getString(labelRes), text)
            }
            progress.progress = if (!enabled || !status.running || deadline == null) 0
            else (1000 - remaining * 1000 / (minutes * 60_000L)).toInt().coerceIn(0, 1000)
        }
        renderTimer(
            binding.tvEyeCountdown, binding.eyeProgress,
            CompanionPreferences.isScreenBreakEnabled(this), true,
            status.nextEye, CompanionPreferences.getEyeStrainMinutes(this), R.string.eye_title
        )
        renderTimer(
            binding.tvWaterCountdown, binding.waterProgress,
            CompanionPreferences.isWaterBreakEnabled(this), false,
            status.nextWater, CompanionPreferences.getWaterIntervalMinutes(this), R.string.water_title
        )

        val focus = CompanionPreferences.getFocusState(this)

        binding.btnFocusDuration.isVisible = !focus.active
        if (focus.active) {
            binding.btnFocusToggle.setText(R.string.focus_open_room)
            binding.btnFocusToggle.setBackgroundResource(R.drawable.bg_pill_active)
            binding.btnFocusToggle.setTextColor(getColor(R.color.on_primary))
        } else {
            val focusMin = CompanionPreferences.getFocusDurationMinutes(this)
            binding.btnFocusToggle.setText(when {
                CompanionPreferences.breakEnd(this) > System.currentTimeMillis() -> R.string.return_to_break
                focusMin == 50 -> R.string.focus_start_50
                else -> R.string.focus_start_25
            })
            binding.btnFocusToggle.setBackgroundResource(R.drawable.bg_button_primary)
            binding.btnFocusToggle.setTextColor(getColor(R.color.on_primary))
            binding.btnFocusDuration.setText(if (focusMin == 50) R.string.focus_50m else R.string.focus_25m)
        }
    }

    private fun renderTasks() {
        val tasks = try {
            TaskManager.getPendingReminders(this)
        } catch (error: TaskStorageException) {
            Log.e("MainActivity", "Could not display saved reminders", error)
            binding.tvTaskError.setText(R.string.task_storage_error)
            binding.tvTaskError.isVisible = true
            binding.tvEmptyTasks.isVisible = false
            binding.btnAddNewTask.isEnabled = false
            return
        }
        binding.tvTaskError.isVisible = false
        binding.btnAddNewTask.isEnabled = true
        binding.tvEmptyTasks.isVisible = tasks.isEmpty()
        binding.tvTaskCount.text = getString(R.string.task_count, tasks.size)
        binding.llTasksContainer.removeAllViews()
        tasks.forEach { task ->
            val item = ItemTaskReminderBinding.inflate(layoutInflater, binding.llTasksContainer, false)
            item.tvTaskTitle.text = task.title
            item.tvTaskTime.text = if (task.isNote) {
                getString(R.string.note_label_badge)
            } else {
                val date = Date(task.timestampMillis)
                val time = "${DateFormat.getMediumDateFormat(this).format(date)}, ${DateFormat.getTimeFormat(this).format(date)}"
                if (task.timestampMillis <= System.currentTimeMillis()) getString(R.string.task_overdue, time) else time
            }
            item.btnEditTask.contentDescription = getString(R.string.edit_task, task.title)
            val openEditor = { TaskEditorDialog.create(task).show(supportFragmentManager, "task-editor") }
            item.btnEditTask.setOnClickListener { openEditor() }
            item.llTaskBody.setOnClickListener { openEditor() }
            item.btnDeleteTask.contentDescription = getString(R.string.done_task, task.title)
            item.btnDeleteTask.setOnClickListener {
                try {
                    TaskManager.removeReminder(this, task.id)
                    Toast.makeText(this, R.string.reminder_deleted, Toast.LENGTH_SHORT).show()
                } catch (error: TaskStorageException) {
                    Log.e("MainActivity", "Could not complete reminder", error)
                    renderTasks()
                }
            }
            binding.llTasksContainer.addView(item.root)
        }
    }
}
