package org.opentrafficmap.citstogo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Traffic
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opentrafficmap.citstogo.drive.engine.DriveSnapshot
import org.opentrafficmap.citstogo.drive.glosa.GlosaAdvisory
import org.opentrafficmap.citstogo.drive.glosa.GlosaStatus
import org.opentrafficmap.citstogo.drive.model.SignalSnapshot
import org.opentrafficmap.citstogo.drive.net.ConnectionPhase
import org.opentrafficmap.citstogo.drive.profile.AlertPriority
import org.opentrafficmap.citstogo.drive.profile.DriverProfile
import org.opentrafficmap.citstogo.drive.safety.SafetyEvent

@Composable
fun DriveHomePage(
    state: DriveSnapshot,
    profile: DriverProfile,
    locationPermissionGranted: Boolean,
    captureRunning: Boolean,
    handlers: DriveUiHandlers,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DriveConnectionBanner(state, captureRunning, handlers)
        GlosaHero(state, profile, locationPermissionGranted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = stringResource(R.string.drive_speed),
                value = state.position?.speedKph?.let { "${it.toInt()}" } ?: "–",
                detail = stringResource(R.string.drive_speed_unit),
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = stringResource(R.string.drive_distance_to_stop_line),
                value = state.intersection?.lane?.distanceToStopLineM?.let { "${it.toInt()}" } ?: "–",
                detail = stringResource(R.string.drive_distance_unit),
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Location",
                value = state.position?.accuracyM?.let { "±${it.toInt()} m" } ?: "–",
                detail = state.intersection?.lane?.confidence?.let {
                    stringResource(R.string.drive_confidence, (it * 100).toInt())
                },
                modifier = Modifier.weight(1f),
            )
        }
        SignalCard(state)
        SafetySummary(state, handlers)
        DataQualityCard(state)
        Text(
            stringResource(R.string.drive_advisory_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun DriveConnectionBanner(
    state: DriveSnapshot,
    captureRunning: Boolean,
    handlers: DriveUiHandlers,
) {
    val usbCaptureMode = !state.settings.hasHardwareAddress && !state.simulated
    val statusColor = when {
        state.simulated -> MaterialTheme.colorScheme.tertiary
        state.connection.connected -> MaterialTheme.colorScheme.tertiary
        state.connection.phase == ConnectionPhase.Connecting -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    DriveSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(statusColor)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        usbCaptureMode && captureRunning -> "USB capture active"
                        usbCaptureMode -> "USB capture stopped"
                        state.simulated -> stringResource(R.string.drive_status_simulation)
                        state.connection.connected -> stringResource(R.string.drive_status_connected)
                        state.connection.phase == ConnectionPhase.Connecting ->
                            stringResource(R.string.drive_status_connecting)
                        else -> stringResource(R.string.drive_status_disconnected)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
                Text(
                    when {
                        usbCaptureMode && captureRunning ->
                            "Receiving from the USB ESP32-C5; packets feed this dashboard."
                        usbCaptureMode ->
                            "No TCP receiver configured. Start Capture to use the USB ESP32-C5; its packets feed this dashboard."
                        state.simulated -> "Offline example data · never live traffic"
                        state.connection.connected -> state.connection.address
                        else -> state.connection.lastError ?: state.connection.address
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (usbCaptureMode) {
                OutlinedButton(onClick = { handlers.onOpenPage(AppPage.Capture) }) {
                    Text("Open Capture")
                }
            } else if (state.connection.phase == ConnectionPhase.Stopped && !state.simulated) {
                Button(onClick = handlers.onStartService) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.drive_start))
                }
            } else {
                OutlinedButton(onClick = handlers.onStopService) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.drive_stop))
                }
            }
        }
        if (!usbCaptureMode && state.connection.phase != ConnectionPhase.Stopped) {
            TextButton(onClick = handlers.onReconnectService) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.drive_reconnect))
            }
        }
    }
}

