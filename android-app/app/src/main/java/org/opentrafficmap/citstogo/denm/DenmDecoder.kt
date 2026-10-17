package org.opentrafficmap.citstogo.denm

import org.opentrafficmap.citstogo.intersection.IntersectionDecodeException
import org.opentrafficmap.citstogo.intersection.ItsPacket
import org.opentrafficmap.citstogo.intersection.UperBitReader

enum class DenmTermination {
    Cancellation,
    Negation,
}

/** An offset from a previous position in 0.1 microdegrees; either value can be [UNAVAILABLE]. */
data class DeltaPosition(val latitude: Int, val longitude: Int) {
    companion object {
        const val UNAVAILABLE = 131_072
    }
}

/** The information quality and event type at the start of the SituationContainer, and the event zone. */
data class DenmSituation(
    val informationQuality: Int,
    val causeCode: Int,
    val subCauseCode: Int,
    /**
     * The eventZone, eventHistory in DENM v1.3.1: the first point is an offset from the event position, every further
     * point one from the point before. Empty without one.
     */
    val eventZone: List<DeltaPosition> = emptyList(),
)

/**
 * A received DENM in transmitted units, with times as TimestampIts. [stationId] is the sender in the ITS PDU header,
 * which can differ from the [originatingStationId] of the actionId, for example for road side units. Distance and
 * traffic direction are the ENUMERATED values. The event position is `null` where unavailable.
 */
data class ReceivedDenm(
    val stationId: Long,
    val originatingStationId: Long,
    val sequenceNumber: Int,
    val detectionTimeIts: Long,
    val referenceTimeIts: Long,
    val termination: DenmTermination?,
    val latitude: Int?,
    val longitude: Int?,
    val awarenessDistance: Int?,
    val awarenessTrafficDirection: Int?,
    val validityDurationSeconds: Int,
    val transmissionIntervalMs: Int?,
    val stationType: Int,
    /** `null` without a SituationContainer. */
    val situation: DenmSituation?,
    /**
     * The traces of the LocationContainer, detectionZonesToEventPosition in TS 103 831: paths towards the event, each
     * like the [DenmSituation.eventZone] a chain of offsets from the event position. Empty without a LocationContainer.
     */
    val traces: List<List<DeltaPosition>> = emptyList(),
)

object DenmDecoder {
    const val MESSAGE_ID_DENM = 1
    private const val DEFAULT_VALIDITY_DURATION_SECONDS = 600

    private const val LATITUDE_UNAVAILABLE = 900_000_001
    private const val LONGITUDE_UNAVAILABLE = 1_800_000_001
    private const val TIMESTAMP_ITS_MAXIMUM = 4_398_046_511_103L

    /**
     * Decodes the ManagementContainer of a protocolVersion 2 DENM, the event type and zone of its SituationContainer
     * and the traces of its LocationContainer. Returns `null` for other messages and versions.
     */
    fun decode(packet: ItsPacket): ReceivedDenm? {
        if (packet.protocolVersion != 2 || packet.messageId != MESSAGE_ID_DENM) return null
        val reader = UperBitReader(packet.payload, packet.bodyOffset)
        return reader.denm(packet.stationId)
    }

    private fun UperBitReader.denm(stationId: Long): ReceivedDenm {
        val hasSituation = bit()
        val hasLocation = bit()
        skipBits(1) // Presence of the alacarte container, which follows the others.
        val managementExtended = bit()
        val hasTermination = bit()
        val hasAwarenessDistance = bit()
        val hasAwarenessTrafficDirection = bit()
        val hasValidityDuration = bit()
        val hasTransmissionInterval = bit()
        val originatingStationId = constrained(0, 0xffff_ffffL)
        val sequenceNumber = constrained(0, 65_535).toInt()
        val detectionTime = constrained(0, TIMESTAMP_ITS_MAXIMUM)
        val referenceTime = constrained(0, TIMESTAMP_ITS_MAXIMUM)
        val termination = if (hasTermination) termination() else null
        val (latitude, longitude) = referencePosition()
        val awarenessDistance = if (hasAwarenessDistance) constrained(0, 7).toInt() else null
        val awarenessTrafficDirection = if (hasAwarenessTrafficDirection) constrained(0, 3).toInt() else null
        val validityDuration =
            if (hasValidityDuration) inRange(0, 86_400).toInt() else DEFAULT_VALIDITY_DURATION_SECONDS
        val transmissionInterval = if (hasTransmissionInterval) inRange(1, 10_000).toInt() else null
        val stationType = constrained(0, 255).toInt()
        if (managementExtended) skipExtensionAdditions()
        val situation = if (hasSituation) situation() else null
        val traces = if (hasLocation) traces() else emptyList()
        return ReceivedDenm(
            stationId = stationId,
            originatingStationId = originatingStationId,
            sequenceNumber = sequenceNumber,
            detectionTimeIts = detectionTime,
            referenceTimeIts = referenceTime,
            termination = termination,
            latitude = latitude.takeUnless { it == LATITUDE_UNAVAILABLE },
            longitude = longitude.takeUnless { it == LONGITUDE_UNAVAILABLE },
            awarenessDistance = awarenessDistance,
            awarenessTrafficDirection = awarenessTrafficDirection,
            validityDurationSeconds = validityDuration,
            transmissionIntervalMs = transmissionInterval,
            stationType = stationType,
            situation = situation,
            traces = traces,
        )
    }

