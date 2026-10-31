package org.opentrafficmap.citstogo.drive.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DIAG_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** The decoding result shown in the raw diagnostics view. */
enum class MessageResult(val label: String) {
    Decoded("Decoded"),
    Unsupported("Unsupported"),
    Malformed("Malformed"),
    NotCits("Not C-ITS"),
    Beacon("GeoNetworking beacon"),
    DecodeError("Decode error"),
}

data class RawMessageRecord(
    val receivedAtMs: Long,
    val messageType: String,
    val destinationPort: Int?,
    val stationId: Long?,
    val lengthBytes: Int,
    val result: MessageResult,
    val detail: String? = null,
) {
    val timeLabel: String
        get() = DIAG_TIME_FORMAT.format(Instant.ofEpochMilli(receivedAtMs).atZone(ZoneId.systemDefault()))
}

/**
 * Structured diagnostics for the TCP link and the decoding pipeline. The ring buffer is bounded so a
 * long drive cannot grow the process without limit, and the export contains message metadata only
 * (never raw payloads), which keeps it safe to share for support.
 */
data class DriveDiagnostics(
    val framesReceived: Long = 0,
    val bytesReceived: Long = 0,
    val itsPackets: Long = 0,
    val mapemSeen: Long = 0,
    val mapemDecoded: Long = 0,
    val spatemSeen: Long = 0,
    val spatemDecoded: Long = 0,
    val denmSeen: Long = 0,
    val denmDecoded: Long = 0,
    val malformedFrames: Long = 0,
    val decodeFailures: Long = 0,
    val unsupportedFrames: Long = 0,
    val beacons: Long = 0,
    val lastError: String? = null,
    val lastErrorAtMs: Long? = null,
    val lastMessageAtMs: Long? = null,
    val messagesPerSecond: Double = 0.0,
    val records: List<RawMessageRecord> = emptyList(),
    val connectionAttempts: Long = 0,
    val reconnects: Long = 0,
    val lastConnectionError: String? = null,
) {
    fun exportText(): String = buildString {
        appendLine("C-ITS Drive Assist diagnostics")
        appendLine("Generated: ${DIAG_TIME_FORMAT.format(Instant.now().atZone(ZoneId.systemDefault()))}")
        appendLine("Frames: $framesReceived, bytes: $bytesReceived, ITS packets: $itsPackets")
        appendLine("MAPEM: $mapemSeen seen / $mapemDecoded decoded")
        appendLine("SPATEM: $spatemSeen seen / $spatemDecoded decoded")
        appendLine("DENM: $denmSeen seen / $denmDecoded decoded")
        appendLine("Malformed: $malformedFrames, decode failures: $decodeFailures, unsupported: $unsupportedFrames")
        appendLine("Message rate: ${String.format(java.util.Locale.US, "%.1f", messagesPerSecond)}/s")
        appendLine("Connection attempts: $connectionAttempts, reconnects: $reconnects")
        lastError?.let { appendLine("Last error: $it") }
        lastConnectionError?.let { appendLine("Last connection error: $it") }
        appendLine()
        appendLine("Recent messages:")
        records.forEach { record ->
            append(record.timeLabel)
            append(' ')
            append(record.messageType)
            append(" len=")
            append(record.lengthBytes)
            append(" result=")
            append(record.result.label)
            record.stationId?.let { append(" station=$it") }
            record.detail?.let { append(" detail=$it") }
            appendLine()
        }
    }

    companion object {
        const val MAX_RECORDS = 300
    }
}
