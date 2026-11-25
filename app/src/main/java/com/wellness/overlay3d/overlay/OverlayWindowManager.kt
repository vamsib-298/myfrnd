package com.wellness.overlay3d.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.wellness.overlay3d.R
import com.wellness.overlay3d.util.CompanionPreferences
import java.util.Locale
import kotlin.math.abs

class OverlayWindowManager(
    private val context: Context,
    private val onDismissed: () -> Unit = {},
    private val onEyeBreakDone: () -> Unit = {},
    private val onEyeBreakSnooze: () -> Unit = {},
    private val onWaterDone: () -> Unit = {},
    private val onWaterSnooze: () -> Unit = {},
    private val onTaskSnooze: (String) -> Unit = {}
) {
    enum class OverlayReason { WATER_REMINDER, EYE_STRAIN, WORK_TASK_REMINDER, GENERAL_TEST }
    enum class EntranceStyle { LEFT_TO_RIGHT, RIGHT_TO_LEFT, BOTTOM_SLIDE }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var character: HumanCompanionView? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<Long, String>? = null
    private var closed = false
    private var dismissing = false
    private var generation = 0L
    private var breakRemainingSeconds = 0
    private val timeout = Runnable { dismiss() }
    private val breakTick = object : Runnable {
        override fun run() {
            val view = root ?: return
            if (dismissing) return
            if (breakRemainingSeconds > 0) {
                breakRemainingSeconds--
                updateBreakCard(view)
                handler.postDelayed(this, 1_000L)
            } else {
                view.findViewById<TextView>(R.id.tvOverlayTitle).text = context.getString(R.string.break_complete_title)
                view.findViewById<TextView>(R.id.speechText).text = context.getString(R.string.break_complete_message)
                view.findViewById<ProgressBar>(R.id.breakProgress).progress = 1000
                character?.apply {
                    animationPhase = HumanCompanionView.PHASE_IDLE
                    currentExpression = HumanCompanionView.Expression.WINK_PLAYFUL
                    triggerTapReaction()
                }
                speak(context.getString(R.string.break_complete_message))
                onEyeBreakDone()
                handler.postDelayed(timeout, 1_800L)
            }
        }
    }

    val isOverlayActive: Boolean get() = root != null

    fun refreshPreferences() {
        character?.reducedMotion = CompanionPreferences.isReducedMotion(context)
        if (!CompanionPreferences.isVoiceEnabled(context)) {
            pendingSpeech = null
            tts?.stop()
            character?.isTalking = false
        } else {
            prepareVoice()
        }
    }

    private fun prepareVoice() {
        if (tts != null || closed || !CompanionPreferences.isVoiceEnabled(context)) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (closed) return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "Speech engine unavailable ($status); reminders remain visible")
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val deviceLocale = Locale.getDefault()
            var langResult = engine.setLanguage(deviceLocale)
            if (langResult < TextToSpeech.LANG_AVAILABLE) {
                langResult = engine.setLanguage(Locale.US)
            }
            // Prefer an offline voice when available, but still speak via default engine voice if not enumerated.
            val localVoice = runCatching {
                engine.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == deviceLocale.language }
            }.getOrNull()
            if (localVoice != null) {
                engine.setVoice(localVoice)
            }
            ttsReady = langResult >= TextToSpeech.LANG_AVAILABLE || localVoice != null
            engine.setSpeechRate(1.0f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    handler.post {
                        if (utteranceId == generation.toString()) character?.isTalking = true
                    }
                }
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        if (utteranceId == generation.toString()) character?.isTalking = false
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onDone(utteranceId)
                }
            })
            pendingSpeech?.let { (queuedGen, text) ->
                pendingSpeech = null
                if (queuedGen == generation && root != null && !dismissing && CompanionPreferences.isVoiceEnabled(context)) {
                    handler.post { speak(text) }
                }
            }
        }
    }

    /** A task may only be acknowledged after its overlay was successfully attached. */
    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay(
        reason: OverlayReason,
        spokenText: String,
        taskTitle: String? = null,
        taskId: String? = null
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || isOverlayActive) return false
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission is no longer available")
            return false
        }
        generation++
        dismissing = false
        val style = when (reason) {
            OverlayReason.GENERAL_TEST -> EntranceStyle.LEFT_TO_RIGHT
            OverlayReason.EYE_STRAIN -> EntranceStyle.BOTTOM_SLIDE
            OverlayReason.WATER_REMINDER -> EntranceStyle.LEFT_TO_RIGHT
            OverlayReason.WORK_TASK_REMINDER -> EntranceStyle.RIGHT_TO_LEFT
        }
        val view = LayoutInflater.from(context).inflate(R.layout.view_overlay_character, null) as ViewGroup
        val companion = view.findViewById<HumanCompanionView>(R.id.humanCompanionView)
        character = companion

        companion.apply {
            reducedMotion = CompanionPreferences.isReducedMotion(context)
            displayMode = HumanCompanionView.DisplayMode.FULL_BODY
            walkDirection = if (style == EntranceStyle.RIGHT_TO_LEFT) -1 else 1
            animationPhase = when (style) {
                EntranceStyle.LEFT_TO_RIGHT, EntranceStyle.RIGHT_TO_LEFT -> HumanCompanionView.PHASE_WALK_IN
                EntranceStyle.BOTTOM_SLIDE -> HumanCompanionView.PHASE_IDLE
            }
            currentExpression = when (reason) {
                OverlayReason.WATER_REMINDER -> HumanCompanionView.Expression.HAPPY_WATER
                OverlayReason.EYE_STRAIN -> HumanCompanionView.Expression.TIRED_EYES
                OverlayReason.WORK_TASK_REMINDER -> HumanCompanionView.Expression.TASK_REMINDER
                OverlayReason.GENERAL_TEST -> HumanCompanionView.Expression.WINK_PLAYFUL
            }
            contentDescription = context.getString(R.string.tap_companion)
        }

        configureCardForReason(view, reason, spokenText, taskTitle, taskId)

        val density = context.resources.displayMetrics.density
        val screen = context.resources.displayMetrics
        val width = (276 * density).toInt().coerceAtMost(screen.widthPixels)
        val height = (370 * density).toInt().coerceAtMost(screen.heightPixels - (48 * density).toInt())
        val animate = ValueAnimator.areAnimatorsEnabled() && !companion.reducedMotion

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = ((screen.widthPixels - width) / 2).coerceAtLeast(0)
            y = (28 * density).toInt()
            alpha = 1f
        }
        currentParams = params

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var startRawX = 0f
        var startRawY = 0f
        var startWinX = 0
        var startWinY = 0
        var dragging = false
        val dragListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startWinX = params.x
                    startWinY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        handler.removeCallbacks(timeout)
                    }
                    if (dragging && root === view && !dismissing) {
                        params.x = (startWinX + dx.toInt()).coerceIn(0, (screen.widthPixels - width).coerceAtLeast(0))
                        params.y = (startWinY - dy.toInt()).coerceIn(0, (screen.heightPixels - height).coerceAtLeast(0))
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: IllegalArgumentException) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) companion.triggerTapReaction()
                    true
                }
                else -> false
            }
        }
        companion.setOnTouchListener(dragListener)

        try {
            windowManager.addView(view, params)
        } catch (error: WindowManager.BadTokenException) {
            return attachmentFailed(error)
        } catch (error: SecurityException) {
            return attachmentFailed(error)
        }
        root = view
        prepareVoice()
        // Speak promptly when reminder arrives if voice is turned ON
        speak(spokenText)

        if (animate) {
            when (style) {
                EntranceStyle.BOTTOM_SLIDE -> view.translationY = height * 0.45f
                EntranceStyle.LEFT_TO_RIGHT -> view.translationX = -width * 0.85f
                EntranceStyle.RIGHT_TO_LEFT -> view.translationX = width * 0.85f
            }
            val isWalk = style == EntranceStyle.LEFT_TO_RIGHT || style == EntranceStyle.RIGHT_TO_LEFT
            val duration = if (isWalk) 1_100L else 550L
            view.animate().translationX(0f).translationY(0f)
                .setDuration(duration).setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    companion.animationPhase = HumanCompanionView.PHASE_IDLE
                    companion.triggerTapReaction()
                }.start()
        } else {
            companion.animationPhase = HumanCompanionView.PHASE_IDLE
        }
        return true
    }

    private fun configureCardForReason(
        view: View,
        reason: OverlayReason,
        spokenText: String,
        taskTitle: String?,
        taskId: String?
    ) {
        val titleView = view.findViewById<TextView>(R.id.tvOverlayTitle)
        val bodyView = view.findViewById<TextView>(R.id.speechText)
        val progress = view.findViewById<ProgressBar>(R.id.breakProgress)
        val btnPrimary = view.findViewById<Button>(R.id.btnOverlayPrimary)
        val btnSecondary = view.findViewById<Button>(R.id.btnOverlaySecondary)

        handler.removeCallbacks(breakTick)
        handler.removeCallbacks(timeout)

        when (reason) {
            OverlayReason.EYE_STRAIN -> {
                breakRemainingSeconds = BREAK_DURATION_SECONDS
                progress.visibility = View.VISIBLE
                updateBreakCard(view)
                btnPrimary.setText(R.string.overlay_done)
                btnSecondary.setText(R.string.overlay_snooze_5m)
                btnSecondary.visibility = View.VISIBLE
                btnPrimary.setOnClickListener {
                    handler.removeCallbacks(breakTick)
                    onEyeBreakDone()
                    dismiss()
                }
                btnSecondary.setOnClickListener {
                    handler.removeCallbacks(breakTick)
                    onEyeBreakSnooze()
                    dismiss()
                }
                handler.postDelayed(breakTick, 1_000L)
            }
            OverlayReason.WATER_REMINDER -> {
                titleView.setText(R.string.water_title)
                bodyView.setText(R.string.water_message)
                progress.visibility = View.GONE
                btnPrimary.setText(R.string.overlay_done)
                btnSecondary.setText(R.string.overlay_snooze_5m)
                btnSecondary.visibility = View.VISIBLE
                btnPrimary.setOnClickListener {
                    onWaterDone()
                    dismiss()
                }
                btnSecondary.setOnClickListener {
                    onWaterSnooze()
                    dismiss()
                }
                handler.postDelayed(timeout, 15_000L)
            }
            OverlayReason.WORK_TASK_REMINDER -> {
                titleView.setText(R.string.overlay_task_label)
                bodyView.text = taskTitle ?: spokenText
                progress.visibility = View.GONE
                btnPrimary.setText(R.string.overlay_done)
                btnPrimary.setOnClickListener { dismiss() }
                if (taskId != null) {
                    btnSecondary.setText(R.string.overlay_snooze_10m)
                    btnSecondary.visibility = View.VISIBLE
                    btnSecondary.setOnClickListener {
                        onTaskSnooze(taskId)
                        dismiss()
                    }
                } else {
                    btnSecondary.visibility = View.GONE
                }
                handler.postDelayed(timeout, 15_000L)
            }
            OverlayReason.GENERAL_TEST -> {
                titleView.setText(R.string.app_name)
                bodyView.text = context.getString(R.string.overlay_preview_body)
                progress.visibility = View.GONE
                btnPrimary.setText(R.string.start_break_short)
                btnSecondary.setText(R.string.overlay_hide)
                btnSecondary.visibility = View.VISIBLE
                btnPrimary.setOnClickListener {
                    character?.apply {
                        animationPhase = HumanCompanionView.PHASE_IDLE
                        currentExpression = HumanCompanionView.Expression.TIRED_EYES
                    }
                    configureCardForReason(view, OverlayReason.EYE_STRAIN, context.getString(R.string.eye_message), null, null)
                }
                btnSecondary.setOnClickListener { dismiss() }
                handler.postDelayed(timeout, 15_000L)
            }
        }
    }

    private fun updateBreakCard(view: View) {
        view.findViewById<TextView>(R.id.tvOverlayTitle).text =
            context.getString(R.string.break_countdown_title, breakRemainingSeconds)
        view.findViewById<TextView>(R.id.speechText).setText(R.string.eye_message)
        val elapsed = BREAK_DURATION_SECONDS - breakRemainingSeconds
        view.findViewById<ProgressBar>(R.id.breakProgress).progress =
            (elapsed * 1000 / BREAK_DURATION_SECONDS).coerceIn(0, 1000)
    }

    private fun attachmentFailed(error: RuntimeException): Boolean {
        Log.e(TAG, "Could not attach reminder window", error)
        character = null
        currentParams = null
        return false
    }

    private fun speak(text: String) {
        if (!CompanionPreferences.isVoiceEnabled(context)) return
        character?.animationPhase = HumanCompanionView.PHASE_TALK
        if (!ttsReady) {
            pendingSpeech = generation to text
            prepareVoice()
            return
        }
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, generation.toString()) == TextToSpeech.ERROR) {
            Log.w(TAG, "Speech request rejected; keeping the text reminder")
        }
    }

    private fun dismiss() {
        val view = root ?: return
        if (dismissing) return
        dismissing = true
        pendingSpeech = null
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(breakTick)
        view.animate().cancel()
        tts?.stop()
        if (!ValueAnimator.areAnimatorsEnabled() || CompanionPreferences.isReducedMotion(context)) {
            finishDismissal()
        } else {
            view.animate().alpha(0f).setDuration(200L).withEndAction { finishDismissal() }.start()
        }
    }

    private fun finishDismissal() {
        destroyOverlayImmediately()
        onDismissed()
    }

    fun destroyOverlayImmediately() {
        generation++
        pendingSpeech = null
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        root?.let { view ->
            view.animate().withEndAction(null).cancel()
            if (view.isAttachedToWindow) windowManager.removeViewImmediate(view)
        }
        root = null
        character = null
        currentParams = null
        dismissing = false
    }

    fun releaseTts() {
        closed = true
        pendingSpeech = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private companion object {
        const val TAG = "OverlayWindowManager"
        const val BREAK_DURATION_SECONDS = 20
    }
}
