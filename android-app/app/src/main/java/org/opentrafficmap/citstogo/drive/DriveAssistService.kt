package org.opentrafficmap.citstogo.drive

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.car.app.notification.CarAppExtender
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opentrafficmap.citstogo.AppContainer
import org.opentrafficmap.citstogo.CitsApplication
import org.opentrafficmap.citstogo.MainActivity
import org.opentrafficmap.citstogo.R
import org.opentrafficmap.citstogo.drive.audio.AlertAudioPlayer
import org.opentrafficmap.citstogo.drive.config.DriveSettings
import org.opentrafficmap.citstogo.drive.engine.DriveEngine
import org.opentrafficmap.citstogo.drive.engine.DriveSnapshot
import org.opentrafficmap.citstogo.drive.net.ConnectionPhase
import org.opentrafficmap.citstogo.drive.net.ConnectionStatus
import org.opentrafficmap.citstogo.drive.net.TcpCitsClient
import org.opentrafficmap.citstogo.drive.position.VehiclePosition
import org.opentrafficmap.citstogo.drive.profile.AlertPriority
import org.opentrafficmap.citstogo.drive.safety.ActionId
import org.opentrafficmap.citstogo.drive.safety.SafetyNotification
import org.opentrafficmap.citstogo.drive.sim.SimulationSource

/**
 * Foreground service that keeps the C-ITS link, location and driving engine alive while the phone
 * screen is off or another app is in front.
 *
 * The service owns the TCP client and the location subscription; the application-scoped
 * [DriveEngine] remains the single source of truth. Android Auto and the phone UI observe the engine
 * through [AppContainer.driveEngine], so neither depends on the other's lifecycle.
 */
