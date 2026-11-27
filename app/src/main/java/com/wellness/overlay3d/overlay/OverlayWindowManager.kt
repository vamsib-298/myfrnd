package com.wellness.overlay3d.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import com.wellness.overlay3d.R
import com.wellness.overlay3d.util.AudioEnvironment
import com.wellness.overlay3d.util.CompanionPreferences
import com.wellness.overlay3d.util.TaskManager
import com.wellness.overlay3d.util.TaskStorageException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayWindowManager(
    private val context: Context,
    private val onDismissed: () -> Unit = {},
    private val onEyeBreakDone: () -> Unit = {},
    private val onEyeBreakSnooze: () -> Unit = {},
    private val onWaterDone: () -> Unit = {},
    private val onWaterSnooze: () -> Unit = {},
    private val onTaskSnooze: (String) -> Unit = {},
    private val onRollTasksTomorrow: () -> Int = { 0 }
) {
    enum class OverlayReason {
        WATER_REMINDER,
        EYE_STRAIN,
        WORK_TASK_REMINDER,
        GENERAL_TEST,
        EVENING_WIND_DOWN
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var character: HumanCompanionView? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private val speech = CompanionSpeech()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<Long, String>? = null
    private var closed = false
    private var dismissing = false
    private var enteringFromLeft = true
    private var walkingFrame: Runnable? = null
    private var arrivalFallback: Runnable? = null
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
                speakIfAllowed(context.getString(R.string.break_complete_message))
                onEyeBreakDone()
                handler.postDelayed(timeout, 1_800L)
            }
        }
    }

    val isOverlayActive: Boolean get() = root != null

    fun refreshPreferences() {
        character?.reducedMotion = CompanionPreferences.isReducedMotion(context)
        if (!CompanionPreferences.isVoiceEnabled(context) && !CompanionPreferences.isHeadphonesOnlyVoice(context)) {
            pendingSpeech = null
            speech.stop()
            tts?.stop()
            character?.isTalking = false
        }
    }

    private fun shouldSpeak(): Boolean {
        val headphonesOn = AudioEnvironment.areHeadphonesConnected(context)
        val voiceOn = CompanionPreferences.isVoiceEnabled(context)
        val headphonesOnly = CompanionPreferences.isHeadphonesOnlyVoice(context)
        return when {
            headphonesOnly -> headphonesOn
            else -> voiceOn
        }
    }

    private fun prepareVoice() {
        if (tts != null || closed) return
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
            val localVoice = runCatching {
                engine.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == deviceLocale.language }
                    ?.maxWithOrNull(compareBy({ voice ->
                        Regex("(^|[^a-z])male([^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(voice.name)
                    }, { it.locale == deviceLocale }, { it.quality }))
            }.getOrNull()
            if (localVoice != null) engine.setVoice(localVoice)
            ttsReady = langResult >= TextToSpeech.LANG_AVAILABLE || localVoice != null
            engine.setSpeechRate(1.0f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    handler.post { if (utteranceId == generation.toString()) character?.isTalking = true }
                }
                override fun onDone(utteranceId: String?) {
                    handler.post { if (utteranceId == generation.toString()) character?.isTalking = false }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onDone(utteranceId)
                }
            })
            pendingSpeech?.let { (queuedGen, text) ->
                pendingSpeech = null
                if (queuedGen == generation && root != null && !dismissing && shouldSpeak()) speakRaw(text)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay(
        reason: OverlayReason,
        spokenText: String,
        taskTitle: String? = null,
        taskId: String? = null,
        pendingCount: Int = 0
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return false
        if (isOverlayActive) return false
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission is no longer available")
            return false
        }
        generation++
        dismissing = false
        enteringFromLeft = when (CompanionPreferences.getPositionMode(context)) {
            CompanionPreferences.POSITION_RIGHT_LEFT, CompanionPreferences.POSITION_TOP_RIGHT -> false
            CompanionPreferences.POSITION_LEFT_RIGHT -> true
            else -> reason != OverlayReason.WORK_TASK_REMINDER
        }

        val view = LayoutInflater.from(context).inflate(R.layout.view_overlay_character, null) as ViewGroup
        val companion = view.findViewById<HumanCompanionView>(R.id.humanCompanionView)
        character = companion
        companion.apply {
            reducedMotion = CompanionPreferences.isReducedMotion(context)
            displayMode = HumanCompanionView.DisplayMode.FULL_BODY
            walkDirection = if (enteringFromLeft) 1 else -1
            currentExpression = when (reason) {
                OverlayReason.WATER_REMINDER -> HumanCompanionView.Expression.HAPPY_WATER
                OverlayReason.EYE_STRAIN -> HumanCompanionView.Expression.TIRED_EYES
                OverlayReason.WORK_TASK_REMINDER, OverlayReason.EVENING_WIND_DOWN -> HumanCompanionView.Expression.TASK_REMINDER
                OverlayReason.GENERAL_TEST ->
                    HumanCompanionView.Expression.WINK_PLAYFUL
            }
            contentDescription = context.getString(R.string.tap_companion)
        }

        val density = context.resources.displayMetrics.density
        val screen = context.resources.displayMetrics
        val width = (284 * density).toInt().coerceAtMost(screen.widthPixels)
        val height = (380 * density).toInt().coerceAtMost(screen.heightPixels - (48 * density).toInt())

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.LEFT
            x = ((screen.widthPixels - width) / 2).coerceAtLeast(0)
            y = (28 * density).toInt()
            alpha = 1f
        }
        currentParams = params

        val animateVisit = ValueAnimator.areAnimatorsEnabled() && !companion.reducedMotion
        val restingX = params.x
        val bubble = view.findViewById<View>(R.id.speechBubbleContainer)
        fun startAction() {
            if (root !== view || dismissing) return
            companion.onRenderStateChanged = null
            companion.translationX = 0f
            companion.animationPhase = HumanCompanionView.PHASE_TALK
            configureCardForReason(view, params, reason, spokenText, taskTitle, taskId, pendingCount)
            speakIfAllowed(spokenText)
        }
        if (animateVisit) {
            bubble.visibility = View.INVISIBLE
            companion.alpha = 0f
            params.x = if (enteringFromLeft) 0 else (screen.widthPixels - width).coerceAtLeast(0)
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            params.alpha = .78f
        }

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var startRawX = 0f
        var startRawY = 0f
        var startWinX = 0
        var startWinY = 0
        var dragging = false
        companion.setOnTouchListener { _, event ->
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

        try {
            windowManager.addView(view, params)
        } catch (error: WindowManager.BadTokenException) {
            return attachmentFailed(error)
        } catch (error: SecurityException) {
            return attachmentFailed(error)
        }
        root = view

        if (animateVisit) {
            val firstX = outsideWindowX(params.width)
            fun enter() {
                if (root !== view || dismissing || companion.onRenderStateChanged == null) return
                if (!view.isAttachedToWindow) {
                    view.post { enter() }
                    return
                }
                companion.onRenderStateChanged = null
                arrivalFallback?.let(handler::removeCallbacks)
                arrivalFallback = null
                companion.alpha = 1f
                companion.animationPhase = HumanCompanionView.PHASE_SCREEN_WALK
                travel(view, params, companion, firstX, restingX) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    params.alpha = 1f
                    if (updateWindow(view, params)) startAction()
                }
            }
            companion.onRenderStateChanged = { state ->
                if (state != HumanCompanionView.RenderState.LOADING) enter()
            }
            arrivalFallback = Runnable { enter() }.also { handler.postDelayed(it, 2200L) }
            if (companion.renderState != HumanCompanionView.RenderState.LOADING) enter()
        } else {
            startAction()
        }
        return true
    }

    private fun configureCardForReason(
        view: View,
        params: WindowManager.LayoutParams,
        reason: OverlayReason,
        spokenText: String,
        taskTitle: String?,
        taskId: String?,
        pendingCount: Int
    ) {
        val bubble = view.findViewById<View>(R.id.speechBubbleContainer)
        val titleView = view.findViewById<TextView>(R.id.tvOverlayTitle)
        val bodyView = view.findViewById<TextView>(R.id.speechText)
        val progress = view.findViewById<ProgressBar>(R.id.breakProgress)
        val btnNote = view.findViewById<Button>(R.id.btnOverlayNoteChip)
        val btnPrimary = view.findViewById<Button>(R.id.btnOverlayPrimary)
        val btnSecondary = view.findViewById<Button>(R.id.btnOverlaySecondary)

        handler.removeCallbacks(breakTick)
        handler.removeCallbacks(timeout)

        bubble.visibility = View.VISIBLE
        setupQuickNoteComposer(view, params, bodyView)

        when (reason) {
            OverlayReason.EYE_STRAIN -> {
                breakRemainingSeconds = BREAK_DURATION_SECONDS
                progress.visibility = View.VISIBLE
                btnNote.visibility = View.GONE
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
                btnNote.visibility = View.VISIBLE
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
                btnNote.visibility = View.GONE
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
            OverlayReason.EVENING_WIND_DOWN -> {
                titleView.setText(R.string.wind_down_title)
                bodyView.text = context.getString(R.string.wind_down_body, pendingCount)
                progress.visibility = View.GONE
                btnNote.visibility = View.GONE
                btnPrimary.setText(R.string.wind_down_roll_tomorrow)
                btnSecondary.setText(R.string.wind_down_keep_today)
                btnSecondary.visibility = View.VISIBLE
                btnPrimary.setOnClickListener {
                    val rolled = onRollTasksTomorrow()
                    bodyView.text = context.getString(R.string.wind_down_rolled, rolled)
                    character?.triggerTapReaction()
                    handler.postDelayed(timeout, 1_500L)
                }
                btnSecondary.setOnClickListener { dismiss() }
                handler.postDelayed(timeout, 18_000L)
            }
            OverlayReason.GENERAL_TEST -> {
                titleView.setText(R.string.app_name)
                bodyView.text = context.getString(R.string.overlay_preview_body)
                progress.visibility = View.GONE
                btnNote.visibility = View.VISIBLE
                btnPrimary.setText(R.string.start_break_short)
                btnSecondary.setText(R.string.overlay_hide)
                btnSecondary.visibility = View.VISIBLE
                btnPrimary.setOnClickListener {
                    character?.apply {
                        animationPhase = HumanCompanionView.PHASE_IDLE
                        currentExpression = HumanCompanionView.Expression.TIRED_EYES
                    }
                    configureCardForReason(view, params, OverlayReason.EYE_STRAIN, context.getString(R.string.eye_message), null, null, 0)
                }
                btnSecondary.setOnClickListener { dismiss() }
                handler.postDelayed(timeout, 5_200L)
            }
        }
    }

    private fun setupQuickNoteComposer(view: View, params: WindowManager.LayoutParams, bodyView: TextView) {
        val btnNoteChip = view.findViewById<Button>(R.id.btnOverlayNoteChip)
        val noteBox = view.findViewById<View>(R.id.overlayQuickNoteBox)
        val actionsRow = view.findViewById<View>(R.id.overlayActionsRow)
        val input = view.findViewById<EditText>(R.id.etQuickOverlayNote)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelQuickNote)
        val btnSave = view.findViewById<Button>(R.id.btnSaveQuickNote)

        fun setFocusable(focusable: Boolean) {
            if (root !== view || dismissing) return
            params.flags = if (focusable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: IllegalArgumentException) {
            }
        }

        fun closeComposer() {
            noteBox.visibility = View.GONE
            actionsRow.visibility = View.VISIBLE
            input.setText("")
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
            setFocusable(false)
            handler.postDelayed(timeout, 10_000L)
        }

        btnNoteChip.setOnClickListener {
            handler.removeCallbacks(timeout)
            noteBox.visibility = View.VISIBLE
            actionsRow.visibility = View.GONE
            setFocusable(true)
            input.requestFocus()
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        btnCancel.setOnClickListener { closeComposer() }

        btnSave.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                input.error = context.getString(R.string.empty_title_error)
                return@setOnClickListener
            }
            try {
                TaskManager.addNote(context, text)
                bodyView.text = context.getString(R.string.quick_note_saved_msg, text)
                character?.triggerTapReaction()
            } catch (error: TaskStorageException) {
                Log.e(TAG, "Could not save quick note", error)
            }
            closeComposer()
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

    private fun speakIfAllowed(text: String) {
        if (!shouldSpeak()) return
        pendingSpeech = null
        tts?.stop()
        speech.speak(text, { character?.isTalking = it }, ::speakWithDeviceVoice)
    }

    private fun speakWithDeviceVoice(text: String) {
        if (closed || dismissing || root == null || !shouldSpeak()) return
        if (!ttsReady) {
            pendingSpeech = generation to text
            prepareVoice()
            return
        }
        speakRaw(text)
    }

    private fun speakRaw(text: String) {
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, generation.toString()) == TextToSpeech.ERROR) {
            Log.w(TAG, "Speech request rejected; keeping visual card")
        }
    }

    private fun outsideWindowX(width: Int): Int {
        val margin = (48 * context.resources.displayMetrics.density).toInt()
        return if (enteringFromLeft) -width/2-margin
        else context.resources.displayMetrics.widthPixels-width/2+margin
    }

    private fun updateWindow(view: View, params: WindowManager.LayoutParams): Boolean {
        if (root !== view || !view.isAttachedToWindow) return false
        return try {
            windowManager.updateViewLayout(view, params)
            true
        } catch (_: IllegalArgumentException) {
            finishDismissal()
            false
        } catch (_: SecurityException) {
            finishDismissal()
            false
        }
    }

    private fun travel(view: View, params: WindowManager.LayoutParams, person: HumanCompanionView,
                       from: Int, to: Int, arrived: () -> Unit) {
        cancelTravel()
        val right = (context.resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0)
        // The window stays within Android's bounds. The local offset carries the person
        // through the actual display edge for the first and last portion of the route.
        fun place(logicalX: Int) {
            params.x = logicalX.coerceIn(0, right)
            person.translationX = (logicalX - params.x).toFloat()
        }
        place(from)
        if (!updateWindow(view, params)) return
        val pixelsPerMetre = person.height.coerceAtLeast((190 * context.resources.displayMetrics.density).toInt()) / 1.96f
        val route = WalkingRoute(abs(to - from) / pixelsPerMetre, dismissing)
        person.walkRoute = route
        val startsAt = SystemClock.uptimeMillis()
        val frame = object : Runnable {
            override fun run() {
                if (root !== view || walkingFrame !== this) return
                val elapsed = (SystemClock.uptimeMillis() - startsAt) / 1000f
                route.sample(elapsed)
                val logicalX = (from + (to - from) * route.distanceFraction).roundToInt()
                place(logicalX)
                if (!updateWindow(view, params)) return
                if (elapsed < route.duration) {
                    view.postOnAnimation(this)
                } else {
                    walkingFrame = null
                    arrived()
                }
            }
        }
        walkingFrame = frame
        view.postOnAnimation(frame)
    }

    private fun cancelTravel() {
        walkingFrame?.let { root?.removeCallbacks(it) }
        walkingFrame = null
        arrivalFallback?.let(handler::removeCallbacks)
        arrivalFallback = null
    }

    private fun dismiss() {
        val view = root ?: return
        if (dismissing) return
        dismissing = true
        pendingSpeech = null
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(breakTick)
        view.animate().cancel()
        speech.stop()
        tts?.stop()
        val person = character
        val params = currentParams
        if (person == null || params == null || !ValueAnimator.areAnimatorsEnabled() ||
            person.reducedMotion) {
            finishDismissal()
            return
        }
        view.findViewById<View>(R.id.speechBubbleContainer).visibility = View.INVISIBLE
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.alpha = .78f
        person.isTalking = false
        person.walkDirection = if (enteringFromLeft) -1 else 1
        person.animationPhase = HumanCompanionView.PHASE_SCREEN_WALK
        travel(view, params, person, params.x + person.translationX.toInt(), outsideWindowX(params.width)) {
            finishDismissal()
        }
    }

    private fun finishDismissal() {
        destroyOverlayImmediately()
        onDismissed()
    }

    fun destroyOverlayImmediately() {
        cancelTravel()
        character?.onRenderStateChanged = null
        generation++
        pendingSpeech = null
        handler.removeCallbacksAndMessages(null)
        speech.stop()
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
        speech.close()
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
