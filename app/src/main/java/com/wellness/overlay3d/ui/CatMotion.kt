package com.wellness.overlay3d.ui

import kotlin.math.PI
import kotlin.math.sin

/** Paw coordinates in the painted room, with the cushion and rug as separate contact surfaces. */
internal object CatMotion {
    enum class Pose { WALK, LEAP, SIT, AWAKE, SLEEP, STRETCH, AWAY }
    data class Frame(val pose: Pose, val x: Float, val paws: Float, val surface: Float,
                     val stride: Int = 0, val sx: Float = 1f, val sy: Float = 1f,
                     val tilt: Float = 0f)

    private const val RUG = 1260f
    private const val SEAT = 975f
    private const val SPOT = 385f
    private const val LAUNCH = 166f
    private const val LAND = 618f
    private const val SPEED = 144f
    private const val STRIDE = 120f // 40% of the 300-unit wide walking plate.
    private const val APPROACH = 2.25f

    private fun wave(t: Float) = sin(PI.toFloat() * t)
    private fun smooth(t: Float) = t*t*(3-2*t)

    private fun walking(start: Float, seconds: Float): Frame {
        val distance = seconds * SPEED
        val stride = ((distance / STRIDE % 1f) * 24).toInt().coerceIn(0, 23)
        return Frame(Pose.WALK, start + distance, RUG, RUG, stride)
    }

    private fun leap(t: Float, up: Boolean): Frame {
        val from = if (up) LAUNCH else SPOT
        val to = if (up) SPOT else LAND
        val startY = if (up) RUG else SEAT
        val endY = if (up) SEAT else RUG
        val x = from + (to-from)*t
        val paws = startY + (endY-startY)*t - 4*126f*t*(1-t)
        val shadow = if (up && t>.70f || !up && t<.14f) SEAT else RUG
        return Frame(Pose.LEAP,x,paws,shadow,sx=1+.09f*wave(t),sy=1-.08f*wave(t),
            tilt=(if (up) -28f else -10f) + (if (up) 40f else 34f)*smooth(t))
    }

    fun focus(age: Float, reduced: Boolean): Frame {
        if (reduced) return Frame(Pose.SLEEP,SPOT,SEAT,SEAT)
        if (age < APPROACH) return walking(LAUNCH - SPEED*APPROACH, age)
        var t = age-APPROACH
        if (t<.28f) {
            val crouch = smooth(t/.28f)
            return Frame(Pose.WALK,LAUNCH,RUG,RUG,walking(0f, APPROACH).stride,1+.07f*crouch,1-.22f*crouch,-3*crouch)
        }
        t -= .28f
        if (t<.75f) return leap(t/.75f,true)
        t -= .75f
        if (t<.42f) {
            val impact=wave(t/.42f)
            return Frame(Pose.SIT,SPOT,SEAT,SEAT,sx=1+.055f*impact,sy=1-.12f*impact)
        }
        return Frame(Pose.SLEEP,SPOT,SEAT,SEAT,sy=1+.009f*sin(age*1.55f))
    }

    fun onCushion(frame: Frame) = frame.paws == SEAT && frame.surface == SEAT

    fun paused(focusAge: Float, awakeAge: Float, reduced: Boolean): Frame {
        val atPause = focus(focusAge, reduced)
        // A jump already in flight still lands on the cushion when the timer is paused.
        val anchor = if (atPause.pose == Pose.LEAP) focus(focusAge + awakeAge, reduced) else atPause
        if (anchor.pose == Pose.LEAP) return anchor
        val onRug = anchor.surface == RUG
        val x = if (onRug) anchor.x.coerceAtLeast(100f) else SPOT
        val rise = if (reduced) 1f else smooth((awakeAge / .48f).coerceIn(0f,1f))
        return Frame(Pose.AWAKE, x, anchor.surface, anchor.surface, sy=.88f + .12f*rise)
    }

    fun rest(age: Float, reduced: Boolean): Frame {
        if (!reduced) {
            if (age<1.05f) {
                val stretch=wave(age/1.05f)
                return Frame(Pose.STRETCH,SPOT,SEAT,SEAT,sx=1+.07f*stretch,sy=1-.08f*stretch)
            }
            if (age<1.77f) return leap((age-1.05f)/.72f,false)
            val landed=age-1.77f
            if (landed<.24f) {
                val impact=wave(landed/.24f)
                return Frame(Pose.WALK,LAND,RUG,RUG,0,1+.04f*impact,1-.16f*impact)
            }
            val leaving=walking(LAND,landed-.24f)
            if (leaving.x<1190f) return leaving
        }
        return Frame(Pose.AWAY,1190f,RUG,RUG)
    }
}
