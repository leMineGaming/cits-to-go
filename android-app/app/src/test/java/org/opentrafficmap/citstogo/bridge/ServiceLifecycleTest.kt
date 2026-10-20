package org.opentrafficmap.citstogo.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether the bridge may keep existing. Everything here is the difference
 * between a service that stopped and a notification that stayed behind it.
 */
class ServiceLifecycleTest {
    @Test
    fun aStoppedBridgeHasNothingLeftToRun() {
        assertTrue(ServiceLifecycle.hasNothingLeftToRun(captureWanted = false, replayActive = false, camBroadcasting = false, recordingActive = false))
    }

    @Test
    fun aCaptureKeepsTheServiceAlive() {
        assertFalse(ServiceLifecycle.hasNothingLeftToRun(captureWanted = true, replayActive = false, camBroadcasting = false, recordingActive = false))
    }

    @Test
    fun aReplayKeepsTheServiceAliveWithoutACapture() {
        assertFalse(ServiceLifecycle.hasNothingLeftToRun(captureWanted = false, replayActive = true, camBroadcasting = false, recordingActive = false))
    }

    /**
     * A CAM broadcast is configured on its own and runs without a receiver attached, so it is work in
     * its own right: the Stop action has to end it, but a settings change must not stop a service that
     * is only broadcasting.
     */
    @Test
    fun aCamBroadcastAloneIsStillWork() {
        assertFalse(ServiceLifecycle.hasNothingLeftToRun(captureWanted = false, replayActive = false, camBroadcasting = true, recordingActive = false))
    }

    /**
     * A recording the user pointed at a file counts even before anything feeds it, so choosing a
     * destination and then pressing Start does not throw that choice away on the way.
     */
    @Test
    fun aChosenRecordingDestinationIsStillWorkEvenWithNothingWritingToIt() {
        assertFalse(ServiceLifecycle.hasNothingLeftToRun(captureWanted = false, replayActive = false, camBroadcasting = false, recordingActive = true))
    }

    /** The Stop action leaves all four off, which is the case that must end the foreground service. */
    @Test
    fun stoppingEndsCaptureReplayBroadcastAndRecordingTogether() {
        assertTrue(ServiceLifecycle.hasNothingLeftToRun(captureWanted = false, replayActive = false, camBroadcasting = false, recordingActive = false))
        assertFalse(ServiceLifecycle.hasNothingLeftToRun(captureWanted = false, replayActive = false, camBroadcasting = false, recordingActive = true))
    }

    /** Nothing short of all four being off is allowed to stop the service. */
    @Test
    fun anySingleReasonIsEnoughToStayRunning() {
        assertFalse(ServiceLifecycle.hasNothingLeftToRun(captureWanted = true, replayActive = false, camBroadcasting = false, recordingActive = false))
        assertFalse(ServiceLifecycle.hasNothingLeftToRun(captureWanted = false, replayActive = true, camBroadcasting = false, recordingActive = false))
        assertFalse(ServiceLifecycle.hasNothingLeftToRun(captureWanted = false, replayActive = false, camBroadcasting = true, recordingActive = false))
        assertFalse(ServiceLifecycle.hasNothingLeftToRun(captureWanted = false, replayActive = false, camBroadcasting = false, recordingActive = true))
    }
}
