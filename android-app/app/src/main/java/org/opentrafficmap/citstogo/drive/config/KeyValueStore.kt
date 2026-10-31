package org.opentrafficmap.citstogo.drive.config

import android.content.SharedPreferences

/**
 * The tiny persistence surface used by the drive configuration and the driver profiles.
 *
 * Keeping it behind an interface lets the profile/settings logic and its algorithms live and be
 * tested as plain Kotlin, without an Android runtime.
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** [KeyValueStore] backed by the app's private [SharedPreferences]. */
class SharedPreferencesKeyValueStore(
    private val preferences: SharedPreferences,
) : KeyValueStore {
    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}

/** Simple in-memory store for unit tests and previews. */
class InMemoryKeyValueStore(
    initial: Map<String, String> = emptyMap(),
) : KeyValueStore {
    private val values = LinkedHashMap(initial)

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    fun snapshot(): Map<String, String> = values.toMap()
}
