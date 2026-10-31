package org.opentrafficmap.citstogo

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import org.opentrafficmap.citstogo.drive.engine.DriveSnapshot
import org.opentrafficmap.citstogo.intersection.IntersectionKey
import org.opentrafficmap.citstogo.intersection.IntersectionSnapshot
import org.opentrafficmap.citstogo.intersection.LaneType
import org.opentrafficmap.citstogo.intersection.MapLane

@Composable
fun DriveMapDebugPage(
    state: DriveSnapshot,
    modifier: Modifier = Modifier,
) {
    val snapshots = state.intersections.filter { it.map != null || it.spat != null }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = snapshots.firstOrNull { it.map?.key?.toString() == selectedKey }
        ?: snapshots.firstOrNull()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (snapshots.isEmpty()) {
            DriveSection {
                Text(
                    stringResource(R.string.drive_no_intersections),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "MAPEM frames are decoded from the C-ITS receiver. The live map page also shows the received intersections on a geographic base map.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            snapshots.forEach { snapshot ->
                val key = snapshot.map?.key ?: snapshot.spat?.key
                FilterChip(
                    selected = (selected?.map?.key ?: selected?.spat?.key) == key,
                    onClick = { selectedKey = key?.toString() },
                    label = { Text(key?.toString() ?: "Intersection") },
                )
            }
        }
        selected?.let { snapshot -> IntersectionDebugCard(snapshot, state) }
    }
}

@Composable
private fun IntersectionDebugCard(snapshot: IntersectionSnapshot, state: DriveSnapshot) {
    val map = snapshot.map
    val spat = snapshot.spat
    DriveSection(title = "Intersection ${map?.key ?: spat?.key}") {
        map?.let { intersection ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                DebugValue("Name", intersection.name ?: "–")
                DebugValue("Revision", intersection.revision.toString())
                DebugValue("Lane width", intersection.laneWidthCm?.let { "${it / 100.0} m" } ?: "–")
                DebugValue("Speed limit", intersection.speedLimitsKph.joinToString().ifBlank { "–" })
            }
            Text(
                "Reference ${intersection.latitude / 1e7}, ${intersection.longitude / 1e7} · ${intersection.lanes.size} lanes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LaneCanvas(intersection.lanes, state.intersection?.lane?.laneId, Modifier.fillMaxWidth().height(340.dp))
            intersection.lanes.sortedBy { it.id }.forEach { lane ->
                LaneDebugRow(lane, lane.id == state.intersection?.lane?.laneId)
            }
        }
        spat?.let { spatIntersection ->
            Text(
                "SPATEM revision ${spatIntersection.revision} · ${spatIntersection.movements.size} signal groups",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            spatIntersection.movements.forEach { movement ->
                Text(
                    "SG ${movement.signalGroup}: " +
                        movement.events.joinToString(", ") { event ->
                            val remaining = event.minEndTime?.let { time ->
                                org.opentrafficmap.citstogo.drive.model.SignalSnapshot.remainingSeconds(
                                    spatIntersection,
                                    time,
                                    state.lastUpdatedMs,
                                )
                            }
                            "${event.state.label} min=${remaining?.let { "${it.toInt()}s" } ?: "–"}"
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DebugValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LaneDebugRow(lane: MapLane, matched: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            "Lane ${lane.id}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (matched) FontWeight.Bold else FontWeight.Normal,
            color = if (matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            buildString {
                append(lane.laneType.label())
                append(if (lane.ingress) " · inbound" else "")
                append(if (lane.egress) " · outbound" else "")
                lane.ingressApproach?.let { append(" · ingress $it") }
                lane.egressApproach?.let { append(" · egress $it") }
                append(" · ${lane.nodes.size} nodes")
                val groups = lane.connections.mapNotNull { it.signalGroup }.distinct()
                if (groups.isNotEmpty()) append(" · SG ${groups.joinToString()}")
                if (lane.connections.isNotEmpty()) {
                    append(" · connects ${lane.connections.joinToString { it.laneId.toString() }}")
                }
                if (lane.speedLimitsKph.isNotEmpty()) {
                    append(" · ${lane.speedLimitsKph.joinToString()} km/h")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LaneCanvas(lanes: List<MapLane>, matchedLaneId: Int?, modifier: Modifier = Modifier) {
    val allNodes = lanes.flatMap { it.nodes }
    if (allNodes.isEmpty()) return
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    val labelColor = MaterialTheme.colorScheme.onSurface
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(
        modifier
            .fillMaxWidth()
            .height(340.dp)
            .padding(2.dp),
    ) {
        drawRoundRect(color = background, cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f))
        val minX = allNodes.minOf { it.xCm }.toFloat()
        val maxX = allNodes.maxOf { it.xCm }.toFloat()
        val minY = allNodes.minOf { it.yCm }.toFloat()
        val maxY = allNodes.maxOf { it.yCm }.toFloat()
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)
        val padding = 34f
        val scale = min((size.width - 2 * padding) / spanX, (size.height - 2 * padding) / spanY)
        fun point(x: Float, y: Float): Offset = Offset(
            x = padding + (x - minX) * scale,
            y = size.height - padding - (y - minY) * scale,
        )
        val labelPaint = Paint().apply {
            color = labelColor.toArgb()
            textSize = 26f
            isAntiAlias = true
        }
        lanes.forEach { lane ->
            val isMatched = lane.id == matchedLaneId
            val color = when {
                isMatched -> primary
                lane.connections.any { it.signalGroup != null } -> tertiary
                else -> secondary
            }
            if (lane.nodes.size >= 2) {
                val path = Path().apply {
                    val first = point(lane.nodes.first().xCm.toFloat(), lane.nodes.first().yCm.toFloat())
                    moveTo(first.x, first.y)
                    lane.nodes.drop(1).forEach { node ->
                        val next = point(node.xCm.toFloat(), node.yCm.toFloat())
                        lineTo(next.x, next.y)
                    }
                }
                drawPath(path, color = color, style = Stroke(width = if (isMatched) 9f else 5f))
                val first = point(lane.nodes.first().xCm.toFloat(), lane.nodes.first().yCm.toFloat())
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText("${lane.id}", first.x + 6f, first.y + 6f, labelPaint)
                }
                lane.nodes.filter { it.stopLine }.forEach { node ->
                    val stop = point(node.xCm.toFloat(), node.yCm.toFloat())
                    drawCircle(color = error, radius = 7f, center = stop)
                }
            }
            val lanesById = lanes.associateBy { it.id }
            lane.connections.forEach { connection ->
                val target = lanesById[connection.laneId] ?: return@forEach
                if (target.nodes.isEmpty()) return@forEach
                val start = point(lane.nodes.last().xCm.toFloat(), lane.nodes.last().yCm.toFloat())
                val end = point(target.nodes.first().xCm.toFloat(), target.nodes.first().yCm.toFloat())
                drawLine(
                    color = tertiary.copy(alpha = 0.65f),
                    start = start,
                    end = end,
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
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
    LaneType.TrackedVehicle -> "tracked"
    LaneType.Parking -> "parking"
    LaneType.Other -> "other"
}
