package org.opentrafficmap.citstogo.drive.config

import org.json.JSONArray
import org.json.JSONObject
import org.opentrafficmap.citstogo.drive.profile.DriverProfile
import org.opentrafficmap.citstogo.drive.profile.ProfileState

/**
 * Portable export/import document for the drive configuration and driver profiles.
 * The document contains no credentials or signing material. Import validates every field and
 * ignores unknown keys, so a file from a newer app version does not corrupt older settings.
 */
data class DriveConfigDocument(
    val settings: DriveSettings,
    val profileState: ProfileState,
) {
    fun toJson(): String = JSONObject().apply {
        put("document", "cits-drive-assist")
        put("schemaVersion", SCHEMA_VERSION)
        put("settings", JSONObject(settings.toJson()))
        put("profiles", JSONArray().apply { profileState.profiles.forEach { put(it.toJson()) } })
        put("selectedProfileId", profileState.selectedId)
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(raw: String): DriveConfigDocument? = try {
            val json = JSONObject(raw)
            if (json.optString("document") != "cits-drive-assist") {
                null
            } else {
                val settings = json.optJSONObject("settings")?.toString()?.let { DriveSettings.fromJson(it) }
                    ?: DriveSettings()
                val profiles = json.optJSONArray("profiles")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            array.optJSONObject(index)?.let { item ->
                                runCatching { add(DriverProfile.fromJson(item)) }
                            }
                        }
                    }
                }.orEmpty()
                val safeProfiles = profiles.ifEmpty { listOf(DriverProfile.default()) }
                val selected = json.optString("selectedProfileId").takeIf { id ->
                    safeProfiles.any { it.id == id }
                } ?: safeProfiles.first().id
                DriveConfigDocument(settings, ProfileState(safeProfiles, selected))
            }
        } catch (_: Exception) {
            null
        }
    }
}
