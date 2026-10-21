package org.opentrafficmap.citstogo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.opentrafficmap.citstogo.CAM_INTERVAL_STRESS_WARNING_TAG
import org.opentrafficmap.citstogo.CamBroadcastPage
import org.opentrafficmap.citstogo.bridge.BridgeStatus
import org.opentrafficmap.citstogo.srem.SremProfile

/**
 * Covers the warning that follows the CAM broadcast interval: anything below the standard's 100 ms
 * has to be said out loud, whether or not the debug menu currently allows such an interval.
 */
class CamIntervalWarningUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun anIntervalBelowTheStandardWarnsEvenWithoutTheDebugMenu() {
        compose.setContent { camPage(interval = "50", debugMenu = false) }

        compose.onNodeWithTag(CAM_INTERVAL_STRESS_WARNING_TAG).assertExists()
        compose.onNodeWithText(TITLE).assertExists()
        compose.onNodeWithText(BODY).assertExists()
        compose.onNodeWithText(START_USES_STANDARD).assertExists()
    }

    @Test
    fun aStandardIntervalIsQuiet() {
        compose.setContent { camPage(interval = "100", debugMenu = true) }

        compose.onNodeWithTag(CAM_INTERVAL_STRESS_WARNING_TAG).assertDoesNotExist()
    }

    @Test
    fun aNumberTheFieldCannotMeanIsQuietToo() {
        compose.setContent { camPage(interval = "", debugMenu = true) }

        compose.onNodeWithTag(CAM_INTERVAL_STRESS_WARNING_TAG).assertDoesNotExist()
    }

    @Test
    fun theDebugMenuSaysWhereTheRangeComesFrom() {
        compose.setContent { camPage(interval = "1", debugMenu = true) }

        compose.onNodeWithTag(CAM_INTERVAL_STRESS_WARNING_TAG).assertExists()
        compose.onNodeWithText(FROM_DEBUG_MENU).assertExists()
        compose.onNodeWithText(START_USES_STANDARD).assertDoesNotExist()
    }

    @Test
    fun theFieldDescribesTheRangeTheMenuItselfAllows() {
        compose.setContent { camPage(interval = "500", debugMenu = true) }

        compose.onNodeWithText(STRESS_RANGE).assertExists()
        compose.onNodeWithText(STANDARD_RANGE).assertDoesNotExist()
    }

    @Test
    fun withoutTheMenuTheFieldStaysInsideTheStandard() {
        compose.setContent { camPage(interval = "500", debugMenu = false) }

        compose.onNodeWithText(STANDARD_RANGE).assertExists()
        compose.onNodeWithText(STRESS_RANGE).assertDoesNotExist()
    }

    @Composable
    private fun camPage(interval: String, debugMenu: Boolean) {
        MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                CamBroadcastPage(
                    status = BridgeStatus(running = true),
                    logLine = "",
                    intervalMs = interval,
                    onIntervalChange = {},
                    debugMenuEnabled = debugMenu,
                    randomizationEnabled = false,
                    onRandomizationEnabledChange = {},
                    randomizationIntervalSeconds = "60",
                    onRandomizationIntervalChange = {},
                    onConfigure = {},
                    vehicleProfile = SremProfile.PEDESTRIAN,
                    trailerProfiles = emptySet(),
                    onVehicleProfileChange = {},
                )
            }
        }
    }

    private companion object {
        // The texts the card and the field build, spelled as strings.xml has them.
        const val TITLE = "Outside the C-ITS standard"
        const val BODY =
            "An interval below 100 ms broadcasts more than the 10 CAMs per second that ETSI EN 302 " +
            "637-2 allows. This is explicitly against the standard, it will most likely overload the " +
            "other participants in range with a flood of CAMs, and it is most likely illegal on public " +
            "roads. Stress test only, in a shielded and authorised environment."
        const val START_USES_STANDARD =
            "Start sends 100 ms instead. This range needs Debug enabled in the Settings."
        const val FROM_DEBUG_MENU = "This range was unlocked by enabling Debug in the Settings."
        const val STANDARD_RANGE = "Allowed CAM range: 100–1000 ms"
        const val STRESS_RANGE = "Stress-test range: 1–1000 ms. The standard allows 100–1000 ms."
    }
}
