package org.opentrafficmap.citstogo.drive.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSettingsTest {
    @Test
    fun defaultsAreSane() {
        val settings = DriveSettings()
        assertEquals(4711, settings.hardwarePort)
        assertFalse(settings.hasHardwareAddress)
        assertTrue(settings.backgroundOperation)
        assertEquals(50, settings.defaultSpeedLimitKph)
        assertFalse(settings.onboardingCompleted)
    }

    @Test
    fun sanitizeClampsAndNormalizesTheAddress() {
        val settings = DriveSettings(
            hardwareHost = "  tcp://192.168.0.10/ ",
            hardwarePort = 99_999,
            reconnectDelaySeconds = 0,
            connectTimeoutSeconds = 0,
            locationIntervalMs = 10,
            defaultSpeedLimitKph = 999,
            staleDataSeconds = 0,
            eventHistoryLimit = 10,
        ).sanitized()
        assertEquals("192.168.0.10", settings.hardwareHost)
        assertEquals(65_535, settings.hardwarePort)
        assertEquals(1, settings.reconnectDelaySeconds)
        assertEquals(1, settings.connectTimeoutSeconds)
        assertEquals(200, settings.locationIntervalMs)
        assertEquals(250, settings.defaultSpeedLimitKph)
        assertEquals(1, settings.staleDataSeconds)
        assertEquals(20, settings.eventHistoryLimit)
        assertTrue(settings.hasHardwareAddress)
    }

    @Test
    fun documentRoundTrips() {
        val original = DriveSettings(
            hardwareHost = "fe80::1",
            hardwarePort = 5000,
            autoConnect = true,
            framing = TcpFraming.Cobs,
            locationPriority = LocationPriority.HighAccuracy,
            simulationEnabled = true,
            onboardingCompleted = true,
            defaultSpeedLimitKph = 30,
        )
        val decoded = DriveSettings.fromJson(original.toJson())
        assertEquals(original, decoded)
    }

    @Test
    fun malformedDocumentKeepsDefaults() {
        val decoded = DriveSettings.fromJson("{\"hardwarePort\": \"oops\"}")
        assertEquals(DriveSettings().hardwarePort, decoded.hardwarePort)
        assertEquals(DriveSettings(), DriveSettings.fromJson("not json"))
    }

    @Test
    fun repositoryPersistsAndEmitsTheNewValue() {
        val store = InMemoryKeyValueStore()
        val repository = DriveSettingsRepository(store)
        repository.update { it.copy(hardwareHost = "10.0.0.2", hardwarePort = 1234) }
        assertEquals("10.0.0.2", repository.current().hardwareHost)
        val reopened = DriveSettingsRepository(store)
        assertEquals("10.0.0.2", reopened.current().hardwareHost)
        assertEquals(1234, reopened.current().hardwarePort)
    }

    @Test
    fun unknownFramingFallsBackWithoutLosingOtherFields() {
        val raw = """{"framing":"future-frame-mode","hardwareHost":"1.2.3.4"}"""
        val decoded = DriveSettings.fromJson(raw)
        assertEquals(TcpFraming.LengthPrefixed4BigEndian, decoded.framing)
        assertEquals("1.2.3.4", decoded.hardwareHost)
    }
}
