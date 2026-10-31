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
    /** Event speed in 0.01 m/s, when the LocationContainer carries one. */
    val eventSpeedCms: Int? = null,
    /** Event heading in 0.1 degrees, when the LocationContainer carries one. */
    val eventHeadingTenths: Int? = null,
    /** The sender included an à la carte container. */
    val alacartePresent: Boolean = false,
    /** Normalised optional à-la-carte contents, or a partial decode with [DenmAlacarte.parseError]. */
    val alacarte: DenmAlacarte? = null,
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
        val hasAlacarte = bit() // The à la carte container follows the others.
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
        val location = if (hasLocation) traces() else TraceData()
        val traces = location.traces
        val alacarte = if (hasAlacarte) {
            try {
                alacarte(
                    baseLatitude = latitude.takeUnless { it == LATITUDE_UNAVAILABLE },
                    baseLongitude = longitude.takeUnless { it == LONGITUDE_UNAVAILABLE },
                )
            } catch (e: Exception) {
                DenmAlacarte(parseError = e.message ?: e.javaClass.simpleName)
            }
        } else {
            null
        }
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
            eventSpeedCms = location.speedCms,
            eventHeadingTenths = location.headingTenths,
            alacartePresent = hasAlacarte,
            alacarte = alacarte,
            situation = situation,
            traces = traces,
        )
    }


    // ---------------------------------------------------------------------------------------------
    // AlacarteContainer (ETSI EN 302 637-3 V1.3.1 / TS 103 831 V2.1.1)
    // ---------------------------------------------------------------------------------------------

    private fun UperBitReader.alacarte(
        baseLatitude: Int?,
        baseLongitude: Int?,
    ): DenmAlacarte {
        val extended = bit()
        val hasLane = bit()
        val hasImpact = bit()
        val hasTemperature = bit()
        val hasRoadWorks = bit()
        val hasPositioning = bit()
        val hasStationary = bit()

        val lanePosition = if (hasLane) inRange(-1, 14).toInt() else null
        val impact = if (hasImpact) impactReduction() else null
        val temperature = if (hasTemperature) constrained(-60, 67).toInt() else null
        val roadWorks = if (hasRoadWorks) roadWorks(baseLatitude, baseLongitude) else null
        val positioning = if (hasPositioning) extensibleEnum(6) else null
        val stationary = if (hasStationary) stationaryVehicle() else null
        if (extended) skipExtensionAdditions()
        return DenmAlacarte(
            lanePosition = lanePosition,
            impactReduction = impact,
            externalTemperatureC = temperature,
            roadWorks = roadWorks,
            positioningSolution = positioning,
            stationaryVehicle = stationary,
        )
    }

    private fun UperBitReader.impactReduction(): DenmImpactReduction {
        fun length(cm: Int, outOfRange: Int): Double? =
            if (cm >= outOfRange) null else cm / 100.0
        fun decimetres(dm: Int, outOfRange: Int): Double? =
            if (dm >= outOfRange) null else dm / 10.0

        val heightLeft = length(constrained(1, 100).toInt(), 99)
        val heightRight = length(constrained(1, 100).toInt(), 99)
        val positionLeft = length(constrained(1, 127).toInt(), 126)
        val positionRight = length(constrained(1, 127).toInt(), 126)
        val pillars = List(extensibleSequenceLength(1, 3)) {
            decimetres(constrained(1, 30).toInt(), 29)
        }.filterNotNull()
        val centreOfMass = decimetres(constrained(1, 63).toInt(), 62)
        val wheelbase = decimetres(constrained(1, 127).toInt(), 126)
        val turningRadius = constrained(1, 255).toInt().let { raw ->
            if (raw >= 254) null else raw * 0.4
        }
        val frontAxle = decimetres(constrained(1, 20).toInt(), 19)
        val occupants = bitStringFixed(20)
        val mass = constrained(1, 1_024).toInt().let { raw ->
            if (raw >= 1_023) null else raw * 100
        }
        val requestResponse = constrained(0, 1).toInt()
        return DenmImpactReduction(
            heightLeftM = heightLeft,
            heightRightM = heightRight,
            positionLeftM = positionLeft,
            positionRightM = positionRight,
            pillarOffsetsM = pillars,
            centreOfMassM = centreOfMass,
            wheelbaseM = wheelbase,
            turningRadiusM = turningRadius,
            frontAxleM = frontAxle,
            occupantsBits = occupants,
            vehicleMassKg = mass,
            requestResponse = requestResponse,
        )
    }

    private fun UperBitReader.roadWorks(
        baseLatitude: Int?,
        baseLongitude: Int?,
    ): DenmRoadWorks {
        val hasLightBar = bit()
        val hasClosedLanes = bit()
        val hasRestriction = bit()
        val hasSpeedLimit = bit()
        val hasIncident = bit()
        val hasRecommendedPath = bit()
        val hasStartingPoint = bit()
        val hasTrafficRule = bit()
        val hasReferenceDenms = bit()

        val lightBar = if (hasLightBar) bitStringFixed(2) else null
        val closedLanes = if (hasClosedLanes) closedLanes() else null
        val restricted = if (hasRestriction) {
            List(extensibleSequenceLength(1, 3)) { constrained(0, 255).toInt() }
        } else {
            emptyList()
        }
        val speedLimit = if (hasSpeedLimit) constrained(1, 255).toInt() else null
        val incident = if (hasIncident) causeCode() else null
        val recommendedPath = if (hasRecommendedPath) {
            List(sequenceLength(1, 40)) { referencePosition() }
        } else {
            emptyList()
        }
        val startingPoint = if (hasStartingPoint) {
            deltaReferencePosition(baseLatitude, baseLongitude)
        } else {
            null
        }
        val trafficRule = if (hasTrafficRule) extensibleEnum(4) else null
        val references = if (hasReferenceDenms) {
            List(extensibleSequenceLength(1, 8)) { actionId() }
        } else {
            emptyList()
        }
        return DenmRoadWorks(
            lightBarSirenBits = lightBar,
            closedLanes = closedLanes,
            restrictedStationTypes = restricted,
            speedLimitKph = speedLimit,
            incidentCauseCode = incident?.first,
            incidentSubCauseCode = incident?.second,
            recommendedPath = recommendedPath,
            startingPointSpeedLimit = startingPoint,
            trafficFlowRule = trafficRule,
            referenceActionIds = references,
        )
    }

    private fun UperBitReader.closedLanes(): DenmClosedLanes {
        val extended = bit()
        val hasInner = bit()
        val hasOuter = bit()
        val hasDriving = bit()
        val inner = if (hasInner) constrained(0, 2).toInt() else null
        val outer = if (hasOuter) constrained(0, 2).toInt() else null
        val driving = if (hasDriving) bitString(1, 13) else null
        if (extended) skipExtensionAdditions()
        return DenmClosedLanes(inner, outer, driving)
    }

    private fun UperBitReader.stationaryVehicle(): DenmStationaryVehicle {
        val hasSince = bit()
        val hasCause = bit()
        val hasDangerousGoods = bit()
        val hasOccupants = bit()
        val hasVehicleIdentification = bit()
        val hasEnergyStorage = bit()

        val since = if (hasSince) constrained(0, 3).toInt() else null
        val cause = if (hasCause) causeCode() else null
        val dangerousGoods = if (hasDangerousGoods) dangerousGoods() else null
        val occupants = if (hasOccupants) constrained(0, 127).toInt() else null
        val identification = if (hasVehicleIdentification) vehicleIdentification() else null
        val energy = if (hasEnergyStorage) bitStringFixed(7) else null
        return DenmStationaryVehicle(
            stationarySince = since,
            causeCode = cause?.first,
            subCauseCode = cause?.second,
            dangerousGoodsType = dangerousGoods?.first,
            dangerousGoodsUnNumber = dangerousGoods?.second,
            numberOfOccupants = occupants,
            vehicleIdentification = identification,
            energyStorageBits = energy,
        )
    }

    private fun UperBitReader.dangerousGoods(): Pair<Int, Int> {
        val extended = bit()
        val hasEmergencyActionCode = bit()
        val hasPhoneNumber = bit()
        val hasCompanyName = bit()
        val type = constrained(0, 19).toInt()
        val unNumber = constrained(0, 9_999).toInt()
        constrained(0, 1) // elevatedTemperature
        constrained(0, 1) // tunnelsRestricted
        constrained(0, 1) // limitedQuantity
        if (hasEmergencyActionCode) ia5String(1, 24)
        if (hasPhoneNumber) numericString(1, 16)
        if (hasCompanyName) utf8String(1, 24)
        if (extended) skipExtensionAdditions()
        return type to unNumber
    }

    private fun UperBitReader.vehicleIdentification(): String? {
        val extended = bit()
        val hasWmi = bit()
        val hasVds = bit()
        val wmi = if (hasWmi) ia5String(1, 3) else ""
        val vds = if (hasVds) ia5String(6, 6) else ""
        if (extended) skipExtensionAdditions()
        return (wmi + vds).takeIf { it.isNotEmpty() }
    }

    private fun UperBitReader.deltaReferencePosition(
        baseLatitude: Int?,
        baseLongitude: Int?,
    ): Pair<Int, Int>? {
        val latitude = constrained(-131_071, 131_072).toInt()
        val longitude = constrained(-131_071, 131_072).toInt()
        constrained(-12_700, 12_800) // deltaAltitude
        if (latitude == 131_072 || longitude == 131_072) return null
        val baseLat = baseLatitude ?: return null
        val baseLon = baseLongitude ?: return null
        return baseLat + latitude to baseLon + longitude
    }

    private fun UperBitReader.actionId(): Pair<Long, Int> {
        val station = constrained(0, 0xffff_ffffL)
        val sequence = constrained(0, 65_535).toInt()
        return station to sequence
    }

    /** SEQUENCE OF with a root size and an extension bit; extended sizes use the PER length determinant. */
    private fun UperBitReader.extensibleSequenceLength(minimum: Int, maximum: Int): Int =
        if (bit()) lengthDeterminant() else sequenceLength(minimum, maximum)

    private fun UperBitReader.extensibleEnum(rootValues: Int): Int {
        if (bit()) throw IntersectionDecodeException("Enum extension is not supported in the à-la-carte container")
        val width = 32 - Integer.numberOfLeadingZeros(rootValues - 1)
        return bits(width).toInt()
    }

    private fun UperBitReader.bitString(minimum: Int, maximum: Int): Int {
        val length = sequenceLength(minimum, maximum)
        return bitStringFixed(length)
    }

    private fun UperBitReader.bitStringFixed(length: Int): Int {
        var value = 0
        repeat(length) { value = (value shl 1) or (if (bit()) 1 else 0) }
        return value
    }

    private fun UperBitReader.ia5String(minimum: Int, maximum: Int): String {
        val length = sequenceLength(minimum, maximum)
        return CharArray(length) { bits(7).toInt().toChar() }.concatToString()
    }

    private fun UperBitReader.numericString(minimum: Int, maximum: Int): String {
        val length = sequenceLength(minimum, maximum)
        return CharArray(length) {
            val digit = bits(4).toInt()
            if (digit == 10) ' ' else ('0'.code + digit).toChar()
        }.concatToString()
    }

    private fun UperBitReader.utf8String(minimum: Int, maximum: Int): String {
        val length = sequenceLength(minimum, maximum)
        return String(ByteArray(length) { bits(8).toByte() })
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

    private data class TraceData(
        val traces: List<List<DeltaPosition>> = emptyList(),
        val speedCms: Int? = null,
        val headingTenths: Int? = null,
    )

    private fun UperBitReader.traces(): TraceData {
        val extended = bit()
        val hasEventSpeed = bit()
        val hasEventPositionHeading = bit()
        val hasRoadType = bit()
        var speedCms: Int? = null
        var headingTenths: Int? = null
        if (hasEventSpeed) {
            speedCms = constrained(0, 16_383).toInt() // speedValue
            constrained(1, 127) // speedConfidence
        }
        if (hasEventPositionHeading) {
            headingTenths = constrained(0, 3_601).toInt() // Heading or Wgs84Angle value
            constrained(1, 127) // and confidence
        }
        val traces = List(inRange(1, 7).toInt()) {
            List(inRange(0, 40).toInt()) { pathPoint() }
        }
        if (hasRoadType) constrained(0, 3)
        if (extended) skipExtensionAdditions()
        return TraceData(traces, speedCms, headingTenths)
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
