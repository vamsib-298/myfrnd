package com.wellness.overlay3d.overlay

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import com.wellness.overlay3d.R
import com.wellness.overlay3d.util.CompanionPreferences
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneVisitTest {
    @Test fun reminderWalksAcrossSystemWindowAndLeavesAfterDone() {
        val frameTime = Duration.ofMillis(16)
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(frameTime)
        val context: Context = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        CompanionPreferences.setPositionMode(context, CompanionPreferences.POSITION_LEFT_RIGHT)
        CompanionPreferences.setVoiceEnabled(context, false)
        CompanionPreferences.setReducedMotion(context, false)
        var delivered = 0
        var dismissed = 0
        val manager = OverlayWindowManager(context, { dismissed++ }, {}, {}, { delivered++ }, {})
        val main = shadowOf(Looper.getMainLooper())
        try {
            assertTrue(manager.showOverlay(OverlayWindowManager.OverlayReason.WATER_REMINDER, "Water"))
            val root = WindowInspector.getGlobalWindowViews().single { it.id == R.id.overlayRoot }
            val params = root.layoutParams as WindowManager.LayoutParams
            val card = root.findViewById<View>(R.id.speechBubbleContainer)
            val person = root.findViewById<HumanCompanionView>(R.id.humanCompanionView)
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
            assertEquals(View.INVISIBLE, card.visibility)
            for (frame in 0 until 150) {
                if (person.animationPhase == HumanCompanionView.PHASE_SCREEN_WALK) break
                main.idleFor(frameTime)
            }
            assertEquals(HumanCompanionView.PHASE_SCREEN_WALK, person.animationPhase)
            val entryX = params.x + person.translationX
            repeat(19) { main.idleFor(frameTime) }
            assertTrue(params.x + person.translationX > entryX)
            repeat(500) { main.idleFor(frameTime) }
            assertEquals(View.VISIBLE, card.visibility)
            root.findViewById<Button>(R.id.btnOverlayPrimary).performClick()
            assertEquals(1, delivered)
            assertTrue(manager.isOverlayActive)
            assertEquals(-1, person.walkDirection)
            assertEquals(HumanCompanionView.PHASE_SCREEN_WALK, person.animationPhase)
            assertEquals(View.INVISIBLE, card.visibility)
            val exitX = params.x + person.translationX
            repeat(40) { main.idleFor(frameTime) }
            assertTrue(params.x + person.translationX < exitX)
            repeat(563) { main.idleFor(frameTime) }
            assertFalse(manager.isOverlayActive)
            assertEquals(1, dismissed)
        } finally {
            manager.destroyOverlayImmediately()
            manager.releaseTts()
        }
    }
}
