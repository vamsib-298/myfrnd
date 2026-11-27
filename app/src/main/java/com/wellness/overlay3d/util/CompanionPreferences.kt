package com.wellness.overlay3d.util

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CompanionPreferences: Settings for Screen Break, Water Break, Voice, Focus Mode,
 * Daily Streaks and Evening Wind-Down.
 */
object CompanionPreferences {
    private const val PREFS_NAME = "myfrnd_preferences"

    private const val KEY_WATER_INTERVAL_MIN = "key_water_interval_min"
    private const val KEY_WATER_BREAK_ENABLED = "key_water_break_enabled"
    private const val KEY_EYE_STRAIN_MIN = "key_eye_strain_min"
    private const val KEY_SCREEN_BREAK_ENABLED = "key_screen_break_enabled"
    private const val KEY_COMPANION_ENABLED = "key_companion_enabled"
    private const val KEY_POSITION_MODE = "key_position_mode"
    private const val KEY_VOICE_ENABLED = "key_voice_enabled"
    private const val KEY_HEADPHONES_ONLY_VOICE = "key_headphones_only_voice"
    private const val KEY_REDUCED_MOTION = "key_reduced_motion"
    private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "key_notification_permission_requested"

    // Focus / Pomodoro + Persisted Runtime Deadlines
    private const val KEY_FOCUS_DURATION_MIN = "key_focus_duration_min"
    private const val KEY_FOCUS_ACTIVE = "key_focus_active"
    private const val KEY_FOCUS_PAUSED = "key_focus_paused"
    private const val KEY_FOCUS_END_WALL_MS = "key_focus_end_wall_ms"
    private const val KEY_FOCUS_REMAINING_MS = "key_focus_remaining_ms"
    private const val KEY_NEXT_EYE_WALL_MS = "key_next_eye_wall_ms"
    private const val KEY_NEXT_WATER_WALL_MS = "key_next_water_wall_ms"

    // Daily Streaks (reset automatically when date key changes)
    private const val KEY_STREAK_DATE = "key_streak_date"
    private const val KEY_BREAKS_TODAY = "key_breaks_today"
    private const val KEY_WATER_TODAY = "key_water_today"
    private const val KEY_FOCUS_TODAY = "key_focus_today"

    // End-of-Day Wind Down
    private const val KEY_WIND_DOWN_ENABLED = "key_wind_down_enabled"
    private const val KEY_LAST_WIND_DOWN_DATE = "key_last_wind_down_date"

    const val POSITION_AUTO = "random"
    const val POSITION_THREAD_DROP = "thread_drop"
    const val POSITION_LEFT_RIGHT = "left_right"
    const val POSITION_RIGHT_LEFT = "right_left"
    const val POSITION_TOP_RIGHT = "top_right"
    const val POSITION_BOTTOM = "bottom"

    fun clearRetiredReaderSettings(context: Context) {
        val prefs = getPrefs(context)
        val keys = listOf("key_notif_reader_enabled", "key_notif_allowed_packages", "key_notif_vip_filter")
        if (keys.any(prefs::contains)) {
            val edit = prefs.edit()
            keys.forEach(edit::remove)
            edit.apply()
        }
    }

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getWaterIntervalMinutes(context: Context): Int =
        getPrefs(context).getInt(KEY_WATER_INTERVAL_MIN, 30)

    fun setWaterIntervalMinutes(context: Context, minutes: Int) {
        require(minutes in listOf(15, 30, 45, 60))
        getPrefs(context).edit().putInt(KEY_WATER_INTERVAL_MIN, minutes).apply()
    }

