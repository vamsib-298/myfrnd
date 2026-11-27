package com.wellness.overlay3d.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wellness.overlay3d.R
import com.wellness.overlay3d.service.CompanionRuntime
import com.wellness.overlay3d.service.OverlayService
import com.wellness.overlay3d.util.CompanionPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class FocusRoomActivity : AppCompatActivity() {
    private lateinit var scene: FocusLoungeView
    private lateinit var title: TextView
    private lateinit var clock: TextView
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private var waitingSince = 0L
    private var displayedPhase: FocusLoungeView.Phase? = null
    private val cream = Color.rgb(255, 245, 223)
    private val ink = Color.rgb(41, 40, 63)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        val root = FrameLayout(this)
        scene = FocusLoungeView(this).apply { onCatEntering = CompanionRuntime::catEnteredRoom }
        root.addView(scene)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(12) + cutout.left, dp(12) + cutout.top, dp(12) + cutout.right, dp(16) + cutout.bottom)
            insets
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val panelWidth = if (r - l > b - t) ((r - l) * .43f).toInt() else r - l
            if (panel.layoutParams.width != panelWidth) panel.layoutParams = panel.layoutParams.apply { width = panelWidth }
        }
        title = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            letterSpacing = .10f
            setTextColor(cream)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        clock = TextView(this).apply {
            textSize = 64f
            typeface = Typeface.SERIF
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(cream)
            fontFeatureSettings = "tnum"
        }
        fun space(weight: Float) = panel.addView(View(this), LinearLayout.LayoutParams(1, 0, weight))
        space(.30f)
        panel.addView(title)
        panel.addView(clock)
        space(.70f)
        primary = roomButton(true)
        secondary = roomButton(false)
        panel.addView(primary, LinearLayout.LayoutParams(-2, -2))
        panel.addView(secondary, LinearLayout.LayoutParams(-2, -2))
        setContentView(root)

        waitingSince = SystemClock.elapsedRealtime()
        if (CompanionPreferences.getFocusState(this).active && !CompanionRuntime.status.value.running) {
            OverlayService.restoreFocusSession(this)
        }
        primary.setOnClickListener {
            if (CompanionPreferences.getFocusState(this).active) {
                OverlayService.togglePauseFocusSession(this)
            } else if (displayedPhase == FocusLoungeView.Phase.BREAK || displayedPhase == FocusLoungeView.Phase.FINISHED) {
                waitingSince = SystemClock.elapsedRealtime()
                OverlayService.startFocusSession(this)
            }
        }
        secondary.setOnClickListener {
            if (CompanionPreferences.getFocusState(this).active) OverlayService.stopFocusSession(this)
            else CompanionPreferences.clearFocusBreak(this)
            finish()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    render()
                    delay(200)
                }
            }
        }
    }

    private fun roomButton(filled: Boolean) = Button(this).apply {
        isAllCaps = false
        minWidth = dp(152)
        minHeight = dp(48)
        setPadding(dp(20), 0, dp(20), 0)
        setTextColor(if (filled) ink else cream)
        backgroundTintList = null
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x44888877),
            GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(if (filled) cream else Color.TRANSPARENT)
            }, null)
    }

    private fun render() {
        val saved = CompanionPreferences.getFocusState(this)
        val status = CompanionRuntime.status.value
        val now = System.currentTimeMillis()
        val breakEnd = CompanionPreferences.breakEnd(this)
        val phase: FocusLoungeView.Phase
        val remaining: Long
        val age: Long
        if (saved.active) {
            phase = if (saved.paused) FocusLoungeView.Phase.PAUSED else FocusLoungeView.Phase.FOCUS
            remaining = when {
                saved.paused -> saved.remainingMs
                status.focusActive && !status.focusPaused && status.focusEndElapsed != null ->
                    status.focusEndElapsed - SystemClock.elapsedRealtime()
                else -> saved.endWallMs - now
            }.coerceAtLeast(0L)
            age = CompanionPreferences.getFocusDurationMinutes(this) * 60_000L - remaining
        } else if (breakEnd > 0L) {
            remaining = (breakEnd - now).coerceAtLeast(0)
            age = CompanionPreferences.FOCUS_BREAK_MS - remaining
            phase = if (remaining > 0L) FocusLoungeView.Phase.BREAK else FocusLoungeView.Phase.FINISHED
        } else {
            if (displayedPhase != null || SystemClock.elapsedRealtime() - waitingSince > 2500L) finish()
            return
        }
        if (phase != displayedPhase) {
            displayedPhase = phase
            val focus = saved.active
            title.setText(when (phase) {
                FocusLoungeView.Phase.FOCUS -> R.string.focus_session_label
                FocusLoungeView.Phase.PAUSED -> R.string.focus_room_paused
                FocusLoungeView.Phase.BREAK -> R.string.focus_break_label
                FocusLoungeView.Phase.FINISHED -> R.string.focus_break_done
            })
            primary.setText(if (!focus) R.string.focus_another else if (saved.paused) R.string.focus_resume else R.string.focus_pause)
            secondary.setText(if (focus) R.string.focus_end_session else R.string.focus_leave_room)
            if (phase == FocusLoungeView.Phase.FOCUS || phase == FocusLoungeView.Phase.BREAK)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val seconds = (remaining + 999L) / 1000
        val digits = getString(R.string.focus_timer_digits, seconds / 60, seconds % 60)
        if (clock.text != digits) clock.text = digits
        scene.show(phase, age, CompanionPreferences.isReducedMotion(this))
    }

    override fun onStart() {
        super.onStart()
        CompanionRuntime.roomVisible = true
    }

    override fun onStop() {
        CompanionRuntime.roomVisible = false
        super.onStop()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
