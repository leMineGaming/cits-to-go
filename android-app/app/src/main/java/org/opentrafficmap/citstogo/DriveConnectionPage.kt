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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.opentrafficmap.citstogo.drive.config.DriveSettings
import org.opentrafficmap.citstogo.drive.config.LocationPriority
import org.opentrafficmap.citstogo.drive.config.TcpFraming
import org.opentrafficmap.citstogo.drive.engine.DriveSnapshot
import org.opentrafficmap.citstogo.drive.net.ConnectionPhase
import org.opentrafficmap.citstogo.drive.net.ConnectionTestResult

@Composable
fun DriveConnectionPage(
    state: DriveSnapshot,
    settings: DriveSettings,
    handlers: DriveUiHandlers,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var host by rememberSaveable(settings.hardwareHost) { mutableStateOf(settings.hardwareHost) }
    var portText by rememberSaveable(settings.hardwarePort) { mutableStateOf(settings.hardwarePort.toString()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    val port = portText.toIntOrNull()
    val portValid = port != null && port in 1..65_535

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DriveSection(title = "Link status") {
            Text(
                when {
                    state.simulated -> "Simulation mode is active; the TCP link is not used."
                    state.connection.connected -> "Connected to ${state.connection.address}"
                    state.connection.phase == ConnectionPhase.Connecting -> "Connecting to ${state.connection.address}"
                    state.connection.phase == ConnectionPhase.Reconnecting ->
                        "Reconnecting to ${state.connection.address}"
                    else -> "Not connected"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (state.connection.connected) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurface,
            )
            state.connection.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Attempts ${state.connection.attempts}", style = MaterialTheme.typography.bodySmall)
                Text("Reconnects ${state.connection.reconnects}", style = MaterialTheme.typography.bodySmall)
                Text("Frames ${state.connection.framesReceived}", style = MaterialTheme.typography.bodySmall)
                Text("Bytes ${state.connection.bytesReceived}", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.connection.phase == ConnectionPhase.Stopped && !state.simulated) {
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
                    OutlinedButton(onClick = handlers.onReconnectService) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.drive_reconnect))
                    }
                }
            }
        }

        DriveSection(title = stringResource(R.string.drive_connection_title)) {
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
                supportingText = {
                    if (portText.isNotEmpty() && !portValid) Text("Port must be 1–65535")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        handlers.onUpdateSettings(
                            settings.copy(
                                hardwareHost = host.trim(),
                                hardwarePort = port ?: settings.hardwarePort,
                            ),
                        )
                    },
                    enabled = portValid,
                ) { Text(stringResource(R.string.drive_save)) }
                OutlinedButton(
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
            }
            testResult?.let { result ->
                Text(
                    result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
                Text(
                    "Elapsed ${result.elapsedMs} ms · ${result.bytesRead} bytes read",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "This address is for a network C-ITS receiver that streams raw 802.11/GeoNetworking frames over TCP. The USB ESP32-C5 does not use a TCP address: leave this blank and start the capture from the Capture page; its packets are also shown on the Drive dashboard.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DriveSection(title = stringResource(R.string.drive_framing)) {
            TcpFraming.entries.forEach { framing ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.framing == framing,
                            onClick = { handlers.onUpdateSettings(settings.copy(framing = framing)) },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.framing == framing,
                        onClick = { handlers.onUpdateSettings(settings.copy(framing = framing)) },
                    )
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(framing.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            framing.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        DriveSection(title = "Connection behaviour") {
            SwitchRow(
                title = stringResource(R.string.drive_auto_connect),
                checked = settings.autoConnect,
                onCheckedChange = { handlers.onUpdateSettings(settings.copy(autoConnect = it)) },
            )
            SwitchRow(
                title = stringResource(R.string.drive_background_operation),
                detail = "Keeps the TCP link and location active while the screen is off.",
                checked = settings.backgroundOperation,
                onCheckedChange = { handlers.onUpdateSettings(settings.copy(backgroundOperation = it)) },
            )
            SliderRow(
                title = stringResource(R.string.drive_reconnect_delay),
                value = settings.reconnectDelaySeconds.toFloat(),
                valueLabel = "${settings.reconnectDelaySeconds} s",
                range = 1f..30f,
                steps = 28,
                onChange = { handlers.onUpdateSettings(settings.copy(reconnectDelaySeconds = it.toInt())) },
            )
            SliderRow(
                title = stringResource(R.string.drive_connect_timeout),
                value = settings.connectTimeoutSeconds.toFloat(),
                valueLabel = "${settings.connectTimeoutSeconds} s",
                range = 2f..30f,
                steps = 27,
                onChange = { handlers.onUpdateSettings(settings.copy(connectTimeoutSeconds = it.toInt())) },
            )
        }

        DriveSection(title = "Location") {
            SliderRow(
                title = stringResource(R.string.drive_location_interval),
                value = settings.locationIntervalMs.toFloat(),
                valueLabel = String.format(java.util.Locale.US, "%.1f s", settings.locationIntervalMs / 1000f),
                range = 500f..5_000f,
                steps = 17,
                onChange = {
                    val rounded = (it / 250).toInt() * 250
                    handlers.onUpdateSettings(settings.copy(locationIntervalMs = rounded))
                },
            )
            LocationPriority.entries.forEach { priority ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.locationPriority == priority,
                            onClick = { handlers.onUpdateSettings(settings.copy(locationPriority = priority)) },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.locationPriority == priority,
                        onClick = { handlers.onUpdateSettings(settings.copy(locationPriority = priority)) },
                    )
                    Text(priority.label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            handlers.onRequestLocationPermission.let {
                OutlinedButton(onClick = it) { Text(stringResource(R.string.drive_permission_location)) }
            }
        }

        DriveSection(title = "Speed") {
            SliderRow(
                title = stringResource(R.string.drive_default_speed_limit),
                value = settings.defaultSpeedLimitKph.toFloat(),
                valueLabel = "${settings.defaultSpeedLimitKph} km/h",
                range = 10f..130f,
                steps = 23,
                onChange = {
                    val rounded = (it / 5).toInt() * 5
                    handlers.onUpdateSettings(settings.copy(defaultSpeedLimitKph = rounded))
                },
            )
            Text(
                "Used when the MAPEM does not carry a regulatory speed limit. The driver profile cap always wins if it is lower.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    detail: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SliderRow(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}
