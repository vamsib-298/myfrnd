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
import com.wellness.overlay3d.util.CompanionPreferences
import com.wellness.overlay3d.util.TaskManager
import com.wellness.overlay3d.util.TaskStorageException

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

        fun startWithInitialDemo(context: Context) = start(context, ACTION_DEMO_NOW)
        fun triggerScreenBreakNow(context: Context) = start(context, ACTION_TRIGGER_EYE_STRAIN)
        fun triggerWaterNow(context: Context) = start(context, ACTION_TRIGGER_WATER)
        fun updateConfiguration(context: Context) = start(context, ACTION_UPDATE_CONFIG)

        fun pause(context: Context) {
            CompanionPreferences.setCompanionEnabled(context, false)
            context.stopService(Intent(context, OverlayService::class.java))
            CompanionRuntime.publish(CompanionStatus())
        }

        private fun start(context: Context, action: String): Boolean {
            if (!CompanionPreferences.isCompanionEnabled(context) || !Settings.canDrawOverlays(context)) {
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
    private val evaluate = Runnable { evaluateReminders() }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!CompanionPreferences.isCompanionEnabled(this)) {
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
        startServiceInForeground()
        screenActive = isScreenAvailable()
        schedule = ReminderSchedule(
            now = SystemClock.elapsedRealtime(),
            waterMinutes = CompanionPreferences.getWaterIntervalMinutes(this),
            eyeMinutes = CompanionPreferences.getEyeStrainMinutes(this),
            screenActive = screenActive,
            waterEnabled = CompanionPreferences.isWaterBreakEnabled(this),
            eyeEnabled = CompanionPreferences.isScreenBreakEnabled(this)
        )
        overlays = OverlayWindowManager(
            context = this,
            onDismissed = { requestEvaluation(400L) },
            onEyeBreakDone = {
                schedule.eyeDelivered(SystemClock.elapsedRealtime())
                publishStatus()
            },
            onEyeBreakSnooze = {
                schedule.snoozeEye(SystemClock.elapsedRealtime(), 5)
                publishStatus()
            },
            onWaterDone = {
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
            }
        )
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
        if (!CompanionPreferences.isCompanionEnabled(this) || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_DEMO_NOW -> previewRequested = true
            ACTION_TRIGGER_EYE_STRAIN -> breakNowRequested = true
            ACTION_TRIGGER_WATER -> waterNowRequested = true
        }
        requestEvaluation()
        return START_STICKY
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
        CompanionRuntime.publish(
            CompanionStatus(
                running = true,
                screenActive = screenActive,
                screenBreakEnabled = CompanionPreferences.isScreenBreakEnabled(this),
                waterBreakEnabled = CompanionPreferences.isWaterBreakEnabled(this),
                nextEye = schedule.nextEye,
                nextWater = schedule.nextWater,
                error = runtimeError
            )
        )
    }

    private fun evaluateReminders() {
        if (!CompanionPreferences.isCompanionEnabled(this) || !Settings.canDrawOverlays(this)) {
            runtimeError = if (!Settings.canDrawOverlays(this)) R.string.setup_required else null
            stopSelf()
            return
        }
        publishStatus()
        if (!screenActive || overlays.isOverlayActive) return
        val now = SystemClock.elapsedRealtime()
        val wallTime = System.currentTimeMillis()
        val pendingTimed = try {
            TaskManager.getPendingTimedReminders(this)
        } catch (error: TaskStorageException) {
            Log.e(TAG, "Reminders could not be loaded; saved data was not modified", error)
            runtimeError = R.string.task_storage_error
            publishStatus()
            requestEvaluation(60_000L)
            return
        }
        runtimeError = null
        val task = pendingTimed.firstOrNull()
        val shown = when {
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
            previewRequested -> {
                overlays.showOverlay(
                    reason = OverlayWindowManager.OverlayReason.GENERAL_TEST,
                    spokenText = getString(R.string.demo_message)
                ).also { if (it) previewRequested = false }
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
            requestEvaluation(schedule.nextDelay(now, task?.timestampMillis?.minus(wallTime)).coerceAtLeast(1_000L))
        }
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
        val open = PendingIntent.getActivity(
            this, 100, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 103,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_description))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.pause), stop)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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
