package org.opentrafficmap.citstogo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opentrafficmap.citstogo.drive.config.DriveSettings
import org.opentrafficmap.citstogo.drive.engine.DriveSnapshot
import org.opentrafficmap.citstogo.drive.model.SignalSnapshot
import org.opentrafficmap.citstogo.drive.net.ConnectionTestResult
import org.opentrafficmap.citstogo.drive.profile.AlertPriority
import org.opentrafficmap.citstogo.drive.profile.DriverProfile
import org.opentrafficmap.citstogo.drive.profile.ProfileState
import org.opentrafficmap.citstogo.drive.safety.ActionId
import org.opentrafficmap.citstogo.drive.safety.SafetyEvent
import org.opentrafficmap.citstogo.intersection.MovementPhaseState

/** Callbacks that connect the drive screens to the activity, service and repositories. */
data class DriveUiHandlers(
    val onStartService: () -> Unit = {},
    val onStopService: () -> Unit = {},
    val onReconnectService: () -> Unit = {},
    val onUpdateSettings: (DriveSettings) -> Unit = {},
    val onUpdateProfile: (DriverProfile) -> Unit = {},
    val onSelectProfile: (String) -> Unit = {},
    val onCreateProfile: (String) -> Unit = {},
    val onDuplicateProfile: (String) -> Unit = {},
    val onDeleteProfile: (String) -> Unit = {},
    val onDismissAlert: (ActionId) -> Unit = {},
    val onTestConnection: suspend (String, Int) -> ConnectionTestResult = { _, _ ->
        ConnectionTestResult(false, "Connection testing unavailable", 0)
    },
    val onExportConfig: () -> Unit = {},
    val onImportConfig: () -> Unit = {},
    val onExportDiagnostics: (String) -> Unit = {},
    val onRequestLocationPermission: () -> Unit = {},
    val onRequestNotificationPermission: () -> Unit = {},
    val onOpenPage: (AppPage) -> Unit = {},
    val onCompleteOnboarding: () -> Unit = {},
    val onResetOnboarding: () -> Unit = {},
    val onSwitchTheme: (ThemeMode) -> Unit = {},
)

@Composable
fun DriveSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    detail: String? = null,
) {
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(color, CircleShape))
}

@Composable
fun PriorityChip(priority: AlertPriority) {
    val color = priorityColor(priority)
    Text(
        text = when (priority) {
            AlertPriority.Critical -> "CRITICAL"
            AlertPriority.Advisory -> "ADVISORY"
            AlertPriority.Informational -> "INFO"
        },
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun priorityColor(priority: AlertPriority): Color = when (priority) {
    AlertPriority.Critical -> MaterialTheme.colorScheme.error
    AlertPriority.Advisory -> MaterialTheme.colorScheme.tertiary
    AlertPriority.Informational -> MaterialTheme.colorScheme.primary
}

@Composable
fun signalColor(signal: SignalSnapshot?): Color = when {
    signal == null -> MaterialTheme.colorScheme.outline
    signal.isGreen -> MaterialTheme.colorScheme.tertiary
    signal.isRed -> MaterialTheme.colorScheme.error
    else -> Color(0xFFF2B33D)
}

@Composable
fun signalLabel(signal: SignalSnapshot?): String = when {
    signal == null -> stringResource(R.string.drive_signal_unknown)
    signal.isGreen -> stringResource(R.string.drive_signal_green)
    signal.isRed -> stringResource(R.string.drive_signal_red)
    else -> stringResource(R.string.drive_signal_yellow)
}

fun signalLabelPlain(state: MovementPhaseState?): String = when (state) {
    MovementPhaseState.ProtectedAllowed, MovementPhaseState.PermissiveAllowed -> "Green"
    MovementPhaseState.StopAndRemain, MovementPhaseState.Dark -> "Red"
    MovementPhaseState.PermissiveClearance, MovementPhaseState.ProtectedClearance,
    MovementPhaseState.CautionConflictingTraffic, MovementPhaseState.StopThenProceed,
    -> "Transition"
    MovementPhaseState.PreMovement -> "Prepare"
    MovementPhaseState.Unavailable, MovementPhaseState.Unknown, null -> "Unknown"
}

fun freshnessText(state: DriveSnapshot): String {
    if (state.simulated) return "Simulation (offline data)"
    val age = state.dataFreshnessMs ?: return "No C-ITS data yet"
    return when {
        age < 1_000 -> "Updated just now"
        age < 60_000 -> "Updated ${age / 1_000} s ago"
        else -> "Updated ${age / 60_000} min ago"
    }
}

fun eventDirectionLabel(event: SafetyEvent): String = when (event.relativeDirection) {
    org.opentrafficmap.citstogo.drive.safety.RelativeDirection.Ahead -> "Ahead"
    org.opentrafficmap.citstogo.drive.safety.RelativeDirection.Behind -> "Behind"
    org.opentrafficmap.citstogo.drive.safety.RelativeDirection.Left -> "Ahead left"
    org.opentrafficmap.citstogo.drive.safety.RelativeDirection.Right -> "Ahead right"
    org.opentrafficmap.citstogo.drive.safety.RelativeDirection.Unknown -> "Direction unknown"
}

fun distanceLabel(meters: Double?): String = when {
    meters == null -> "Distance unknown"
    meters < 1_000 -> "${meters.toInt()} m"
    else -> String.format(java.util.Locale.US, "%.1f km", meters / 1_000.0)
}

fun profileSummary(profile: DriverProfile): String = buildString {
    append(if (profile.glosaEnabled) "GLOSA on" else "GLOSA off")
    append(" · cap ${profile.maxSpeedCapKph} km/h")
    append(" · ${profile.enabledCategories.size} alert categories")
    if (profile.quietMode) append(" · quiet")
}

fun selectedProfileOrNull(state: ProfileState): DriverProfile? =
    state.profiles.firstOrNull { it.id == state.selectedId }
