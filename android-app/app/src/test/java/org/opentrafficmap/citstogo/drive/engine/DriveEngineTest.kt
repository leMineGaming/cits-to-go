package org.opentrafficmap.citstogo.drive.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.denm.DenmSituation
import org.opentrafficmap.citstogo.denm.ReceivedDenm
import org.opentrafficmap.citstogo.drive.config.DriveSettings
import org.opentrafficmap.citstogo.drive.config.DriveSettingsRepository
import org.opentrafficmap.citstogo.drive.config.InMemoryKeyValueStore
import org.opentrafficmap.citstogo.drive.model.DenmCategory
import org.opentrafficmap.citstogo.drive.position.GeoMath
import org.opentrafficmap.citstogo.drive.position.VehiclePosition
import org.opentrafficmap.citstogo.drive.profile.DriverProfile
import org.opentrafficmap.citstogo.drive.profile.DriverProfileRepository
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
import org.opentrafficmap.citstogo.protocol.ItsTime

class DriveEngineTest {
    private val now = 1_700_000_000_000L
    private val key = IntersectionKey(1, 42)
    private val lat = 482_082_000
    private val lon = 163_738_000

    private fun engine(): DriveEngine {
        val settings = DriveSettingsRepository(InMemoryKeyValueStore())
        settings.replace(DriveSettings(defaultSpeedLimitKph = 50, staleDataSeconds = 3))
        return DriveEngine(settings, DriverProfileRepository(InMemoryKeyValueStore()))
    }

    private fun map(laneSpeedLimitsKph: List<Int> = emptyList()) = MapIntersection(
        key = key,
        name = "Engine junction",
        revision = 1,
        latitude = lat,
        longitude = lon,
        laneWidthCm = 350,
        lanes = listOf(
            MapLane(
                id = 5,
                ingressApproach = 1,
                egressApproach = null,
                laneType = LaneType.Vehicle,
                ingress = true,
                egress = false,
                nodes = listOf(
                    LaneNode(0, -15_000),
                    LaneNode(0, -8_000),
                    LaneNode(0, 0, stopLine = true),
                ),
                connections = listOf(LaneConnection(6, 3, 1, null)),
                speedLimitsKph = laneSpeedLimitsKph,
            ),
        ),
        receivedAtMs = now,
        speedLimitsKph = listOf(50),
    )

    private fun spat(): SpatIntersection {
        val minute = ((now / 60_000L) % 60).toInt()
        val millis = (now % 60_000L).toInt()
        val moy = 600 + minute
        val messageTenths = (moy % 60) * 600 + millis / 100
        val target = (messageTenths + 200).mod(36_000)
        return SpatIntersection(
            key = key,
            revision = 1,
            moy = moy,
            timestampMs = millis,
            movements = listOf(
                SignalMovement(
                    signalGroup = 3,
                    events = listOf(
                        SignalEvent(MovementPhaseState.ProtectedAllowed, target, target, (target + 20).mod(36_000), 12),
                    ),
                    connectionIds = emptyList(),
                ),
            ),
            receivedAtMs = now,
        )
    }

    private fun position(northMeters: Double = -100.0) = VehiclePosition(
        latitudeE7 = GeoMath.offsetE7(lat, lon, 0, (northMeters * 100).toInt()).first,
        longitudeE7 = GeoMath.offsetE7(lat, lon, 0, (northMeters * 100).toInt()).second,
        accuracyM = 4f,
        speedMetersPerSecond = 10f,
        headingDegrees = 0f,
        timestampMs = now,
    )

    @Test
    fun simulationProducesSignalAndGlosa() {
        val engine = engine()
        engine.applySimulatedMap(map(), now)
        engine.applySimulatedSpat(spat(), now)
        engine.onPosition(position())
        engine.recomputeNow(now)
        val state = engine.current()
        assertTrue(state.simulated)
        assertNotNull(state.intersection)
        assertEquals(5, state.intersection!!.lane.laneId)
        assertNotNull(state.signal)
        assertTrue(state.signal!!.isGreen)
        assertNotNull(state.glosa)
        assertNull(state.glosaSuppressedBy)
        assertTrue(state.glosa!!.targetSpeedKph!! <= 50.0)
    }

    @Test
    fun laneSpecificSpeedLimitCapsGlosa() {
        val engine = engine()
        engine.applySimulatedMap(map(laneSpeedLimitsKph = listOf(30)), now)
        engine.applySimulatedSpat(spat(), now)
        engine.onPosition(position())
        engine.recomputeNow(now)
        val glosa = engine.current().glosa
        assertNotNull(glosa)
        assertTrue("target ${glosa!!.targetSpeedKph} must respect the lane limit", glosa.targetSpeedKph!! <= 30.0)
    }