    fun isWaterBreakEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_WATER_BREAK_ENABLED, true)

    fun setWaterBreakEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_WATER_BREAK_ENABLED, enabled).apply()
    }

    fun getEyeStrainMinutes(context: Context): Int =
        getPrefs(context).getInt(KEY_EYE_STRAIN_MIN, 20)

    fun setEyeStrainMinutes(context: Context, minutes: Int) {
        require(minutes in listOf(15, 20, 30, 45))
        getPrefs(context).edit().putInt(KEY_EYE_STRAIN_MIN, minutes).apply()
    }

    fun isScreenBreakEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SCREEN_BREAK_ENABLED, true)

    fun setScreenBreakEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SCREEN_BREAK_ENABLED, enabled).apply()
    }

    fun isCompanionEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_COMPANION_ENABLED, false)

    fun setCompanionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_COMPANION_ENABLED, enabled).apply()
    }

    fun getPositionMode(context: Context): String =
        getPrefs(context).getString(KEY_POSITION_MODE, POSITION_AUTO) ?: POSITION_AUTO

    fun setPositionMode(context: Context, mode: String) {
        require(mode in listOf(POSITION_AUTO, POSITION_THREAD_DROP, POSITION_LEFT_RIGHT,
            POSITION_RIGHT_LEFT, POSITION_TOP_RIGHT, POSITION_BOTTOM))
        getPrefs(context).edit().putString(KEY_POSITION_MODE, mode).apply()
    }

    fun isVoiceEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_VOICE_ENABLED, true)

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VOICE_ENABLED, enabled).apply()
    }

    fun isHeadphonesOnlyVoice(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_HEADPHONES_ONLY_VOICE, false)

    fun setHeadphonesOnlyVoice(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HEADPHONES_ONLY_VOICE, enabled).apply()
    }

    fun isReducedMotion(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_REDUCED_MOTION, false)

    fun setReducedMotion(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_REDUCED_MOTION, enabled).apply()
    }

    fun wasNotificationPermissionRequested(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)

    fun markNotificationPermissionRequested(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
    }

    // Focus / Pomodoro
    fun getFocusDurationMinutes(context: Context): Int =
        getPrefs(context).getInt(KEY_FOCUS_DURATION_MIN, 25)

    fun setFocusDurationMinutes(context: Context, minutes: Int) {
        require(minutes in listOf(25, 50))
        getPrefs(context).edit().putInt(KEY_FOCUS_DURATION_MIN, minutes).apply()
    }

    // Daily Streaks
    fun todayDateKey(nowMillis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis))

    private fun ensureTodayStreak(context: Context, nowMillis: Long = System.currentTimeMillis()): SharedPreferences {
        val prefs = getPrefs(context)
        val today = todayDateKey(nowMillis)
        if (prefs.getString(KEY_STREAK_DATE, null) != today) {
            prefs.edit()
                .putString(KEY_STREAK_DATE, today)
                .putInt(KEY_BREAKS_TODAY, 0)
                .putInt(KEY_WATER_TODAY, 0)
                .putInt(KEY_FOCUS_TODAY, 0)
                .apply()
        }
        return prefs
    }

    fun getBreaksToday(context: Context, nowMillis: Long = System.currentTimeMillis()): Int =
        ensureTodayStreak(context, nowMillis).getInt(KEY_BREAKS_TODAY, 0)

    fun incrementBreaksToday(context: Context, nowMillis: Long = System.currentTimeMillis()): Int {
        val prefs = ensureTodayStreak(context, nowMillis)
        val next = prefs.getInt(KEY_BREAKS_TODAY, 0) + 1
        prefs.edit().putInt(KEY_BREAKS_TODAY, next).apply()
        return next
    }

    fun getWaterToday(context: Context, nowMillis: Long = System.currentTimeMillis()): Int =
        ensureTodayStreak(context, nowMillis).getInt(KEY_WATER_TODAY, 0)

    fun incrementWaterToday(context: Context, nowMillis: Long = System.currentTimeMillis()): Int {
        val prefs = ensureTodayStreak(context, nowMillis)
        val next = prefs.getInt(KEY_WATER_TODAY, 0) + 1
        prefs.edit().putInt(KEY_WATER_TODAY, next).apply()
        return next
    }

    fun getFocusToday(context: Context, nowMillis: Long = System.currentTimeMillis()): Int =
        ensureTodayStreak(context, nowMillis).getInt(KEY_FOCUS_TODAY, 0)

    fun incrementFocusToday(context: Context, nowMillis: Long = System.currentTimeMillis()): Int {
        val prefs = ensureTodayStreak(context, nowMillis)
        val next = prefs.getInt(KEY_FOCUS_TODAY, 0) + 1
        prefs.edit().putInt(KEY_FOCUS_TODAY, next).apply()
        return next
    }

    // Wind Down
    fun isWindDownEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_WIND_DOWN_ENABLED, true)

    fun setWindDownEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_WIND_DOWN_ENABLED, enabled).apply()
    }

    fun wasWindDownShownToday(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean =
        getPrefs(context).getString(KEY_LAST_WIND_DOWN_DATE, null) == todayDateKey(nowMillis)

    fun markWindDownShownToday(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        getPrefs(context).edit().putString(KEY_LAST_WIND_DOWN_DATE, todayDateKey(nowMillis)).apply()
    }

    data class PersistedFocusState(
        val active: Boolean,
        val paused: Boolean,
        val endWallMs: Long,
        val remainingMs: Long
    )

    fun saveFocusState(
        context: Context,
        active: Boolean,
        paused: Boolean,
        endWallMs: Long,
        remainingMs: Long
    ) {
        getPrefs(context).edit()
            .putBoolean(KEY_FOCUS_ACTIVE, active)
            .putBoolean(KEY_FOCUS_PAUSED, paused)
            .putLong(KEY_FOCUS_END_WALL_MS, endWallMs)
            .putLong(KEY_FOCUS_REMAINING_MS, remainingMs)
            .apply()
    }

    fun getFocusState(context: Context): PersistedFocusState {
        val p = getPrefs(context)
        return PersistedFocusState(
            active = p.getBoolean(KEY_FOCUS_ACTIVE, false),
            paused = p.getBoolean(KEY_FOCUS_PAUSED, false),
            endWallMs = p.getLong(KEY_FOCUS_END_WALL_MS, 0L),
            remainingMs = p.getLong(KEY_FOCUS_REMAINING_MS, 0L)
        )
    }

    fun clearFocusState(context: Context) {
        saveFocusState(context, active = false, paused = false, endWallMs = 0L, remainingMs = 0L)
    }

    const val FOCUS_BREAK_MS = 5 * 60_000L
    private const val KEY_BREAK_END = "focus_break_end_wall"

    fun breakEnd(context: Context) = getPrefs(context).getLong(KEY_BREAK_END, 0L)

    fun clearFocusBreak(context: Context) = getPrefs(context).edit().remove(KEY_BREAK_END).apply()

    /** Commit the reward, break deadline and finished timer together, including after a restart. */
    fun finishFocus(context: Context, finishedAt: Long) {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_FOCUS_ACTIVE, false)) return
        val editor = prefs.edit()
        if (todayDateKey(finishedAt) == todayDateKey()) {
            ensureTodayStreak(context, finishedAt)
            editor.putInt(KEY_FOCUS_TODAY, prefs.getInt(KEY_FOCUS_TODAY, 0) + 1)
        }
        editor.putBoolean(KEY_FOCUS_ACTIVE, false).putBoolean(KEY_FOCUS_PAUSED, false)
            .putLong(KEY_FOCUS_END_WALL_MS, 0).putLong(KEY_FOCUS_REMAINING_MS, 0)
            .putLong(KEY_BREAK_END, finishedAt + FOCUS_BREAK_MS).apply()
    }

    fun isScheduleDeadlineKey(key: String?) = key == KEY_NEXT_EYE_WALL_MS || key == KEY_NEXT_WATER_WALL_MS

    fun saveScheduleDeadlines(context: Context, nextEyeWallMs: Long?, nextWaterWallMs: Long?) {
        getPrefs(context).edit()
            .putLong(KEY_NEXT_EYE_WALL_MS, nextEyeWallMs ?: 0L)
            .putLong(KEY_NEXT_WATER_WALL_MS, nextWaterWallMs ?: 0L)
            .apply()
    }

    fun getNextEyeWallMs(context: Context): Long? =
        getPrefs(context).getLong(KEY_NEXT_EYE_WALL_MS, 0L).takeIf { it > 0L }

    fun getNextWaterWallMs(context: Context): Long? =
        getPrefs(context).getLong(KEY_NEXT_WATER_WALL_MS, 0L).takeIf { it > 0L }
}
