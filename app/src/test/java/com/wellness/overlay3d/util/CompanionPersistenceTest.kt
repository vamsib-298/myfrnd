package com.wellness.overlay3d.util

import android.content.Context
import com.wellness.overlay3d.model.ReminderSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionPersistenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        CompanionPreferences.getPrefs(context).edit().clear().commit()
    }

    @Test
    fun focusStatePersistsAndClearsCleanly() {
        CompanionPreferences.saveFocusState(
            context = context,
            active = true,
            paused = true,
            endWallMs = 0L,
            remainingMs = 123_000L
        )
        val restored = CompanionPreferences.getFocusState(context)
        assertTrue(restored.active)
        assertTrue(restored.paused)
        assertEquals(123_000L, restored.remainingMs)

        CompanionPreferences.clearFocusState(context)
        val cleared = CompanionPreferences.getFocusState(context)
        assertFalse(cleared.active)
        assertFalse(cleared.paused)
        assertEquals(0L, cleared.remainingMs)
    }

    @Test
    fun finishingTwiceRecordsOneSessionAndAnchorsBreakToFocusDeadline() {
        val deadline = System.currentTimeMillis() - 8_000L
        CompanionPreferences.saveFocusState(context, true, false, deadline, 25 * 60_000L)
        CompanionPreferences.finishFocus(context, deadline)
        CompanionPreferences.finishFocus(context, deadline + 8_000L)
        assertFalse(CompanionPreferences.getFocusState(context).active)
        assertEquals(1, CompanionPreferences.getFocusToday(context))
        assertEquals(deadline + CompanionPreferences.FOCUS_BREAK_MS, CompanionPreferences.breakEnd(context))
    }

    @Test
    fun endingEarlyDoesNotAwardACompletedSessionOrStartABreak() {
        CompanionPreferences.saveFocusState(context, true, true, 0, 32_000L)
        CompanionPreferences.clearFocusState(context)
        CompanionPreferences.finishFocus(context, System.currentTimeMillis())
        assertEquals(0L, CompanionPreferences.breakEnd(context))
        assertEquals(0, CompanionPreferences.getFocusToday(context))
    }

    @Test
    fun reminderScheduleRestoresRemainingDeadlinesAcrossRestart() {
        val nowElapsed = 50_000L
        val wallNow = 1_700_000_000_000L
        val schedule = ReminderSchedule(
            now = nowElapsed,
            waterMinutes = 30,
            eyeMinutes = 20,
            screenActive = true
        )
        schedule.restoreFromWallDeadlines(
            now = nowElapsed,
            wallNow = wallNow,
            eyeWallMs = wallNow + 120_000L,
            waterWallMs = wallNow + 300_000L,
            screenActive = true
        )
        assertEquals(nowElapsed + 120_000L, schedule.nextEye)
        assertEquals(nowElapsed + 300_000L, schedule.nextWater)
    }
}
