package com.wellness.overlay3d.ui

import org.junit.Assert.*
import org.junit.Test

class CatMotionTest {
    @Test fun walkPlantsPawsAndBothJumpsLandOnTheirDestination() {
        val steps = (0 until 100).map { CatMotion.focus(it/100f, false) }
        assertEquals(24, steps.map { it.stride }.toSet().size)
        assertTrue(steps.all { it.surface == it.paws })
        val jump = CatMotion.focus(3.05f, false)
        val landed = CatMotion.focus(3.3f, false)
        assertEquals(CatMotion.Pose.LEAP, jump.pose)
        assertTrue(jump.paws < landed.paws)
        assertEquals(landed.surface, landed.paws, .001f)
        assertEquals(CatMotion.Pose.STRETCH, CatMotion.rest(.4f, false).pose)
        assertEquals(CatMotion.Pose.LEAP, CatMotion.rest(1.4f, false).pose)
        assertEquals(1260f, CatMotion.rest(1.8f, false).paws, .001f)
        assertEquals(CatMotion.Pose.AWAY, CatMotion.rest(8f, false).pose)
        assertEquals(CatMotion.Pose.SLEEP, CatMotion.focus(.25f, true).pose)
        assertEquals(CatMotion.Pose.AWAY, CatMotion.rest(.25f, true).pose)
    }

    @Test fun catSleepsThroughoutFocusAndLooksAtUserThroughoutPause() {
        for (age in listOf(4f, 15f, 80f, 165f, 1499f)) {
            for (reduced in listOf(false, true)) {
                val asleep = CatMotion.focus(age, reduced)
                assertEquals(CatMotion.Pose.SLEEP, asleep.pose)
                val awake = CatMotion.paused(age, 30f, reduced)
                assertEquals(CatMotion.Pose.AWAKE, awake.pose)
                assertEquals(asleep.paws, awake.paws, 0f)
                assertEquals(asleep.x, awake.x, 0f)
            }
        }
        assertEquals(CatMotion.Pose.LEAP, CatMotion.paused(3f, .01f, false).pose)
        assertEquals(CatMotion.Pose.AWAKE, CatMotion.paused(3f, .9f, false).pose)
    }
}
