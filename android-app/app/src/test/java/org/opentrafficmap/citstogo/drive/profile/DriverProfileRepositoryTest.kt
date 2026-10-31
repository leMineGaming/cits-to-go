package org.opentrafficmap.citstogo.drive.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.drive.config.InMemoryKeyValueStore
import org.opentrafficmap.citstogo.drive.config.DriveConfigDocument
import org.opentrafficmap.citstogo.drive.config.DriveSettings
import org.opentrafficmap.citstogo.drive.model.DenmCategory

class DriverProfileRepositoryTest {
    @Test
    fun defaultProfileIsSelectedOnFirstLaunch() {
        val repository = DriverProfileRepository(InMemoryKeyValueStore())
        assertEquals(1, repository.current().profiles.size)
        assertEquals(DriverProfile.DEFAULT_ID, repository.current().selectedId)
        assertTrue(repository.current().selected.glosaEnabled)
    }

    @Test
    fun profilesRoundTripThroughTheStore() {
        val store = InMemoryKeyValueStore()
        val first = DriverProfileRepository(store)
        val created = first.create("Night driving")
        first.update(created.copy(maxSpeedCapKph = 90, quietMode = true))
        first.select(created.id)

        val reopened = DriverProfileRepository(store)
        assertEquals(2, reopened.current().profiles.size)
        assertEquals(created.id, reopened.current().selectedId)
        val loaded = reopened.current().selected
        assertEquals("Night driving", loaded.name)
        assertEquals(90, loaded.maxSpeedCapKph)
        assertTrue(loaded.quietMode)
    }

    @Test
    fun duplicateCreatesAnIndependentProfile() {
        val repository = DriverProfileRepository(InMemoryKeyValueStore())
        repository.update(
            repository.current().selected.copy(
                name = "Comfort",
                comfortableAccelerationMps2 = 0.6f,
                enabledCategories = setOf(DenmCategory.Accident),
            ),
        )
        val copy = repository.duplicate(DriverProfile.DEFAULT_ID)!!
        assertNotEquals(DriverProfile.DEFAULT_ID, copy.id)
        assertTrue(copy.name.contains("copy"))
        repository.update(copy.copy(comfortableAccelerationMps2 = 1.8f))
        val original = repository.current().profiles.first { it.id == DriverProfile.DEFAULT_ID }
        assertEquals(0.6f, original.comfortableAccelerationMps2)
        assertEquals(1.8f, repository.current().profiles.first { it.id == copy.id }.comfortableAccelerationMps2)
    }

    @Test
    fun selectedProfileCannotBeDeletedAndLastProfileIsProtected() {
        val repository = DriverProfileRepository(InMemoryKeyValueStore())
        assertFalse(repository.delete(DriverProfile.DEFAULT_ID))
        val second = repository.create("Second")
        val selectedId = repository.current().selectedId
        assertFalse(repository.delete(selectedId))
        assertTrue(repository.delete(second.id).not())
        repository.select(second.id)
        assertTrue(repository.delete(DriverProfile.DEFAULT_ID))
        assertEquals(second.id, repository.current().selectedId)
    }

    @Test
    fun corruptDocumentFallsBackToTheDefault() {
        val store = InMemoryKeyValueStore(mapOf(DriverProfileRepository.KEY_DOCUMENT to "{not json"))
        val repository = DriverProfileRepository(store)
        assertEquals(1, repository.current().profiles.size)
        assertEquals(DriverProfile.DEFAULT_ID, repository.current().selectedId)
    }

    @Test
    fun olderDocumentKeepsItsValuesAndAddsNewDefaults() {
        val old = """{"schemaVersion":1,"selectedId":"default","profiles":[{"id":"default","name":"Old profile","maxSpeedCapKph":100}]}"""
        val repository = DriverProfileRepository(
            InMemoryKeyValueStore(mapOf(DriverProfileRepository.KEY_DOCUMENT to old)),
        )
        val profile = repository.current().selected
        assertEquals("Old profile", profile.name)
        assertEquals(100, profile.maxSpeedCapKph)
        assertEquals(DriverProfile.default().comfortableAccelerationMps2, profile.comfortableAccelerationMps2)
        assertTrue(profile.enabledCategories.isNotEmpty())
    }

    @Test
    fun exportedConfigurationImportsWithTheSameProfileSelection() {
        val repository = DriverProfileRepository(InMemoryKeyValueStore())
        val second = repository.create("Towing")
        repository.select(second.id)
        val document = DriveConfigDocument(
            DriveSettings(hardwareHost = "192.168.1.50", hardwarePort = 4000),
            repository.current(),
        )
        val decoded = assertNotNull(DriveConfigDocument.fromJson(document.toJson())).let {
            DriveConfigDocument.fromJson(document.toJson())!!
        }
        assertEquals("192.168.1.50", decoded.settings.hardwareHost)
        assertEquals(4000, decoded.settings.hardwarePort)
        assertEquals(second.id, decoded.profileState.selectedId)
        assertEquals(2, decoded.profileState.profiles.size)
    }

    @Test
    fun invalidConfigurationIsRejected() {
        assertEquals(null, DriveConfigDocument.fromJson("not json"))
        assertEquals(null, DriveConfigDocument.fromJson("""{"document":"other"}"""))
    }
}
