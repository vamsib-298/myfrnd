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
    private var tapCount = 0
    private val stopTalking = Runnable { binding.liveDashboardCompanion.isTalking = false }
    private val taskListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> renderTasks() }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> loadSettings() }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) enableCompanion()
        loadSettings()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        loadSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
                requestOverlay()
            } else {
                if (!CompanionPreferences.isCompanionEnabled(this@MainActivity)) enableCompanion()
                OverlayService.startWithInitialDemo(this@MainActivity)
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

        switchScreenBreak.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            CompanionPreferences.setScreenBreakEnabled(this@MainActivity, checked)
            loadSettings()
        }

        eyeChoices().forEach { (button, value) ->
            button.setOnClickListener {
                CompanionPreferences.setEyeStrainMinutes(this@MainActivity, value)
                if (!CompanionPreferences.isScreenBreakEnabled(this@MainActivity)) {
                    CompanionPreferences.setScreenBreakEnabled(this@MainActivity, true)
                }
            }
        }

        liveDashboardCompanion.dragToRotate = true
        liveDashboardCompanion.setOnClickListener {
            val expressions = listOf(
                HumanCompanionView.Expression.WINK_PLAYFUL to R.string.demo_cheer,
                HumanCompanionView.Expression.TIRED_EYES to R.string.eye_message,
                HumanCompanionView.Expression.NORMAL to R.string.demo_message
            )
            val (exp, msg) = expressions[tapCount++ % expressions.size]
            liveDashboardCompanion.displayMode = HumanCompanionView.DisplayMode.FULL_BODY
            liveDashboardCompanion.animationPhase = if (tapCount % 3 == 1)
                HumanCompanionView.PHASE_WALK_IN else HumanCompanionView.PHASE_IDLE
            liveDashboardCompanion.currentExpression = exp
            liveDashboardCompanion.isTalking = true
            liveDashboardCompanion.triggerTapReaction()
            liveDashboardCompanion.removeCallbacks(stopTalking)
            liveDashboardCompanion.postDelayed(stopTalking, 2_400L)
            tvLiveCompanionSpeech.setText(msg)
        }

        btnAddNewTask.setOnClickListener {
            TaskEditorDialog.create().show(supportFragmentManager, "task-editor")
        }
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

    private fun select(button: Button, selected: Boolean) {
        button.isSelected = selected
        ViewCompat.setStateDescription(button, getString(if (selected) R.string.selected else R.string.not_selected))
    }

    private fun loadSettings() = with(binding) {
        syncing = true
        switchScreenBreak.isChecked = CompanionPreferences.isScreenBreakEnabled(this@MainActivity)
        liveDashboardCompanion.reducedMotion = CompanionPreferences.isReducedMotion(this@MainActivity)
        syncing = false
        eyeChoices().forEach { select(it.first, it.second == CompanionPreferences.getEyeStrainMinutes(this@MainActivity)) }
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
        val screenBreakOn = CompanionPreferences.isScreenBreakEnabled(this)
        val minutes = CompanionPreferences.getEyeStrainMinutes(this)
        val deadline = status.nextEye
        val remaining = ((deadline ?: 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        val seconds = (remaining + 999) / 1000
        val text = when {
            !screenBreakOn || !status.running -> getString(R.string.countdown_paused)
            !status.screenActive -> getString(R.string.countdown_unlock)
            seconds == 0L -> getString(R.string.countdown_waiting)
            else -> getString(R.string.countdown, seconds / 60, seconds % 60)
        }
        if (binding.tvEyeCountdown.text != text) {
            binding.tvEyeCountdown.text = text
            binding.tvEyeCountdown.contentDescription =
                getString(R.string.countdown_accessibility, getString(R.string.eye_title), text)
        }
        binding.eyeProgress.progress = if (!screenBreakOn || !status.running || deadline == null) 0
        else (1000 - remaining * 1000 / (minutes * 60_000L)).toInt().coerceIn(0, 1000)
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
