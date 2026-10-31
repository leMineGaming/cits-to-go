package org.opentrafficmap.citstogo

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.opentrafficmap.citstogo.drive.config.DriveSettings
import org.opentrafficmap.citstogo.drive.config.DriveSettingsRepository
import org.opentrafficmap.citstogo.drive.config.SharedPreferencesKeyValueStore
import org.opentrafficmap.citstogo.drive.engine.DriveEngine
import org.opentrafficmap.citstogo.drive.history.EventHistoryDatabase
import org.opentrafficmap.citstogo.drive.profile.DriverProfileRepository

/**
 * Application-scoped dependency container. There is exactly one driving engine per process, shared
 * by the foreground service, the phone UI and the Android Auto session, so all three observe the
 * same state. Nothing here is a mutable global; the container is created by [CitsApplication] and
 * accessed through the application instance.
 */
class AppContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository = DriveSettingsRepository(
        SharedPreferencesKeyValueStore(
            context.getSharedPreferences(DriveSettings.PREFS_NAME, Context.MODE_PRIVATE),
        ),
    )

    val profileRepository = DriverProfileRepository(
        SharedPreferencesKeyValueStore(
            context.getSharedPreferences(PROFILE_PREFS_NAME, Context.MODE_PRIVATE),
        ),
    )

    val eventHistory = EventHistoryDatabase(context.applicationContext)

    val driveEngine = DriveEngine(
        settingsRepository = settingsRepository,
        profileRepository = profileRepository,
        scope = applicationScope,
    )

    /** Scope used by the foreground service for its own long-running work. */
    val serviceScope: CoroutineScope get() = applicationScope

    companion object {
        const val PROFILE_PREFS_NAME = "cits_drive_profiles"
    }
}
