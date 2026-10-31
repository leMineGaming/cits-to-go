package org.opentrafficmap.citstogo.denm

import org.opentrafficmap.citstogo.cam.UperBitWriter
import org.opentrafficmap.citstogo.denm.DenmTermination.Negation
import kotlin.math.roundToInt

/** Synthetic DENM PDUs for the decoder and map tests. */
internal object SyntheticDenm {
    /**
     * Writes [denm] field by field from the ASN.1, independently of the decoder, with a LocationContainer whenever it
     * has traces. An extended ManagementContainer or event type carries one unknown addition, and so do the situation
     * and location containers with [extensions]. [optionalFields] adds a linked cause, delta times to the event zone
     * and the path points, the first of them beyond the root range of PathDeltaTime, and the event speed, heading and
     * road type. The decoder retains event speed and heading.
     */
    fun pdu(
        denm: ReceivedDenm,
        protocolVersion: Long = 2,
        messageId: Long = 1,
        writeValidityDuration: Boolean = true,
        managementExtension: Boolean = false,
        causeCodeExtension: Boolean = false,
        optionalFields: Boolean = false,
        extensions: Boolean = false,
    ): ByteArray {
        val out = UperBitWriter()
        out.constrained(protocolVersion, 0, 255)
        out.constrained(messageId, 0, 255)
        out.constrained(denm.stationId, 0, 0xffff_ffffL)
        val situation = denm.situation
        out.bit(situation != null)
        out.bit(denm.traces.isNotEmpty()) // location
        out.bit(denm.alacarte != null) // alacarte

        out.bit(managementExtension)
        out.bit(denm.termination != null)
        out.bit(denm.awarenessDistance != null)
        out.bit(denm.awarenessTrafficDirection != null)
        out.bit(writeValidityDuration)
        out.bit(denm.transmissionIntervalMs != null)
        out.constrained(denm.originatingStationId, 0, 0xffff_ffffL)
        out.constrained(denm.sequenceNumber.toLong(), 0, 65_535)
        out.constrained(denm.detectionTimeIts, 0, 4_398_046_511_103)
        out.constrained(denm.referenceTimeIts, 0, 4_398_046_511_103)
        denm.termination?.let { out.bit(it == Negation) }
        // Raw, so that out-of-range values can be written.
        out.bits((denm.latitude ?: 900_000_001) + 900_000_000L, 31)
        out.bits((denm.longitude ?: 1_800_000_001) + 1_800_000_000L, 32)
        out.constrained(250, 0, 4_095)
        out.constrained(120, 0, 4_095)
        out.constrained(1_800, 0, 3_601)
        out.constrained(800_001, -100_000, 800_001)
        out.constrained(15, 0, 15)
        denm.awarenessDistance?.let { out.constrained(it.toLong(), 0, 7) }
        denm.awarenessTrafficDirection?.let { out.constrained(it.toLong(), 0, 3) }
        if (writeValidityDuration) out.bits(denm.validityDurationSeconds.toLong(), 17)
        denm.transmissionIntervalMs?.let { out.bits(it - 1L, 14) }
        out.constrained(denm.stationType.toLong(), 0, 255)
        if (managementExtension) writeOneUnknownAddition(out)

        if (situation != null) {
            out.bit(extensions)
            out.bit(optionalFields) // linkedCause
            out.bit(situation.eventZone.isNotEmpty())
            out.constrained(situation.informationQuality.toLong(), 0, 7)
            writeCause(out, situation.causeCode, situation.subCauseCode, causeCodeExtension)
            if (optionalFields) writeCause(out, causeCode = 2, subCauseCode = 1, extension = false)
            if (situation.eventZone.isNotEmpty()) {
                out.constrained(situation.eventZone.size.toLong(), 1, 23)
                situation.eventZone.forEachIndexed { index, point ->
                    out.bit(optionalFields) // eventDeltaTime, in all points or in none
                    writeDeltaPosition(out, point)
                    if (optionalFields) writePathDeltaTime(out, extended = index == 0)
                    out.constrained(5, 0, 7) // informationQuality
                }
            }
            if (extensions) writeOneUnknownAddition(out)
        }

        if (denm.traces.isNotEmpty()) {
            out.bit(extensions)
            repeat(3) { out.bit(optionalFields) } // eventSpeed, eventPositionHeading and roadType
            if (optionalFields) {
                out.constrained(1_389, 0, 16_383)
                out.constrained(5, 1, 127)
                out.constrained(900, 0, 3_601)
                out.constrained(10, 1, 127)
            }
            out.constrained(denm.traces.size.toLong(), 1, 7)
            denm.traces.forEachIndexed { pathIndex, path ->
                out.constrained(path.size.toLong(), 0, 40)
                path.forEachIndexed { index, point ->
                    out.bit(optionalFields) // pathDeltaTime
                    writeDeltaPosition(out, point)
                    if (optionalFields) writePathDeltaTime(out, extended = pathIndex == 0 && index == 0)
                }
            }
            if (optionalFields) out.constrained(3, 0, 3) // roadType
            if (extensions) writeOneUnknownAddition(out)
        }

        denm.alacarte?.let { writeAlacarte(out, it) }
        return out.toByteArray()
    }

