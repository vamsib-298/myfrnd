package com.wellness.overlay3d.model

import org.junit.Assert.*
import org.junit.Test

class ReminderScheduleTest {
    @Test fun initialDeadlineUsesElapsedTime() {
        val schedule = ReminderSchedule(500L, 20, true)
        assertEquals(1_200_500L, schedule.nextEye)
    }

    @Test fun reopeningDashboardDoesNotResetDeadline() {
        val schedule = ReminderSchedule(0, 15, true)
        schedule.configure(60_000L, 15, true)
        assertEquals(900_000L, schedule.nextEye)
    }

    @Test fun changingEyeIntervalUpdatesDeadline() {
        val schedule = ReminderSchedule(0, 15, true)
        schedule.configure(100L, 20, true)
        assertEquals(1_200_100L, schedule.nextEye)
    }

    @Test fun disablingScreenBreakClearsDeadline() {
        val schedule = ReminderSchedule(0, 15, true, true)
        schedule.configure(100L, 15, false)
        assertNull(schedule.nextEye)
        schedule.configure(200L, 20, true)
        assertEquals(1_200_200L, schedule.nextEye)
    }

    @Test fun lockResetsContinuousEyeSession() {
        val schedule = ReminderSchedule(0, 15, true)
        schedule.screenChanged(100L, false)
        assertNull(schedule.nextEye)
        schedule.screenChanged(200L, true)
        assertEquals(900_200L, schedule.nextEye)
    }

    @Test fun duplicateUnlockDoesNotExtendEyeTimer() {
        val schedule = ReminderSchedule(0, 15, true)
        schedule.screenChanged(100L, true)
        assertEquals(900_000L, schedule.nextEye)
    }

    @Test fun deliveredAndSnoozedEyeBreakScheduleCorrectNextBreak() {
        val schedule = ReminderSchedule(0, 15, true)
        schedule.eyeDelivered(900_000L)
        assertEquals(1_800_000L, schedule.nextEye)
        schedule.snoozeEye(1_000_000L, 5)
        assertEquals(1_300_000L, schedule.nextEye)
    }

    @Test fun earliestTaskDeterminesWakeupWithoutPolling() {
        val schedule = ReminderSchedule(0, 15, true)
        assertEquals(1234L, schedule.nextDelay(0, 1234L))
        assertEquals(900_000L, schedule.nextDelay(0, null))
        assertEquals(0L, schedule.nextDelay(0, -100L))
    }

    @Test fun configurationWhileLockedDoesNotStartEyeSession() {
        val schedule = ReminderSchedule(0, 15, false)
        schedule.configure(100, 20, true)
        assertNull(schedule.nextEye)
        schedule.screenChanged(200, true)
        assertEquals(1_200_200L, schedule.nextEye)
    }
}
