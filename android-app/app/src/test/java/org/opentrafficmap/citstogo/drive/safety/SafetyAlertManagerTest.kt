package org.opentrafficmap.citstogo.drive.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.denm.DenmSituation
import org.opentrafficmap.citstogo.denm.DenmTermination
import org.opentrafficmap.citstogo.denm.ReceivedDenm
import org.opentrafficmap.citstogo.drive.model.DenmCategory
import org.opentrafficmap.citstogo.drive.position.GeoMath
import org.opentrafficmap.citstogo.drive.position.VehiclePosition
import org.opentrafficmap.citstogo.drive.profile.AlertPriority
import org.opentrafficmap.citstogo.drive.profile.DriverProfile
import org.opentrafficmap.citstogo.protocol.ItsTime

class SafetyAlertManagerTest {
    private val now = 1_700_000_000_000L
    private val referenceLat = 482_082_000
    private val referenceLon = 163_738_000
    private val position = VehiclePosition(
        latitudeE7 = referenceLat,
        longitudeE7 = referenceLon,
        accuracyM = 4f,
        speedMetersPerSecond = 10f,
        headingDegrees = 0f,
        timestampMs = now,
    )

    private fun denm(
        sequence: Int = 1,
        cause: Int = 2,
        subCause: Int = 1,
        validitySeconds: Int = 60,
        detectionAtMs: Long = now - 1_000,
        termination: DenmTermination? = null,
        offsetNorthCm: Int = 10_000,
        informationQuality: Int = 1,
        trafficDirection: Int = 2,
        awarenessDistance: Int = 3,
    ): ReceivedDenm {
        val (latitude, longitude) = GeoMath.offsetE7(referenceLat, referenceLon, 0, offsetNorthCm)
        return ReceivedDenm(
            stationId = 42,
            originatingStationId = 9001,
            sequenceNumber = sequence,
            detectionTimeIts = ItsTime.fromUnixMs(detectionAtMs),
            referenceTimeIts = ItsTime.fromUnixMs(detectionAtMs),
            termination = termination,
            latitude = latitude,
            longitude = longitude,
            awarenessDistance = awarenessDistance,
            awarenessTrafficDirection = trafficDirection,
            validityDurationSeconds = validitySeconds,
            transmissionIntervalMs = 1_000,
            stationType = 15,
            situation = if (termination == null) {
                DenmSituation(informationQuality = informationQuality, causeCode = cause, subCauseCode = subCause)
            } else {
                null
            },
        )
    }

    @Test
    fun newEventIsClassifiedPositionedAndNotified() {
        val manager = SafetyAlertManager()
        val result = manager.accept(denm(), now, position, DriverProfile.default(), now)
        val event = assertNotNull(result.event).let { result.event!! }
        assertEquals(SafetyEventStatus.New, event.status)
        assertEquals(DenmCategory.Accident, event.category)
        assertEquals(AlertPriority.Critical, event.priority)
        assertEquals(RelativeDirection.Ahead, event.relativeDirection)
        assertTrue(event.distanceM!! in 90.0..110.0)
        assertNotNull(result.notification)
        assertTrue(event.active)
    }

    @Test
    fun repeatedDenmIsDeduplicated() {
        val manager = SafetyAlertManager()
        manager.accept(denm(), now, position, DriverProfile.default(), now)
        val repeated = manager.accept(denm(), now + 1_000, position, DriverProfile.default(), now + 1_000)
        assertEquals("Repeated event", repeated.ignoredReason)
        assertNull(repeated.notification)
        assertEquals(1, manager.activeEvents(position, DriverProfile.default(), now + 1_000).size)
        assertEquals(1, manager.history().size)
    }

    @Test
    fun updatedDenmReplacesTheEventWithoutDuplicatingIt() {
        val manager = SafetyAlertManager()
        manager.accept(denm(), now, position, DriverProfile.default(), now)
        val updated = manager.accept(
            denm(detectionAtMs = now - 500),
            now + 2_000,
            position,
            DriverProfile.default(),
            now + 2_000,
        )
        assertEquals(SafetyEventStatus.Updated, updated.event!!.status)
        assertEquals(1, manager.activeEvents(position, DriverProfile.default(), now + 2_000).size)
    }

