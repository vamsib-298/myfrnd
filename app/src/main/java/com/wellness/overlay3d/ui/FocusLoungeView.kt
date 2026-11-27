package com.wellness.overlay3d.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.wellness.overlay3d.R
import kotlin.math.abs
import kotlin.math.max

/** Cat poses share a floor contact point in the illustration's 1024 × 1536 coordinate space. */
internal class FocusLoungeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Phase { FOCUS, PAUSED, BREAK, FINISHED }
    private class Art(resources: Resources) {
        val room = BitmapFactory.decodeResource(resources, R.drawable.focus_lounge)
        val walk = BitmapFactory.decodeResource(resources, R.drawable.focus_tabby_walk)
        val poses = mapOf(
            CatMotion.Pose.LEAP to R.drawable.focus_cat_leap,
            CatMotion.Pose.SIT to R.drawable.focus_cat_sit,
            CatMotion.Pose.AWAKE to R.drawable.focus_cat_awake,
            CatMotion.Pose.SLEEP to R.drawable.focus_cat_sleep,
            CatMotion.Pose.STRETCH to R.drawable.focus_cat_stretch
        ).mapValues { BitmapFactory.decodeResource(resources, it.value) }
    }
    private companion object { var sharedArt: Art? = null }
    private val art by lazy { sharedArt ?: Art(resources).also { sharedArt = it } }
    private val tile = Rect()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dest = RectF()
    private var phase = Phase.FOCUS
    private var ageMs = 0L
    private var syncTime = 0L
    private var still = false
    private var shade: LinearGradient? = null

    var onCatEntering: (() -> Unit)? = null
    private var entranceReported = false
    private var phaseStartedAt = 0L
    private var previousPose: CatMotion.Frame? = null
    private var alreadyOnCushion = false

    private fun currentFrame(now: Long): CatMotion.Frame {
        val running = phase == Phase.FOCUS || phase == Phase.BREAK
        val seconds = (ageMs + if (running) now - syncTime else 0L) / 1000f
        val age = if (alreadyOnCushion && (phase == Phase.FOCUS || phase == Phase.PAUSED)) seconds.coerceAtLeast(4f) else seconds
        return when (phase) {
            Phase.FOCUS -> CatMotion.focus(age, still)
            Phase.PAUSED -> CatMotion.paused(age, (now-phaseStartedAt)/1000f, still)
            Phase.BREAK -> CatMotion.rest(age, still)
            Phase.FINISHED -> CatMotion.rest(age, true)
        }
    }

    fun show(value: Phase, elapsedMs: Long, reducedMotion: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val reduced = reducedMotion || !ValueAnimator.areAnimatorsEnabled()
        val elapsed = elapsedMs.coerceAtLeast(0L)
        val moving = phase == Phase.FOCUS || phase == Phase.BREAK
        val predicted = ageMs + if (moving) now - syncTime else 0L
        if (syncTime != 0L && phase == value && still == reduced && abs(elapsed - predicted) < 100L) return
        if (syncTime == 0L || value != phase) {
            entranceReported = false
            previousPose = if (syncTime > 0 && !reduced && (value == Phase.PAUSED || phase == Phase.PAUSED)) currentFrame(now) else null
            if (phase == Phase.PAUSED && value == Phase.FOCUS) {
                alreadyOnCushion = previousPose?.let(CatMotion::onCushion) ?: false
            } else if (value != Phase.PAUSED) {
                alreadyOnCushion = false
            }
            phaseStartedAt = now
        }
        phase = value
        if (elapsed >= 4000L) alreadyOnCushion = false
        ageMs = elapsed
        syncTime = now
        still = reduced
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shade = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x900f100b.toInt(),
            0x730f100b, Color.TRANSPARENT, Color.TRANSPARENT, 0xb316100a.toInt()),
            floatArrayOf(0f, .26f, .49f, .78f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val landscape = w > h
        val left = if (landscape) w * .43f else 0f
        val scale = max((w - left) / 1024f, h / 1536f)
        val originX = left + (w - left - 1024f * scale) / 2f
        val originY = (h - 1536f * scale) * .63f
        canvas.drawColor(0xff30291e.toInt())
        canvas.save()
        canvas.clipRect(left, 0f, w, h)
        canvas.translate(originX, originY)
        canvas.scale(scale, scale)
        dest.set(0f, 0f, 1024f, 1536f)
        paint.color = Color.WHITE
        canvas.drawBitmap(art.room, null, dest, paint)

        val now = SystemClock.elapsedRealtime()
        val state = currentFrame(now)
        val blend = if (still) 1f else ((now-phaseStartedAt) / 360f).coerceIn(0f, 1f)
        previousPose?.let { if (blend < 1f) drawCat(canvas, it, 1f-blend) else previousPose = null }
        drawCat(canvas, state, if (previousPose == null) 1f else blend)
        canvas.restore()
        if (!entranceReported && syncTime != 0L && phase == Phase.FOCUS &&
            originX + (state.x + 70f) * scale >= left) {
            entranceReported = true
            onCatEntering?.invoke()
        }
        if (!landscape) {
            paint.color = Color.WHITE
            paint.shader = shade
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }
        val moving = phase != Phase.PAUSED || now-phaseStartedAt < 1600
        if (moving && !still && state.pose != CatMotion.Pose.AWAY && isShown) {
            postInvalidateDelayed(if (state.pose == CatMotion.Pose.SLEEP) 80L else 32L)
        }
    }

    private fun drawCat(canvas: Canvas, state: CatMotion.Frame, opacity: Float) {
        if (state.pose != CatMotion.Pose.AWAY) {
            val walking = state.pose == CatMotion.Pose.WALK
            val cat = if (walking) art.walk else art.poses.getValue(state.pose)
            val source = if (walking) {
                val wTile = cat.width / 6
                val hTile = cat.height / 4
                val tx = state.stride % 6 * wTile
                val ty = state.stride / 6 * hTile
                tile.set(tx, ty, tx + wTile, ty + hTile)
                tile
            } else null
            val baseWidth = when (state.pose) {
                CatMotion.Pose.WALK -> 300f
                CatMotion.Pose.LEAP -> 263f
                CatMotion.Pose.SIT, CatMotion.Pose.AWAKE -> 154f
                CatMotion.Pose.SLEEP -> 211f
                else -> 238f
            }
            val hCat = baseWidth * (source?.height() ?: cat.height) / (source?.width() ?: cat.width)
            val heightAbove = (state.surface-state.paws).coerceAtLeast(0f)
            val shade = (1-heightAbove/520f).coerceIn(.18f,1f)
            paint.color = Color.argb((64*shade*opacity).toInt(),36,22,12)
            val radius = baseWidth*(.28f+.09f*shade)
            canvas.drawOval(state.x-radius,state.surface-6f,state.x+radius,state.surface+5f,paint)
            paint.color = Color.WHITE
            canvas.save()
            paint.alpha = (255 * opacity).toInt()
            canvas.translate(state.x,state.paws)
            canvas.rotate(state.tilt)
            canvas.scale(state.sx,state.sy)
            // Walking frames retain their identical origin including the 2.6% transparent floor gutter.
            val gutter = if (walking) .026f*hCat else 0f
            dest.set(-baseWidth/2,-hCat+gutter,baseWidth/2,gutter)
            canvas.drawBitmap(cat,source,dest,paint)
            canvas.restore()
        }
    }
}
