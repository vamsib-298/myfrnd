package com.wellness.overlay3d.model

/** Screen Break deadline uses continuous screen-active elapsed time; calendar tasks use wall-clock time separately. */
class ReminderSchedule(
    now: Long,
    eyeMinutes: Int,
    screenActive: Boolean,
    private var eyeEnabled: Boolean = true
) {
    var eyeInterval = eyeMinutes * 60_000L
        private set
    var nextEye: Long? = if (screenActive && eyeEnabled) now + eyeInterval else null
        private set

    fun configure(now: Long, eyeMinutes: Int, enabled: Boolean = true) {
        require(eyeMinutes > 0)
        val eye = eyeMinutes * 60_000L
        if (!enabled) {
            eyeEnabled = false
            nextEye = null
        } else {
            val wasDisabled = !eyeEnabled
            eyeEnabled = true
            if (wasDisabled || (eye != eyeInterval && nextEye != null)) {
                nextEye = now + eye
            }
        }
        eyeInterval = eye
    }

    fun screenChanged(now: Long, active: Boolean) {
        if (!active || !eyeEnabled) nextEye = null
        else if (nextEye == null) nextEye = now + eyeInterval
    }

    fun eyeDelivered(now: Long) {
        nextEye = if (eyeEnabled) now + eyeInterval else null
    }

    fun snoozeEye(now: Long, snoozeMinutes: Int = 5) {
        nextEye = if (eyeEnabled) now + snoozeMinutes * 60_000L else null
    }

    fun nextDelay(now: Long, nextTaskDelay: Long?): Long =
        listOfNotNull(nextEye?.minus(now), nextTaskDelay)
            .minOrNull()?.coerceAtLeast(0L) ?: 60_000L
}