    @Test
    fun cancellationHidesTheEventAndIsRecordedInHistory() {
        val manager = SafetyAlertManager()
        manager.accept(denm(), now, position, DriverProfile.default(), now)
        val cancelled = manager.accept(
            denm(detectionAtMs = now + 1_000, termination = DenmTermination.Cancellation),
            now + 1_000,
            position,
            DriverProfile.default(),
            now + 1_000,
        )
        assertEquals(SafetyEventStatus.Cancelled, cancelled.event!!.status)
        assertFalse(cancelled.event!!.active)
        assertTrue(manager.activeEvents(position, DriverProfile.default(), now + 1_000).isEmpty())
        assertTrue(manager.history().any { it.status == SafetyEventStatus.Cancelled })
    }

    @Test
    fun negationIsDistinguishedFromCancellation() {
        val manager = SafetyAlertManager()
        manager.accept(denm(), now, position, DriverProfile.default(), now)
        val negated = manager.accept(
            denm(detectionAtMs = now + 1_000, termination = DenmTermination.Negation),
            now + 1_000,
            position,
            DriverProfile.default(),
            now + 1_000,
        )
        assertEquals(SafetyEventStatus.Negated, negated.event!!.status)
        assertFalse(negated.event!!.active)
    }

    @Test
    fun terminationOfAnUnknownEventIsIgnored() {
        val manager = SafetyAlertManager()
        val result = manager.accept(
            denm(termination = DenmTermination.Cancellation),
            now,
            position,
            DriverProfile.default(),
            now,
        )
        assertNull(result.event)
        assertEquals("Termination for an unknown event", result.ignoredReason)
    }

    @Test
    fun eventExpiresWithItsValidity() {
        val manager = SafetyAlertManager()
        manager.accept(denm(validitySeconds = 10), now, position, DriverProfile.default(), now)
        assertTrue(manager.activeEvents(position, DriverProfile.default(), now + 20_000).isEmpty())
        assertTrue(manager.history().any { it.status == SafetyEventStatus.Expired })
    }

    @Test
    fun disabledCategoryStillAppearsButDoesNotNotify() {
        val manager = SafetyAlertManager()
        val profile = DriverProfile.default().copy(
            enabledCategories = DenmCategory.entries.toSet() - DenmCategory.Accident,
        )
        val result = manager.accept(denm(), now, position, profile, now)
        assertNotNull(result.event)
        assertNull(result.notification)
    }

    @Test
    fun eventBehindTheVehicleDoesNotNotifyAnAccident() {
        val manager = SafetyAlertManager()
        val result = manager.accept(
            denm(offsetNorthCm = -10_000),
            now,
            position,
            DriverProfile.default(),
            now,
        )
        assertEquals(RelativeDirection.Behind, result.event!!.relativeDirection)
        assertNull(result.notification)
    }

    @Test
    fun collisionRiskBehindTheVehicleStillNotifies() {
        val manager = SafetyAlertManager()
        val result = manager.accept(
            denm(cause = 97, subCause = 1, offsetNorthCm = -10_000),
            now,
            position,
            DriverProfile.default(),
            now,
        )
        assertEquals(DenmCategory.CollisionRisk, result.event!!.category)
        assertNotNull(result.notification)
    }

    @Test
    fun priorityClassificationUsesEtsiCauses() {
        assertEquals(
            AlertPriority.Critical,
            SafetyAlertManager.classifyPriority(DenmCategory.Accident, 2, 1),
        )
        assertEquals(
            AlertPriority.Critical,
            SafetyAlertManager.classifyPriority(DenmCategory.TrafficJam, 27, 1),
        )
        assertEquals(
            AlertPriority.Advisory,
            SafetyAlertManager.classifyPriority(DenmCategory.TrafficJam, 1, 1),
        )
        assertEquals(
            AlertPriority.Advisory,
            SafetyAlertManager.classifyPriority(DenmCategory.Roadworks, 3, 1),
        )
        assertEquals(
            AlertPriority.Informational,
            SafetyAlertManager.classifyPriority(DenmCategory.Other, 200, 1),
        )
    }

    @Test
    fun unknownCauseFallsBackToOtherAndKeepsRawCodes() {
        val manager = SafetyAlertManager()
        val result = manager.accept(
            denm(cause = 200, subCause = 5),
            now,
            position,
            DriverProfile.default(),
            now,
        )
        assertEquals(DenmCategory.Other, result.event!!.category)
        assertEquals(200, result.event!!.causeCode)
        assertEquals(5, result.event!!.subCauseCode)
    }

    @Test
    fun restoreHistoryDoesNotDuplicateVersions() {
        val manager = SafetyAlertManager()
        val event = manager.accept(denm(), now, position, DriverProfile.default(), now).event!!
        manager.restoreHistory(listOf(event, event))
        assertEquals(1, manager.history().count { it.actionId == event.actionId && it.version == event.version })
    }
}
