package org.opentrafficmap.citstogo

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.opentrafficmap.citstogo.drive.engine.DriveSnapshot
import org.opentrafficmap.citstogo.drive.model.DenmCategory
import org.opentrafficmap.citstogo.drive.profile.AlertPriority
import org.opentrafficmap.citstogo.drive.safety.SafetyEvent
import org.opentrafficmap.citstogo.drive.safety.SafetyEventStatus

private val EVENT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun DriveSafetyPage(
    state: DriveSnapshot,
    history: List<SafetyEvent>,
    handlers: DriveUiHandlers,
    modifier: Modifier = Modifier,
) {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var priorityFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val events = if (showHistory) history else state.alerts
    val filtered = events.filter { event ->
        (priorityFilter == null || event.priority.name == priorityFilter) &&
            (categoryFilter == null || event.category.name == categoryFilter)
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.simulated) {
            Text(
                "Simulation · offline example data, not live traffic",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !showHistory,
                onClick = { showHistory = false },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text("${stringResource(R.string.drive_active)} (${state.activeAlertCount})")
            }
            SegmentedButton(
                selected = showHistory,
                onClick = { showHistory = true },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text("${stringResource(R.string.drive_history)} (${history.size})")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = priorityFilter == null,
                onClick = { priorityFilter = null },
                label = { Text(stringResource(R.string.drive_all)) },
            )
            AlertPriority.entries.forEach { priority ->
                FilterChip(
                    selected = priorityFilter == priority.name,
                    onClick = { priorityFilter = if (priorityFilter == priority.name) null else priority.name },
                    label = {
                        Text(
                            when (priority) {
                                AlertPriority.Critical -> stringResource(R.string.drive_priority_critical)
                                AlertPriority.Advisory -> stringResource(R.string.drive_priority_advisory)
                                AlertPriority.Informational -> stringResource(R.string.drive_priority_informational)
                            },
                        )
                    },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = categoryFilter == null,
                onClick = { categoryFilter = null },
                label = { Text("All types") },
            )
            DenmCategory.entries.forEach { category ->
                FilterChip(
                    selected = categoryFilter == category.name,
                    onClick = { categoryFilter = if (categoryFilter == category.name) null else category.name },
                    label = { Text(category.shortLabel) },
                )
            }
        }
        if (filtered.isEmpty()) {
            DriveSection {
                Text(
                    if (showHistory) stringResource(R.string.drive_history_empty)
                    else stringResource(R.string.drive_alerts_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { event ->
                    "${event.actionId.originatingStationId}/${event.actionId.sequenceNumber}/${event.version}/${event.status}"
                }) { event ->
                    SafetyEventCard(
                        event = event,
                        onDismiss = { handlers.onDismissAlert(event.actionId) },
                        showDismiss = event.active && !showHistory,
                    )
                }
            }
        }
    }
}

@Composable
private fun SafetyEventCard(
    event: SafetyEvent,
    onDismiss: () -> Unit,
    showDismiss: Boolean,
) {
    val accent = priorityColor(event.priority)
    DriveSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityChip(event.priority)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        event.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    event.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EventField("Distance", distanceLabel(event.distanceM))
            EventField("Direction", eventDirectionLabel(event))
            EventField("Status", event.status.label)
            EventField("Validity", event.remainingValidityLabel)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            EventField(
                "Detected",
                EVENT_TIME_FORMAT.format(Instant.ofEpochMilli(event.detectionAtMs).atZone(ZoneId.systemDefault())),
            )
            EventField("Quality", event.informationQuality?.let { "$it/7" } ?: "–")
            EventField("Confidence", "${(event.confidence * 100).toInt()}%")
            EventField("Category", event.category.shortLabel)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildString {
                    append("Cause ${event.causeCode ?: "?"} / sub ${event.subCauseCode ?: "?"}")
                    append(" · ")
                    append("Station ${event.actionId.originatingStationId} #${event.actionId.sequenceNumber}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (showDismiss) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_dismiss)) }
            }
        }
        if (event.status == SafetyEventStatus.Cancelled || event.status == SafetyEventStatus.Negated) {
            Text(
                "The sender terminated this event.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        event.alacarte?.let { AlacarteDetails(it) }
    }
}

@Composable
private fun AlacarteDetails(alacarte: org.opentrafficmap.citstogo.denm.DenmAlacarte) {
    Text(
        "À-la-carte information",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val details = buildList {
        alacarte.lanePosition?.let { add("Lane position: ${alacarte.lanePositionLabel}") }
        alacarte.externalTemperatureC?.let { add("Temperature: $it °C") }
        alacarte.positioningSolution?.let { add("Positioning solution: $it") }
        alacarte.roadWorks?.let { road ->
            road.speedLimitKph?.let { add("Roadworks speed limit: $it km/h") }
            road.closedLanes?.let { lanes ->
                val closed = buildList {
                    lanes.innerHardShoulderStatus?.let { add("inner shoulder $it") }
                    lanes.outerHardShoulderStatus?.let { add("outer shoulder $it") }
                    lanes.drivingLaneStatusBits?.let { add("driving lanes 0b${Integer.toBinaryString(it)}") }
                }
                if (closed.isNotEmpty()) add("Closed lanes: ${closed.joinToString()}")
            }
            if (road.restrictedStationTypes.isNotEmpty()) {
                add("Restricted station types: ${road.restrictedStationTypes.joinToString()}")
            }
            road.recommendedPath.takeIf { it.isNotEmpty() }?.let { add("Recommended path: ${it.size} points") }
        }
        alacarte.stationaryVehicle?.let { stationary ->
            add(stationary.stationarySinceLabel)
            stationary.dangerousGoodsType?.let { add("Dangerous goods class: $it") }
            stationary.numberOfOccupants?.let { add("Occupants: $it") }
            stationary.vehicleIdentification?.let { add("Vehicle: $it") }
        }
        alacarte.impactReduction?.let { impact ->
            impact.vehicleMassKg?.let { add("Vehicle mass: $it kg") }
            impact.wheelbaseM?.let { add("Wheelbase: ${"%.1f".format(it)} m") }
            impact.turningRadiusM?.let { add("Turning radius: ${"%.1f".format(it)} m") }
        }
        alacarte.parseError?.let { add("Optional container partially decoded: $it") }
    }
    if (details.isEmpty()) {
        Text(
            "The sender included an à-la-carte container without displayable fields.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            details.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EventField(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