    private fun writeAlacarte(out: UperBitWriter, alacarte: DenmAlacarte) {
        out.bit(false) // extension
        out.bit(alacarte.lanePosition != null)
        out.bit(alacarte.impactReduction != null)
        out.bit(alacarte.externalTemperatureC != null)
        out.bit(alacarte.roadWorks != null)
        out.bit(alacarte.positioningSolution != null)
        out.bit(alacarte.stationaryVehicle != null)
        alacarte.lanePosition?.let { out.constrained(it.toLong(), -1, 14) }
        alacarte.impactReduction?.let { writeImpactReduction(out, it) }
        alacarte.externalTemperatureC?.let { out.constrained(it.toLong(), -60, 67) }
        alacarte.roadWorks?.let { writeRoadWorks(out, it) }
        alacarte.positioningSolution?.let {
            out.bit(false) // extensible ENUM root
            out.constrained(it.toLong(), 0, 5)
        }
        alacarte.stationaryVehicle?.let { writeStationaryVehicle(out, it) }
    }

    private fun writeImpactReduction(out: UperBitWriter, impact: DenmImpactReduction) {
        fun length(meters: Double?, fallback: Int): Int =
            meters?.let { (it * 100).toInt().coerceIn(1, 98) } ?: fallback
        fun decimetres(meters: Double?, fallback: Int, outOfRange: Int): Int =
            meters?.let { (it * 10).toInt().coerceIn(1, outOfRange - 1) } ?: fallback
        fun centimetres(meters: Double?, fallback: Int): Int =
            meters?.let { (it * 100).toInt().coerceIn(1, 125) } ?: fallback

        out.constrained(length(impact.heightLeftM, 100).toLong(), 1, 100)
        out.constrained(length(impact.heightRightM, 100).toLong(), 1, 100)
        out.constrained(centimetres(impact.positionLeftM, 127).toLong(), 1, 127)
        out.constrained(centimetres(impact.positionRightM, 127).toLong(), 1, 127)
        val pillars = impact.pillarOffsetsM.map { (it * 10).toInt().coerceIn(1, 28) }
        out.bit(false) // no extension
        out.constrained(pillars.size.toLong(), 1, 3)
        pillars.forEach { out.constrained(it.toLong(), 1, 30) }
        out.constrained(decimetres(impact.centreOfMassM, 63, 62).toLong(), 1, 63)
        out.constrained(decimetres(impact.wheelbaseM, 127, 126).toLong(), 1, 127)
        val turning = impact.turningRadiusM?.let { (it / 0.4).roundToInt().coerceIn(1, 253) } ?: 255
        out.constrained(turning.toLong(), 1, 255)
        out.constrained(decimetres(impact.frontAxleM, 20, 19).toLong(), 1, 20)
        writeBitString(out, 20, impact.occupantsBits)
        val mass = impact.vehicleMassKg?.let { (it / 100).coerceIn(1, 1_022) } ?: 1_024
        out.constrained(mass.toLong(), 1, 1_024)
        out.constrained((impact.requestResponse ?: 0).toLong(), 0, 1)
    }

    private fun writeRoadWorks(out: UperBitWriter, roadWorks: DenmRoadWorks) {
        out.bit(roadWorks.lightBarSirenBits != null)
        out.bit(roadWorks.closedLanes != null)
        out.bit(roadWorks.restrictedStationTypes.isNotEmpty())
        out.bit(roadWorks.speedLimitKph != null)
        out.bit(roadWorks.incidentCauseCode != null)
        out.bit(roadWorks.recommendedPath.isNotEmpty())
        out.bit(roadWorks.startingPointSpeedLimit != null)
        out.bit(roadWorks.trafficFlowRule != null)
        out.bit(roadWorks.referenceActionIds.isNotEmpty())

        roadWorks.lightBarSirenBits?.let { writeBitString(out, 2, it) }
        roadWorks.closedLanes?.let { closed ->
            out.bit(false) // no extension
            out.bit(closed.innerHardShoulderStatus != null)
            out.bit(closed.outerHardShoulderStatus != null)
            out.bit(closed.drivingLaneStatusBits != null)
            closed.innerHardShoulderStatus?.let { out.constrained(it.toLong(), 0, 2) }
            closed.outerHardShoulderStatus?.let { out.constrained(it.toLong(), 0, 2) }
            closed.drivingLaneStatusBits?.let {
                val width = 13
                out.constrained(width.toLong(), 1, 13)
                writeBitString(out, width, it)
            }
        }
        if (roadWorks.restrictedStationTypes.isNotEmpty()) {
            out.bit(false) // no extension
            out.constrained(roadWorks.restrictedStationTypes.size.toLong(), 1, 3)
            roadWorks.restrictedStationTypes.forEach { out.constrained(it.toLong(), 0, 255) }
        }
        roadWorks.speedLimitKph?.let { out.constrained(it.toLong(), 1, 255) }
        if (roadWorks.incidentCauseCode != null && roadWorks.incidentSubCauseCode != null) {
            writeCause(out, roadWorks.incidentCauseCode, roadWorks.incidentSubCauseCode, extension = false)
        }
        if (roadWorks.recommendedPath.isNotEmpty()) {
            out.constrained(roadWorks.recommendedPath.size.toLong(), 1, 40)
            roadWorks.recommendedPath.forEach { (latitude, longitude) ->
                out.bits(latitude + 900_000_000L, 31)
                out.bits(longitude + 1_800_000_000L, 32)
                out.constrained(250, 0, 4_095)
                out.constrained(120, 0, 4_095)
                out.constrained(1_800, 0, 3_601)
                out.constrained(50_000, -100_000, 800_001)
                out.constrained(15, 0, 15)
            }
        }
        roadWorks.startingPointSpeedLimit?.let { (latitude, longitude) ->
            out.constrained(0, -131_071, 131_072) // deltaLatitude placeholder; base is the event position
            out.constrained(0, -131_071, 131_072) // deltaLongitude
            out.constrained(-250, -12_700, 12_800)
        }
        roadWorks.trafficFlowRule?.let {
            out.bit(false)
            out.constrained(it.toLong(), 0, 3)
        }
        if (roadWorks.referenceActionIds.isNotEmpty()) {
            out.bit(false) // no extension
            out.constrained(roadWorks.referenceActionIds.size.toLong(), 1, 8)
            roadWorks.referenceActionIds.forEach { (station, sequence) ->
                out.constrained(station, 0, 0xffff_ffffL)
                out.constrained(sequence.toLong(), 0, 65_535)
            }
        }
    }

