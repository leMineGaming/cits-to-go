package org.opentrafficmap.citstogo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.opentrafficmap.citstogo.drive.config.DriveSettings
import org.opentrafficmap.citstogo.drive.config.TcpFraming
import org.opentrafficmap.citstogo.drive.net.ConnectionTestResult
import org.opentrafficmap.citstogo.drive.profile.ProfileState

@Composable
fun DriveOnboarding(
    settings: DriveSettings,
    profileState: ProfileState,
    locationPermissionGranted: Boolean,
    handlers: DriveUiHandlers,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableStateOf(0) }
    var host by rememberSaveable(settings.hardwareHost) { mutableStateOf(settings.hardwareHost) }
    var portText by rememberSaveable(settings.hardwarePort) { mutableStateOf(settings.hardwarePort.toString()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    val scope = rememberCoroutineScope()
    val port = portText.toIntOrNull()
    val portValid = port != null && port in 1..65_535
    val steps = 5

    fun complete(startService: Boolean) {
        handlers.onUpdateSettings(
            settings.copy(
                hardwareHost = host.trim(),
                hardwarePort = port ?: settings.hardwarePort,
                onboardingCompleted = true,
            ),
        )
        handlers.onCompleteOnboarding()
        if (startService) handlers.onStartService()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.drive_app_name),
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(
            progress = { (step + 1) / steps.toFloat() },
            modifier = Modifier.fillMaxWidth().height(5.dp),
        )
        Text(
            "Step ${step + 1} of $steps",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (step) {
            0 -> {
                DriveSection(title = stringResource(R.string.drive_onboarding_title)) {
                    Text(
                        "C-ITS Drive Assist turns SPATEM, MAPEM and DENM messages from a roadside receiver into a glanceable traffic-light, speed and safety dashboard.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.drive_onboarding_disclaimer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DriveSection(title = "What you need") {
                    Text("• A C-ITS receiver that streams frames over Wi-Fi TCP", style = MaterialTheme.typography.bodyMedium)
                    Text("• The receiver's IP address and TCP port", style = MaterialTheme.typography.bodyMedium)
                    Text("• Location permission for junction matching", style = MaterialTheme.typography.bodyMedium)
                    Text("• Optionally an Android Auto head unit or the Desktop Head Unit", style = MaterialTheme.typography.bodyMedium)
                }
            }
            1 -> {
                DriveSection(title = stringResource(R.string.drive_connection_title)) {
                    Text(
                        "Enter the address of your C-ITS hardware. It is stored only on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(R.string.drive_host)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.drive_port)) },
                        singleLine = true,
                        isError = portText.isNotEmpty() && !portValid,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("TCP framing", style = MaterialTheme.typography.labelLarge)
                    TcpFraming.entries.forEach { framing ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = settings.framing == framing,
                                    onClick = { handlers.onUpdateSettings(settings.copy(framing = framing)) },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.framing == framing,
                                onClick = { handlers.onUpdateSettings(settings.copy(framing = framing)) },
                            )
                            Text(framing.label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Button(
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = handlers.onTestConnection(host.trim(), port ?: 0)
                                testing = false
                            }
                        },
                        enabled = host.isNotBlank() && portValid && !testing,
                    ) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.drive_testing))
                        } else {
                            Text(stringResource(R.string.drive_test_connection))
                        }
                    }
                    testResult?.let {
                        Text(
                            it.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            2 -> {
                DriveSection(title = "Permissions and background operation") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Location", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        if (locationPermissionGranted) {
                            "Location permission granted. Junction matching can run."
                        } else {
                            stringResource(R.string.drive_location_permission_missing)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (locationPermissionGranted) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = handlers.onRequestLocationPermission) {
                        Text(stringResource(R.string.drive_permission_location))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Notifications, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Notifications", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "A foreground notification keeps the receiver and location active while the screen is off. Safety alerts use a high-priority notification channel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = handlers.onRequestNotificationPermission) {
                        Text(stringResource(R.string.drive_permission_notifications))
                    }
                }
            }
            3 -> {
                DriveSection(title = stringResource(R.string.drive_profiles_title)) {
                    Text(
                        "Choose the profile that matches how you drive. You can edit every comfort, speed and alert preference later.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    profileState.profiles.forEach { profile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = profile.id == profileState.selectedId,
                                    onClick = { handlers.onSelectProfile(profile.id) },
                                )
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = profile.id == profileState.selectedId,
                                onClick = { handlers.onSelectProfile(profile.id) },
                            )
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(profile.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    profileSummary(profile),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    OutlinedButton(onClick = { handlers.onCreateProfile("New profile") }) {
                        Text(stringResource(R.string.drive_new_profile))
                    }
                }
            }
            else -> {
                DriveSection(title = "Ready to drive") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Verified,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Configuration complete", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        if (host.isBlank()) "No receiver address saved. You can add it under C-ITS Connection." else
                            "Receiver: $host:${port ?: settings.hardwarePort}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Profile: ${profileState.selected.name}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Start the service now, or use the dashboard Start button at any time. If you have no hardware yet, enable Simulation in Drive Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.drive_onboarding_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 0) {
                OutlinedButton(onClick = { step-- }) {
                    Text(stringResource(R.string.drive_onboarding_back))
                }
            }
            Button(
                onClick = {
                    if (step < steps - 1) step++ else complete(startService = true)
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (step == steps - 1) stringResource(R.string.drive_onboarding_finish)
                    else stringResource(R.string.drive_onboarding_next),
                )
            }
        }
        TextButton(onClick = { complete(startService = false) }) {
            Text(stringResource(R.string.drive_onboarding_skip))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                "You can revisit this setup from Drive Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
