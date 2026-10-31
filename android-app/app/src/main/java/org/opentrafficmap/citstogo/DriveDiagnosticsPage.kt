package org.opentrafficmap.citstogo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.opentrafficmap.citstogo.drive.diagnostics.MessageResult
import org.opentrafficmap.citstogo.drive.diagnostics.RawMessageRecord
import org.opentrafficmap.citstogo.drive.engine.DriveSnapshot

@Composable
fun DriveDiagnosticsPage(
    state: DriveSnapshot,
    handlers: DriveUiHandlers,
    modifier: Modifier = Modifier,
) {
    val diagnostics = state.diagnostics
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            DriveSection(title = stringResource(R.string.drive_raw_diagnostics)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Counter(stringResource(R.string.drive_frames), diagnostics.framesReceived.toString())
                    Counter(stringResource(R.string.drive_bytes), diagnostics.bytesReceived.toString())
                    Counter(stringResource(R.string.drive_message_rate),
                        String.format(java.util.Locale.US, "%.1f", diagnostics.messagesPerSecond))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Counter("MAPEM", "${diagnostics.mapemDecoded}/${diagnostics.mapemSeen}")
                    Counter("SPATEM", "${diagnostics.spatemDecoded}/${diagnostics.spatemSeen}")
                    Counter("DENM", "${diagnostics.denmDecoded}/${diagnostics.denmSeen}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Counter(stringResource(R.string.drive_malformed), diagnostics.malformedFrames.toString())
                    Counter(stringResource(R.string.drive_decode_failures), diagnostics.decodeFailures.toString())
                    Counter("Unsupported", diagnostics.unsupportedFrames.toString())
                    Counter("Beacons", diagnostics.beacons.toString())
                }
                diagnostics.lastError?.let {
                    Text(
                        "${stringResource(R.string.drive_last_error)}: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Connection: ${state.connection.address} · attempts ${diagnostics.connectionAttempts} · " +
                        "reconnects ${diagnostics.reconnects}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { handlers.onExportDiagnostics(diagnostics.exportText()) }) {
                    Text(stringResource(R.string.drive_export_diagnostics))
                }
                Text(
                    "The export contains message metadata, counters and errors. Raw payloads are not included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (diagnostics.records.isEmpty()) {
            item {
                DriveSection {
                    Text(
                        "No frames received yet. Start the C-ITS service or enable simulation in Drive Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(diagnostics.records.asReversed()) { record ->
                MessageRecordRow(record)
            }
        }
    }
}

@Composable
private fun Counter(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MessageRecordRow(record: RawMessageRecord) {
    DriveSection {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                record.timeLabel,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(
                record.messageType,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                record.result.label,
                style = MaterialTheme.typography.bodySmall,
                color = when (record.result) {
                    MessageResult.Decoded -> MaterialTheme.colorScheme.tertiary
                    MessageResult.DecodeError, MessageResult.Malformed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            buildString {
                append("${record.lengthBytes} bytes")
                record.destinationPort?.let { append(" · port $it") }
                record.stationId?.let { append(" · station $it") }
                record.detail?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