    private fun writeStationaryVehicle(out: UperBitWriter, stationary: DenmStationaryVehicle) {
        out.bit(stationary.stationarySince != null)
        out.bit(stationary.causeCode != null)
        out.bit(stationary.dangerousGoodsType != null)
        out.bit(stationary.numberOfOccupants != null)
        out.bit(stationary.vehicleIdentification != null)
        out.bit(stationary.energyStorageBits != null)
        stationary.stationarySince?.let { out.constrained(it.toLong(), 0, 3) }
        if (stationary.causeCode != null) {
            writeCause(out, stationary.causeCode, stationary.subCauseCode ?: 0, extension = false)
        }
        if (stationary.dangerousGoodsType != null) {
            out.bit(false) // extension
            out.bit(false) // emergencyActionCode
            out.bit(false) // phoneNumber
            out.bit(false) // companyName
            out.constrained(stationary.dangerousGoodsType.toLong(), 0, 19)
            out.constrained((stationary.dangerousGoodsUnNumber ?: 0).toLong(), 0, 9_999)
            out.bit(false)
            out.bit(false)
            out.bit(false)
        }
        stationary.numberOfOccupants?.let { out.constrained(it.toLong(), 0, 127) }
        stationary.vehicleIdentification?.let { identification ->
            out.bit(false) // extension
            out.bit(true) // WMI
            out.bit(identification.length > 3) // VDS
            val wmi = identification.take(3)
            out.constrained(wmi.length.toLong(), 1, 3)
            wmi.forEach { out.bits(it.code.toLong(), 7) }
            if (identification.length > 3) {
                val vds = identification.drop(3).take(6)
                out.constrained(vds.length.toLong(), 6, 6)
                vds.forEach { out.bits(it.code.toLong(), 7) }
            }
        }
        stationary.energyStorageBits?.let { writeBitString(out, 7, it) }
    }

    private fun writeBitString(out: UperBitWriter, length: Int, value: Int) {
        for (shift in length - 1 downTo 0) out.bit(((value ushr shift) and 1) != 0)
    }


    /** A CauseCode, whose layout CauseCodeV2 shares: the CauseCodeChoice index is the cause code. */
    private fun writeCause(out: UperBitWriter, causeCode: Int, subCauseCode: Int, extension: Boolean) {
        out.bit(extension)
        out.constrained(causeCode.toLong(), 0, 128)
        out.constrained(subCauseCode.toLong(), 0, 255)
        if (extension) writeOneUnknownAddition(out)
    }

    private fun writeDeltaPosition(out: UperBitWriter, position: DeltaPosition) {
        out.constrained(position.latitude.toLong(), -131_071, 131_072)
        out.constrained(position.longitude.toLong(), -131_071, 131_072)
        out.constrained(-250, -12_700, 12_800) // deltaAltitude
    }

    /** A PathDeltaTime of 1.2 s, or with [extended] one of 700 s, beyond its root range, as an extension. */
    private fun writePathDeltaTime(out: UperBitWriter, extended: Boolean) {
        out.bit(extended)
        if (extended) {
            out.bits(3, 8) // Length of the unconstrained integer in octets.
            out.bits(70_000, 24)
        } else {
            out.constrained(120, 1, 65_535)
        }
    }

    /** An extension bitmap with one present addition: an open type of one octet. */
    private fun writeOneUnknownAddition(out: UperBitWriter) {
        out.bit(false) // Normally small bitmap length 1.
        out.bits(0, 6)
        out.bit(true)
        out.bits(1, 8)
        out.bits(0x5a, 8)
    }
}
