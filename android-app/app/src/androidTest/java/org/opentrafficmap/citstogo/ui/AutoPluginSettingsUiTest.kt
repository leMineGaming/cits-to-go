package org.opentrafficmap.citstogo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.opentrafficmap.citstogo.SettingsPage
import org.opentrafficmap.citstogo.ThemeMode
import org.opentrafficmap.citstogo.livemap.MapHistoryDays
import org.opentrafficmap.citstogo.livemap.MapStationTimeout
import org.opentrafficmap.citstogo.srem.SremProfile

/**
 * Covers the **Receiver on plug-in** card: recording waits for the connection, and it waits for the
 * folder it has to write into, because a plug-in cannot answer a save dialog the way
 * `MainActivity.choosePcapFile` does.
 */
class AutoPluginSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun recordingSwitchWaitsForAutoConnect() {
        compose.setContent { settings(autoConnect = false, autoRecord = true) }

        compose.onNodeWithTag("auto_connect_switch").assertExists()
        compose.onNodeWithTag("auto_record_switch").assertIsNotEnabled()
        compose.onNodeWithText(NEEDS_CONNECT)
            
            .assertExists()
    }

    @Test
    fun recordingIsEnoughOnItsOwnOnceConnectIsOn() {
        compose.setContent { settings(autoConnect = true, autoRecord = true) }

        compose.onNodeWithTag("auto_record_switch").assertIsEnabled()
        compose.onNodeWithText(NEEDS_CONNECT).assertDoesNotExist()
    }

    @Test
    fun recordingWithoutAFolderSaysTheConnectionStillStarts() {
        compose.setContent { settings(autoConnect = true, autoRecord = true) }

        compose.onNodeWithText("Recording folder").assertExists()
        compose.onNodeWithText("No folder chosen yet").assertExists()
        compose.onNodeWithText(NO_FOLDER).assertExists()
    }

    @Test
    fun aChosenFolderIsNamedAndTheWarningGoesAway() {
        compose.setContent { settings(autoConnect = true, autoRecord = true, folderLabel = "Download/CITS") }

        compose.onNodeWithText("Download/CITS").assertExists()
        compose.onNodeWithText(NO_FOLDER).assertDoesNotExist()
    }

    @Test
    fun noFolderIsAskedForWhileRecordingStaysOff() {
        compose.setContent { settings(autoConnect = true, autoRecord = false) }

        compose.onNodeWithText("Recording folder").assertDoesNotExist()
    }

    @Composable
    private fun settings(
        autoConnect: Boolean,
        autoRecord: Boolean,
        folderLabel: String = "",
    ) {
        MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SettingsPage(
                    mqttUri = "mqtt://localhost:1883",
                    mqttEnabled = false,
                    onMqttEnabledChange = {},
                    nodeId = "node-1",
                    maxQueueLength = "100",
                    maxQueueAgeSeconds = "0.2",
                    mapStationTimeoutSeconds = MapStationTimeout.DEFAULT_SECONDS,
                    mapHistoryDays = MapHistoryDays.ALL,
                    vehicleDimensions = emptyMap(),
                    onSaveVehicleDimensions = { _, _ -> },
                    txApproved = false,
                    sremProfile = SremProfile.PEDESTRIAN,
                    bridgeRunning = false,
                    bluetoothEnrollmentRunning = false,
                    bluetoothEnrollmentMessage = "",
                    bluetoothEnrollmentError = false,
                    debugMenuEnabled = false,
                    onDebugMenuEnabledChange = {},
                    themeMode = ThemeMode.System,
                    onThemeModeChange = {},
                    displayAlwaysOnEnabled = false,
                    onDisplayAlwaysOnEnabledChange = {},
                    batteryOptimizationIgnored = true,
                    onOpenBatteryOptimizationSettings = {},
                    stationDiscoveryNotificationEnabled = false,
                    onStationDiscoveryNotificationChange = {},
                    appUpdateNotificationEnabled = false,
                    onAppUpdateNotificationChange = {},
                    autoConnectOnPluginEnabled = autoConnect,
                    onAutoConnectOnPluginChange = {},
                    autoRecordOnPluginEnabled = autoRecord,
                    onAutoRecordOnPluginChange = {},
                    autoRecordFolderLabel = folderLabel,
                    onChooseAutoRecordFolder = {},
                    onEnrollBluetooth = {},
                    onRevokeTxApproval = {},
                    onSave = { _, _, _, _, _, _, _ -> },
                )
            }
        }
    }

    private companion object {
        // The two texts the card's promises turn on, spelled as strings.xml has them.
        const val NEEDS_CONNECT = "Auto PCAP recording needs Auto connect. Turn it on above."
        const val NO_FOLDER = "Without a folder there is nowhere to record. The connection still starts."
    }
}