    @Test
    fun criticalHazardAheadSuppressesGlosa() {
        val engine = engine()
        engine.applySimulatedMap(map(), now)
        engine.applySimulatedSpat(spat(), now)
        engine.onPosition(position())
        engine.recomputeNow(now)
        assertNotNull(engine.current().glosa)
        val (eventLat, eventLon) = GeoMath.offsetE7(lat, lon, 0, 15_000)
        engine.applySimulatedDenm(
            ReceivedDenm(
                stationId = 77,
                originatingStationId = 77,
                sequenceNumber = 3,
                detectionTimeIts = ItsTime.fromUnixMs(now),
                referenceTimeIts = ItsTime.fromUnixMs(now),
                termination = null,
                latitude = eventLat,
                longitude = eventLon,
                awarenessDistance = 3,
                awarenessTrafficDirection = 2,
                validityDurationSeconds = 120,
                transmissionIntervalMs = 1_000,
                stationType = 5,
                situation = DenmSituation(1, 2, 1),
            ),
            now,
        )
        val state = engine.current()
        assertEquals(1, state.activeAlertCount)
        assertEquals(DenmCategory.Accident, state.primaryAlert!!.category)
        assertEquals("Critical hazard ahead takes priority", state.glosaSuppressedBy)
        assertNull(state.glosa)
    }

    @Test
    fun staleSignalDataSuppressesGlosa() {
        val engine = engine()
        engine.applySimulatedMap(map(), now)
        engine.applySimulatedSpat(spat(), now)
        engine.onPosition(position())
        engine.recomputeNow(now + 30_000)
        val state = engine.current()
        assertTrue(state.dataStale)
        assertNull(state.glosa)
        assertEquals("C-ITS data is stale", state.glosaSuppressedBy)
    }

    @Test
    fun oldPositionFixIsNotUsedForMatching() {
        val engine = engine()
        engine.applySimulatedMap(map(), now)
        engine.applySimulatedSpat(spat(), now)
        engine.onPosition(position())
        engine.recomputeNow(now + DriveEngine.POSITION_MAX_AGE_MS + 1_000)
        assertNull(engine.current().intersection)
    }

    @Test
    fun diagnosticsCountFramesAndMalformedData() {
        val engine = engine()
        engine.onFrame(byteArrayOf(1, 2, 3), now)
        val state = engine.current()
        assertEquals(1, state.diagnostics.framesReceived)
        assertEquals(1, state.diagnostics.malformedFrames)
        assertTrue(state.diagnostics.records.isNotEmpty())
    }

    @Test
    fun profileChangesAreVisibleInTheSnapshot() {
        val settings = DriveSettingsRepository(InMemoryKeyValueStore())
        val profiles = DriverProfileRepository(InMemoryKeyValueStore())
        val engine = DriveEngine(settings, profiles)
        profiles.update(profiles.current().selected.copy(maxSpeedCapKph = 42, name = "Changed"))
        engine.recomputeNow(now)
        assertEquals("Changed", engine.current().profile.name)
        assertEquals(42, engine.current().profile.maxSpeedCapKph)
    }

    @Test
    fun dismissingAlertRemovesItFromTheDashboard() {
        val engine = engine()
        engine.applySimulatedMap(map(), now)
        engine.applySimulatedSpat(spat(), now)
        engine.onPosition(position())
        val (eventLat, eventLon) = GeoMath.offsetE7(lat, lon, 0, 15_000)
        engine.applySimulatedDenm(
            ReceivedDenm(
                stationId = 88,
                originatingStationId = 88,
                sequenceNumber = 1,
                detectionTimeIts = ItsTime.fromUnixMs(now),
                referenceTimeIts = ItsTime.fromUnixMs(now),
                termination = null,
                latitude = eventLat,
                longitude = eventLon,
                awarenessDistance = 3,
                awarenessTrafficDirection = 2,
                validityDurationSeconds = 120,
                transmissionIntervalMs = 1_000,
                stationType = 5,
                situation = DenmSituation(1, 2, 1),
            ),
            now,
        )
        val alert = engine.current().primaryAlert!!
        engine.dismissAlert(alert.actionId)
        engine.recomputeNow(now)
        assertFalse(engine.current().alerts.any { it.actionId == alert.actionId })
    }
}