class DriveAssistService : Service() {
    private lateinit var container: AppContainer
    private lateinit var engine: DriveEngine
    private lateinit var locationManager: LocationManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var audio: AlertAudioPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: TcpCitsClient? = null
    private var simulation: SimulationSource? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var locationUpdatesActive = false
    private var locationSettings: DriveSettings? = null
    private var lastAppliedSettings: DriveSettings? = null
    private var stateJob: Job? = null
    private var notificationJob: Job? = null
    private var tickerJob: Job? = null
    private var lastNotificationFingerprint = ""
    private val insertedHistory = ConcurrentHashMap<String, Boolean>()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            engine.onPosition(
                VehiclePosition(
                    latitudeE7 = (location.latitude * 10_000_000.0).toInt(),
                    longitudeE7 = (location.longitude * 10_000_000.0).toInt(),
                    accuracyM = location.takeIf { it.hasAccuracy() }?.accuracy,
                    speedMetersPerSecond = location.takeIf { it.hasSpeed() && it.speed >= 0f }?.speed,
                    headingDegrees = location.takeIf { it.hasBearing() }?.bearing,
                    timestampMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                ),
            )
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Deprecated("Deprecated in the platform, kept for API 26 compatibility")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as CitsApplication).container
        engine = container.driveEngine
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        audio = AlertAudioPlayer(this)
        createNotificationChannels()
        serviceScope.launch(Dispatchers.IO) {
            engine.restoreHistory(container.eventHistory.recent(200))
        }
        observeEngine()
        observeSettings()
        observeNotifications()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                client?.reconnectNow()
                applySettings(container.settingsRepository.current())
                return START_STICKY
            }
        }
        startForegroundSafely(engine.current())
        applySettings(container.settingsRepository.current())
        startTicker()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!container.settingsRepository.current().backgroundOperation) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stateJob?.cancel()
        notificationJob?.cancel()
        tickerJob?.cancel()
        client?.stop()
        client = null
        simulation?.stop()
        simulation = null
        stopLocationUpdates()
        unregisterNetworkCallback()
        audio.release()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun observeEngine() {
        stateJob = serviceScope.launch {
            engine.state.collect { state ->
                updateForegroundNotification(state)
                recordHistoryProgress(state)
            }
        }
    }

    private fun observeSettings() {
        serviceScope.launch {
            container.settingsRepository.state.collect { settings ->
                applySettings(settings)
            }
        }
        serviceScope.launch {
            container.profileRepository.state.collect { profileState ->
                audio.initialize(profileState.selected.speechEnabled)
            }
        }
    }

    private fun observeNotifications() {
        notificationJob = serviceScope.launch {
            engine.notifications.collect { notification ->
                handleNotification(notification)
            }
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = serviceScope.launch {
            while (isActive) {
                engine.tick()
                delay(500L)
            }
        }
    }

    @Synchronized
    private fun applySettings(settings: DriveSettings) {
        val previous = lastAppliedSettings
        lastAppliedSettings = settings
        if (settings.simulationEnabled) {
            client?.stop()
            client = null
            if (simulation == null) {
                simulation = SimulationSource(engine, serviceScope).also { it.start() }
            }
        } else {
            simulation?.stop()
            simulation = null
            val needsRestart = previous == null ||
                previous.hardwareHost != settings.hardwareHost ||
                previous.hardwarePort != settings.hardwarePort ||
                previous.framing != settings.framing ||
                previous.connectTimeoutSeconds != settings.connectTimeoutSeconds ||
                previous.reconnectDelaySeconds != settings.reconnectDelaySeconds ||
                previous.simulationEnabled != settings.simulationEnabled
            if (needsRestart || client == null) {
                client?.stop()
                if (!settings.hasHardwareAddress) {
                    engine.onConnectionStatus(
                        ConnectionStatus(
                            phase = ConnectionPhase.Error,
                            lastError = "No C-ITS receiver address configured",
                        ),
                    )
                } else {
                    client = TcpCitsClient(
                        scope = serviceScope,
                        onFrame = { frame -> engine.onFrame(frame) },
                        onStatus = { status -> engine.onConnectionStatus(status) },
                    ).also { updated ->
                        updated.start(
                            host = settings.hardwareHost,
                            port = settings.hardwarePort,
                            framing = settings.framing,
                            connectTimeoutMs = settings.connectTimeoutSeconds * 1_000,
                            reconnectBaseMs = settings.reconnectDelaySeconds * 1_000,
                        )
                    }
                }
            }
        }
        startLocationUpdates(settings)
        updateForegroundNotification(engine.current())
    }

    private fun startLocationUpdates(settings: DriveSettings) {
        val previous = locationSettings
        val changed = previous == null ||
            previous.locationIntervalMs != settings.locationIntervalMs ||
            previous.locationMinDistanceM != settings.locationMinDistanceM ||
            previous.locationPriority != settings.locationPriority
        if (locationUpdatesActive && !changed) return
        if (locationUpdatesActive) stopLocationUpdates()
        locationSettings = settings
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (coarse != PackageManager.PERMISSION_GRANTED && fine != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val criteria = Criteria().apply {
            accuracy = when (settings.locationPriority) {
                org.opentrafficmap.citstogo.drive.config.LocationPriority.HighAccuracy ->
                    Criteria.ACCURACY_FINE
                org.opentrafficmap.citstogo.drive.config.LocationPriority.Balanced ->
                    Criteria.ACCURACY_HIGH
                org.opentrafficmap.citstogo.drive.config.LocationPriority.LowPower ->
                    Criteria.ACCURACY_COARSE
            }
            powerRequirement = when (settings.locationPriority) {
                org.opentrafficmap.citstogo.drive.config.LocationPriority.HighAccuracy ->
                    Criteria.POWER_HIGH
                org.opentrafficmap.citstogo.drive.config.LocationPriority.Balanced ->
                    Criteria.POWER_MEDIUM
                org.opentrafficmap.citstogo.drive.config.LocationPriority.LowPower ->
                    Criteria.POWER_LOW
            }
        }
        val provider = locationManager.getBestProvider(criteria, true) ?: LocationManager.GPS_PROVIDER
        try {
            locationManager.requestLocationUpdates(
                provider,
                settings.locationIntervalMs.toLong(),
                settings.locationMinDistanceM,
                locationListener,
                Looper.getMainLooper(),
            )
            locationUpdatesActive = true
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the request.
        } catch (_: IllegalArgumentException) {
            // No suitable provider on this device.
        }
    }

    private fun stopLocationUpdates() {
        if (!locationUpdatesActive) return
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: SecurityException) {
            // Nothing else to release.
        }
        locationUpdatesActive = false
    }

    private fun startForegroundSafely(state: DriveSnapshot) {
        val notification = buildForegroundNotification(state)
        val locationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (locationGranted) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(FOREGROUND_NOTIFICATION_ID, notification, type)
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(state: DriveSnapshot) {
        val fingerprint = listOf(
            state.connection.phase.name,
            state.connection.lastError,
            state.simulated,
            state.signal?.state?.name,
            state.glosa?.targetSpeedKph?.toInt(),
            state.primaryAlert?.actionId,
            state.primaryAlert?.title,
        ).joinToString("|")
        if (fingerprint == lastNotificationFingerprint) return
        lastNotificationFingerprint = fingerprint
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(state))
    }

    private fun buildForegroundNotification(state: DriveSnapshot): Notification {
        val text = when {
            state.simulated -> "Simulation running · no live C-ITS data"
            state.connection.connected -> "Connected to ${state.connection.address}"
            state.connection.phase == ConnectionPhase.Connecting -> "Connecting to ${state.connection.address}"
            else -> state.connection.lastError ?: "C-ITS receiver not connected"
        }
        val detail = buildString {
            append(text)
            state.glosa?.targetSpeedKph?.let { append(" · GLOSA ${it.toInt()} km/h") }
            state.primaryAlert?.let { append(" · ${it.priority.name}: ${it.title}") }
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DriveAssistService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_cits)
            .setContentTitle("C-ITS Drive Assist")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun handleNotification(notification: SafetyNotification) {
        val profile = container.profileRepository.current().selected
        insertedHistory[historyKey(notification.event.actionId, notification.event.version)] = true
        container.eventHistory.insert(notification.event)
        audio.play(notification, profile)
        if (!profile.notificationsEnabled) return
        val carImportance = if (notification.priority == AlertPriority.Critical) {
            NotificationManager.IMPORTANCE_HIGH
        } else {
            NotificationManager.IMPORTANCE_DEFAULT
        }
        val openIntent = PendingIntent.getActivity(
            this,
            notification.actionId.hashCode(),
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val simulationPrefix = if (engine.current().simulated) "Simulation · " else ""
        val notificationTitle = simulationPrefix + notification.title
        val notificationMessage = simulationPrefix + notification.message
        val builder = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_cits)
            .setContentTitle(notificationTitle)
            .setContentText(notificationMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationMessage))
            .setPriority(
                if (notification.priority == AlertPriority.Critical) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .extend(
                CarAppExtender.Builder()
                    .setImportance(carImportance)
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationMessage)
                    .build(),
            )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
            ALERT_NOTIFICATION_BASE + (notification.actionId.hashCode() and 0x0fff),
            builder.build(),
        )
    }

    private fun recordHistoryProgress(state: DriveSnapshot) {
        engine.alertHistory(40).forEach { event ->
            val key = historyKey(event.actionId, event.version)
            if (insertedHistory.putIfAbsent(key, true) == null) {
                container.eventHistory.insert(event)
            }
        }
    }

    private fun historyKey(actionId: ActionId, version: Int): String =
        "${actionId.originatingStationId}/${actionId.sequenceNumber}/$version"

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder().build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                engine.setNetworkAvailable(true)
                client?.reconnectNow()
            }

            override fun onLost(network: Network) {
                engine.setNetworkAvailable(false)
            }
        }
        runCatching { connectivityManager.registerNetworkCallback(request, callback) }
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "C-ITS drive status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the C-ITS receiver and driving assistance active."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Driving safety alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Critical and advisory C-ITS safety events."
                enableVibration(true)
            },
        )
    }

    companion object {
        const val ACTION_START = "org.opentrafficmap.citstogo.action.DRIVE_START"
        const val ACTION_STOP = "org.opentrafficmap.citstogo.action.DRIVE_STOP"
        const val ACTION_RECONNECT = "org.opentrafficmap.citstogo.action.DRIVE_RECONNECT"
        private const val CHANNEL_SERVICE = "cits_drive_service"
        private const val CHANNEL_ALERTS = "cits_drive_alerts"
        private const val FOREGROUND_NOTIFICATION_ID = 4100
        private const val ALERT_NOTIFICATION_BASE = 4200

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DriveAssistService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DriveAssistService::class.java))
        }

        fun reconnect(context: Context) {
            context.startService(
                Intent(context, DriveAssistService::class.java).setAction(ACTION_RECONNECT),
            )
        }
    }
}