    private fun UperBitReader.termination(): DenmTermination =
        if (bit()) DenmTermination.Negation else DenmTermination.Cancellation

    private fun UperBitReader.referencePosition(): Pair<Int, Int> {
        val latitude = inRange(-900_000_000, 900_000_001).toInt()
        val longitude = inRange(-1_800_000_000, 1_800_000_001).toInt()
        constrained(0, 4_095) // semiMajorConfidence
        constrained(0, 4_095) // semiMinorConfidence
        constrained(0, 3_601) // semiMajorOrientation
        constrained(-100_000, 800_001) // altitudeValue
        constrained(0, 15) // altitudeConfidence
        return latitude to longitude
    }

    private fun UperBitReader.situation(): DenmSituation {
        val extended = bit()
        val hasLinkedCause = bit()
        val hasEventZone = bit()
        val informationQuality = constrained(0, 7).toInt()
        val (causeCode, subCauseCode) = causeCode()
        if (hasLinkedCause) causeCode()
        val eventZone = if (hasEventZone) List(inRange(1, 23).toInt()) { eventPoint() } else emptyList()
        if (extended) skipExtensionAdditions()
        return DenmSituation(informationQuality, causeCode, subCauseCode, eventZone)
    }

    /**
     * A CauseCode of DENM v1.3.1: extension bit, causeCode and subCauseCode. The CauseCodeChoice of the CDD v2
     * CauseCodeV2 is encoded the same way, its choice index being the cause code.
     */
    private fun UperBitReader.causeCode(): Pair<Int, Int> {
        val extended = bit()
        val causeCode = constrained(0, 255).toInt()
        val subCauseCode = constrained(0, 255).toInt()
        if (extended) skipExtensionAdditions()
        return causeCode to subCauseCode
    }

    private fun UperBitReader.eventPoint(): DeltaPosition {
        val hasDeltaTime = bit()
        val position = deltaPosition()
        if (hasDeltaTime) pathDeltaTime()
        constrained(0, 7) // informationQuality
        return position
    }

    private fun UperBitReader.traces(): List<List<DeltaPosition>> {
        val extended = bit()
        val hasEventSpeed = bit()
        val hasEventPositionHeading = bit()
        val hasRoadType = bit()
        if (hasEventSpeed) {
            constrained(0, 16_383) // speedValue
            constrained(1, 127) // speedConfidence
        }
        if (hasEventPositionHeading) {
            constrained(0, 3_601) // Heading or Wgs84Angle value
            constrained(1, 127) // and confidence
        }
        val traces = List(inRange(1, 7).toInt()) {
            List(inRange(0, 40).toInt()) { pathPoint() }
        }
        if (hasRoadType) constrained(0, 3)
        if (extended) skipExtensionAdditions()
        return traces
    }

    private fun UperBitReader.pathPoint(): DeltaPosition {
        val hasDeltaTime = bit()
        val position = deltaPosition()
        if (hasDeltaTime) pathDeltaTime()
        return position
    }

    private fun UperBitReader.deltaPosition(): DeltaPosition {
        val latitude = constrained(-131_071, 131_072).toInt()
        val longitude = constrained(-131_071, 131_072).toInt()
        constrained(-12_700, 12_800) // deltaAltitude
        return DeltaPosition(latitude, longitude)
    }

    /** PathDeltaTime is extensible: a value outside 1..65535 follows as an unconstrained integer. */
    private fun UperBitReader.pathDeltaTime() {
        if (bit()) skipOctets(lengthDeterminant()) else constrained(1, 65_535)
    }

    private fun UperBitReader.inRange(minimum: Long, maximum: Long): Long {
        val value = constrained(minimum, maximum)
        if (value > maximum) throw IntersectionDecodeException("DENM value $value is outside $minimum..$maximum")
        return value
    }
}
