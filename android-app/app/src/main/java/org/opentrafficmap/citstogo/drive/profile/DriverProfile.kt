package org.opentrafficmap.citstogo.drive.profile

import org.json.JSONArray
import org.json.JSONObject
import org.opentrafficmap.citstogo.drive.config.InMemoryKeyValueStore
import org.opentrafficmap.citstogo.drive.config.KeyValueStore
import org.opentrafficmap.citstogo.drive.model.DenmCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How GLOSA balances a smooth arrival against keeping the current cruising speed. */
enum class TargetSpeedBehaviour(val label: String) {
    KeepCurrent("Keep current speed where possible"),
    SmoothArrival("Prioritise a smooth green arrival"),
    Economy("Fuel-saving, gentle acceleration"),
}

enum class AlertTone(val label: String) {
    SystemDefault("System default"),
    Chime("Chime"),
    Urgent("Urgent"),
    Silent("Silent"),
}

/**
 * A complete driver profile. Everything that changes how GLOSA or the safety alerts behave lives
 * here, so switching a profile updates all of it at once and none of it leaks into another profile.
 */
data class DriverProfile(
    val id: String = DEFAULT_ID,
    val schemaVersion: Int = SCHEMA_VERSION,
    val name: String = "Everyday driving",
    val glosaEnabled: Boolean = true,
    val comfortableAccelerationMps2: Float = 1.0f,
    val comfortableDecelerationMps2: Float = 1.5f,
    val targetSpeedBehaviour: TargetSpeedBehaviour = TargetSpeedBehaviour.SmoothArrival,
    val maxSpeedCapKph: Int = 130,
    val enabledCategories: Set<DenmCategory> = DenmCategory.entries.toSet(),
    val notificationsEnabled: Boolean = true,
    val audioWarningsEnabled: Boolean = true,
    val speechEnabled: Boolean = false,
    val quietMode: Boolean = false,
    val alertVolume: Float = 0.8f,
    val alertTone: AlertTone = AlertTone.SystemDefault,
    val criticalDistanceM: Int = 500,
    val advisoryDistanceM: Int = 1_000,
    val informationalDistanceM: Int = 1_500,
    val repeatAlerts: Boolean = false,
    val suppressGlosaForCriticalHazard: Boolean = true,
    val showMapPreview: Boolean = true,
    val keepScreenOn: Boolean = false,
    val largeDashboardText: Boolean = false,
    val metricUnits: Boolean = true,
) {
    fun sanitized(): DriverProfile = copy(
        name = name.trim().ifBlank { "Driver profile" }.take(60),
        comfortableAccelerationMps2 = comfortableAccelerationMps2.coerceIn(0.2f, 3.0f),
        comfortableDecelerationMps2 = comfortableDecelerationMps2.coerceIn(0.5f, 6.0f),
        maxSpeedCapKph = maxSpeedCapKph.coerceIn(10, 250),
        alertVolume = alertVolume.coerceIn(0f, 1f),
        criticalDistanceM = criticalDistanceM.coerceIn(50, 5_000),
        advisoryDistanceM = advisoryDistanceM.coerceIn(50, 10_000),
        informationalDistanceM = informationalDistanceM.coerceIn(50, 20_000),
    )

    /** The lower of the profile cap and the applicable road limit; never lets GLOSA speed past either. */
    fun effectiveSpeedLimitKph(roadLimitKph: Int?): Int =
        minOf(maxSpeedCapKph, roadLimitKph ?: maxSpeedCapKph)

    fun isCategoryEnabled(category: DenmCategory): Boolean = category in enabledCategories

    fun alertDistanceFor(category: DenmCategory, priority: AlertPriority): Int = when (priority) {
        AlertPriority.Critical -> criticalDistanceM
        AlertPriority.Advisory -> advisoryDistanceM
        AlertPriority.Informational -> informationalDistanceM
    }.coerceAtLeast(1)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("schemaVersion", SCHEMA_VERSION)
        put("name", name)
        put("glosaEnabled", glosaEnabled)
        put("comfortableAccelerationMps2", comfortableAccelerationMps2.toDouble())
        put("comfortableDecelerationMps2", comfortableDecelerationMps2.toDouble())
        put("targetSpeedBehaviour", targetSpeedBehaviour.name)
        put("maxSpeedCapKph", maxSpeedCapKph)
        put("enabledCategories", JSONArray(enabledCategories.map { it.name }.sorted()))
        put("notificationsEnabled", notificationsEnabled)
        put("audioWarningsEnabled", audioWarningsEnabled)
        put("speechEnabled", speechEnabled)
        put("quietMode", quietMode)
        put("alertVolume", alertVolume.toDouble())
        put("alertTone", alertTone.name)
        put("criticalDistanceM", criticalDistanceM)
        put("advisoryDistanceM", advisoryDistanceM)
        put("informationalDistanceM", informationalDistanceM)
        put("repeatAlerts", repeatAlerts)
        put("suppressGlosaForCriticalHazard", suppressGlosaForCriticalHazard)
        put("showMapPreview", showMapPreview)
        put("keepScreenOn", keepScreenOn)
        put("largeDashboardText", largeDashboardText)
        put("metricUnits", metricUnits)
    }

    companion object {
        const val DEFAULT_ID = "default"
        const val SCHEMA_VERSION = 2

        fun default() = DriverProfile()

        fun fromJson(json: JSONObject): DriverProfile {
            val fallback = default()
            fun bool(key: String, default: Boolean) = json.optBoolean(key, default)
            fun int(key: String, default: Int) = json.optInt(key, default)
            fun float(key: String, default: Float) = json.optDouble(key, default.toDouble()).toFloat()
            val categories = json.optJSONArray("enabledCategories")?.let { array ->
                buildSet {
                    for (index in 0 until array.length()) {
                        DenmCategory.entries.firstOrNull { it.name == array.optString(index) }
                            ?.let(::add)
                    }
                }
            }
            return DriverProfile(
                id = json.optString("id", fallback.id),
                name = json.optString("name", fallback.name),
                glosaEnabled = bool("glosaEnabled", fallback.glosaEnabled),
                comfortableAccelerationMps2 = float(
                    "comfortableAccelerationMps2",
                    fallback.comfortableAccelerationMps2,
                ),
                comfortableDecelerationMps2 = float(
                    "comfortableDecelerationMps2",
                    fallback.comfortableDecelerationMps2,
                ),
                targetSpeedBehaviour = TargetSpeedBehaviour.entries.firstOrNull {
                    it.name == json.optString("targetSpeedBehaviour")
                } ?: fallback.targetSpeedBehaviour,
                maxSpeedCapKph = int("maxSpeedCapKph", fallback.maxSpeedCapKph),
                // A profile written before category filtering existed keeps every category.
                enabledCategories = categories ?: fallback.enabledCategories,
                notificationsEnabled = bool("notificationsEnabled", fallback.notificationsEnabled),
                audioWarningsEnabled = bool("audioWarningsEnabled", fallback.audioWarningsEnabled),
                speechEnabled = bool("speechEnabled", fallback.speechEnabled),
                quietMode = bool("quietMode", fallback.quietMode),
                alertVolume = float("alertVolume", fallback.alertVolume),
                alertTone = AlertTone.entries.firstOrNull {
                    it.name == json.optString("alertTone")
                } ?: fallback.alertTone,
                criticalDistanceM = int("criticalDistanceM", fallback.criticalDistanceM),
                advisoryDistanceM = int("advisoryDistanceM", fallback.advisoryDistanceM),
                informationalDistanceM = int("informationalDistanceM", fallback.informationalDistanceM),
                repeatAlerts = bool("repeatAlerts", fallback.repeatAlerts),
                suppressGlosaForCriticalHazard = bool(
                    "suppressGlosaForCriticalHazard",
                    fallback.suppressGlosaForCriticalHazard,
                ),
                showMapPreview = bool("showMapPreview", fallback.showMapPreview),
                keepScreenOn = bool("keepScreenOn", fallback.keepScreenOn),
                largeDashboardText = bool("largeDashboardText", fallback.largeDashboardText),
                metricUnits = bool("metricUnits", fallback.metricUnits),
            ).sanitized()
        }
    }
}

