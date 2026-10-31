package org.opentrafficmap.citstogo.drive.config

import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Framing used by the C-ITS hardware on its TCP stream. */
enum class TcpFraming(val label: String, val detail: String) {
    LengthPrefixed4BigEndian(
        "4-byte length prefix",
        "Every frame is preceded by an unsigned 32-bit big-endian length.",
    ),
    LengthPrefixed2BigEndian(
        "2-byte length prefix",
        "Every frame is preceded by an unsigned 16-bit big-endian length.",
    ),
    Cobs(
        "COBS (CITS-to-go firmware)",
        "COBS-encoded frames terminated by a zero byte.",
    ),
    NewlineHex(
        "Hex lines",
        "One hexadecimal frame per line, terminated by LF or CRLF.",
    ),
    ;

    companion object {
        fun fromName(value: String?): TcpFraming =
            entries.firstOrNull { it.name == value } ?: LengthPrefixed4BigEndian
    }
}

enum class LocationPriority(val label: String) {
    HighAccuracy("High accuracy (GPS, battery heavy)"),
    Balanced("Balanced"),
    LowPower("Low power"),
}

/**
 * All user-configurable drive settings. The values are persisted as one versioned JSON document so
 * future fields can be added without clobbering what the user already chose.
 */
data class DriveSettings(
    val schemaVersion: Int = SCHEMA_VERSION,
    val hardwareHost: String = "",
    val hardwarePort: Int = DEFAULT_PORT,
    val autoConnect: Boolean = false,
    val reconnectDelaySeconds: Int = 3,
    val connectTimeoutSeconds: Int = 8,
    val backgroundOperation: Boolean = true,
    val framing: TcpFraming = TcpFraming.LengthPrefixed4BigEndian,
    val locationIntervalMs: Int = 1_000,
    val locationMinDistanceM: Float = 0f,
    val locationPriority: LocationPriority = LocationPriority.Balanced,
    val defaultSpeedLimitKph: Int = 50,
    val simulationEnabled: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val staleDataSeconds: Int = 3,
    val eventHistoryLimit: Int = 200,
) {
    val hasHardwareAddress: Boolean get() = hardwareHost.isNotBlank() && hardwarePort in 1..65535

    fun sanitized(): DriveSettings = copy(
        hardwareHost = hardwareHost.trim().removePrefix("tcp://").substringBefore('/').trim(),
        hardwarePort = hardwarePort.coerceIn(1, 65_535),
        reconnectDelaySeconds = reconnectDelaySeconds.coerceIn(1, 300),
        connectTimeoutSeconds = connectTimeoutSeconds.coerceIn(1, 120),
        locationIntervalMs = locationIntervalMs.coerceIn(200, 60_000),
        locationMinDistanceM = locationMinDistanceM.coerceIn(0f, 1_000f),
        defaultSpeedLimitKph = defaultSpeedLimitKph.coerceIn(5, 250),
        staleDataSeconds = staleDataSeconds.coerceIn(1, 60),
        eventHistoryLimit = eventHistoryLimit.coerceIn(20, 2_000),
    )

    fun toJson(): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("hardwareHost", hardwareHost)
        put("hardwarePort", hardwarePort)
        put("autoConnect", autoConnect)
        put("reconnectDelaySeconds", reconnectDelaySeconds)
        put("connectTimeoutSeconds", connectTimeoutSeconds)
        put("backgroundOperation", backgroundOperation)
        put("framing", framing.name)
        put("locationIntervalMs", locationIntervalMs)
        put("locationMinDistanceM", locationMinDistanceM.toDouble())
        put("locationPriority", locationPriority.name)
        put("defaultSpeedLimitKph", defaultSpeedLimitKph)
        put("simulationEnabled", simulationEnabled)
        put("onboardingCompleted", onboardingCompleted)
        put("notificationsEnabled", notificationsEnabled)
        put("staleDataSeconds", staleDataSeconds)
        put("eventHistoryLimit", eventHistoryLimit)
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 1
        const val DEFAULT_PORT = 4711
        const val PREFS_NAME = "cits_drive_settings"
        const val KEY_DOCUMENT = "drive_settings"

        /**
         * Reads a stored document. Missing or malformed fields keep the default, so an update that
         * adds a setting never wipes the settings a user already has.
         */
        fun fromJson(raw: String?): DriveSettings {
            if (raw.isNullOrBlank()) return DriveSettings()
            val json = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return DriveSettings()
            }
            val defaults = DriveSettings()
            fun string(key: String, fallback: String) = json.optString(key, fallback)
            fun int(key: String, fallback: Int) = json.optInt(key, fallback)
            fun bool(key: String, fallback: Boolean) = json.optBoolean(key, fallback)
            return DriveSettings(
                hardwareHost = string("hardwareHost", defaults.hardwareHost),
                hardwarePort = int("hardwarePort", defaults.hardwarePort),
                autoConnect = bool("autoConnect", defaults.autoConnect),
                reconnectDelaySeconds = int("reconnectDelaySeconds", defaults.reconnectDelaySeconds),
                connectTimeoutSeconds = int("connectTimeoutSeconds", defaults.connectTimeoutSeconds),
                backgroundOperation = bool("backgroundOperation", defaults.backgroundOperation),
                framing = TcpFraming.fromName(json.optString("framing")),
                locationIntervalMs = int("locationIntervalMs", defaults.locationIntervalMs),
                locationMinDistanceM = json.optDouble("locationMinDistanceM", 0.0).toFloat(),
                locationPriority = LocationPriority.entries.firstOrNull {
                    it.name == json.optString("locationPriority")
                } ?: defaults.locationPriority,
                defaultSpeedLimitKph = int("defaultSpeedLimitKph", defaults.defaultSpeedLimitKph),
                simulationEnabled = bool("simulationEnabled", defaults.simulationEnabled),
                onboardingCompleted = bool("onboardingCompleted", defaults.onboardingCompleted),
                notificationsEnabled = bool("notificationsEnabled", defaults.notificationsEnabled),
                staleDataSeconds = int("staleDataSeconds", defaults.staleDataSeconds),
                eventHistoryLimit = int("eventHistoryLimit", defaults.eventHistoryLimit),
            ).sanitized()
        }
    }
}

/**
 * Observable holder for [DriveSettings]. Writes are serialized and immediately visible to every
 * collector, which keeps the service and the UI in step when configuration changes.
 */
class DriveSettingsRepository(
    private val store: KeyValueStore,
) {
    private val mutable = MutableStateFlow(load())

    val state: StateFlow<DriveSettings> = mutable

    fun current(): DriveSettings = mutable.value

    fun update(transform: (DriveSettings) -> DriveSettings): DriveSettings {
        val updated = transform(mutable.value).sanitized()
        mutable.value = updated
        store.put(DriveSettings.KEY_DOCUMENT, updated.toJson())
        return updated
    }

    fun replace(settings: DriveSettings): DriveSettings = update { settings }

    private fun load(): DriveSettings =
        DriveSettings.fromJson(store.get(DriveSettings.KEY_DOCUMENT)).sanitized()
}
