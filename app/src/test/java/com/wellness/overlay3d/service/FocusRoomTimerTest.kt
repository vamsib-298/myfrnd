package com.wellness.overlay3d.service

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.inspector.WindowInspector
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.descendants
import com.wellness.overlay3d.R
import com.wellness.overlay3d.ui.FocusRoomActivity
import com.wellness.overlay3d.util.CompanionPreferences
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSettings
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusRoomTimerTest {
    @Test fun reopeningRoomShowsPausedCountdownThenRestoredBreakControls() {
        val context: Context = RuntimeEnvironment.getApplication()
        CompanionPreferences.getPrefs(context).edit().clear().commit()
        CompanionPreferences.saveFocusState(context, true, true, 0, 123_000)
        val controller = Robolectric.buildActivity(FocusRoomActivity::class.java).setup()
        val main = shadowOf(Looper.getMainLooper())
        fun labels(): List<String> = controller.get().findViewById<ViewGroup>(android.R.id.content)
            .descendants.filterIsInstance<TextView>().map { it.text.toString() }.toList()
        try {
            main.idle()
            assertTrue(labels().containsAll(listOf("02:03", context.getString(R.string.focus_resume))))
            CompanionPreferences.finishFocus(context, System.currentTimeMillis())
            main.idleFor(Duration.ofMillis(250))
            assertTrue(labels().containsAll(listOf(context.getString(R.string.focus_break_label),
                context.getString(R.string.focus_another))))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun restoredTimerPausesResumesAndFinishesWithoutOverlayPermission() {
        val context: Context = RuntimeEnvironment.getApplication()
        CompanionPreferences.getPrefs(context).edit().clear().commit()
        CompanionPreferences.setVoiceEnabled(context, false)
        CompanionPreferences.saveFocusState(context, true, false, System.currentTimeMillis() + 10_000, 10_000)
        CompanionRuntime.roomVisible = true
        val controller = Robolectric.buildService(OverlayService::class.java).create()
        val service = controller.get()
        val main = shadowOf(Looper.getMainLooper())
        fun send(action: String) {
            service.onStartCommand(Intent(action), 0, 1)
            main.idle()
        }
        try {
            send(OverlayService.ACTION_START_FOCUS)
            assertTrue(CompanionPreferences.getFocusState(context).active)
            send(OverlayService.ACTION_TOGGLE_PAUSE_FOCUS)
            val paused = CompanionPreferences.getFocusState(context)
            assertTrue(paused.paused)
            main.idleFor(Duration.ofSeconds(30))
            assertEquals(paused, CompanionPreferences.getFocusState(context))
            send(OverlayService.ACTION_TOGGLE_PAUSE_FOCUS)
            assertFalse(CompanionPreferences.getFocusState(context).paused)
            main.idleFor(Duration.ofMillis(paused.remainingMs + 100))
            assertFalse(CompanionPreferences.getFocusState(context).active)
            assertEquals(1, CompanionPreferences.getFocusToday(context))
            assertTrue(CompanionPreferences.breakEnd(context) > System.currentTimeMillis())
        } finally {
            controller.destroy()
            CompanionRuntime.roomVisible = false
        }
    }

    @Test fun backgroundFocusNeverAddsAWindowEvenWithOverlayPermission() {
        val context: Context = RuntimeEnvironment.getApplication()
        CompanionPreferences.getPrefs(context).edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(true)
        CompanionPreferences.setCompanionEnabled(context, true)
        CompanionPreferences.setScreenBreakEnabled(context, false)
        CompanionPreferences.setWaterBreakEnabled(context, false)
        CompanionPreferences.setWindDownEnabled(context, false)
        CompanionPreferences.saveFocusState(context, true, false, System.currentTimeMillis() + 6_000, 6_000)
        CompanionRuntime.roomVisible = false
        val controller = Robolectric.buildService(OverlayService::class.java).create()
        val main = shadowOf(Looper.getMainLooper())
        fun assertRoomOnly() {
            main.idle()
            assertTrue(WindowInspector.getGlobalWindowViews().none { it.id == R.id.overlayRoot })
        }
        try {
            controller.get().onStartCommand(Intent(OverlayService.ACTION_START_FOCUS), 0, 1)
            assertRoomOnly()
            repeat(2) {
                controller.get().onStartCommand(Intent(OverlayService.ACTION_TOGGLE_PAUSE_FOCUS), 0, it + 2)
                assertRoomOnly()
            }
            main.idleFor(Duration.ofSeconds(7))
            assertFalse(CompanionPreferences.getFocusState(context).active)
            assertRoomOnly()
        } finally {
            controller.destroy()
        }
    }

    @Test fun onlyANewSessionArmsTheEntranceMeow() {
        val context: Context = RuntimeEnvironment.getApplication()
        CompanionPreferences.getPrefs(context).edit().clear().commit()
        val controller = Robolectric.buildService(OverlayService::class.java).create()
        fun send(action: String) = controller.get().onStartCommand(Intent(action), 0, 1)
        try {
            send(OverlayService.ACTION_START_FOCUS)
            assertNotNull(CompanionRuntime.pendingCatEntrance)
            CompanionRuntime.catEnteredRoom()
            assertNull(CompanionRuntime.pendingCatEntrance)
            send(OverlayService.ACTION_START_FOCUS)
            send(OverlayService.ACTION_TOGGLE_PAUSE_FOCUS)
            send(OverlayService.ACTION_TOGGLE_PAUSE_FOCUS)
            assertNull(CompanionRuntime.pendingCatEntrance)
            send(OverlayService.ACTION_STOP_FOCUS)
            send(OverlayService.ACTION_START_FOCUS)
            assertNotNull(CompanionRuntime.pendingCatEntrance)
        } finally {
            controller.destroy()
        }
    }
}