@Composable
private fun GlosaHero(
    state: DriveSnapshot,
    profile: DriverProfile,
    locationPermissionGranted: Boolean,
) {
    val glosa = state.glosa
    val suppressed = state.glosaSuppressedBy
    val accent = when {
        suppressed != null -> MaterialTheme.colorScheme.onSurfaceVariant
        glosa?.status == GlosaStatus.Available -> MaterialTheme.colorScheme.tertiary
        glosa?.status == GlosaStatus.StopAtLine -> MaterialTheme.colorScheme.error
        glosa?.status == GlosaStatus.ImpossibleArrival -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    DriveSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.drive_glosa_target),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.simulated) {
                Text(
                    stringResource(R.string.drive_simulation),
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        when {
            suppressed != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            suppressed,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "GLOSA is hidden so the hazard stays the primary information.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            glosa == null -> {
                Text(
                    stringResource(R.string.drive_no_recommendation),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        !profile.glosaEnabled -> "GLOSA is disabled in the selected driver profile."
                        state.position == null && !locationPermissionGranted ->
                            "Location permission is required for junction matching."
                        state.position == null -> "Waiting for a reliable position fix."
                        state.intersection == null -> "No MAPEM lane matched to the current position."
                        state.signal == null -> "No SPATEM signal group matched to the current lane."
                        else -> "Waiting for enough fresh information for a safe recommendation."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            glosa.targetSpeedKph == null -> {
                Text(
                    when (glosa.status) {
                        GlosaStatus.ImpossibleArrival -> "Arrival not possible safely"
                        GlosaStatus.StaleData -> "Data too old"
                        GlosaStatus.LowConfidence -> "Location confidence too low"
                        GlosaStatus.NoTiming -> "No signal timing"
                        GlosaStatus.NoSignal -> "No signal state"
                        GlosaStatus.Disabled -> "GLOSA disabled"
                        else -> "No recommendation"
                    },
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                Text(
                    glosa.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    AnimatedContent(
                        targetState = glosa.targetSpeedKph.toInt(),
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                        label = "glosaSpeed",
                    ) { speed ->
                        Text(
                            "$speed",
                            fontSize = 74.sp,
                            lineHeight = 76.sp,
                            fontWeight = FontWeight.Black,
                            color = accent,
                        )
                    }
                    Text(
                        stringResource(R.string.drive_speed_unit),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        modifier = Modifier.padding(bottom = 14.dp, start = 6.dp),
                    )
                }
                Text(
                    when (glosa.advisory) {
                        GlosaAdvisory.MaintainSpeed -> stringResource(R.string.drive_glosa_maintain)
                        GlosaAdvisory.AccelerateGently -> stringResource(R.string.drive_glosa_accelerate)
                        GlosaAdvisory.Decelerate -> stringResource(R.string.drive_glosa_decelerate)
                        GlosaAdvisory.Stop -> stringResource(R.string.drive_glosa_stop)
                        GlosaAdvisory.BrakeNow -> stringResource(R.string.drive_glosa_brake_now)
                        GlosaAdvisory.None -> ""
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
                Text(
                    glosa.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val progress = glosa.confidence.toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column {
                        Text("Green window", style = MaterialTheme.typography.labelSmall)
                        Text(
                            glosa.greenEndsAtMs?.let { end ->
                                val seconds = ((end - System.currentTimeMillis()) / 1_000).coerceAtLeast(0)
                                "${seconds}s remaining"
                            } ?: "Timing estimate",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Column {
                        Text("Distance", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "${glosa.distanceToStopLineM.toInt()} m",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Column {
                        Text("Arrival", style = MaterialTheme.typography.labelSmall)
                        Text(
                            glosa.expectedArrivalMs?.let { arrival ->
                                val seconds = ((arrival - System.currentTimeMillis()) / 1_000).coerceAtLeast(0)
                                "~${seconds}s"
                            } ?: "–",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalCard(state: DriveSnapshot) {
    val signal = state.signal
    val color = signalColor(signal)
    DriveSection(title = stringResource(R.string.drive_traffic_light_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrafficLightGraphic(signal, Modifier.size(width = 44.dp, height = 108.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    signalLabel(signal),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    color = color,
                )
                signal?.let {
                    Text(
                        stringResource(R.string.drive_signal_group, it.signalGroup),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    it.conservativeEndSeconds?.let { seconds ->
                        Text(
                            stringResource(R.string.drive_signal_ends_in, seconds.toInt()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                state.intersection?.let { match ->
                    Text(
                        match.lane.intersectionName?.takeIf { it.isNotBlank() }
                            ?: "Junction ${match.lane.intersectionKey}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Lane ${match.lane.laneId} · ${if (match.lane.approaching) "approaching" else "past stop line"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (signal == null) {
                    Text(
                        stringResource(R.string.drive_no_intersections),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun TrafficLightGraphic(signal: SignalSnapshot?, modifier: Modifier = Modifier) {
    val red = MaterialTheme.colorScheme.error
    val yellow = Color(0xFFF2B33D)
    val green = MaterialTheme.colorScheme.tertiary
    val off = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeRed = signal?.isRed == true
    val activeGreen = signal?.isGreen == true
    val activeYellow = signal != null && !activeRed && !activeGreen
    Canvas(modifier) {
        drawRoundRect(
            color = Color(0xFF17191C),
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 3f),
        )
        val radius = size.width * 0.28f
        val x = size.width / 2f
        val positions = listOf(size.height * 0.18f, size.height * 0.5f, size.height * 0.82f)
        listOf(
            if (activeRed) red else off.copy(alpha = 0.35f),
            if (activeYellow) yellow else off.copy(alpha = 0.35f),
            if (activeGreen) green else off.copy(alpha = 0.35f),
        ).forEachIndexed { index, color ->
            drawCircle(color = color, radius = radius, center = Offset(x, positions[index]))
        }
    }
}

@Composable
private fun SafetySummary(state: DriveSnapshot, handlers: DriveUiHandlers) {
    val primary = state.primaryAlert
    DriveSection(title = stringResource(R.string.drive_safety_title)) {
        if (primary == null) {
            Text(
                stringResource(R.string.drive_alerts_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = priorityColor(primary.priority),
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PriorityChip(primary.priority)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            primary.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "${distanceLabel(primary.distanceM)} · ${eventDirectionLabel(primary)} · " +
                            primary.remainingValidityLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { handlers.onDismissAlert(primary.actionId) }) {
                    Text(stringResource(R.string.drive_dismiss))
                }
            }
            if (state.alerts.size > 1) {
                Text(
                    stringResource(R.string.drive_alerts_active, state.alerts.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { handlers.onOpenPage(AppPage.SafetyAlerts) }) {
                Text("All safety alerts")
            }
        }
        if (primary == null && state.activeAlertCount > 0) {
            Text(
                stringResource(R.string.drive_alerts_active, state.activeAlertCount),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DataQualityCard(state: DriveSnapshot) {
    DriveSection(title = "Data quality") {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column {
                Text("Freshness", style = MaterialTheme.typography.labelSmall)
                Text(
                    freshnessText(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.dataStale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            Column {
                Text("Rate", style = MaterialTheme.typography.labelSmall)
                Text(
                    String.format(java.util.Locale.US, "%.1f/s", state.diagnostics.messagesPerSecond),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column {
                Text("Matched lane", style = MaterialTheme.typography.labelSmall)
                Text(
                    state.intersection?.let { "Lane ${it.lane.laneId}" } ?: "–",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.dataStale) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.drive_data_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
