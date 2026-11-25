package com.wellness.overlay3d.model

/** Interval deadlines for Water Break and Screen Break; calendar tasks use wall-clock time separately. */
class ReminderSchedule(
    now: Long,
    waterMinutes: Int,
    eyeMinutes: Int,
    screenActive: Boolean,
    private var waterEnabled: Boolean = true,
    private var eyeEnabled: Boolean = true
) {
    var waterInterval = waterMinutes * 60_000L
        private set
    var eyeInterval = eyeMinutes * 60_000L
        private set
    var nextWater: Long? = if (waterEnabled) now + waterInterval else null
        private set
    var nextEye: Long? = if (screenActive && eyeEnabled) now + eyeInterval else null
        private set

    fun configure(
        now: Long,
        waterMinutes: Int,
        eyeMinutes: Int,
        waterOn: Boolean = true,
        eyeOn: Boolean = true
    ) {
        require(waterMinutes > 0 && eyeMinutes > 0)
        val water = waterMinutes * 60_000L
        val eye = eyeMinutes * 60_000L

        if (!waterOn) {
            waterEnabled = false
            nextWater = null
        } else {
            val wasDisabled = !waterEnabled
            waterEnabled = true
            if (wasDisabled || water != waterInterval) {
                nextWater = now + water
            }
        }

        if (!eyeOn) {
            eyeEnabled = false
            nextEye = null
        } else {
            val wasDisabled = !eyeEnabled
            eyeEnabled = true
            if (wasDisabled || (eye != eyeInterval && nextEye != null)) {
                nextEye = now + eye
            }
        }

        waterInterval = water
        eyeInterval = eye
    }

    fun screenChanged(now: Long, active: Boolean) {
        if (!active || !eyeEnabled) nextEye = null
        else if (nextEye == null) nextEye = now + eyeInterval
    }

    fun waterDelivered(now: Long) {
        nextWater = if (waterEnabled) now + waterInterval else null
    }

    fun snoozeWater(now: Long, snoozeMinutes: Int = 5) {
        nextWater = if (waterEnabled) now + snoozeMinutes * 60_000L else null
    }

    fun eyeDelivered(now: Long) {
        nextEye = if (eyeEnabled) now + eyeInterval else null
    }

    fun snoozeEye(now: Long, snoozeMinutes: Int = 5) {
        nextEye = if (eyeEnabled) now + snoozeMinutes * 60_000L else null
    }

    fun nextDelay(now: Long, nextTaskDelay: Long?): Long =
        listOfNotNull(nextWater?.minus(now), nextEye?.minus(now), nextTaskDelay)
            .minOrNull()?.coerceAtLeast(0L) ?: 60_000L
}
