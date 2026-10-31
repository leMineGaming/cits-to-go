package org.opentrafficmap.citstogo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opentrafficmap.citstogo.drive.engine.DriveSnapshot
import org.opentrafficmap.citstogo.drive.glosa.GlosaStatus
import org.opentrafficmap.citstogo.drive.model.SignalSnapshot
import org.opentrafficmap.citstogo.intersection.LaneType
import org.opentrafficmap.citstogo.intersection.MovementPhaseState

@Composable
fun DriveTrafficLightPage(
    state: DriveSnapshot,
    modifier: Modifier = Modifier,
) {
    val match = state.intersection
    val signal = state.signal
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (match == null) {
            DriveSection {
                Text(
                    stringResource(R.string.drive_no_intersections),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Drive towards a known MAPEM intersection and make sure a fresh position fix is available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        DriveSection(title = stringResource(R.string.drive_traffic_light_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrafficLightGraphic(signal, Modifier.size(width = 64.dp, height = 160.dp))
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        signalLabel(signal),
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black,
                        color = signalColor(signal),
                    )
                    signal?.let {
                        Text(
                            stringResource(R.string.drive_signal_group, it.signalGroup),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        it.conservativeEndSeconds?.let { seconds ->
                            Text(
                                stringResource(R.string.drive_signal_ends_in, seconds.toInt()),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Text(
                            "Timing confidence ${it.timingConfidence?.let { value -> "$value/15" } ?: "unknown"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column {
                    Text("Junction", style = MaterialTheme.typography.labelSmall)
                    Text(
                        match.map.name?.takeIf { it.isNotBlank() } ?: match.lane.intersectionKey.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Column {
                    Text("Lane", style = MaterialTheme.typography.labelSmall)
                    Text("${match.lane.laneId}", style = MaterialTheme.typography.bodyMedium)
                }
                Column {
                    Text("Stop line", style = MaterialTheme.typography.labelSmall)
                    Text("${match.lane.distanceToStopLineM.toInt()} m", style = MaterialTheme.typography.bodyMedium)
                }
                Column {
                    Text("Confidence", style = MaterialTheme.typography.labelSmall)
                    Text("${(match.lane.confidence * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        DriveSection(title = "Next phases") {
            val movement = signal?.let { current ->
                match.spat?.movements?.firstOrNull { it.signalGroup == current.signalGroup }
            }
            if (movement == null || movement.events.isEmpty()) {
                Text(
                    "No upcoming phase information in the current SPATEM.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                movement.events.forEachIndexed { index, event ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (index == 0) "Current" else "Next ${index}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(74.dp),
                        )
                        Text(
                            signalLabelPlain(event.state),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when (event.state) {
                                MovementPhaseState.ProtectedAllowed, MovementPhaseState.PermissiveAllowed ->
                                    MaterialTheme.colorScheme.tertiary
                                MovementPhaseState.StopAndRemain, MovementPhaseState.Dark ->
                                    MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        val spat = match.spat
                        val nowMs = state.lastUpdatedMs
                        Text(
                            listOfNotNull(
                                event.minEndTime?.takeIf { it in 0..35_999 }?.let { time ->
                                    SignalSnapshot.remainingSeconds(spat!!, time, nowMs)
                                        ?.let { "min ${it.toInt()}s" }
                                },
                                event.likelyTime?.takeIf { it in 0..35_999 }?.let { time ->
                                    SignalSnapshot.remainingSeconds(spat!!, time, nowMs)
                                        ?.let { "likely ${it.toInt()}s" }
                                },
                                event.maxEndTime?.takeIf { it in 0..35_999 }?.let { time ->
                                    SignalSnapshot.remainingSeconds(spat!!, time, nowMs)
                                        ?.let { "max ${it.toInt()}s" }
                                },
                            ).joinToString(" · ").ifBlank { "no timing" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        DriveSection(title = "GLOSA") {
            val glosa = state.glosa
            when {
                state.glosaSuppressedBy != null -> Text(
                    state.glosaSuppressedBy,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                glosa?.targetSpeedKph != null -> {
                    Text(
                        "${glosa.targetSpeedKph.toInt()} km/h",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(glosa.reason, style = MaterialTheme.typography.bodyMedium)
                }
                glosa != null -> Text(
                    "${glosa.status.name.lowercase().replace('_', ' ')}: ${glosa.reason}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "No recommendation available for the current lane and signal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DriveSection(title = "MAPEM lanes") {
            val lanes = match.map.lanes
            if (lanes.isEmpty()) {
                Text(stringResource(R.string.drive_no_lanes), style = MaterialTheme.typography.bodyMedium)
            } else {
                lanes.sortedBy { it.id }.forEach { lane ->
                    val isMatched = lane.id == match.lane.laneId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Lane ${lane.id}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isMatched) FontWeight.Bold else FontWeight.Normal,
                            color = if (isMatched) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(88.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                buildString {
                                    append(if (lane.ingress && lane.egress) "Inbound + outbound"
                                    else if (lane.ingress) "Inbound" else if (lane.egress) "Outbound" else "Direction unknown")
                                    append(" · ")
                                    append(lane.laneType.label())
                                    if (lane.connections.isNotEmpty()) {
                                        append(" · SG ")
                                        append(lane.connections.mapNotNull { it.signalGroup }.distinct()
                                            .joinToString(",").ifBlank { "–" })
                                    }
                                    if (lane.speedLimitsKph.isNotEmpty()) {
                                        append(" · ${lane.speedLimitsKph.min()} km/h")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "${lane.nodes.size} nodes · " +
                                    lane.connections.joinToString(", ") { "→ ${it.laneId}" }.ifBlank { "no connections" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LaneType.label(): String = when (this) {
    LaneType.Vehicle -> "vehicle"
    LaneType.Crosswalk -> "crosswalk"
    LaneType.Bike -> "bike"
    LaneType.Sidewalk -> "sidewalk"
    LaneType.Median -> "median"
    LaneType.Striping -> "striping"
    LaneType.TrackedVehicle -> "tracked vehicle"
    LaneType.Parking -> "parking"
    LaneType.Other -> "other"
}
