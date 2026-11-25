package com.wellness.overlay3d

import android.content.Context
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wellness.overlay3d.overlay.HumanCompanionView
import com.wellness.overlay3d.service.CompanionRuntime
import com.wellness.overlay3d.service.OverlayService
import com.wellness.overlay3d.ui.MainActivity
import com.wellness.overlay3d.util.CompanionPreferences
import com.wellness.overlay3d.util.TaskManager
import org.hamcrest.CoreMatchers.allOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun launch() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { OverlayService.pause(context) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        CompanionPreferences.getPrefs(context).edit().clear().commit()
        TaskManager.preferences(context).edit().clear().commit()
        CompanionPreferences.setReducedMotion(context, true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun close() { scenario.close() }

    @Test fun startsPausedWithoutRequestingPermissions() {
        assertFalse(CompanionPreferences.isCompanionEnabled(context))
        assertFalse(CompanionRuntime.status.value.running)
    }

    @Test fun dashboardRendersThe3dCompanionModel() {
        onView(withId(R.id.liveDashboardCompanion)).perform(scrollTo())
            .check(matches(allOf(isDisplayed(), isAssignableFrom(HumanCompanionView::class.java))))
        val deadline = SystemClock.uptimeMillis() + 15_000L
        var state = HumanCompanionView.RenderState.LOADING
        var frames = 0L
        while (true) {
            scenario.onActivity {
                val companion = it.findViewById<HumanCompanionView>(R.id.liveDashboardCompanion)
                state = companion.renderState
                frames = companion.framesRendered
            }
            if (state != HumanCompanionView.RenderState.LOADING || SystemClock.uptimeMillis() > deadline) break
            SystemClock.sleep(100L)
        }
        assertEquals(HumanCompanionView.RenderState.READY, state)
        assertTrue(frames > 0)
    }

    @Test fun changingIntervalsWhilePausedDoesNotStartService() {
        onView(withId(R.id.chipEye30)).perform(scrollTo(), click())
        onView(withId(R.id.chipWater45)).perform(scrollTo(), click())
        assertEquals(30, CompanionPreferences.getEyeStrainMinutes(context))
        assertEquals(45, CompanionPreferences.getWaterIntervalMinutes(context))
        assertFalse(CompanionRuntime.status.value.running)
        scenario.recreate()
        onView(withId(R.id.chipEye30)).check(matches(isSelected()))
        onView(withId(R.id.chipWater45)).check(matches(isSelected()))
    }

    @Test fun customCompanionRespondsToRealTap() {
        onView(withId(R.id.liveDashboardCompanion)).perform(scrollTo(), click())
        onView(withId(R.id.tvLiveCompanionSpeech)).check(matches(withText(R.string.demo_cheer)))
    }

    @Test fun blankTaskShowsValidationAndKeepsEditorOpen() {
        onView(withId(R.id.btnAddNewTask)).perform(scrollTo(), click())
        onView(withText(R.string.save)).perform(click())
        onView(withId(R.id.etTaskTitle)).check(matches(hasErrorText(context.getString(R.string.empty_title_error))))
        assertTrue(TaskManager.getPendingReminders(context).isEmpty())
    }

    @Test fun reminderCrudAndCompletePersists() {
        onView(withId(R.id.btnAddNewTask)).perform(scrollTo(), click())
        onView(withId(R.id.etTaskTitle)).perform(replaceText("Finish the report"), closeSoftKeyboard())
        onView(withText(R.string.save)).perform(click())
        val id = TaskManager.getPendingReminders(context).single().id
        onView(withId(R.id.btnEditTask)).perform(scrollTo(), click())
        onView(withId(R.id.etTaskTitle)).perform(replaceText("Review the report"), closeSoftKeyboard())
        onView(withText(R.string.save)).perform(click())
        assertEquals(id, TaskManager.getPendingReminders(context).single().id)
        scenario.recreate()
        onView(withId(R.id.tvTaskTitle)).check(matches(withText("Review the report")))
        onView(withId(R.id.btnDeleteTask)).perform(scrollTo(), click())
        assertTrue(TaskManager.getPendingReminders(context).isEmpty())
    }

    @Test fun quickNoteSavesWithoutTimer() {
        onView(withId(R.id.btnAddNewTask)).perform(scrollTo(), click())
        onView(withId(R.id.etTaskTitle)).perform(replaceText("Remember posture"), closeSoftKeyboard())
        onView(withId(R.id.pillNoteOnly)).perform(scrollTo(), click())
        onView(withText(R.string.save)).perform(click())
        val saved = TaskManager.getPendingReminders(context).single()
        assertTrue(saved.isNote)
        assertTrue(TaskManager.getPendingTimedReminders(context).isEmpty())
    }

    @Test fun taskDraftSurvivesActivityRecreation() {
        onView(withId(R.id.btnAddNewTask)).perform(scrollTo(), click())
        onView(withId(R.id.etTaskTitle)).perform(replaceText("Keep my draft"), closeSoftKeyboard())
        onView(withId(R.id.pillTomorrowMorning)).perform(scrollTo(), click())
        var dateText = ""
        onView(withId(R.id.tvSelectedDateTime)).check { view, _ -> dateText = (view as TextView).text.toString() }
        scenario.recreate()
        onView(withId(R.id.etTaskTitle)).check(matches(withText("Keep my draft")))
        onView(withId(R.id.tvSelectedDateTime)).check(matches(withText(dateText)))
        onView(withId(R.id.pillTomorrowMorning)).check(matches(isSelected()))
        onView(withId(R.id.pillIn1Hour)).check(matches(isNotSelected()))
    }

    @Test fun corruptDataHasVisibleErrorRatherThanEmptySuccess() {
        scenario.onActivity {
            TaskManager.preferences(context).edit().putString("key_scheduled_reminders", "broken").apply()
        }
        onView(withId(R.id.tvTaskError)).perform(scrollTo()).check(matches(withText(R.string.task_storage_error)))
        onView(withId(R.id.btnAddNewTask)).check(matches(isNotEnabled()))
    }
}
