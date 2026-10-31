package org.opentrafficmap.citstogo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.opentrafficmap.citstogo.drive.model.DenmCategory
import org.opentrafficmap.citstogo.drive.profile.AlertTone
import org.opentrafficmap.citstogo.drive.profile.DriverProfile
import org.opentrafficmap.citstogo.drive.profile.ProfileState
import org.opentrafficmap.citstogo.drive.profile.TargetSpeedBehaviour

@Composable
fun DriveProfilesPage(
    profileState: ProfileState,
    handlers: DriveUiHandlers,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<DriverProfile?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DriveSection {
            Text(
                "A profile changes GLOSA comfort, speed cap, alert filtering and dashboard preferences at once. The selected profile is restored at the next start.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { handlers.onCreateProfile("New profile") }) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.drive_new_profile))
            }
        }
        profileState.profiles.forEach { profile ->
            val selected = profile.id == profileState.selectedId
            DriveSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selected,
                        onClick = { handlers.onSelectProfile(profile.id) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            profileSummary(profile),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { editing = profile }) {
                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.drive_edit))
                    }
                    IconButton(onClick = { handlers.onDuplicateProfile(profile.id) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.drive_duplicate))
                    }
                    IconButton(
                        onClick = { handlers.onDeleteProfile(profile.id) },
                        enabled = profileState.profiles.size > 1 && !selected,
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.drive_delete))
                    }
                }
                if (selected) {
                    Text(
                        "Selected · changes apply immediately",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
    editing?.let { profile ->
        ProfileEditorDialog(
            initial = profile,
            onDismiss = { editing = null },
            onSave = {
                handlers.onUpdateProfile(it)
                editing = null
            },
        )
    }
}

@Composable
private fun ProfileEditorDialog(
    initial: DriverProfile,
    onDismiss: () -> Unit,
    onSave: (DriverProfile) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_edit) + " · " + initial.name) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text(stringResource(R.string.drive_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    title = stringResource(R.string.drive_glosa_enabled),
                    checked = draft.glosaEnabled,
                    onCheckedChange = { draft = draft.copy(glosaEnabled = it) },
                )
                Text("Arrival strategy", style = MaterialTheme.typography.labelLarge)
                TargetSpeedBehaviour.entries.forEach { behaviour ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = draft.targetSpeedBehaviour == behaviour,
                                onClick = { draft = draft.copy(targetSpeedBehaviour = behaviour) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = draft.targetSpeedBehaviour == behaviour,
                            onClick = { draft = draft.copy(targetSpeedBehaviour = behaviour) },
                        )
                        Text(behaviour.label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                SliderRow(
                    title = stringResource(R.string.drive_comfort_accel),
                    value = draft.comfortableAccelerationMps2,
                    valueLabel = String.format(java.util.Locale.US, "%.1f m/s²", draft.comfortableAccelerationMps2),
                    range = 0.3f..3.0f,
                    steps = 26,
                    onChange = { draft = draft.copy(comfortableAccelerationMps2 = it) },
                )
                SliderRow(
                    title = stringResource(R.string.drive_comfort_decel),
                    value = draft.comfortableDecelerationMps2,
                    valueLabel = String.format(java.util.Locale.US, "%.1f m/s²", draft.comfortableDecelerationMps2),
                    range = 0.5f..5.0f,
                    steps = 44,
                    onChange = { draft = draft.copy(comfortableDecelerationMps2 = it) },
                )
                SliderRow(
                    title = stringResource(R.string.drive_speed_cap),
                    value = draft.maxSpeedCapKph.toFloat(),
                    valueLabel = "${draft.maxSpeedCapKph} km/h",
                    range = 30f..200f,
                    steps = 33,
                    onChange = { draft = draft.copy(maxSpeedCapKph = (it / 5).toInt() * 5) },
                )
                Text("Alert relevance distances", style = MaterialTheme.typography.labelLarge)
                SliderRow(
                    title = "Critical",
                    value = draft.criticalDistanceM.toFloat(),
                    valueLabel = "${draft.criticalDistanceM} m",
                    range = 100f..1_000f,
                    steps = 17,
                    onChange = { draft = draft.copy(criticalDistanceM = (it / 50).toInt() * 50) },
                )
                SliderRow(
                    title = "Advisory",
                    value = draft.advisoryDistanceM.toFloat(),
                    valueLabel = "${draft.advisoryDistanceM} m",
                    range = 200f..3_000f,
                    steps = 27,
                    onChange = { draft = draft.copy(advisoryDistanceM = (it / 100).toInt() * 100) },
                )
                SliderRow(
                    title = "Information",
                    value = draft.informationalDistanceM.toFloat(),
                    valueLabel = "${draft.informationalDistanceM} m",
                    range = 500f..5_000f,
                    steps = 17,
                    onChange = { draft = draft.copy(informationalDistanceM = (it / 250).toInt() * 250) },
                )
                Text(stringResource(R.string.drive_alert_categories), style = MaterialTheme.typography.labelLarge)
                DenmCategory.entries.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = category in draft.enabledCategories,
                                onClick = {
                                    draft = draft.copy(
                                        enabledCategories = if (category in draft.enabledCategories) {
                                            draft.enabledCategories - category
                                        } else {
                                            draft.enabledCategories + category
                                        },
                                    )
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = category in draft.enabledCategories,
                            onCheckedChange = { checked ->
                                draft = draft.copy(
                                    enabledCategories = if (checked) {
                                        draft.enabledCategories + category
                                    } else {
                                        draft.enabledCategories - category
                                    },
                                )
                            },
                        )
                        Text(category.label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                SwitchRow(
                    title = "Show notifications",
                    checked = draft.notificationsEnabled,
                    onCheckedChange = { draft = draft.copy(notificationsEnabled = it) },
                )
                SwitchRow(
                    title = stringResource(R.string.drive_audio_warnings),
                    checked = draft.audioWarningsEnabled,
                    onCheckedChange = { draft = draft.copy(audioWarningsEnabled = it) },
                )
                SwitchRow(
                    title = stringResource(R.string.drive_speech),
                    checked = draft.speechEnabled,
                    onCheckedChange = { draft = draft.copy(speechEnabled = it) },
                )
                SwitchRow(
                    title = stringResource(R.string.drive_quiet_mode),
                    checked = draft.quietMode,
                    onCheckedChange = { draft = draft.copy(quietMode = it) },
                )
                SwitchRow(
                    title = stringResource(R.string.drive_repeat_alerts),
                    checked = draft.repeatAlerts,
                    onCheckedChange = { draft = draft.copy(repeatAlerts = it) },
                )
                SwitchRow(
                    title = "Hide GLOSA when a critical hazard is ahead",
                    detail = "Keeps the hazard as the primary information.",
                    checked = draft.suppressGlosaForCriticalHazard,
                    onCheckedChange = { draft = draft.copy(suppressGlosaForCriticalHazard = it) },
                )
                SliderRow(
                    title = stringResource(R.string.drive_alert_volume),
                    value = draft.alertVolume,
                    valueLabel = "${(draft.alertVolume * 100).toInt()}%",
                    range = 0f..1f,
                    steps = 9,
                    onChange = { draft = draft.copy(alertVolume = it) },
                )
                Text("Alert tone", style = MaterialTheme.typography.labelLarge)
                AlertTone.entries.forEach { tone ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = draft.alertTone == tone,
                                onClick = { draft = draft.copy(alertTone = tone) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = draft.alertTone == tone,
                            onClick = { draft = draft.copy(alertTone = tone) },
                        )
                        Text(tone.label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Dashboard", style = MaterialTheme.typography.labelLarge)
                SwitchRow(
                    title = stringResource(R.string.drive_units_metric),
                    checked = draft.metricUnits,
                    onCheckedChange = { draft = draft.copy(metricUnits = it) },
                )
                SwitchRow(
                    title = stringResource(R.string.drive_large_text),
                    checked = draft.largeDashboardText,
                    onCheckedChange = { draft = draft.copy(largeDashboardText = it) },
                )
                SwitchRow(
                    title = stringResource(R.string.drive_keep_screen_on),
                    checked = draft.keepScreenOn,
                    onCheckedChange = { draft = draft.copy(keepScreenOn = it) },
                )
                SwitchRow(
                    title = "Show map preview on the dashboard",
                    checked = draft.showMapPreview,
                    onCheckedChange = { draft = draft.copy(showMapPreview = it) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.drive_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) }
        },
    )
}
