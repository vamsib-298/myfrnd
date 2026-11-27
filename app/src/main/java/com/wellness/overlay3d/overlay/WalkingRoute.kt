package com.wellness.overlay3d.overlay

import kotlin.math.roundToInt

/** A route starts or ends with a planted-foot turn. Distance and clip time share one eased curve. */
internal class WalkingRoute(val metres: Float, private val leaving: Boolean) {
    private val travelSeconds = metres.coerceAtLeast(.01f) / (SPEED * (1f - RAMP))
    private val halfSteps = (metres / (SPEED * PERIOD * .5f)).roundToInt().coerceAtLeast(1)
    val duration = travelSeconds + TURN
    var distanceFraction = 0f
        private set
    var strideTime = 0f
        private set
    var profile = if (leaving) 0f else 1f
        private set

    fun sample(seconds: Float) {
        val t = ((seconds - if (leaving) TURN else 0f) / travelSeconds).coerceIn(0f, 1f)
        // Integrate a smoothstep speed ramp over the first and last part of the path.
        fun ramp(x: Float): Float {
            val u = x/RAMP
            return RAMP*u*u*u*(1f-.5f*u)/(1f-RAMP)
        }
        distanceFraction = when {
            t < RAMP -> ramp(t)
            t > 1f-RAMP -> 1f-ramp(1f-t)
            else -> (t-.5f*RAMP)/(1f-RAMP)
        }
        strideTime = distanceFraction * halfSteps * PERIOD * .5f
        val turn = (if (leaving) seconds / TURN else (duration - seconds) / TURN).coerceIn(0f, 1f)
        profile = turn*turn*(3f-2f*turn)
    }

    companion object {
        const val PERIOD = 1.0916623f
        const val SPEED = 1.26f
        private const val TURN = .32f
        private const val RAMP = .13f
    }
}