/** Priority levels used by the safety system and the dashboard coordinator. */
enum class AlertPriority {
    Critical,
    Advisory,
    Informational,
}

/**
 * Persists the profile collection and the selected profile.
 *
 * The document is versioned. An unknown/older schema is decoded field by field with defaults rather
 * than replaced, and a profile that fails to parse is skipped instead of taking the collection with
 * it. The last known valid selection is retained across updates.
 */
class DriverProfileRepository(
    private val store: KeyValueStore,
) {
    private val mutable = MutableStateFlow(load())

    val state: StateFlow<ProfileState> = mutable

    fun current(): ProfileState = mutable.value

    val selectedProfile: DriverProfile get() = mutable.value.selected

    fun select(profileId: String): ProfileState = commit {
        val profile = it.profiles.firstOrNull { candidate -> candidate.id == profileId }
            ?: return@commit it
        it.copy(selectedId = profile.id)
    }

    fun create(name: String, base: DriverProfile = DriverProfile.default()): DriverProfile {
        val state = mutable.value
        val id = uniqueId(state.profiles.map { it.id }.toSet())
        val profile = base.copy(id = id, name = name).sanitized()
        commit { it.copy(profiles = it.profiles + profile, selectedId = id) }
        return profile
    }

    fun duplicate(profileId: String): DriverProfile? {
        val state = mutable.value
        val source = state.profiles.firstOrNull { it.id == profileId } ?: return null
        val id = uniqueId(state.profiles.map { it.id }.toSet())
        val copy = source.copy(id = id, name = "${source.name} copy").sanitized()
        commit { it.copy(profiles = it.profiles + copy, selectedId = id) }
        return copy
    }

    fun update(profile: DriverProfile): ProfileState = commit { state ->
        val sanitized = profile.sanitized()
        if (state.profiles.none { it.id == sanitized.id }) return@commit state
        state.copy(profiles = state.profiles.map { if (it.id == sanitized.id) sanitized else it })
    }

    /** Deletes a profile. The last remaining profile and the selected profile cannot be deleted. */
    fun delete(profileId: String): Boolean {
        val state = mutable.value
        if (state.profiles.size <= 1) return false
        if (state.profiles.none { it.id == profileId }) return false
        if (state.selectedId == profileId) return false
        val remaining = state.profiles.filterNot { it.id == profileId }
        val selected = if (state.selectedId == profileId) remaining.first().id else state.selectedId
        commit { it.copy(profiles = remaining, selectedId = selected) }
        return true
    }

    /** Replaces the whole collection, used by configuration import after validation. */
    fun replace(profiles: List<DriverProfile>, selectedId: String?): ProfileState {
        val valid = profiles.map { it.sanitized() }.distinctBy { it.id }.ifEmpty { listOf(DriverProfile.default()) }
        val selected = valid.firstOrNull { it.id == selectedId }?.id ?: valid.first().id
        return commit { ProfileState(profiles = valid, selectedId = selected) }
    }

    private fun commit(transform: (ProfileState) -> ProfileState): ProfileState {
        val updated = transform(mutable.value).normalized()
        mutable.value = updated
        store.put(KEY_DOCUMENT, encode(updated))
        return updated
    }

    private fun load(): ProfileState {
        val raw = store.get(KEY_DOCUMENT) ?: return ProfileState.default()
        return try {
            decode(raw)
        } catch (_: Exception) {
            // A corrupt document must not brick the app: keep the old one untouched for export and
            // continue with the sensible default.
            ProfileState.default()
        }
    }

    companion object {
        const val KEY_DOCUMENT = "driver_profiles"
        const val COLLECTION_SCHEMA = 2

        fun encode(state: ProfileState): String = JSONObject().apply {
            put("schemaVersion", COLLECTION_SCHEMA)
            put("selectedId", state.selectedId)
            put("profiles", JSONArray().apply {
                state.profiles.forEach { put(it.toJson()) }
            })
        }.toString()

        fun decode(raw: String): ProfileState {
            val json = JSONObject(raw)
            val array = json.optJSONArray("profiles") ?: return ProfileState.default()
            val profiles = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    try {
                        add(DriverProfile.fromJson(item))
                    } catch (_: Exception) {
                        // Skip only the unreadable profile.
                    }
                }
            }.distinctBy { it.id }
            val safeProfiles = profiles.ifEmpty { listOf(DriverProfile.default()) }
            val selected = json.optString("selectedId").takeIf { id ->
                safeProfiles.any { it.id == id }
            } ?: safeProfiles.first().id
            return ProfileState(safeProfiles, selected).normalized()
        }

        private fun uniqueId(existing: Set<String>): String {
            var index = existing.size + 1
            while ("profile-$index" in existing) index++
            return "profile-$index"
        }
    }

}

private fun ProfileState.normalized(): ProfileState = normalizeProfileState(this)

private fun normalizeProfileState(state: ProfileState): ProfileState {
    val safeProfiles = state.profiles.map { it.sanitized() }.distinctBy { it.id }
        .ifEmpty { listOf(DriverProfile.default()) }
    val safeSelected = state.selectedId.takeIf { id -> safeProfiles.any { it.id == id } }
        ?: safeProfiles.first().id
    return ProfileState(safeProfiles, safeSelected)
}

data class ProfileState(
    val profiles: List<DriverProfile>,
    val selectedId: String,
) {
    val selected: DriverProfile
        get() = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull() ?: DriverProfile.default()

    companion object {
        fun default() = ProfileState(listOf(DriverProfile.default()), DriverProfile.DEFAULT_ID)
    }
}

/** Test/preview helper. */
fun inMemoryProfileRepository(): DriverProfileRepository =
    DriverProfileRepository(InMemoryKeyValueStore())
