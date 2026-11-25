package com.wellness.overlay3d.util

import android.content.Context
import android.content.SharedPreferences

/**
 * CompanionPreferences: Focused settings for Screen Break, Companion state, and permissions.
 */
object CompanionPreferences {
    private const val PREFS_NAME = "myfrnd_preferences"

    private const val KEY_EYE_STRAIN_MIN = "key_eye_strain_min"
    private const val KEY_SCREEN_BREAK_ENABLED = "key_screen_break_enabled"
    private const val KEY_COMPANION_ENABLED = "key_companion_enabled"
    private const val KEY_POSITION_MODE = "key_position_mode"
    private const val KEY_VOICE_ENABLED = "key_voice_enabled"
    private const val KEY_REDUCED_MOTION = "key_reduced_motion"
    private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "key_notification_permission_requested"

    const val POSITION_AUTO = "random"
    const val POSITION_THREAD_DROP = "thread_drop"
    const val POSITION_LEFT_RIGHT = "left_right"
    const val POSITION_RIGHT_LEFT = "right_left"
    const val POSITION_TOP_RIGHT = "top_right"
    const val POSITION_BOTTOM = "bottom"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getEyeStrainMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_EYE_STRAIN_MIN, 20) // Default 20 min (20-20-20 rule)
    }

    fun setEyeStrainMinutes(context: Context, minutes: Int) {
        require(minutes in listOf(15, 20, 30, 45))
        getPrefs(context).edit().putInt(KEY_EYE_STRAIN_MIN, minutes).apply()
    }

    fun isScreenBreakEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SCREEN_BREAK_ENABLED, true)
    }

    fun setScreenBreakEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SCREEN_BREAK_ENABLED, enabled).apply()
    }

    fun isCompanionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_COMPANION_ENABLED, false)
    }

    fun setCompanionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_COMPANION_ENABLED, enabled).apply()
    }

    fun getPositionMode(context: Context): String {
        return getPrefs(context).getString(KEY_POSITION_MODE, POSITION_AUTO) ?: POSITION_AUTO
    }

    fun setPositionMode(context: Context, mode: String) {
        require(mode in listOf(POSITION_AUTO, POSITION_THREAD_DROP, POSITION_LEFT_RIGHT,
            POSITION_RIGHT_LEFT, POSITION_TOP_RIGHT, POSITION_BOTTOM))
        getPrefs(context).edit().putString(KEY_POSITION_MODE, mode).apply()
    }

    fun isVoiceEnabled(context: Context) = getPrefs(context).getBoolean(KEY_VOICE_ENABLED, false)

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VOICE_ENABLED, enabled).apply()
    }

    fun isReducedMotion(context: Context) = getPrefs(context).getBoolean(KEY_REDUCED_MOTION, false)

    fun setReducedMotion(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_REDUCED_MOTION, enabled).apply()
    }

    fun wasNotificationPermissionRequested(context: Context) =
        getPrefs(context).getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)

    fun markNotificationPermissionRequested(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
    }
}
