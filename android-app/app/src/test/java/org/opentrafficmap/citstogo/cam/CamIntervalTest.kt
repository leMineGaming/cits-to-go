package org.opentrafficmap.citstogo.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CamIntervalTest {
    @Test
    fun debugModeIsWhatLowersTheFloor() {
        assertEquals(100..1000, CamInterval.rangeMs(stressTestEnabled = false))
        assertEquals(1..1000, CamInterval.rangeMs(stressTestEnabled = true))
    }

    @Test
    fun anIntervalOutsideTheStandardNeedsDebugMode() {
        assertEquals(100, CamInterval.clamp(1, stressTestEnabled = false))
        assertEquals(1, CamInterval.clamp(1, stressTestEnabled = true))
        assertEquals(1, CamInterval.clamp(0, stressTestEnabled = true))
        assertEquals(1000, CamInterval.clamp(5_000, stressTestEnabled = true))
        assertEquals(500, CamInterval.clamp(500, stressTestEnabled = false))
    }

    @Test
    fun theFieldAcceptsNumbersAndFallsBackToTheDefault() {
        assertEquals(500, CamInterval.parse("500", stressTestEnabled = false))
        assertEquals(250, CamInterval.parse(" 250 ", stressTestEnabled = false))
        assertEquals(500, CamInterval.parse("", stressTestEnabled = true))
        assertEquals(500, CamInterval.parse("fast", stressTestEnabled = true))
        // 1 ms is only a number the debug menu may turn into a broadcast.
        assertEquals(1, CamInterval.parse("1", stressTestEnabled = true))
        assertEquals(100, CamInterval.parse("1", stressTestEnabled = false))
    }

    @Test
    fun belowTenHertzIsOutsideTheStandard() {
        assertTrue(CamInterval.outsideStandard(99))
        assertTrue(CamInterval.outsideStandard(1))
        assertFalse(CamInterval.outsideStandard(100))
        assertFalse(CamInterval.outsideStandard(1_000))
    }

    @Test
    fun theWarningFollowsTheFieldWhateverItHolds() {
        assertTrue(CamInterval.outsideStandard(" 50 "))
        assertFalse(CamInterval.outsideStandard("100"))
        assertFalse("an empty or broken field asks for no broadcast", CamInterval.outsideStandard(""))
        assertFalse(CamInterval.outsideStandard("fast"))
    }

    @Test
    fun theBroadcasterWakesOftenEnoughForTheInterval() {
        assertEquals(100L, CamInterval.triggerCheckMs(1_000))
        assertEquals(100L, CamInterval.triggerCheckMs(100))
        assertEquals(20L, CamInterval.triggerCheckMs(20))
        assertEquals(1L, CamInterval.triggerCheckMs(1))
        // A field the service never clamped must not park the broadcaster or spin it.
        assertEquals(1L, CamInterval.triggerCheckMs(0))
        assertEquals(1L, CamInterval.triggerCheckMs(-5))
    }

    @Test
    fun dynamicsTriggersNeverExceedTheStandardRateWithoutDebugMode() {
        assertEquals(100L, CamInterval.motionTriggerFloorMs(1_000))
        assertEquals(100L, CamInterval.motionTriggerFloorMs(100))
        assertEquals(20L, CamInterval.motionTriggerFloorMs(20))
        assertEquals(1L, CamInterval.motionTriggerFloorMs(1))
    }
}
