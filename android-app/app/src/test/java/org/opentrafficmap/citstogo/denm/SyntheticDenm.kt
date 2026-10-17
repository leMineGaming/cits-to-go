package org.opentrafficmap.citstogo.denm

import org.opentrafficmap.citstogo.cam.UperBitWriter
import org.opentrafficmap.citstogo.denm.DenmTermination.Negation

/** Synthetic DENM PDUs for the decoder and map tests. */
internal object SyntheticDenm {
    /**
     * Writes [denm] field by field from the ASN.1, independently of the decoder, with a LocationContainer whenever it
     * has traces. An extended ManagementContainer or event type carries one unknown addition, and so do the situation
     * and location containers with [extensions]. [optionalFields] adds a linked cause, delta times to the event zone
     * and the path points, the first of them beyond the root range of PathDeltaTime, and the event speed, heading and
     * road type, none of which the decoder keeps.
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
        out.bit(false) // alacarte

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
        return out.toByteArray()
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
