package org.opentrafficmap.citstogo.drive.position

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.intersection.IntersectionKey
import org.opentrafficmap.citstogo.intersection.LaneConnection
import org.opentrafficmap.citstogo.intersection.LaneNode
import org.opentrafficmap.citstogo.intersection.LaneType
import org.opentrafficmap.citstogo.intersection.MapIntersection
import org.opentrafficmap.citstogo.intersection.MapLane
import org.opentrafficmap.citstogo.intersection.MovementPhaseState
import org.opentrafficmap.citstogo.intersection.SignalEvent
import org.opentrafficmap.citstogo.intersection.SignalMovement
import org.opentrafficmap.citstogo.intersection.SpatIntersection

class IntersectionMatcherTest {
    private val now = 1_700_000_000_000L
    private val key = IntersectionKey(1, 100)
    private val lat = 482_082_000
    private val lon = 163_738_000
    private val signalGroup = 7

    private fun map(withSignalGroup: Boolean = true) = MapIntersection(
        key = key,
        name = "Test junction",
        revision = 1,
        latitude = lat,
        longitude = lon,
        laneWidthCm = 350,
        lanes = listOf(
            MapLane(
                id = 1,
                ingressApproach = 1,
                egressApproach = null,
                laneType = LaneType.Vehicle,
                ingress = true,
                egress = false,
                nodes = listOf(
                    LaneNode(xCm = 0, yCm = -15_000),
                    LaneNode(xCm = 0, yCm = -8_000),
                    LaneNode(xCm = 0, yCm = 0, stopLine = true),
                ),
                connections = listOf(
                    LaneConnection(
                        laneId = 2,
                        signalGroup = if (withSignalGroup) signalGroup else null,
                        connectionId = 1,
                        remoteIntersection = null,
                    ),
                ),
            ),
            MapLane(
                id = 2,
                ingressApproach = null,
                egressApproach = 2,
                laneType = LaneType.Vehicle,
                ingress = false,
                egress = true,
                nodes = listOf(LaneNode(0, 0), LaneNode(0, 12_000)),
                connections = emptyList(),
            ),
        ),
        receivedAtMs = now,
        speedLimitsKph = listOf(50),
    )

    private fun spat() = SpatIntersection(
        key = key,
        revision = 1,
        moy = 1_000,
        timestampMs = 0,
        movements = listOf(
            SignalMovement(
                signalGroup = signalGroup,
                events = listOf(SignalEvent(MovementPhaseState.ProtectedAllowed, 200, 200, 220, 12)),
                connectionIds = emptyList(),
            ),
        ),
        receivedAtMs = now,
    )

    private fun position(
        northMeters: Double = -100.0,
        eastMeters: Double = 0.0,
        heading: Float? = 0f,
        accuracy: Float = 4f,
        speed: Float = 10f,
    ): VehiclePosition {
        val (latE7, lonE7) = GeoMath.offsetE7(
            lat,
            lon,
            (eastMeters * 100).toInt(),
            (northMeters * 100).toInt(),
        )
        return VehiclePosition(latE7, lonE7, accuracy, speed, heading, now)
    }

    @Test
    fun matchesInboundLaneStopLineAndSignalGroup() {
        val result = IntersectionMatcher.match(position(), listOf(map()), mapOf(key to spat()))
        val match = result!!
        assertEquals(key, match.lane.intersectionKey)
        assertEquals(1, match.lane.laneId)
        assertEquals(signalGroup, match.lane.signalGroup)
        assertEquals(SignalGroupSource.LaneConnection, match.lane.signalGroupSource)
        assertTrue(match.lane.distanceToStopLineM in 95.0..105.0)
        assertTrue(match.lane.confidence >= IntersectionMatcher.MIN_GLOSA_CONFIDENCE)
        assertEquals("Test junction", match.lane.intersectionName)
    }

    @Test
    fun stoppedVehicleWithoutBearingIsStillMatched() {
        val result = IntersectionMatcher.match(
            position(heading = null, speed = 0f),
            listOf(map()),
            mapOf(key to spat()),
        )
        assertEquals(1, result!!.lane.laneId)
    }

    @Test
    fun positionFarLateralFromTheLaneIsRejected() {
        val result = IntersectionMatcher.match(
            position(eastMeters = 25.0),
            listOf(map()),
            mapOf(key to spat()),
        )
        assertNull(result)
    }

    @Test
    fun positionPastTheStopLineIsRejected() {
        val result = IntersectionMatcher.match(
            position(northMeters = 10.0),
            listOf(map()),
            mapOf(key to spat()),
        )
        assertNull(result)
    }

    @Test
    fun intersectionOutsideTheSearchRadiusIsIgnored() {
        val result = IntersectionMatcher.match(
            position(northMeters = -500.0),
            listOf(map()),
            mapOf(key to spat()),
        )
        assertNull(result)
    }

    @Test
    fun laneWithoutASignalGroupHasLowerConfidence() {
        val withGroup = IntersectionMatcher.match(position(), listOf(map(true)), mapOf(key to spat()))!!
        val withoutGroup = IntersectionMatcher.match(position(), listOf(map(false)), mapOf(key to spat()))!!
        assertNull(withoutGroup.lane.signalGroup)
        assertEquals(SignalGroupSource.None, withoutGroup.lane.signalGroupSource)
        assertTrue(withoutGroup.lane.confidence < withGroup.lane.confidence)
    }

    @Test
    fun wrongWayHeadingStillGivesALowConfidenceOrNoMatch() {
        val result = IntersectionMatcher.match(
            position(heading = 90f),
            listOf(map()),
            mapOf(key to spat()),
        )
        assertTrue(result == null || result.lane.confidence < 0.8)
    }
}
