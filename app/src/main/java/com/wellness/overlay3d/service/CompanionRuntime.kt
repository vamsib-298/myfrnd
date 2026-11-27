package com.wellness.overlay3d.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CompanionStatus(
    val running: Boolean = false,
    val screenActive: Boolean = false,
    val screenBreakEnabled: Boolean = true,
    val waterBreakEnabled: Boolean = true,
    val nextEye: Long? = null,
    val nextWater: Long? = null,
    val focusActive: Boolean = false,
    val focusPaused: Boolean = false,
    val focusEndElapsed: Long? = null,
    val focusRemainingMs: Long = 0L,
    val focusDurationMin: Int = 25,
    val error: Int? = null
)

object CompanionRuntime {
    var onRoomVisibilityChanged: (() -> Unit)? = null
    var pendingCatEntrance: (() -> Unit)? = null

    fun catEnteredRoom() {
        val play = pendingCatEntrance ?: return
        pendingCatEntrance = null
        play()
    }

    var roomVisible = false
        set(value) {
            field = value
            onRoomVisibilityChanged?.invoke()
        }
    private val mutableStatus = MutableStateFlow(CompanionStatus())
    val status = mutableStatus.asStateFlow()

    fun publish(status: CompanionStatus) {
        mutableStatus.value = status
    }
}
