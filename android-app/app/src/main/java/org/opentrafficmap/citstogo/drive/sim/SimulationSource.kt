package org.opentrafficmap.citstogo.drive.sim

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opentrafficmap.citstogo.denm.DenmSituation
import org.opentrafficmap.citstogo.denm.ReceivedDenm
import org.opentrafficmap.citstogo.drive.engine.DriveEngine
import org.opentrafficmap.citstogo.drive.position.GeoMath
import org.opentrafficmap.citstogo.drive.position.VehiclePosition
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

/**
 * A deterministic, offline C-ITS source for trying the application without roadside hardware.
 *
 * The simulation produces the same normalized MAPEM/SPATEM/DENM structures as the real decoders,
 * including a signal cycle and a roadworks DENM. [DriveEngine] marks the resulting snapshot as
 * [DriveSnapshot.simulated], so the UI can label it unmistakably and never present it as live data.
 */
class SimulationSource(
    private val engine: DriveEngine,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var job: Job? = null
    @Volatile private var running = false
    private var distanceToStopLineM = 150.0
    private var lastTickMs = 0L
    private var lastDenmMs = 0L

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        engine.setSimulationEnabled(true)
        lastTickMs = clock()
        lastDenmMs = 0L
        emitMap(clock())
        job = scope.launch {
            while (isActive && running) {
                val now = clock()
                val deltaSeconds = ((now - lastTickMs).coerceIn(0L, 2_000L)) / 1_000.0
                lastTickMs = now
                updateVehicle(deltaSeconds, now)
                emitSpat(now)
                if (now - lastDenmMs >= 10_000L) {
                    lastDenmMs = now
                    emitRoadworks(now)
                }
                delay(250L)
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        engine.clearSimulation()
    }

    private fun updateVehicle(deltaSeconds: Double, now: Long) {
        distanceToStopLineM -= SPEED_MPS * deltaSeconds
        if (distanceToStopLineM < 1.5) distanceToStopLineM = 260.0
        val (latitude, longitude) = GeoMath.offsetE7(
            REF_LATITUDE_E7,
            REF_LONGITUDE_E7,
            eastCm = 0,
            northCm = -(distanceToStopLineM * 100).toInt(),
        )
        engine.onPosition(
            VehiclePosition(
                latitudeE7 = latitude,
                longitudeE7 = longitude,
                accuracyM = 4.5f,
                speedMetersPerSecond = SPEED_MPS.toFloat(),
                headingDegrees = 0f,
                timestampMs = now,
            ),
        )
    }

    private fun emitMap(now: Long) {
        val key = IntersectionKey(region = 1, id = 9001)
        val northbound = MapLane(
            id = 1,
            ingressApproach = 1,
            egressApproach = null,
            laneType = LaneType.Vehicle,
            ingress = true,
            egress = false,
            nodes = listOf(
                LaneNode(xCm = -175, yCm = -15000),
                LaneNode(xCm = -175, yCm = -8000),
                LaneNode(xCm = -175, yCm = 0, stopLine = true),
            ),
            connections = listOf(LaneConnection(laneId = 2, signalGroup = 1, connectionId = 1, remoteIntersection = null)),
        )
        val northboundOut = MapLane(
            id = 2,
            ingressApproach = null,
            egressApproach = 2,
            laneType = LaneType.Vehicle,
            ingress = false,
            egress = true,
            nodes = listOf(
                LaneNode(xCm = -175, yCm = 0),
                LaneNode(xCm = -175, yCm = 12000),
            ),
            connections = emptyList(),
        )
        val eastbound = MapLane(
            id = 3,
            ingressApproach = 3,
            egressApproach = null,
            laneType = LaneType.Vehicle,
            ingress = true,
            egress = false,
            nodes = listOf(
                LaneNode(xCm = -12000, yCm = 350),
                LaneNode(xCm = -6000, yCm = 350),
                LaneNode(xCm = 0, yCm = 350, stopLine = true),
            ),
            connections = listOf(LaneConnection(laneId = 4, signalGroup = 2, connectionId = 2, remoteIntersection = null)),
        )
        val eastboundOut = MapLane(
            id = 4,
            ingressApproach = null,
            egressApproach = 4,
            laneType = LaneType.Vehicle,
            ingress = false,
            egress = true,
            nodes = listOf(
                LaneNode(xCm = 0, yCm = 350),
                LaneNode(xCm = 12000, yCm = 350),
            ),
            connections = emptyList(),
        )
        val map = MapIntersection(
            key = key,
            name = "Simulated Junction",
            revision = 1,
            latitude = REF_LATITUDE_E7,
            longitude = REF_LONGITUDE_E7,
            laneWidthCm = 350,
            lanes = listOf(northbound, northboundOut, eastbound, eastboundOut),
            receivedAtMs = now,
            speedLimitsKph = listOf(50),
        )
        engine.applySimulatedMap(map, now)
    }

    private fun emitSpat(now: Long) {
        val key = IntersectionKey(region = 1, id = 9001)
        val cycleMs = CYCLE_SECONDS * 1_000L
        val inCycleMs = now % cycleMs
        val green = inCycleMs < GREEN_SECONDS * 1_000L
        val remainingSeconds = if (green) {
            GREEN_SECONDS - inCycleMs / 1_000.0
        } else {
            CYCLE_SECONDS - inCycleMs / 1_000.0
        }.coerceAtLeast(0.1)
        val (moy, timestamp) = timeMarkBasis(now)
        val messageTenths = (moy % 60) * 600 + timestamp / 100
        val targetTenths = (messageTenths + (remainingSeconds * 10).toInt()).mod(36_000)
        val event = SignalEvent(
            state = if (green) MovementPhaseState.ProtectedAllowed else MovementPhaseState.StopAndRemain,
            minEndTime = targetTenths,
            likelyTime = targetTenths,
            maxEndTime = (targetTenths + 20).mod(36_000),
            confidence = 12,
        )
        val otherGroupEvent = SignalEvent(
            state = if (green) MovementPhaseState.StopAndRemain else MovementPhaseState.ProtectedAllowed,
            minEndTime = (targetTenths + 1).mod(36_000),
            likelyTime = (targetTenths + 1).mod(36_000),
            maxEndTime = (targetTenths + 20).mod(36_000),
            confidence = 10,
        )
        val spat = SpatIntersection(
            key = key,
            revision = 1,
            moy = moy,
            timestampMs = timestamp,
            movements = listOf(
                SignalMovement(signalGroup = 1, events = listOf(event), connectionIds = emptyList()),
                SignalMovement(signalGroup = 2, events = listOf(otherGroupEvent), connectionIds = emptyList()),
            ),
            receivedAtMs = now,
        )
        engine.applySimulatedSpat(spat, now)
    }

    private fun emitRoadworks(now: Long) {
        val (latitude, longitude) = GeoMath.offsetE7(
            REF_LATITUDE_E7,
            REF_LONGITUDE_E7,
            eastCm = 150,
            northCm = 9000,
        )
        val detection = now
        val denm = ReceivedDenm(
            stationId = 42,
            originatingStationId = 9001,
            sequenceNumber = 7,
            detectionTimeIts = ItsTime.fromUnixMs(detection),
            referenceTimeIts = ItsTime.fromUnixMs(now),
            termination = null,
            latitude = latitude,
            longitude = longitude,
            awarenessDistance = 3,
            awarenessTrafficDirection = 2,
            validityDurationSeconds = 300,
            transmissionIntervalMs = 1_000,
            stationType = 15,
            situation = DenmSituation(
                informationQuality = 1,
                causeCode = 3,
                subCauseCode = 1,
            ),
        )
        engine.applySimulatedDenm(denm, now)
    }

    private fun timeMarkBasis(now: Long): Pair<Int, Int> {
        val minuteOfDay = (now / 60_000L).toInt()
        val minuteOfHour = minuteOfDay % 60
        val minuteOfYear = 1_000 + (minuteOfDay % 525_600)
        val millisecondsIntoMinute = (now % 60_000L).toInt()
        // Keep minuteOfYear and minute-hour consistent: moy % 60 must equal minuteOfHour.
        val moy = minuteOfYear - (minuteOfYear % 60) + minuteOfHour
        return moy to millisecondsIntoMinute
    }

    companion object {
        const val REF_LATITUDE_E7 = 482_082_000
        const val REF_LONGITUDE_E7 = 163_738_000
        const val SPEED_MPS = 13.9 // 50 km/h
        const val GREEN_SECONDS = 20.0
        const val CYCLE_SECONDS = 40.0
    }
}
