package com.wellness.overlay3d.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.round

class WalkingRouteTest {
    @Test fun turnKeepsTheFeetPlantedAtBothEndsOfAVisit() {
        for (leaving in listOf(false, true)) {
            val route = WalkingRoute(1.35f, leaving)
            route.sample(if (leaving) 0.16f else route.duration - 0.16f)
            assertEquals(if (leaving) 0f else 1f, route.distanceFraction, 0.0001f)
            assertEquals(0.5f, route.profile, 0.0001f)
            var previous = 0f
            for (frame in 0..100) {
                route.sample(route.duration * frame / 100f)
                assertTrue(route.distanceFraction >= previous)
                previous = route.distanceFraction
            }
            assertEquals(1f, route.distanceFraction, 0.0001f)
            val steps = 2f * route.strideTime / WalkingRoute.PERIOD
            assertEquals(round(steps), steps, 0.0001f)
        }
    }
}
