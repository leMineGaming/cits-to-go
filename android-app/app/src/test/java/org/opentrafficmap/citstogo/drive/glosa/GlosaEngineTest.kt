package org.opentrafficmap.citstogo.drive.glosa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.drive.profile.DriverProfile
import org.opentrafficmap.citstogo.intersection.MovementPhaseState

class GlosaEngineTest {
    private val now = 1_700_000_000_000L
    private val profile = DriverProfile.default().copy(maxSpeedCapKph = 130)

    private fun green(
        distance: Double = 100.0,
        speedMps: Double = 10.0,
        greenSeconds: Double = 15.0,
        limitKph: Int = 50,
        ageMs: Long = 0,
        accuracy: Double? = 4.0,
        laneConfidence: Double = 0.95,
        state: MovementPhaseState = MovementPhaseState.ProtectedAllowed,
        min: Double? = greenSeconds,
        likely: Double? = greenSeconds,
        max: Double? = greenSeconds + 2.0,
        signalConfidence: Int? = 12,
        wait: Long = 0,
        profile: DriverProfile = this.profile,
    ) = GlosaInput(
        distanceToStopLineM = distance,
        currentSpeedMps = speedMps,
        roadSpeedLimitKph = limitKph,
        signalState = state,
        minEndSeconds = min,
        likelyEndSeconds = likely,
        maxEndSeconds = max,
        signalConfidence = signalConfidence,
        spatReceivedAtMs = now - ageMs,
        nowMs = now + wait,
        positionAccuracyM = accuracy,
        laneConfidence = laneConfidence,
        profile = profile,
        staleAfterMs = 3_000,
    )

    @Test
    fun feasibleGreenGivesSafeTargetWithinLimit() {
        val result = GlosaEngine.calculate(green())
        assertEquals(GlosaStatus.Available, result.status)
        assertNotNull(result.targetSpeedKph)
        assertTrue(result.targetSpeedKph!! <= 50.0)
        assertTrue(result.targetSpeedKph!! >= 36.0)
        assertTrue(result.confidence > 0.5)
        assertNotNull(result.greenEndsAtMs)
    }

    @Test
    fun neverRecommendsAboveTheProfileCap() {
        val capped = profile.copy(maxSpeedCapKph = 30)
        val result = GlosaEngine.calculate(green(profile = capped, greenSeconds = 30.0, speedMps = 0.0))
        assertTrue(result.targetSpeedKph!! <= 30.0)
    }

    @Test
    fun tooShortGreenStopsInsteadOfSpeeding() {
        val result = GlosaEngine.calculate(green(distance = 100.0, speedMps = 10.0, greenSeconds = 3.0))
        assertEquals(GlosaStatus.StopAtLine, result.status)
        assertEquals(0.0, result.targetSpeedKph)
        assertEquals(GlosaAdvisory.Stop, result.advisory)
    }

    @Test
    fun impossibleArrivalIsFlaggedWhenStoppingIsNoLongerComfortable() {
        val result = GlosaEngine.calculate(
            green(distance = 10.0, speedMps = 20.0, greenSeconds = 1.0),
        )
        assertEquals(GlosaStatus.ImpossibleArrival, result.status)
        assertNull(result.targetSpeedKph)
        assertEquals(GlosaAdvisory.BrakeNow, result.advisory)
    }

    @Test
    fun staleDataHasNoRecommendation() {
        val result = GlosaEngine.calculate(green(ageMs = 5_000))
        assertEquals(GlosaStatus.StaleData, result.status)
        assertNull(result.targetSpeedKph)
    }

    @Test
    fun lowLaneConfidenceHasNoRecommendation() {
        val result = GlosaEngine.calculate(green(laneConfidence = 0.2))
        assertEquals(GlosaStatus.LowConfidence, result.status)
        assertNull(result.targetSpeedKph)
    }

    @Test
    fun missingTimingHasNoRecommendation() {
        val result = GlosaEngine.calculate(green(min = null, likely = null, max = null))
        assertEquals(GlosaStatus.NoTiming, result.status)
        assertNull(result.targetSpeedKph)
    }

    @Test
    fun redLightWithKnownGreenStartSuggestsRollingArrival() {
        val result = GlosaEngine.calculate(
            green(
                distance = 100.0,
                speedMps = 10.0,
                state = MovementPhaseState.StopAndRemain,
                min = 10.0,
                likely = 10.0,
                max = 12.0,
            ),
        )
        assertEquals(GlosaStatus.Available, result.status)
        assertNotNull(result.targetSpeedKph)
        assertTrue(result.targetSpeedKph!! <= 50.0)
        assertEquals(GlosaAdvisory.Decelerate, result.advisory)
    }

    @Test
    fun redLightWithNoTimingStops() {
        val result = GlosaEngine.calculate(
            green(
                distance = 80.0,
                speedMps = 10.0,
                state = MovementPhaseState.StopAndRemain,
                min = null,
                likely = null,
                max = null,
            ),
        )
        assertEquals(GlosaStatus.StopAtLine, result.status)
        assertEquals(0.0, result.targetSpeedKph)
    }

    @Test
    fun doesNotAskForMoreAccelerationThanComfortable() {
        val slowProfile = profile.copy(comfortableAccelerationMps2 = 0.5f)
        val result = GlosaEngine.calculate(
            green(distance = 20.0, speedMps = 1.0, greenSeconds = 4.0, profile = slowProfile),
        )
        if (result.targetSpeedKph != null) {
            val targetMps = result.targetSpeedKph!! / 3.6
            val requiredAccel = (targetMps * targetMps - 1.0) / (2 * 20.0)
            assertTrue("required acceleration $requiredAccel must stay comfortable", requiredAccel <= 0.5 + 0.01)
        }
    }

    @Test
    fun disabledGlosaReturnsDisabled() {
        val result = GlosaEngine.calculate(green(profile = profile.copy(glosaEnabled = false)))
        assertEquals(GlosaStatus.Disabled, result.status)
    }

    @Test
    fun unknownSignalStateHasNoRecommendation() {
        val result = GlosaEngine.calculate(
            green(state = MovementPhaseState.Unknown, min = null, likely = null, max = null),
        )
        assertEquals(GlosaStatus.NoSignal, result.status)
    }
}
