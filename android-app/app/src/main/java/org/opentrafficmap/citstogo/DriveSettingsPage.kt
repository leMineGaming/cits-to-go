package org.opentrafficmap.citstogo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.opentrafficmap.citstogo.drive.config.DriveSettings
import org.opentrafficmap.citstogo.drive.engine.DriveSnapshot

@Composable
fun DriveSettingsPage(
    state: DriveSnapshot,
    settings: DriveSettings,
    themeMode: ThemeMode,
    handlers: DriveUiHandlers,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DriveSection(title = "Data source") {
            SwitchRow(
                title = stringResource(R.string.drive_simulation),
                detail = stringResource(R.string.drive_simulation_on),
                checked = settings.simulationEnabled,
                onCheckedChange = { handlers.onUpdateSettings(settings.copy(simulationEnabled = it)) },
            )
            SliderRow(
                title = stringResource(R.string.drive_stale_after),
                value = settings.staleDataSeconds.toFloat(),
                valueLabel = stringResource(R.string.drive_seconds, settings.staleDataSeconds),
                range = 1f..20f,
                steps = 18,
                onChange = { handlers.onUpdateSettings(settings.copy(staleDataSeconds = it.toInt())) },
            )
            SliderRow(
                title = stringResource(R.string.drive_history_limit),
                value = settings.eventHistoryLimit.toFloat(),
                valueLabel = "${settings.eventHistoryLimit}",
                range = 50f..1_000f,
                steps = 18,
                onChange = {
                    val rounded = (it / 50).toInt() * 50
                    handlers.onUpdateSettings(settings.copy(eventHistoryLimit = rounded))
                },
            )
        }

        DriveSection(title = "Current driver profile") {
            Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(profileSummary(profile), style = MaterialTheme.typography.bodySmall)
            SwitchRow(
                title = stringResource(R.string.drive_units_metric),
                checked = profile.metricUnits,
                onCheckedChange = { handlers.onUpdateProfile(profile.copy(metricUnits = it)) },
            )
            SwitchRow(
                title = stringResource(R.string.drive_large_text),
                checked = profile.largeDashboardText,
                onCheckedChange = { handlers.onUpdateProfile(profile.copy(largeDashboardText = it)) },
            )
            SwitchRow(
                title = stringResource(R.string.drive_keep_screen_on),
                checked = profile.keepScreenOn,
                onCheckedChange = { handlers.onUpdateProfile(profile.copy(keepScreenOn = it)) },
            )
            OutlinedButton(onClick = { handlers.onOpenPage(AppPage.Profiles) }) {
                Text(stringResource(R.string.drive_profiles_title))
            }
        }

        DriveSection(title = "Appearance") {
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = themeMode == mode,
                            onClick = { handlers.onSwitchTheme(mode) },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = themeMode == mode,
                        onClick = { handlers.onSwitchTheme(mode) },
                    )
                    Text(
                        when (mode) {
                            ThemeMode.System -> "Follow system theme"
                            ThemeMode.Dark -> "Dark dashboard"
                            ThemeMode.Light -> "Light dashboard"
                        },
                        Modifier.padding(start = 4.dp),
                    )
                }
            }
            Text(
                "The dashboard is designed for a dark cabin and uses accessible contrast in both themes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DriveSection(title = "Notifications") {
            OutlinedButton(onClick = handlers.onRequestNotificationPermission) {
                Text(stringResource(R.string.drive_permission_notifications))
            }
            Text(
                "Android may also require battery optimisation to be disabled for continuous background reception.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DriveSection(title = "Configuration") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = handlers.onExportConfig) {
                    Text(stringResource(R.string.drive_export_config))
                }
                OutlinedButton(onClick = handlers.onImportConfig) {
                    Text(stringResource(R.string.drive_import_config))
                }
            }
            OutlinedButton(onClick = handlers.onResetOnboarding) {
                Text(stringResource(R.string.drive_replay_onboarding))
            }
            Text(
                "Exports contain the hardware address, driver profiles and preferences. They contain no credentials.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DriveSection(title = stringResource(R.string.drive_disclaimer_title)) {
            Text(
                stringResource(R.string.drive_disclaimer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.drive_auto_center),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
