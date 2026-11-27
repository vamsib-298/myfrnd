package com.wellness.overlay3d.service

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wellness.overlay3d.R
import com.wellness.overlay3d.model.ReminderSchedule
import com.wellness.overlay3d.overlay.OverlayWindowManager
import com.wellness.overlay3d.ui.MainActivity
import com.wellness.overlay3d.util.AudioEnvironment
import com.wellness.overlay3d.util.CompanionPreferences
import com.wellness.overlay3d.util.TaskManager
import com.wellness.overlay3d.util.TaskStorageException
import java.util.Calendar

class OverlayService : Service() {
    companion object {
        private const val TAG = "OverlayService"
        const val NOTIFICATION_CHANNEL_ID = "myfrnd_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_DEMO_NOW = "com.myfrnd.app.ACTION_DEMO_NOW"
        const val ACTION_STOP_SERVICE = "com.myfrnd.app.ACTION_STOP_SERVICE"
        const val ACTION_UPDATE_CONFIG = "com.myfrnd.app.ACTION_UPDATE_CONFIG"
        const val ACTION_TRIGGER_EYE_STRAIN = "com.myfrnd.app.ACTION_TRIGGER_EYE_STRAIN"
        const val ACTION_TRIGGER_WATER = "com.myfrnd.app.ACTION_TRIGGER_WATER"
        const val ACTION_START_FOCUS = "com.myfrnd.app.ACTION_START_FOCUS"
        const val ACTION_TOGGLE_PAUSE_FOCUS = "com.myfrnd.app.ACTION_TOGGLE_PAUSE_FOCUS"
        const val ACTION_STOP_FOCUS = "com.myfrnd.app.ACTION_STOP_FOCUS"
        const val ACTION_TRIGGER_WIND_DOWN = "com.myfrnd.app.ACTION_TRIGGER_WIND_DOWN"

        private const val ACTION_RESTORE_FOCUS = "com.myfrnd.app.ACTION_RESTORE_FOCUS"
        private val focusActions = setOf(ACTION_RESTORE_FOCUS, ACTION_START_FOCUS, ACTION_TOGGLE_PAUSE_FOCUS, ACTION_STOP_FOCUS)

        fun startWithInitialDemo(context: Context) = start(context, ACTION_DEMO_NOW)
        fun triggerScreenBreakNow(context: Context) = start(context, ACTION_TRIGGER_EYE_STRAIN)
        fun triggerWaterNow(context: Context) = start(context, ACTION_TRIGGER_WATER)
        fun restoreFocusSession(context: Context) = start(context, ACTION_RESTORE_FOCUS)
        fun startFocusSession(context: Context) = start(context, ACTION_START_FOCUS)
        fun togglePauseFocusSession(context: Context) = start(context, ACTION_TOGGLE_PAUSE_FOCUS)
        fun stopFocusSession(context: Context) = start(context, ACTION_STOP_FOCUS)
        fun triggerWindDownNow(context: Context) = start(context, ACTION_TRIGGER_WIND_DOWN)
        fun updateConfiguration(context: Context) = start(context, ACTION_UPDATE_CONFIG)

        fun pause(context: Context) {
            CompanionPreferences.setCompanionEnabled(context, false)
            context.stopService(Intent(context, OverlayService::class.java))
            CompanionRuntime.publish(CompanionStatus())
        }

        private fun start(context: Context, action: String): Boolean {
            if (action !in focusActions && (!CompanionPreferences.isCompanionEnabled(context) || !Settings.canDrawOverlays(context))) {
                CompanionRuntime.publish(CompanionStatus(error = R.string.setup_required))
                return false
            }
            return try {
                ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java).setAction(action))
                true
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Android rejected foreground service startup", error)
                CompanionRuntime.publish(CompanionStatus(error = R.string.service_start_error))
                false
            } catch (error: SecurityException) {
                Log.e(TAG, "Foreground service permission denied", error)
                CompanionRuntime.publish(CompanionStatus(error = R.string.service_start_error))
                false
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlays: OverlayWindowManager
    private lateinit var schedule: ReminderSchedule
    private var screenActive = false
    private var destroyed = false
    private var runtimeError: Int? = null

    private var previewRequested = false
    private var breakNowRequested = false
    private var waterNowRequested = false
    private var windDownRequested = false

    private var focusActive = false
    private var focusPaused = false
    private var catSound: MediaPlayer? = null
    private var focusEndElapsed: Long = 0L
    private var focusRemainingMs: Long = 0L

    private val evaluate = Runnable { evaluateReminders() }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (CompanionPreferences.isScheduleDeadlineKey(key)) return@OnSharedPreferenceChangeListener
        if (!CompanionPreferences.isCompanionEnabled(this) && !focusActive && !CompanionRuntime.roomVisible) {
            stopSelf()
        } else {
            schedule.configure(
                now = SystemClock.elapsedRealtime(),
                waterMinutes = CompanionPreferences.getWaterIntervalMinutes(this),
                eyeMinutes = CompanionPreferences.getEyeStrainMinutes(this),
                waterOn = CompanionPreferences.isWaterBreakEnabled(this),
                eyeOn = CompanionPreferences.isScreenBreakEnabled(this)
            )
            overlays.refreshPreferences()
            requestEvaluation()
        }
    }
    private val taskListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> requestEvaluation() }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenActive = isScreenAvailable()
            schedule.screenChanged(SystemClock.elapsedRealtime(), screenActive)
            if (!screenActive) overlays.destroyOverlayImmediately()
            requestEvaluation()
        }
    }

    override fun onCreate() {
        super.onCreate()
        screenActive = isScreenAvailable()
        val now = SystemClock.elapsedRealtime()
        val wallNow = System.currentTimeMillis()
        schedule = ReminderSchedule(
            now = now,
            waterMinutes = CompanionPreferences.getWaterIntervalMinutes(this),
            eyeMinutes = CompanionPreferences.getEyeStrainMinutes(this),
            screenActive = screenActive,
            waterEnabled = CompanionPreferences.isWaterBreakEnabled(this),
            eyeEnabled = CompanionPreferences.isScreenBreakEnabled(this)
        ).apply {
            restoreFromWallDeadlines(
                now = now,
                wallNow = wallNow,
                eyeWallMs = CompanionPreferences.getNextEyeWallMs(this@OverlayService),
                waterWallMs = CompanionPreferences.getNextWaterWallMs(this@OverlayService),
                screenActive = screenActive
            )
        }
        restorePersistedFocus(now, wallNow)
        startServiceInForeground()
        overlays = OverlayWindowManager(
            context = this,
            onDismissed = { requestEvaluation(400L) },
            onEyeBreakDone = {
                CompanionPreferences.incrementBreaksToday(this)
                schedule.eyeDelivered(SystemClock.elapsedRealtime())
                publishStatus()
            },
            onEyeBreakSnooze = {
                schedule.snoozeEye(SystemClock.elapsedRealtime(), 5)
                publishStatus()
            },
            onWaterDone = {
                CompanionPreferences.incrementWaterToday(this)
                schedule.waterDelivered(SystemClock.elapsedRealtime())
                publishStatus()
            },
            onWaterSnooze = {
                schedule.snoozeWater(SystemClock.elapsedRealtime(), 5)
                publishStatus()
            },
            onTaskSnooze = { taskId ->
                try {
                    TaskManager.snoozeReminder(this, taskId, 10 * 60_000L)
                } catch (error: TaskStorageException) {
                    Log.e(TAG, "Could not snooze task", error)
                }
            },
            onRollTasksTomorrow = {
                try {
                    TaskManager.rollPendingToTomorrowMorning(this)
                } catch (error: TaskStorageException) {
                    Log.e(TAG, "Could not roll reminders to tomorrow", error)
                    0
                }
            }
        )
        CompanionRuntime.onRoomVisibilityChanged = { requestEvaluation() }
        overlays.refreshPreferences()
        CompanionPreferences.getPrefs(this).registerOnSharedPreferenceChangeListener(preferenceListener)
        TaskManager.preferences(this).registerOnSharedPreferenceChangeListener(taskListener)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            pause(this)
            return START_NOT_STICKY
        }
        if (!canShowReminders() && !focusActive && intent?.action !in focusActions) {
            stopSelf()
            return START_NOT_STICKY
        }
        val now = SystemClock.elapsedRealtime()
        when (intent?.action) {
            ACTION_DEMO_NOW -> previewRequested = true
            ACTION_TRIGGER_EYE_STRAIN -> breakNowRequested = true
            ACTION_TRIGGER_WATER -> waterNowRequested = true
            ACTION_TRIGGER_WIND_DOWN -> windDownRequested = true
            ACTION_START_FOCUS -> {
                if (focusActive) {
                    requestEvaluation()
                    return START_STICKY
                }
                val mins = CompanionPreferences.getFocusDurationMinutes(this)
                focusActive = true
                CompanionPreferences.clearFocusBreak(this)
                focusPaused = false
                focusRemainingMs = mins * 60_000L
                focusEndElapsed = now + focusRemainingMs
                persistFocusState(now)
                CompanionRuntime.pendingCatEntrance = {
                    if (focusActive && !focusPaused && SystemClock.elapsedRealtime() - now <= 4_000) playCatSound()
                }
                if (overlays.isOverlayActive) overlays.destroyOverlayImmediately()
            }
            ACTION_TOGGLE_PAUSE_FOCUS -> {
                toggleFocusPauseInternal()
            }
            ACTION_STOP_FOCUS -> {
                CompanionRuntime.pendingCatEntrance = null
                releaseCatSound()
                focusActive = false
                focusPaused = false
                focusEndElapsed = 0L
                focusRemainingMs = 0L
                CompanionPreferences.clearFocusState(this)
            }
        }
        requestEvaluation()
        return START_STICKY
    }

    private fun restorePersistedFocus(now: Long, wallNow: Long) {
        val saved = CompanionPreferences.getFocusState(this)
        if (!saved.active) return
        if (saved.paused) {
            focusActive = true
            focusPaused = true
            focusRemainingMs = saved.remainingMs.coerceAtLeast(1_000L)
            focusEndElapsed = 0L
        } else {
            val remaining = saved.endWallMs - wallNow
            if (remaining > 0L) {
                focusActive = true
                focusPaused = false
                focusRemainingMs = remaining
                focusEndElapsed = now + remaining
            } else {
                focusActive = false
                focusPaused = false
                focusEndElapsed = 0L
                focusRemainingMs = 0L
                CompanionPreferences.finishFocus(this, saved.endWallMs)
            }
        }
    }

    private fun persistFocusState(now: Long) {
        if (!focusActive) {
            CompanionPreferences.clearFocusState(this)
            return
        }
        val wallEnd = if (focusPaused) 0L else System.currentTimeMillis() + (focusEndElapsed - now).coerceAtLeast(0L)
        val rem = if (focusPaused) focusRemainingMs else (focusEndElapsed - now).coerceAtLeast(0L)
        CompanionPreferences.saveFocusState(this, active = true, paused = focusPaused, endWallMs = wallEnd, remainingMs = rem)
    }

    private fun persistScheduleDeadlines(now: Long, wallNow: Long) {
        val eyeWall = schedule.nextEye?.let { wallNow + (it - now).coerceAtLeast(0L) }
        val waterWall = schedule.nextWater?.let { wallNow + (it - now).coerceAtLeast(0L) }
        CompanionPreferences.saveScheduleDeadlines(this, eyeWall, waterWall)
    }

    private fun toggleFocusPauseInternal() {
        if (!focusActive) return
        val now = SystemClock.elapsedRealtime()
        if (!focusPaused && now >= focusEndElapsed) {
            requestEvaluation()
            return
        }
        if (!focusPaused) {
            CompanionRuntime.pendingCatEntrance = null
            focusPaused = true
            focusRemainingMs = (focusEndElapsed - now).coerceAtLeast(1_000L)
            focusEndElapsed = 0L
            playCatSound()
        } else {
            releaseCatSound()
            focusPaused = false
            focusEndElapsed = now + focusRemainingMs.coerceAtLeast(1_000L)
        }
        persistFocusState(now)
        publishStatus()
        requestEvaluation()
    }

    private fun playCatSound() {
        releaseCatSound()
        val audio = getSystemService(AudioManager::class.java)
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 ||
            AudioEnvironment.isInActiveCall(this) ||
            (CompanionPreferences.isHeadphonesOnlyVoice(this) && !AudioEnvironment.areHeadphonesConnected(this))) return
        try {
            val player = MediaPlayer.create(this, R.raw.tabby_meow) ?: return
            catSound = player
            player.setVolume(.42f, .42f)
            player.setOnCompletionListener { if (catSound === it) releaseCatSound() }
            player.setOnErrorListener { failed, _, _ ->
                if (catSound === failed) releaseCatSound()
                true
            }
            player.start()
        } catch (error: RuntimeException) {
            releaseCatSound()
            Log.w(TAG, "Could not play the cat sound", error)
        }
    }

    private fun releaseCatSound() {
        catSound?.release()
        catSound = null
    }

    private fun isScreenAvailable(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun requestEvaluation(delay: Long = 0L) {
        if (destroyed) return
        handler.removeCallbacks(evaluate)
        handler.postDelayed(evaluate, delay)
    }

    private fun publishStatus() {
        val currentRemaining = if (focusActive) {
            if (focusPaused) focusRemainingMs else (focusEndElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else 0L
        CompanionRuntime.publish(
            CompanionStatus(
                running = true,
                screenActive = screenActive,
                screenBreakEnabled = CompanionPreferences.isScreenBreakEnabled(this),
                waterBreakEnabled = CompanionPreferences.isWaterBreakEnabled(this),
                nextEye = schedule.nextEye,
                nextWater = schedule.nextWater,
                focusActive = focusActive,
                focusPaused = focusPaused,
                focusEndElapsed = if (focusActive && !focusPaused) focusEndElapsed else null,
                focusRemainingMs = currentRemaining,
                focusDurationMin = CompanionPreferences.getFocusDurationMinutes(this),
                error = runtimeError
            )
        )
        updateForegroundNotification()
    }

    private fun isEveningWindDownDue(wallTime: Long): Boolean {
        if (!CompanionPreferences.isWindDownEnabled(this)) return false
        if (CompanionPreferences.wasWindDownShownToday(this, wallTime)) return false
        val cal = Calendar.getInstance().apply { timeInMillis = wallTime }
        return cal.get(Calendar.HOUR_OF_DAY) >= 20
    }

    private fun canShowReminders() =
        CompanionPreferences.isCompanionEnabled(this) && Settings.canDrawOverlays(this)

    private fun evaluateReminders() {
        val now = SystemClock.elapsedRealtime()
        val wallTime = System.currentTimeMillis()
        if (focusActive && !focusPaused && now >= focusEndElapsed) {
            CompanionPreferences.finishFocus(this, wallTime - (now - focusEndElapsed))
            focusActive = false
            focusPaused = false
            focusEndElapsed = 0L
            focusRemainingMs = 0L
        }

        if (CompanionRuntime.roomVisible || !canShowReminders() || !screenActive) {
            if (overlays.isOverlayActive) overlays.destroyOverlayImmediately()
            publishStatus()
            if (focusActive && !focusPaused) requestEvaluation((focusEndElapsed - now).coerceAtLeast(1L))
            if (!canShowReminders() && !focusActive) stopSelf()
            return
        }
        if (focusActive) {
            if (previewRequested && !overlays.isOverlayActive && !AudioEnvironment.isInActiveCall(this)) {
                if (overlays.showOverlay(OverlayWindowManager.OverlayReason.GENERAL_TEST, getString(R.string.demo_message))) {
                    previewRequested = false
                }
            }
            publishStatus()
            if (!focusPaused) requestEvaluation((focusEndElapsed - now).coerceAtLeast(1L))
            return
        }
        persistScheduleDeadlines(now, wallTime)
        publishStatus()

        // Delay reminder visits during a phone or VoIP call.
        val inCall = AudioEnvironment.isInActiveCall(this)
        if (inCall) {
            requestEvaluation(15_000L)
            return
        }

        if (overlays.isOverlayActive) return

        val allPending = try {
            TaskManager.getPendingReminders(this)
        } catch (error: TaskStorageException) {
            Log.e(TAG, "Reminders could not be loaded; saved data was not modified", error)
            runtimeError = R.string.task_storage_error
            publishStatus()
            requestEvaluation(60_000L)
            return
        }
        runtimeError = null
        val pendingTimed = allPending.filterNot { it.isNote }
        val task = pendingTimed.firstOrNull()

        val shown = when {
            previewRequested -> {
                overlays.showOverlay(
                    reason = OverlayWindowManager.OverlayReason.GENERAL_TEST,
                    spokenText = getString(R.string.demo_message)
                ).also { if (it) previewRequested = false }
            }
            task != null && task.timestampMillis <= wallTime -> {
                overlays.showOverlay(
                    reason = OverlayWindowManager.OverlayReason.WORK_TASK_REMINDER,
                    spokenText = getString(R.string.task_message, task.title),
                    taskTitle = task.title,
                    taskId = task.id
                ).also { attached ->
                    if (attached) TaskManager.markDelivered(this, task.id)
                }
            }
            breakNowRequested -> {
                overlays.showOverlay(
                    reason = OverlayWindowManager.OverlayReason.EYE_STRAIN,
                    spokenText = getString(R.string.eye_message)
                ).also { if (it) { breakNowRequested = false; schedule.eyeDelivered(now) } }
            }
            waterNowRequested -> {
                overlays.showOverlay(
                    reason = OverlayWindowManager.OverlayReason.WATER_REMINDER,
                    spokenText = getString(R.string.water_message)
                ).also { if (it) { waterNowRequested = false; schedule.waterDelivered(now) } }
            }
            (windDownRequested || isEveningWindDownDue(wallTime)) && allPending.isNotEmpty() -> {
                overlays.showOverlay(
                    reason = OverlayWindowManager.OverlayReason.EVENING_WIND_DOWN,
                    spokenText = getString(R.string.wind_down_body, allPending.size),
                    pendingCount = allPending.size
                ).also {
                    if (it) {
                        windDownRequested = false
                        CompanionPreferences.markWindDownShownToday(this, wallTime)
                    }
                }
            }
            schedule.nextWater?.let { now >= it } == true -> {
                overlays.showOverlay(
                    reason = OverlayWindowManager.OverlayReason.WATER_REMINDER,
                    spokenText = getString(R.string.water_message)
                ).also { if (it) schedule.waterDelivered(now) }
            }
            schedule.nextEye?.let { now >= it } == true -> {
                overlays.showOverlay(
                    reason = OverlayWindowManager.OverlayReason.EYE_STRAIN,
                    spokenText = getString(R.string.eye_message)
                ).also { if (it) schedule.eyeDelivered(now) }
            }
            else -> false
        }
        publishStatus()
        if (!shown) {
            val nextWake = schedule.nextDelay(now, task?.timestampMillis?.minus(wallTime))?.coerceAtLeast(1_000L) ?: 60_000L
            requestEvaluation(nextWake)
        }
    }

    private fun buildForegroundNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 100, Intent(this, if (focusActive) com.wellness.overlay3d.ui.FocusRoomActivity::class.java else MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopService = PendingIntent.getService(
            this, 103,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (focusActive) {
            val content = if (focusPaused) {
                val sec = (focusRemainingMs + 999L) / 1000L
                getString(R.string.focus_notif_paused, sec / 60, sec % 60)
            } else {
                getString(R.string.focus_notif_active, CompanionPreferences.getFocusDurationMinutes(this))
            }
            val pauseToggle = PendingIntent.getService(
                this, 104,
                Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_PAUSE_FOCUS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val stopFocus = PendingIntent.getService(
                this, 105,
                Intent(this, OverlayService::class.java).setAction(ACTION_STOP_FOCUS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentText(content)
                .addAction(0, getString(if (focusPaused) R.string.focus_resume else R.string.focus_pause), pauseToggle)
                .addAction(0, getString(R.string.focus_stop), stopFocus)
        } else {
            builder.setContentText(getString(R.string.notification_description))
                .addAction(0, getString(R.string.pause), stopService)
        }
        return builder.build()
    }

    private fun updateForegroundNotification() {
        if (destroyed) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildForegroundNotification())
    }

    private fun startServiceInForeground() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildForegroundNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseCatSound()
        CompanionRuntime.pendingCatEntrance = null
        CompanionRuntime.onRoomVisibilityChanged = null
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(screenReceiver)
        CompanionPreferences.getPrefs(this).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        TaskManager.preferences(this).unregisterOnSharedPreferenceChangeListener(taskListener)
        overlays.destroyOverlayImmediately()
        overlays.releaseTts()
        stopForeground(STOP_FOREGROUND_REMOVE)
        CompanionRuntime.publish(CompanionStatus(error = runtimeError))
        super.onDestroy()
    }
}
