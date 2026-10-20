package org.opentrafficmap.citstogo.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.net.Uri
import android.provider.DocumentsContract
import org.opentrafficmap.citstogo.BuildConfig
import org.opentrafficmap.citstogo.MainActivity
import org.opentrafficmap.citstogo.cam.CamIdentity
import org.opentrafficmap.citstogo.cam.CamPosition
import org.opentrafficmap.citstogo.cam.ItsG5FrameBuilder
import org.opentrafficmap.citstogo.cam.StationType
import org.opentrafficmap.citstogo.cam.VehicleDimensions
import org.opentrafficmap.citstogo.cam.randomizedCamIdentity
import org.opentrafficmap.citstogo.intersection.IntersectionSnapshot
import org.opentrafficmap.citstogo.intersection.IntersectionSnapshotList
import org.opentrafficmap.citstogo.intersection.IntersectionStateStore
import org.opentrafficmap.citstogo.intersection.ItsExtractionResult
import org.opentrafficmap.citstogo.intersection.ItsFrameExtractor
import org.opentrafficmap.citstogo.intersection.ItsPacket
import org.opentrafficmap.citstogo.intersection.SsemDecoder
import org.opentrafficmap.citstogo.intersection.SsemResponseStatus
import org.opentrafficmap.citstogo.intersection.SsemStatus
import org.opentrafficmap.citstogo.livemap.LiveMapStore
import org.opentrafficmap.citstogo.protocol.CaptureSequenceTracker
import org.opentrafficmap.citstogo.protocol.CitsPacket
import org.opentrafficmap.citstogo.protocol.CtgFrameEncoder
import org.opentrafficmap.citstogo.protocol.CtgInboundFrame
import org.opentrafficmap.citstogo.protocol.FirmwareStatistics
import org.opentrafficmap.citstogo.protocol.Ieee80211Mac
import org.opentrafficmap.citstogo.protocol.SerialPacketReader
import org.opentrafficmap.citstogo.srem.SremPosition
import org.opentrafficmap.citstogo.srem.SremProfile
import org.opentrafficmap.citstogo.srem.SremRequest
import org.opentrafficmap.citstogo.station.DiscoveredStationList
import org.opentrafficmap.citstogo.station.ItsPacketType
import org.opentrafficmap.citstogo.station.StationDiscoveryStore
import org.opentrafficmap.citstogo.station.StationObservation
import org.opentrafficmap.citstogo.util.parcelableExtra
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class CitsBridgeService : Service() {
    private val logger = java.util.logging.Logger.getLogger("org.opentrafficmap.citstogo.Bridge")
    private val handler = Handler(Looper.getMainLooper())
    private val pcapLock = Any()
    private lateinit var usbManager: UsbManager
    private lateinit var locationManager: LocationManager
    private lateinit var spool: MqttSpool

    @Volatile private var serial: CtgByteTransport? = null
    private var serialReadThread: Thread? = null
    private var serialWriteThread: Thread? = null
    private var pcapWriter: PcapWriter? = null
    private var replayThread: Thread? = null
    @Volatile private var replayWanted = false
    private val mqttClient = MiniMqttClient()
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var connectionWanted = false
    @Volatile private var connectionMode = ConnectionMode.USB
    @Volatile private var mqttEnabled = false
    private var mqttEnabledByUser = true
    private var mqttConnecting = AtomicBoolean(false)
    private var mqttFlushing = AtomicBoolean(false)
    private var mqttReconnectDelayMs = 1_000L
    private var selectedDeviceName: String? = null
    /** Device the service has already shown the USB permission prompt for in this connection. */
    private var permissionRequestedDeviceName: String? = null
    /** Whether the running connection came from a plug-in rather than from the Start button. */
    @Volatile private var autoStartedConnection = false
    /** Ends an automatic capture whose receiver went away for good. See [scheduleAutoUnplugStop]. */
    private val autoUnplugStop = Runnable {
        if (!autoStartedConnection || serial != null) return@Runnable
        publishStatus(AutoRecording.UNPLUGGED_NOTICE)
        stopEverything()
    }
    private var mqttUri = ""
    private var nodeId = ""
    private var mqttMaxQueueLength = DEFAULT_MQTT_MAX_QUEUE_LENGTH
    private var mqttMaxQueueAgeMs = DEFAULT_MQTT_MAX_QUEUE_AGE_MS
    private var mqttQueueFirstElapsedMs = 0L
    private var mqttQueueAgeFlushScheduled = false
    private var startElapsedMs = 0L
    private var lastStatsElapsedMs = 0L
    private var lastPacketStatusElapsedMs = 0L
    private var lastPacketStatusPackets = 0L
    private val txQueue = LinkedBlockingQueue<TxEnvelope>(MAX_TX_QUEUE)
    private val nextTxRequestId = AtomicLong(1)
    private val nextSremRequestId = AtomicLong(1)
    private val nextSremSequenceNumber = AtomicLong(1)
    private val pendingTx = ConcurrentHashMap<Long, PendingTx>()
    private val pendingSremRequests = ConcurrentHashMap<Int, PendingSremRequest>()
    private lateinit var baseCamIdentity: CamIdentity
    private lateinit var camIdentity: CamIdentity
    private var camSequenceCounters = ItsG5FrameBuilder.SequenceCounters.randomized()
    @Volatile private var lastLocation: Location? = null
    private var camEnabled = false
    private var camStationType = StationType.PEDESTRIAN
    private var camIntervalMs = DEFAULT_CAM_INTERVAL_MS
    private var camMacRandomizationEnabled = true
    private var camRandomizationIntervalMs = DEFAULT_CAM_RANDOMIZATION_INTERVAL_MS
    private var camVehicleDimensions = VehicleDimensions.UNAVAILABLE
    private var lastCamIdentityRotationElapsedMs = 0L
    private var lastCamGeneratedElapsedMs = 0L
    private var lastCamLocation: Location? = null
    private val intersectionStore = IntersectionStateStore()
    private var intersectionSnapshot: IntersectionSnapshot? = null
    private var intersectionSnapshots: List<IntersectionSnapshot> = emptyList()
    private val liveMapStore = LiveMapStore()
    private var lastLiveMapSnapshotElapsedMs = 0L

    private var status = BridgeStatus()
    private var packets = 0L
    private var mqttPublished = 0L
    private var pcapPackets = 0L
    private var replayPackets = 0L
    private var discoveredDevices = 0L
    private var truncated = 0L
    private var transportDropped = 0L
    private val captureSequenceTracker = CaptureSequenceTracker()
    private var protocolErrors = 0L
    private var txRequested = 0L
    private var txSuccessful = 0L
    private var txFailed = 0L
    private var camSent = 0L
    private var nextStationNotificationId = STATION_NOTIFICATION_ID_BASE
    /** Ids actually posted, so teardown cancels these rather than guessing at a range. */
    private val stationNotificationIds = mutableListOf<Int>()
    private val discoveredMacAddresses = mutableSetOf<String>()

    /** Per-station discovery detail, persisted so the table survives restarts. */
    private val stationStore = StationDiscoveryStore()
    private var storedStationsGeneration = -1L
    private var lastStationsPublishedElapsedMs = 0L
    private var stationsPublishForced = true

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    logger.info("USB device attached")
                    // Back within the grace period of an automatic capture, so it keeps running.
                    handler.removeCallbacks(autoUnplugStop)
                    val device = intent.parcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        handleDiscoveredDevice(device)
                        selectedDeviceName = device.deviceName
                    }
                    // A fresh connection has no stored grant or denial, so a new prompt is allowed.
                    permissionRequestedDeviceName = null
                    if (connectionWanted && connectionMode == ConnectionMode.USB && serial == null) scheduleConnectionReconnect(0)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    logger.info("USB device detached")
                    val device = intent.parcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device == null || device.deviceName == selectedDeviceName) {
                        permissionRequestedDeviceName = null
                        closeSerial("USB device detached")
                        if (connectionWanted && connectionMode == ConnectionMode.USB) {
                            scheduleConnectionReconnect(2_000)
                            scheduleAutoUnplugStop()
                        }
                    }
                }
                ACTION_USB_PERMISSION -> {
                    logger.info("USB permission result: granted=${intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)}")
                    // Either answer ends the wait: granted opens the port right away, denied falls
                    // back to the slow reconnect poll without nagging the user with another prompt.
                    if (connectionWanted && connectionMode == ConnectionMode.USB && serial == null) scheduleConnectionReconnect(0)
                }
            }
        }
    }

    private val locationListener = LocationListener { location ->
        val previous = lastLocation
        if (previous == null || location.time >= previous.time ||
            (location.hasAccuracy() && (!previous.hasAccuracy() || location.accuracy < previous.accuracy))
        ) {
            lastLocation = location
        }
    }

    private val camBroadcaster = object : Runnable {
        override fun run() {
            if (!camEnabled) return
            val elapsedMs = SystemClock.elapsedRealtime()
            val wallClockMs = System.currentTimeMillis()
            rotateCamIdentityIfDue(elapsedMs)
            val sampledLocation = lastLocation?.takeIf {
                it.time in (wallClockMs - MAX_LOCATION_AGE_MS)..(wallClockMs + 1_000L)
            }
            if (sampledLocation == null) {
                handler.postDelayed(this, CAM_TRIGGER_CHECK_MS)
                return
            }
            if (!camGenerationDue(elapsedMs, sampledLocation)) {
                handler.postDelayed(this, CAM_TRIGGER_CHECK_MS)
                return
            }
            val referenceTimeMs = sampledLocation.time
            val packet = ItsG5FrameBuilder.camFrame(
                camIdentity,
                camStationType,
                CamPosition.fromLocation(sampledLocation),
                referenceTimeMs,
                camSequenceCounters,
                camVehicleDimensions,
            )
            if (queueTransmit(packet, TxKind.Cam)) {
                lastCamGeneratedElapsedMs = elapsedMs
                lastCamLocation = sampledLocation
            }
            handler.postDelayed(this, CAM_TRIGGER_CHECK_MS)
        }
    }

    /**
     * Applies the CAM privacy mode: while randomization is enabled the station periodically
     * switches to a fresh MAC address, station ID, and randomized transmit sequence counters so
     * receivers cannot correlate successive CAMs into one tracked vehicle trail. The first CAM
     * after enabling already uses a rotated identity so the stored identity is never transmitted.
     */
    private fun rotateCamIdentityIfDue(nowElapsedMs: Long) {
        if (!camMacRandomizationEnabled) return
        if (lastCamIdentityRotationElapsedMs != 0L &&
            nowElapsedMs - lastCamIdentityRotationElapsedMs < camRandomizationIntervalMs
        ) return
        camIdentity = randomizedCamIdentity()
        camSequenceCounters = ItsG5FrameBuilder.SequenceCounters.randomized()
        lastCamIdentityRotationElapsedMs = nowElapsedMs
        lastCamGeneratedElapsedMs = 0L
        lastCamLocation = null
        publishStatus("CAM identity randomized to ${formatMac(camIdentity.macAddress)}")
    }

    private fun camGenerationDue(nowElapsedMs: Long, location: Location?): Boolean {
        val elapsed = nowElapsedMs - lastCamGeneratedElapsedMs
        if (lastCamGeneratedElapsedMs == 0L || elapsed >= camIntervalMs) return true
        if (elapsed < MIN_CAM_INTERVAL_MS) return false
        val previous = lastCamLocation ?: return false
        val current = location ?: return false
        if (previous.distanceTo(current) > 4f) return true
        if (previous.hasSpeed() && current.hasSpeed() &&
            kotlin.math.abs(previous.speed - current.speed) > 0.5f
        ) return true
        if (previous.hasBearing() && current.hasBearing()) {
            val raw = kotlin.math.abs(previous.bearing - current.bearing).mod(360f)
            if (minOf(raw, 360f - raw) > 4f) return true
        }
        return false
    }

    private val maintenance = object : Runnable {
        override fun run() {
            if (mqttEnabled) {
                if (!mqttClient.isConnected()) ensureMqttConnected()
                flushMqttSpool()
            }
            flushPcapQuietly()
            persistStationsIfDue()
            publishStatsIfDue()
            publishStatus(null)
            updateNotification()
            handler.postDelayed(this, MAINTENANCE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        spool = MqttSpool(this)
        mqttClient.onConnectionLost = { reason -> reportMqttLost(reason) }
        nodeId = loadOrCreateNodeId()
        baseCamIdentity = loadOrCreateCamIdentity()
        camIdentity = baseCamIdentity
        loadCamSettings()
        loadMqttSettings()
        discoveredMacAddresses.addAll(loadDiscoveredMacAddresses())
        discoveredDevices = discoveredMacAddresses.size.toLong()
        stationStore.restore(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_DISCOVERED_STATIONS, null))
        stationStore.seedMacAddresses(discoveredMacAddresses)
        storedStationsGeneration = stationStore.generation
        createNotificationChannel()
        registerReceiverCompat(usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        })
        startInForeground("Starting")
        scanKnownUsbDevices()
        handler.post(maintenance)
        publishStatus("Bridge service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBridge(
                ConnectionMode.fromWireValue(intent.getStringExtra(EXTRA_CONNECTION_MODE)),
                intent.getStringExtra(EXTRA_DEVICE_NAME),
                intent.getStringExtra(EXTRA_MQTT_URI).orEmpty(),
                intent.getStringExtra(EXTRA_NODE_ID).orEmpty(),
                intent.getIntExtra(EXTRA_MQTT_MAX_QUEUE_LENGTH, mqttMaxQueueLength),
                intent.getLongExtra(EXTRA_MQTT_MAX_QUEUE_AGE_MS, mqttMaxQueueAgeMs),
                intent.getBooleanExtra(EXTRA_MQTT_ENABLED, mqttEnabledByUser),
            )
            ACTION_AUTO_START -> autoStartBridge(
                ConnectionMode.fromWireValue(intent.getStringExtra(EXTRA_CONNECTION_MODE)),
                intent.getStringExtra(EXTRA_DEVICE_NAME),
                intent.getStringExtra(EXTRA_MQTT_URI).orEmpty(),
                intent.getStringExtra(EXTRA_NODE_ID).orEmpty(),
                intent.getIntExtra(EXTRA_MQTT_MAX_QUEUE_LENGTH, mqttMaxQueueLength),
                intent.getLongExtra(EXTRA_MQTT_MAX_QUEUE_AGE_MS, mqttMaxQueueAgeMs),
                intent.getBooleanExtra(EXTRA_MQTT_ENABLED, mqttEnabledByUser),
                intent.getStringExtra(EXTRA_AUTO_PCAP_TREE_URI).orEmpty(),
            )
            ACTION_STOP -> stopEverything()
            ACTION_SET_MQTT_ENABLED -> setMqttEnabledByUser(
                intent.getBooleanExtra(EXTRA_MQTT_ENABLED, true),
            )
            ACTION_START_PCAP -> startPcap(intent.getStringExtra(EXTRA_PCAP_URI).orEmpty())
            ACTION_STOP_PCAP -> stopPcap(log = true)
            ACTION_START_REPLAY -> startReplay(
                intent.getStringExtra(EXTRA_PCAP_URI).orEmpty(),
                intent.getStringExtra(EXTRA_NODE_ID).orEmpty(),
            )
            ACTION_STOP_REPLAY -> stopReplay(log = true)
            ACTION_REQUEST_STATUS -> publishStatus(null)
            ACTION_CLEAR_DISCOVERED_STATIONS -> clearDiscoveredStations(
                intent.getBooleanExtra(EXTRA_INCLUDE_ROAD_SIDE_UNITS, true),
            )
            ACTION_CONFIGURE_CAM -> configureCam(
                intent.getBooleanExtra(EXTRA_CAM_ENABLED, false),
                StationType.selectableFromCode(intent.getIntExtra(EXTRA_CAM_STATION_TYPE, StationType.PEDESTRIAN.code)),
                intent.getIntExtra(EXTRA_CAM_INTERVAL_MS, DEFAULT_CAM_INTERVAL_MS),
                intent.getBooleanExtra(EXTRA_CAM_RANDOMIZE_MAC, camMacRandomizationEnabled),
                intent.getIntExtra(EXTRA_CAM_RANDOMIZATION_INTERVAL_MS, camRandomizationIntervalMs),
                VehicleDimensions(
                    lengthDm = intent.getIntExtra(EXTRA_CAM_VEHICLE_LENGTH_DM, 0).takeIf { it > 0 },
                    widthDm = intent.getIntExtra(EXTRA_CAM_VEHICLE_WIDTH_DM, 0).takeIf { it > 0 },
                    trailer = VehicleDimensions.Trailer.fromCode(intent.getIntExtra(EXTRA_CAM_TRAILER, 0)),
                ),
            )
            ACTION_SEND_SREM -> sendSrem(intent)
            ACTION_REVOKE_TX_APPROVAL -> revokeTxApproval()
            else -> publishStatus(null)
        }
        // A command that started nothing may not leave a foreground service standing. Every control
        // intent arrives through startService, which creates the service when it is gone, and it goes
        // to the foreground as soon as it exists, so nothing above has any other reason to end it.
        val recording = synchronized(pcapLock) { pcapWriter != null }
        if (ServiceLifecycle.hasNothingLeftToRun(connectionWanted, replayWanted, camEnabled, recording)) stopEverything()
        // Nothing survives the process here, and nothing should be asked to. A capture needs the USB
        // handle and a replay needs its document URI, both of which only the start intent carries, so
        // a redelivered start could do nothing but show a notification over work nobody is doing. That
        // resurrection is what made a stopped bridge look like a bridge that never stopped.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Ends the capture when the user throws the app away.
     *
     * The manifest declares `stopWithTask`, and on a stock platform that is what handles a swipe out
     * of recents: the framework stops the service and `onDestroy` does the teardown. Stock AOSP does
     * not deliver this callback once the flag is set — `cleanUpServices` either stops the service or
     * sends `onTaskRemoved`, never both. It is kept because a task removed behind a customised
     * launcher or vendor firmware is precisely the case where the flag is the one thing that silently
     * stops being honoured, and this costs nothing to have.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        logger.info("App task removed; stopping the bridge")
        stopEverything()
    }

    /**
     * Starts the capture a plug-in asked for. The service, not the activity, decides: it is the only
     * one that still knows a running capture or a replay when the activity was just created and its
     * status is still the default. A start that would disturb either is reported and dropped, leaving
     * what already runs untouched.
     */
    private fun autoStartBridge(
        requestedConnectionMode: ConnectionMode,
        deviceName: String?,
        requestedMqttUri: String,
        requestedNodeId: String,
        requestedMaxQueueLength: Int,
        requestedMaxQueueAgeMs: Long,
        requestedMqttEnabledByUser: Boolean,
        autoPcapTreeUri: String,
    ) {
        if (connectionWanted || replayWanted) {
            publishStatus(AutoRecording.BUSY_NOTICE)
            return
        }
        startBridge(
            requestedConnectionMode,
            deviceName,
            requestedMqttUri,
            requestedNodeId,
            requestedMaxQueueLength,
            requestedMaxQueueAgeMs,
            requestedMqttEnabledByUser,
            autoStarted = true,
        )
        if (AutoRecording.isTreeUri(autoPcapTreeUri)) startAutoPcap(autoPcapTreeUri)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopLocationUpdates()
        runCatching { unregisterReceiver(usbReceiver) }
        stopBridge()
        persistStations()
        runCatching { spool.close() }
        // Repeated rather than trusted to stopEverything: this is also the path a task removal takes
        // on a platform that acts on the manifest flag instead of calling onTaskRemoved.
        releaseWakeLock()
        clearForegroundNotification()
        super.onDestroy()
    }

    private fun startBridge(
        requestedConnectionMode: ConnectionMode,
        deviceName: String?,
        requestedMqttUri: String,
        requestedNodeId: String,
        requestedMaxQueueLength: Int,
        requestedMaxQueueAgeMs: Long,
        requestedMqttEnabledByUser: Boolean,
        autoStarted: Boolean = false,
    ) {
        stopReplay(log = false)
        liveMapStore.clear()
        startElapsedMs = SystemClock.elapsedRealtime()
        connectionMode = requestedConnectionMode
        autoStartedConnection = autoStarted
        handler.removeCallbacks(autoUnplugStop)
        selectedDeviceName = deviceName?.takeIf { it.isNotBlank() } ?: selectedDeviceName
        permissionRequestedDeviceName = null
        if (requestedNodeId.isNotBlank()) {
            nodeId = requestedNodeId.trim()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_NODE_ID, nodeId).apply()
        }
        updateMqttQueueSettings(requestedMaxQueueLength, requestedMaxQueueAgeMs)
        mqttUri = requestedMqttUri.trim()
        mqttEnabledByUser = requestedMqttEnabledByUser
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_MQTT_ENABLED, mqttEnabledByUser)
            .apply()
        applyMqttEnablement()
        connectionWanted = true
        updateWakeLock()
        // The foreground claim belongs to the work, not to the moment the service object happened to
        // be created. Teardown now drops it explicitly, so a start that arrives while an earlier stop
        // is still being torn down would otherwise run a capture with no notification and none of the
        // priority one.
        startInForeground("Starting")
        ensureMaintenanceRunning()
        openConnection()
        publishStatus(if (mqttEnabled) "Capture started" else "Capture started without MQTT")
    }

    private fun setMqttEnabledByUser(enabled: Boolean) {
        mqttEnabledByUser = enabled
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_MQTT_ENABLED, enabled)
            .apply()
        if (!connectionWanted) {
            publishStatus(null)
            return
        }
        applyMqttEnablement()
        publishStatus(
            when {
                mqttEnabled -> "MQTT enabled"
                !enabled -> "MQTT disabled"
                else -> "MQTT enabled. Enter a broker URL to connect."
            },
        )
    }

    /**
     * Reconciles the live MQTT connection with the settings. Called when the bridge starts and
     * whenever the setting changes so the toggle takes effect without restarting the capture.
     */
    private fun applyMqttEnablement() {
        val wasEnabled = mqttEnabled
        mqttEnabled = mqttForwardingEnabled(mqttEnabledByUser, mqttUri)
        if (mqttEnabled) {
            if (!wasEnabled) mqttReconnectDelayMs = 1_000
            ensureMqttConnected()
            return
        }
        if (wasEnabled) closeMqttClient()
        status = status.copy(mqttState = "Disabled")
    }

    private fun stopBridge() {
        // The recording goes first and unconditionally. A PCAP file is independent of the capture, the
        // replay and the broadcast, so the check below would be entitled to decide there is nothing to
        // do while the file handle stayed open and its global header unflushed — a stop that leaves a
        // half-written file behind is not a stop.
        stopPcap(log = false)
        // The same rule the command handler uses, read the other way round: where there is nothing
        // running there is also nothing to stop, and saying so twice would be worse than silent — a
        // CAM broadcasting on its own, with no capture behind it, is stopped here and nowhere else.
        // The recording is already closed above, so it is passed as spent rather than looked up.
        if (ServiceLifecycle.hasNothingLeftToRun(connectionWanted, replayWanted, camEnabled, false)) return
        connectionWanted = false
        autoStartedConnection = false
        handler.removeCallbacks(autoUnplugStop)
        mqttEnabled = false
        configureCam(
            false,
            camStationType,
            camIntervalMs,
            camMacRandomizationEnabled,
            camRandomizationIntervalMs,
            camVehicleDimensions,
        )
        closeSerial("Connection stopped")
        stopReplay(log = false)
        // The wake lock goes before the broker session and the timers go before both: a DISCONNECT to
        // a peer that stopped answering holds until the kernel tires of retransmitting, and a phone the
        // user has just stopped has no business staying awake behind it.
        updateWakeLock()
        closeMqttClient()
        status = status.copy(running = false, usbState = "Stopped", mqttState = "Disabled")
        publishStatus("Capture stopped")
    }

    private fun openConnection() {
        logger.fine("Connection attempt: mode=$connectionMode wanted=$connectionWanted selected=$selectedDeviceName")
        if (!connectionWanted) return
        when (connectionMode) {
            ConnectionMode.USB -> openUsb()
            ConnectionMode.BLUETOOTH -> openBluetooth()
        }
    }

    private fun openUsb() {
        val device = findDevice(selectedDeviceName)
        if (device == null) {
            status = status.copy(running = connectionWanted, usbState = "Waiting for USB device")
            scheduleConnectionReconnect(2_000)
            return
        }
        selectedDeviceName = device.deviceName
        if (!usbManager.hasPermission(device)) {
            status = status.copy(running = connectionWanted, usbState = "USB permission required")
            publishStatus("Grant USB permission in the app")
            requestUsbPermission(device)
            scheduleConnectionReconnect(2_000)
            return
        }

        closeSerial(null)
        try {
            val opened = UsbCdcSerial(usbManager, device).also { it.open() }
            serial = opened
            handler.removeCallbacks(autoUnplugStop)
            promoteConnectedDeviceForeground()
            status = status.copy(running = true, usbState = "Connected: ${opened.description()}")
            val reader = SerialPacketReader(::handlePacket, ::handleTxResult, ::handleProtocolError, ::handleFirmwareStatistics, diagnosticSource = "USB")
            serialReadThread = Thread({
                val buffer = ByteArray(16 * 1024)
                // The firmware emits a CTG1 statistics record every second, so a healthy open
                // port never goes this long without bytes. bulkTransfer cannot distinguish a
                // read timeout from a stalled pipe, so byte-level silence is the reliable
                // liveness signal; on expiry the shared error path closes and reconnects.
                var lastActivityMs = SystemClock.elapsedRealtime()
                while (connectionWanted && connectionMode == ConnectionMode.USB && serial === opened) {
                    try {
                        val count = opened.read(buffer, USB_READ_TIMEOUT_MS)
                        if (count > 0) {
                            lastActivityMs = SystemClock.elapsedRealtime()
                            reader.accept(buffer, count)
                        } else if (SystemClock.elapsedRealtime() - lastActivityMs > USB_SILENCE_TIMEOUT_MS) {
                            throw IOException("USB serial silent for ${USB_SILENCE_TIMEOUT_MS / 1000}s")
                        }
                    } catch (e: Exception) {
                        handleSerialError(e)
                    }
                }
            }, "ctg-serial-read").apply { start() }
            serialWriteThread = Thread({
                while (connectionWanted && connectionMode == ConnectionMode.USB && serial === opened) {
                    try {
                        val tx = txQueue.take()
                        opened.writeAll(
                            CtgFrameEncoder.txRequest(tx.requestId, tx.packet),
                            USB_WRITE_TIMEOUT_MS,
                        )
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        handleSerialError(e)
                    }
                }
            }, "ctg-serial-write").apply { start() }
            publishStatus("USB connected")
        } catch (e: Exception) {
            closeSerial(null)
            logger.log(java.util.logging.Level.SEVERE, "USB open failed", e)
            status = status.copy(running = connectionWanted, usbState = "USB error", lastError = "USB open failed: ${e.message}")
            publishStatus(status.lastError)
            scheduleConnectionReconnect(2_000)
        }
    }

    /**
     * Prompts for USB permission from the service so a capture that was started before the grant
     * exists (for example right after plugging in, while the phone is still enumerating) recovers
     * on its own once the user taps Allow. The prompt is shown once per device connection; after a
     * denial the reconnect poll keeps waiting instead of nagging, and an explicit Start press or a
     * fresh attach allows a new prompt again. The result arrives through [usbReceiver].
     */
    private fun requestUsbPermission(device: UsbDevice) {
        if (permissionRequestedDeviceName == device.deviceName) {
            logger.fine("USB permission prompt already requested for ${device.deviceName}; waiting for grant or reattach")
            return
        }
        permissionRequestedDeviceName = device.deviceName
        logger.info("Requesting USB permission from service: ${ConnectionDiagnostics.usb(device, usbManager)}")
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        runCatching {
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, USB_PERMISSION_REQUEST_CODE, intent, flags))
        }.onFailure { logger.log(java.util.logging.Level.SEVERE, "USB permission request failed", it) }
    }

    private fun openBluetooth() {
        closeSerial(null)
        status = status.copy(running = connectionWanted, usbState = "Searching for CITS-to-go Bluetooth")
        publishStatus("Searching for CITS-to-go Bluetooth")
        Thread({
            try {
                val opened = BleGattSerial(this).also { it.connect() }
                if (!connectionWanted || connectionMode != ConnectionMode.BLUETOOTH) {
                    opened.close()
                    return@Thread
                }
                serial = opened
                promoteConnectedDeviceForeground()
                status = status.copy(running = true, usbState = "Connected: ${opened.description()}", lastError = "")
                val reader = SerialPacketReader(::handlePacket, ::handleTxResult, ::handleProtocolError, ::handleFirmwareStatistics, diagnosticSource = "BLE")
                serialReadThread = Thread({
                    val buffer = ByteArray(16 * 1024)
                    while (connectionWanted && connectionMode == ConnectionMode.BLUETOOTH && serial === opened) {
                        try {
                            val count = opened.read(buffer, USB_READ_TIMEOUT_MS)
                            if (count > 0) reader.accept(buffer, count)
                        } catch (e: Exception) {
                            handleSerialError(e)
                        }
                    }
                }, "ctg-ble-read").apply { start() }
                serialWriteThread = Thread({
                    while (connectionWanted && connectionMode == ConnectionMode.BLUETOOTH && serial === opened) {
                        try {
                            val tx = txQueue.take()
                            opened.writeAll(CtgFrameEncoder.txRequest(tx.requestId, tx.packet), BLE_WRITE_TIMEOUT_MS)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        } catch (e: Exception) {
                            handleSerialError(e)
                        }
                    }
                }, "ctg-ble-write").apply { start() }
                publishStatus("Bluetooth connected")
            } catch (e: Exception) {
                closeSerial(null)
                logger.log(java.util.logging.Level.SEVERE, "Bluetooth connect failed", e)
                status = status.copy(
                    running = connectionWanted,
                    usbState = "Bluetooth error",
                    lastError = "Bluetooth connect failed: ${e.message}",
                )
                publishStatus(status.lastError)
                if (connectionWanted && connectionMode == ConnectionMode.BLUETOOTH) scheduleConnectionReconnect(2_000)
            }
        }, "ctg-ble-connect").start()
    }

    private fun closeSerial(message: String?) {
        val old = serial
        serial = null
        captureSequenceTracker.reset()
        status = status.copy(firmwareStatistics = null, bleDebugParameters = null)
        serialReadThread?.interrupt()
        serialWriteThread?.interrupt()
        runCatching { old?.close() }
        serialReadThread = null
        serialWriteThread = null
        txQueue.clear()
        if (pendingTx.isNotEmpty()) {
            val pendingSrem = pendingTx.values.firstOrNull { it.kind == TxKind.Srem && it.sremRequestId != null }
            txFailed += pendingTx.size
            pendingTx.clear()
            if (pendingSrem?.sremRequestId != null) {
                pendingSremRequests.remove(pendingSrem.sremRequestId)
                status = status.copy(
                    lastSremState = SREM_STATE_FAILED,
                    lastSremSummary = "SREM transmit canceled: connection lost",
                    lastSremUpdatedAtMs = System.currentTimeMillis(),
                )
            }
        }
        if (message != null) {
            status = status.copy(usbState = if (connectionWanted) "Waiting for ${connectionMode.label} device" else "Stopped")
            publishStatus(message)
        }
    }

    private fun handleFirmwareStatistics(statistics: FirmwareStatistics) {
        status = status.copy(
            firmwareStatistics = statistics,
            bleDebugParameters = (serial as? BleGattSerial)?.debugParameters(),
        )
        publishStatus(null)
    }

    private fun handlePacket(packet: CitsPacket, publishToMqtt: Boolean = true) {
        packets += 1
        if (serial != null) transportDropped += captureSequenceTracker.observe(packet.sequence)
        if (packet.truncated) truncated += 1
        val discoveredMacAddress = Ieee80211Mac.sourceAddress(packet.payload)
        if (discoveredMacAddress != null && discoveredMacAddresses.add(discoveredMacAddress)) {
            discoveredDevices = discoveredMacAddresses.size.toLong()
            storeDiscoveredMacAddresses()
            sendStationDiscoveryNotification(discoveredMacAddress)
        }
        val extraction = ItsFrameExtractor.extractDetailed(packet.payload)
        val itsPacket = (extraction as? ItsExtractionResult.Success)?.packet
        val geoNetworkingSource = itsPacket?.geoNetworkingSource ?: (extraction as? ItsExtractionResult.Beacon)?.source
        val itsType = itsPacket?.messageId?.let(ItsPacketType::fromMessageId)
        if (discoveredMacAddress != null) {
            stationStore.observe(
                discoveredMacAddress,
                StationObservation(
                    packetType = itsType,
                    rssiDbm = packet.rssiDbm.takeIf { it != 0 },
                    latitudeE7 = geoNetworkingSource?.latitude,
                    longitudeE7 = geoNetworkingSource?.longitude,
                    stationType = geoNetworkingSource?.stationType,
                    forwardedFrom = geoNetworkingSource?.takeIf { it.forwarded }?.address,
                ),
                packet.wallClockMs ?: System.currentTimeMillis(),
            )
        }
        val frequency = packet.frequencyMhz.takeIf { it > 0 }?.let { "$it MHz" } ?: "— MHz"
        val rssi = packet.rssiDbm.takeIf { it != 0 }?.let { "$it dBm" } ?: "— dBm"
        val summary = buildList {
            add("#${packet.sequence}")
            itsType?.let { add(it.label) }
            add("${packet.payload.size} B")
            add(frequency)
            add(rssi)
        }.joinToString(" • ")
        if (publishToMqtt) queueMqttPacket(packet.payload)
        writePcap(packet)
        updateIntersection(packet.payload)
        updateSsem(packet.payload)
        itsPacket?.let { updateLiveMap(it, packet.wallClockMs) }
        status = status.copy(
            running = true,
            packets = packets,
            truncated = truncated,
            transportDropped = transportDropped,
            mqttPublished = mqttPublished,
            pcapRecording = pcapWriter != null,
            pcapPackets = pcapPackets,
            mqttQueued = spool.pendingCount(),
            discoveredDevices = discoveredDevices,
            lastPacketSummary = summary,
            packetTopic = "its/$nodeId/packet",
        )
        if (shouldPublishPacketStatus()) publishStatus("Packet $summary")
    }

    private fun startReplay(
        uriString: String,
        requestedNodeId: String,
    ) {
        if (uriString.isBlank()) {
            status = status.copy(lastError = "PCAP source missing")
            publishStatus(status.lastError)
            return
        }
        stopReplay(log = false)
        connectionWanted = false
        closeSerial(null)
        stopPcap(log = false)
        startElapsedMs = SystemClock.elapsedRealtime()
        if (requestedNodeId.isNotBlank()) nodeId = requestedNodeId.trim()
        mqttEnabled = false
        closeMqttClient()
        replayPackets = 0L
        liveMapStore.clear()
        replayWanted = true
        updateWakeLock()
        startInForeground("Replaying PCAP")
        ensureMaintenanceRunning()
        status = status.copy(running = true, usbState = "Replaying PCAP", replaying = true, replayPackets = 0, lastError = "")
        val uri = Uri.parse(uriString)
        val thread = Thread({
            val currentThread = Thread.currentThread()
            try {
                contentResolver.openInputStream(uri).use { stream ->
                    requireNotNull(stream) { "Could not open input stream" }
                    val reader = PcapReader(stream.buffered())
                    PcapReplay(
                        elapsedRealtimeUs = { SystemClock.elapsedRealtimeNanos() / 1_000L },
                        sleepMs = { Thread.sleep(it) },
                    ).run(reader, { replayWanted && replayThread === currentThread }) { frame ->
                        replayPackets += 1
                        handlePacket(
                            CitsPacket(
                                sequence = replayPackets,
                                timestampUs = frame.timestampUs,
                                frequencyMhz = frame.frequencyMhz ?: 0,
                                rssiDbm = frame.rssiDbm ?: 0,
                                wifiType = 0,
                                rxState = 0,
                                flags = if (frame.originalLength > frame.payload.size) CitsPacket.FLAG_TRUNCATED else 0,
                                originalLength = frame.originalLength,
                                capturedLength = frame.payload.size,
                                payload = frame.payload,
                            ),
                            publishToMqtt = false,
                        )
                    }
                }
                if (replayWanted && replayThread === currentThread) finishReplay("PCAP replay complete")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                if (replayWanted && replayThread === currentThread) {
                    finishReplay("PCAP replay failed: ${e.message}", error = true)
                }
            }
        }, "ctg-pcap-replay")
        replayThread = thread
        thread.start()
        publishStatus("PCAP replay started")
    }

    private fun finishReplay(message: String, error: Boolean = false) {
        replayWanted = false
        replayThread = null
        updateWakeLock()
        status = status.copy(
            running = false,
            usbState = if (error) "Replay error" else "Replay complete",
            replaying = false,
            replayPackets = replayPackets,
            lastError = if (error) message else "",
        )
        publishStatus(message)
    }

    private fun stopReplay(log: Boolean) {
        val wasRunning = replayWanted
        replayWanted = false
        replayThread?.interrupt()
        replayThread = null
        if (wasRunning) {
            updateWakeLock()
            status = status.copy(running = false, usbState = "Stopped", replaying = false, replayPackets = replayPackets)
            if (log) publishStatus("PCAP replay stopped")
        }
    }

    private fun updateIntersection(packet: ByteArray) {
        intersectionStore.accept(packet)?.let {
            refreshIntersectionSnapshots()
            intersectionSnapshot = intersectionSnapshots.firstOrNull() ?: it
        }
    }

    private fun updateSsem(packet: ByteArray) {
        try {
            val its = org.opentrafficmap.citstogo.intersection.ItsFrameExtractor.extract(packet) ?: return
            if (its.messageId != SsemDecoder.MESSAGE_ID_SSEM) return
            SsemDecoder.decode(its, System.currentTimeMillis()).forEach { ssem ->
                handleSsemStatus(ssem)
            }
        } catch (_: Exception) {
            // SSEM decoding is best-effort; unsupported optional branches should not affect capture.
        }
    }

    /**
     * [capturedAtMs] is the capture time of a replayed frame, [CitsPacket.wallClockMs]. Live packets count as captured
     * on arrival: their [CitsPacket.timestampUs] is the radio's free-running microsecond timer.
     */
    private fun updateLiveMap(packet: ItsPacket, capturedAtMs: Long?) {
        try {
            val receivedAtMs = System.currentTimeMillis()
            liveMapStore.accept(packet, receivedAtMs, capturedAtMs ?: receivedAtMs)
        } catch (_: Exception) {
            // The map is best-effort as well and must not affect capture, MQTT or PCAP.
        }
    }

    private fun handleSsemStatus(ssem: SsemStatus) {
        prunePendingSremRequests()
        val pending = matchingSremRequest(ssem) ?: return
        val state = when (ssem.responseStatus) {
            SsemResponseStatus.Granted -> SREM_STATE_GRANTED
            SsemResponseStatus.Rejected,
            SsemResponseStatus.MaxPresence,
            SsemResponseStatus.ReserviceLocked -> SREM_STATE_REJECTED
            SsemResponseStatus.Processing -> SREM_STATE_PROCESSING
            SsemResponseStatus.Requested -> SREM_STATE_ACKNOWLEDGED
            SsemResponseStatus.WatchOtherTraffic -> SREM_STATE_WATCH_OTHER_TRAFFIC
            SsemResponseStatus.Unknown,
            SsemResponseStatus.Unsupported -> SREM_STATE_UNKNOWN_RESPONSE
        }
        status = status.copy(
            lastSremState = state,
            lastSremSummary = "SSEM ${ssem.responseStatus.label.lowercase()} for request ${pending.requestId}",
            lastSremRequestId = pending.requestId,
            lastSremIntersectionId = pending.intersectionId,
            lastSremInboundLaneId = pending.inboundLaneId,
            lastSremOutboundLaneId = pending.outboundLaneId,
            lastSremUpdatedAtMs = ssem.receivedAtMs,
            lastError = if (state == SREM_STATE_REJECTED) ssem.responseStatus.label else status.lastError,
        )
        if (state in setOf(SREM_STATE_GRANTED, SREM_STATE_REJECTED)) {
            pendingSremRequests.remove(pending.requestId)
        }
        publishStatus(status.lastSremSummary)
    }

    private fun matchingSremRequest(ssem: SsemStatus): PendingSremRequest? {
        ssem.requestId?.let { requestId ->
            pendingSremRequests[requestId]?.takeIf { it.matches(ssem, requireLaneMatch = false) }?.let {
                return it
            }
        }
        return pendingSremRequests.values
            .filter { it.matches(ssem, requireLaneMatch = true) }
            .maxByOrNull { it.sentAtMs }
    }

    private fun prunePendingSremRequests(nowMs: Long = System.currentTimeMillis()) {
        pendingSremRequests.entries.removeAll { (_, request) ->
            nowMs - request.sentAtMs > PENDING_SREM_MAX_AGE_MS
        }
    }

    private fun queueTransmit(packet: ByteArray, kind: TxKind, sremRequestId: Int? = null): Boolean {
        if (!hasTxApproval()) {
            txFailed += 1
            status = status.copy(lastError = "TX approval is required before transmitting")
            publishStatus(status.lastError)
            return false
        }
        if (packet.isEmpty() || packet.size > CtgFrameEncoder.MAX_PACKET_BYTES) {
            txFailed += 1
            status = status.copy(lastError = "TX packet size ${packet.size} is outside 1..${CtgFrameEncoder.MAX_PACKET_BYTES}")
            publishStatus(status.lastError)
            return false
        }
        if (serial == null) {
            txFailed += 1
            status = status.copy(lastError = "TX requires a connected USB device")
            publishStatus(status.lastError)
            return false
        }
        val requestId = nextTxRequestId.getAndIncrement() and 0xffff_ffffL
        val packetCopy = packet.copyOf()
        val envelope = TxEnvelope(requestId, packetCopy)
        pendingTx[requestId] = PendingTx(packetCopy, kind, sremRequestId)
        if (!txQueue.offer(envelope)) {
            pendingTx.remove(requestId)
            txFailed += 1
            status = status.copy(lastError = "Android TX queue is full")
            publishStatus(status.lastError)
            return false
        }
        txRequested += 1
        return true
    }

    private fun handleTxResult(result: CtgInboundFrame.TxResult) {
        val pending = pendingTx.remove(result.requestId)
        val isCam = pending?.kind == TxKind.Cam
        val sremRequestId = pending?.sremRequestId
        val summary = "#${result.requestId} ${result.packetLength}B " +
            if (result.successful) "sent" else "failed (ESP 0x${result.status.toString(16)})"
        if (result.successful) {
            txSuccessful += 1
            if (isCam) camSent += 1
            if (sremRequestId != null) {
                status = status.copy(
                    lastSremState = SREM_STATE_TRANSMITTED,
                    lastSremSummary = "SREM transmitted for request $sremRequestId",
                    lastSremUpdatedAtMs = System.currentTimeMillis(),
                )
            }
            val packet = result.packet ?: pending?.packet
            queueMqttPacket(packet)
            writePcapPacket(packet)
        } else {
            txFailed += 1
            if (sremRequestId != null) {
                pendingSremRequests.remove(sremRequestId)
                status = status.copy(
                    lastSremState = SREM_STATE_FAILED,
                    lastSremSummary = "SREM transmit failed for request $sremRequestId",
                    lastSremUpdatedAtMs = System.currentTimeMillis(),
                )
            }
        }
        status = status.copy(lastTxSummary = summary, lastError = if (result.successful) "" else summary)
        publishStatus("TX $summary")
    }

    private fun sendSrem(intent: Intent) {
        val nowMs = System.currentTimeMillis()
        if (!hasTxApproval()) {
            status = status.copy(
                lastError = "TX approval is required before SREM",
                lastSremState = SREM_STATE_FAILED,
                lastSremSummary = "SREM blocked: TX approval required",
                lastSremUpdatedAtMs = nowMs,
            )
            publishStatus(status.lastError)
            return
        }
        if (serial == null) {
            status = status.copy(
                lastError = "SREM requires a connected USB device",
                lastSremState = SREM_STATE_FAILED,
                lastSremSummary = "SREM blocked: USB disconnected",
                lastSremUpdatedAtMs = nowMs,
            )
            publishStatus(status.lastError)
            return
        }

        val intersectionId = intent.getIntExtra(EXTRA_SREM_INTERSECTION_ID, -1)
        val inboundLaneId = intent.getIntExtra(EXTRA_SREM_INBOUND_LANE_ID, -1)
        val outboundLaneId = intent.getIntExtra(EXTRA_SREM_OUTBOUND_LANE_ID, -1)
        val latitude = intent.getIntExtra(EXTRA_SREM_LATITUDE_E7, 900_000_001)
        val longitude = intent.getIntExtra(EXTRA_SREM_LONGITUDE_E7, 1_800_000_001)
        val positionTimeMs = intent.getLongExtra(EXTRA_SREM_POSITION_TIME_MS, 0L)
        val packageRequestTimeMs = intent.getLongExtra(EXTRA_SREM_PACKAGE_REQUEST_TIME_MS, 0L)
        val heading = intent.getIntExtra(EXTRA_SREM_HEADING, -1)
        val positionAccurate = intent.getBooleanExtra(EXTRA_SREM_POSITION_ACCURATE, false)
        val profileCode = intent.getIntExtra(EXTRA_SREM_PROFILE, -1)
        val profile = SremProfile.entries.firstOrNull { it.preferenceCode == profileCode }
        val region = intent.getIntExtra(EXTRA_SREM_REGION, -1).takeIf { it >= 0 }
        if (
            intersectionId !in 0..65_535 ||
            inboundLaneId !in 0..255 ||
            outboundLaneId !in 0..255 ||
            latitude !in -900_000_000..900_000_000 ||
            longitude !in -1_800_000_000..1_800_000_000 ||
            heading !in 0..3_600 ||
            profile == null ||
            positionTimeMs !in (nowMs - MAX_LOCATION_AGE_MS)..(nowMs + 1_000L) ||
            packageRequestTimeMs !in (nowMs - 1_000L)..(nowMs + MAX_SREM_ETA_MS + 1_000L)
        ) {
            status = status.copy(
                lastError = "SREM requires a valid intersection, lane pair, and fresh location",
                lastSremState = SREM_STATE_FAILED,
                lastSremSummary = "SREM blocked: invalid request context",
                lastSremUpdatedAtMs = nowMs,
            )
            publishStatus(status.lastError)
            return
        }

        val requestId = ((nextSremRequestId.getAndIncrement() - 1) % 255 + 1).toInt()
        val sequenceNumber = (nextSremSequenceNumber.getAndIncrement() % 128).toInt()
        val request = SremRequest(
            region = region,
            intersectionId = intersectionId,
            requestId = requestId,
            sequenceNumber = sequenceNumber,
            inboundLaneId = inboundLaneId,
            outboundLaneId = outboundLaneId,
            position = SremPosition(latitude, longitude, heading, positionAccurate),
            nowUnixMs = nowMs,
            profile = profile,
            packageRequestUnixMs = packageRequestTimeMs,
        )
        val packet = try {
            ItsG5FrameBuilder.sremFrame(camIdentity, request, camSequenceCounters)
        } catch (e: Exception) {
            status = status.copy(
                lastError = "SREM encode failed: ${e.message}",
                lastSremState = SREM_STATE_FAILED,
                lastSremSummary = "SREM encode failed",
                lastSremUpdatedAtMs = nowMs,
            )
            publishStatus(status.lastError)
            return
        }

        status = status.copy(
            lastSremState = SREM_STATE_QUEUED,
            lastSremSummary = "SREM queued for lane $inboundLaneId -> $outboundLaneId",
            lastSremRequestId = requestId,
            lastSremIntersectionId = intersectionId,
            lastSremInboundLaneId = inboundLaneId,
            lastSremOutboundLaneId = outboundLaneId,
            lastSremUpdatedAtMs = nowMs,
            lastError = "",
        )
        pendingSremRequests[requestId] = PendingSremRequest(
            requestId = requestId,
            sequenceNumber = sequenceNumber,
            stationId = camIdentity.stationId,
            region = region,
            intersectionId = intersectionId,
            inboundLaneId = inboundLaneId,
            outboundLaneId = outboundLaneId,
            sentAtMs = nowMs,
        )
        if (queueTransmit(packet, TxKind.Srem, requestId)) {
            publishStatus("SREM queued")
        } else {
            pendingSremRequests.remove(requestId)
            status = status.copy(
                lastSremState = SREM_STATE_FAILED,
                lastSremSummary = status.lastError.ifBlank { "SREM queue failed" },
                lastSremUpdatedAtMs = System.currentTimeMillis(),
            )
            publishStatus(status.lastError)
        }
    }

    private fun configureCam(
        enabled: Boolean,
        stationType: StationType,
        requestedIntervalMs: Int,
        randomizeIdentity: Boolean,
        requestedRandomizationIntervalMs: Int,
        dimensions: VehicleDimensions,
    ) {
        if (enabled && !hasTxApproval()) {
            camEnabled = false
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_CAM_ENABLED, false)
                .apply()
            status = status.copy(lastError = "TX approval is required before CAM broadcast")
            publishStatus(status.lastError)
            return
        }
        camStationType = if (stationType in StationType.selectable) stationType else StationType.PEDESTRIAN
        camIntervalMs = requestedIntervalMs.coerceIn(MIN_CAM_INTERVAL_MS, MAX_CAM_INTERVAL_MS)
        camMacRandomizationEnabled = randomizeIdentity
        camRandomizationIntervalMs = requestedRandomizationIntervalMs
            .coerceIn(MIN_CAM_RANDOMIZATION_INTERVAL_MS, MAX_CAM_RANDOMIZATION_INTERVAL_MS)
        camVehicleDimensions = dimensions
        camEnabled = enabled
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_CAM_ENABLED, camEnabled)
            .putInt(PREF_CAM_VEHICLE_LENGTH_DM, dimensions.lengthDm ?: 0)
            .putInt(PREF_CAM_VEHICLE_WIDTH_DM, dimensions.widthDm ?: 0)
            .putInt(PREF_CAM_TRAILER, dimensions.trailer.code)
            .putInt(PREF_CAM_INTERVAL_MS, camIntervalMs)
            .putBoolean(PREF_CAM_RANDOMIZE_MAC, camMacRandomizationEnabled)
            .putInt(PREF_CAM_RANDOMIZATION_INTERVAL_MS, camRandomizationIntervalMs)
            .apply()
        handler.removeCallbacks(camBroadcaster)
        if (camEnabled) {
            if (!startLocationUpdates()) {
                camEnabled = false
                status = status.copy(lastError = "Location permission is required for CAM broadcast")
                publishStatus(status.lastError)
                return
            }
            promoteCamForeground()
            lastCamGeneratedElapsedMs = 0
            lastCamLocation = null
            // A zero rotation stamp makes the first broadcaster tick start from a fresh identity.
            lastCamIdentityRotationElapsedMs = 0L
            if (!camMacRandomizationEnabled) camIdentity = baseCamIdentity
            handler.post(camBroadcaster)
        } else {
            stopLocationUpdates()
        }
        publishStatus(if (camEnabled) "CAM broadcast enabled" else "CAM broadcast disabled")
    }

    private fun revokeTxApproval() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_TX_APPROVED, false)
            .putBoolean(PREF_CAM_ENABLED, false)
            .apply()
        handler.removeCallbacks(camBroadcaster)
        if (camEnabled) stopLocationUpdates()
        camEnabled = false
        txQueue.clear()
        if (pendingTx.isNotEmpty()) {
            pendingTx.values.mapNotNull { it.sremRequestId }.forEach { pendingSremRequests.remove(it) }
            txFailed += pendingTx.size
            pendingTx.clear()
        }
        status = status.copy(lastTxSummary = "", lastError = "")
        publishStatus("TX approval revoked")
    }

    private fun startLocationUpdates(): Boolean {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return false
        runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastLocation = it }
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 100L, 0f, locationListener, Looper.getMainLooper())
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 250L, 0f, locationListener, Looper.getMainLooper())
            }
        }.onFailure {
            status = status.copy(lastError = "Location unavailable: ${it.message}")
            return false
        }
        return true
    }

    private fun stopLocationUpdates() {
        runCatching { locationManager.removeUpdates(locationListener) }
    }

    private fun shouldPublishPacketStatus(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val elapsedSinceLastUpdate = now - lastPacketStatusElapsedMs
        val packetsSinceLastUpdate = packets - lastPacketStatusPackets
        if (
            lastPacketStatusElapsedMs != 0L &&
            elapsedSinceLastUpdate < PACKET_STATUS_INTERVAL_MS &&
            packetsSinceLastUpdate < PACKET_STATUS_PACKET_INTERVAL
        ) {
            return false
        }
        lastPacketStatusElapsedMs = now
        lastPacketStatusPackets = packets
        return true
    }

    /** The map refreshes once per second, so status updates in between leave its snapshot out. */
    private fun liveMapSnapshotDue(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastLiveMapSnapshotElapsedMs != 0L && now - lastLiveMapSnapshotElapsedMs < LIVE_MAP_SNAPSHOT_INTERVAL_MS) {
            return false
        }
        lastLiveMapSnapshotElapsedMs = now
        return true
    }

    /**
     * Names a file in the folder chosen on the Settings page and records into it. The document is created
     * here and not in the activity, so a plug-in that the service refuses leaves no empty file behind.
     * The storage provider asks for a MIME type it knows; the PCAP one is tried first, and a provider
     * that turns it down gets the unspecific one, which every provider accepts.
     */
    private fun startAutoPcap(treeUriString: String) {
        val treeUri = Uri.parse(treeUriString)
        val fileName = AutoRecording.fileName(System.currentTimeMillis())
        val document = createInTree(treeUri, fileName, AutoRecording.MIME_TYPE)
            ?: createInTree(treeUri, fileName, "*/*")
        if (document == null) {
            status = status.copy(
                lastError = "Auto PCAP could not be created in ${AutoRecording.folderLabel(treeUriString)}",
            )
            publishStatus(status.lastError)
            return
        }
        startPcap(document.toString())
    }

    private fun createInTree(treeUri: Uri, displayName: String, mimeType: String): Uri? =
        runCatching { DocumentsContract.createDocument(contentResolver, treeUri, mimeType, displayName) }
            .onFailure { logger.log(java.util.logging.Level.SEVERE, "Auto PCAP create failed", it) }
            .getOrNull()

    private fun startPcap(uriString: String) {
        if (uriString.isBlank()) {
            status = status.copy(lastError = "PCAP destination missing")
            publishStatus(status.lastError)
            return
        }
        synchronized(pcapLock) {
            stopPcapLocked(log = false)
            try {
                val uri = Uri.parse(uriString)
                val stream = contentResolver.openOutputStream(uri, "w")
                    ?: throw IllegalStateException("Could not open output stream")
                pcapWriter = PcapWriter(stream)
                pcapPackets = 0
                status = status.copy(pcapRecording = true, pcapPackets = 0, lastError = "")
                publishStatus("PCAP recording started")
            } catch (e: Exception) {
                pcapWriter = null
                status = status.copy(pcapRecording = false, lastError = "PCAP open failed: ${e.message}")
                publishStatus(status.lastError)
            }
        }
    }

    private fun stopPcap(log: Boolean) {
        synchronized(pcapLock) {
            stopPcapLocked(log)
        }
    }

    private fun stopPcapLocked(log: Boolean) {
        val writer = pcapWriter ?: return
        pcapWriter = null
        runCatching { writer.close() }
            .onFailure { status = status.copy(lastError = "PCAP close failed: ${it.message}") }
        status = status.copy(pcapRecording = false, pcapPackets = pcapPackets)
        if (log) publishStatus("PCAP recording stopped")
    }

    private fun writePcap(packet: CitsPacket) {
        synchronized(pcapLock) {
            val writer = pcapWriter ?: return
            try {
                writer.writePacket(packet)
                pcapPackets += 1
            } catch (e: Exception) {
                pcapWriter = null
                runCatching { writer.close() }
                status = status.copy(pcapRecording = false, lastError = "PCAP write failed: ${e.message}")
                publishStatus(status.lastError)
            }
        }
    }

    private fun writePcapPacket(packet: ByteArray?) {
        if (packet == null) return
        synchronized(pcapLock) {
            val writer = pcapWriter ?: return
            try {
                writer.writeRawPacket(packet, System.currentTimeMillis() * 1_000L)
                pcapPackets += 1
            } catch (e: Exception) {
                pcapWriter = null
                runCatching { writer.close() }
                status = status.copy(pcapRecording = false, lastError = "PCAP write failed: ${e.message}")
                publishStatus(status.lastError)
            }
        }
    }

    private fun flushPcapQuietly() {
        synchronized(pcapLock) {
            runCatching { pcapWriter?.flush() }
        }
    }

    private fun handleProtocolError(message: String) {
        protocolErrors += 1
        status = status.copy(protocolErrors = protocolErrors, lastError = "Protocol: $message")
        if (protocolErrors % 10L == 1L) publishStatus(status.lastError)
    }

    private fun handleSerialError(error: Exception) {
        logger.log(java.util.logging.Level.SEVERE, "$connectionMode transport I/O failed", error)
        status = status.copy(usbState = "USB error", lastError = "Serial read failed: ${error.message}")
        publishStatus(status.lastError)
        closeSerial(null)
        if (connectionWanted && connectionMode == ConnectionMode.USB) scheduleConnectionReconnect(2_000)
    }

    private fun queueMqttPacket(packet: ByteArray?) {
        if (!mqttEnabled || packet == null) return
        runCatching {
            spool.append(packet)
            updateQueueDrainSchedule()
        }.onFailure {
            status = status.copy(lastError = "MQTT spool failed: ${it.message}")
        }
    }

    private fun ensureMqttConnected() {
        if (!mqttEnabled || mqttClient.isConnected() || !mqttConnecting.compareAndSet(false, true)) return
        Thread({
            try {
                status = status.copy(mqttState = "Connecting")
                publishStatus(null)
                mqttClient.connect(mqttUri, nodeId, BuildConfig.VERSION_NAME)
                if (!mqttEnabled) {
                    // The setting was turned off while the handshake was in flight.
                    runCatching { mqttClient.close() }
                    status = status.copy(mqttState = "Disabled")
                } else {
                    mqttReconnectDelayMs = 1_000
                    status = status.copy(mqttState = "Connected", packetTopic = "its/$nodeId/packet", lastError = "")
                    publishStatus("MQTT connected")
                }
            } catch (e: Exception) {
                runCatching { mqttClient.close() }
                status = status.copy(mqttState = "Reconnecting", lastError = "MQTT connect failed: ${e.message}")
                publishStatus(status.lastError)
                scheduleMqttReconnect()
            } finally {
                mqttConnecting.set(false)
            }
        }, "mqtt-connect").start()
    }

    private fun flushMqttSpool() {
        if (!mqttEnabled || !mqttClient.isConnected() || !mqttFlushing.compareAndSet(false, true)) return
        Thread({
            var shouldContinueDraining = false
            try {
                val batch = spool.readBatch(MQTT_MAX_BATCH)
                if (batch.isEmpty()) return@Thread
                var sent = 0
                var nextOffset = 0L
                for (record in batch) {
                    mqttClient.publishPacket(record.payload)
                    sent += 1
                    nextOffset = record.nextOffset
                }
                mqttClient.flush()
                spool.ack(nextOffset, sent)
                mqttPublished += sent
                val pendingAfter = spool.pendingCount()
                if (pendingAfter == 0L) {
                    resetQueueDrainSchedule()
                } else {
                    mqttQueueFirstElapsedMs = SystemClock.elapsedRealtime()
                    shouldContinueDraining = pendingAfter > mqttMaxQueueLength
                    if (!shouldContinueDraining) scheduleQueueAgeFlush()
                }
                status = status.copy(
                    mqttState = "Connected",
                    mqttPublished = mqttPublished,
                    mqttQueued = spool.pendingCount(),
                )
            } catch (e: Exception) {
                runCatching { mqttClient.close() }
                status = status.copy(mqttState = "Offline, spooling", lastError = "MQTT publish failed: ${e.message}")
                publishStatus(status.lastError)
                scheduleMqttReconnect()
            } finally {
                mqttFlushing.set(false)
                if (shouldContinueDraining) flushMqttSpool()
            }
        }, "mqtt-flush").start()
    }

    private fun updateQueueDrainSchedule() {
        val pending = spool.pendingCount()
        if (pending <= 0L) {
            resetQueueDrainSchedule()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (mqttQueueFirstElapsedMs == 0L) mqttQueueFirstElapsedMs = now
        if (pending > mqttMaxQueueLength) {
            flushMqttSpool()
            return
        }
        scheduleQueueAgeFlush()
    }

    private fun scheduleQueueAgeFlush() {
        if (mqttMaxQueueAgeMs <= 0L) {
            flushMqttSpool()
            return
        }
        if (mqttQueueAgeFlushScheduled || mqttQueueFirstElapsedMs == 0L) return
        val delayMs = (mqttQueueFirstElapsedMs + mqttMaxQueueAgeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        mqttQueueAgeFlushScheduled = true
        handler.postDelayed({
            mqttQueueAgeFlushScheduled = false
            val firstQueuedAt = mqttQueueFirstElapsedMs
            if (firstQueuedAt != 0L && SystemClock.elapsedRealtime() - firstQueuedAt >= mqttMaxQueueAgeMs) {
                flushMqttSpool()
            } else {
                scheduleQueueAgeFlush()
            }
        }, delayMs)
    }

    private fun resetQueueDrainSchedule() {
        mqttQueueFirstElapsedMs = 0L
        mqttQueueAgeFlushScheduled = false
    }

    private fun publishStatsIfDue() {
        if (!mqttEnabled || !mqttClient.isConnected()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsElapsedMs < 60_000) return
        lastStatsElapsedMs = now
        runCatching {
            val uptime = if (startElapsedMs == 0L) 0L else (now - startElapsedMs) / 1000L
            mqttClient.publishStats(uptime)
        }.onFailure {
            runCatching { mqttClient.close() }
            status = status.copy(mqttState = "Offline, spooling", lastError = "MQTT stats failed: ${it.message}")
            scheduleMqttReconnect()
        }
    }

    private fun scheduleConnectionReconnect(delayMs: Long) {
        if (!connectionWanted) return
        logger.fine("$connectionMode reconnect scheduled in ${delayMs}ms")
        handler.postDelayed({ if (connectionWanted && serial == null) openConnection() }, delayMs)
    }

    /**
     * Puts an automatic capture on a clock once its receiver has been pulled out. Such a capture belongs
     * to that receiver: with it gone there is nothing left to record, and a foreground service waiting
     * forever for a device the user took along keeps the phone awake and drains its battery. A
     * connection started by hand keeps retrying as it always did — the user who pressed Start knows it
     * is meant to come back. The grace period covers a firmware reset or a cable that re-enumerates,
     * which would otherwise end a recording that was going well.
     */
    private fun scheduleAutoUnplugStop() {
        if (!autoStartedConnection) return
        handler.removeCallbacks(autoUnplugStop)
        handler.postDelayed(autoUnplugStop, AUTO_UNPLUG_STOP_MS)
    }

    private fun scheduleMqttReconnect() {
        if (!mqttEnabled) return
        val delay = mqttReconnectDelayMs
        mqttReconnectDelayMs = min(mqttReconnectDelayMs * 2, 60_000L)
        handler.postDelayed({ ensureMqttConnected() }, delay)
    }

    private fun findDevice(deviceName: String?): UsbDevice? {
        val devices = usbManager.deviceList.values.toList()
        logger.fine("USB selection: requested=$deviceName attached=${devices.size}")
        devices.forEach { logger.fine("USB candidate: ${ConnectionDiagnostics.usb(it, usbManager)}") }
        if (devices.isEmpty()) {
            logger.fine("No USB devices enumerated; waiting for attach")
            return null
        }
        if (deviceName != null) {
            devices.firstOrNull { it.deviceName == deviceName }?.let {
                logger.fine("Using selected USB device ${it.deviceName}")
                return it
            }
            logger.warning("Selected USB device $deviceName is absent; falling back to available device")
        }
        return (devices.firstOrNull { it.vendorId == ESPRESSIF_VENDOR_ID } ?: devices.first()).also {
            logger.info("USB fallback selection: ${ConnectionDiagnostics.usb(it, usbManager)} Espressif=${it.vendorId == ESPRESSIF_VENDOR_ID}")
        }
    }

    private fun scanKnownUsbDevices() {
        usbManager.deviceList.values.forEach { handleDiscoveredDevice(it) }
    }

    private fun handleDiscoveredDevice(device: UsbDevice) {
        logger.fine("USB discovery: ${ConnectionDiagnostics.usb(device, usbManager)} recognized=${device.isCitsDevice()}")
        if (!device.isCitsDevice()) return
        val message = "C-ITS device discovered: ${device.discoveryLabel()}"
        publishStatus(message)
    }

    private fun UsbDevice.isCitsDevice(): Boolean = vendorId == ESPRESSIF_VENDOR_ID

    private fun UsbDevice.discoveryLabel(): String {
        val product = listOfNotNull(manufacturerName, productName).joinToString(" ").ifBlank { deviceName }
        return "$product (vid=%04x pid=%04x)".format(vendorId, productId)
    }

    private fun publishStatus(log: String?) {
        if (!log.isNullOrBlank() && !log.startsWith("Packet ") && !log.startsWith("TX ")) {
            logger.log(if (log == status.lastError) java.util.logging.Level.SEVERE else java.util.logging.Level.INFO, log)
        }
        refreshIntersectionSnapshots()
        status = status.copy(
            running = connectionWanted || replayWanted,
            nodeId = nodeId,
            packetTopic = "its/$nodeId/packet",
            mqttQueued = spool.pendingCount(),
            packets = packets,
            mqttPublished = mqttPublished,
            pcapRecording = pcapWriter != null,
            pcapPackets = pcapPackets,
            replaying = replayWanted,
            replayPackets = replayPackets,
            discoveredDevices = discoveredDevices,
            truncated = truncated,
            transportDropped = transportDropped,
            protocolErrors = protocolErrors,
            txRequested = txRequested,
            txSuccessful = txSuccessful,
            txFailed = txFailed,
            camEnabled = camEnabled,
            camSent = camSent,
            bleDebugParameters = (serial as? BleGattSerial)?.debugParameters(),
            intersectionDiagnostics = intersectionStore.diagnostics(),
            mqttState = when {
                !mqttEnabled -> "Disabled"
                mqttClient.isConnected() -> "Connected"
                // Only a handshake or a scheduled retry is worth repeating. Anything else the state
                // carried before — a "Connected" from a session that has since died above all — is a
                // claim the client no longer makes, so it is dropped rather than kept on screen.
                status.mqttState == "Connecting" || status.mqttState == "Reconnecting" -> status.mqttState
                else -> "Offline, spooling"
            },
        )
        val intent = Intent(ACTION_STATUS).setPackage(packageName)
        intent.putExtra(EXTRA_RUNNING, status.running)
        intent.putExtra(EXTRA_USB_STATE, status.usbState)
        intent.putExtra(EXTRA_MQTT_STATE, status.mqttState)
        intent.putExtra(EXTRA_NODE_ID, status.nodeId)
        intent.putExtra(EXTRA_PACKET_TOPIC, status.packetTopic)
        intent.putExtra(EXTRA_PACKETS, status.packets)
        intent.putExtra(EXTRA_MQTT_PUBLISHED, status.mqttPublished)
        intent.putExtra(EXTRA_MQTT_QUEUED, status.mqttQueued)
        intent.putExtra(EXTRA_PCAP_RECORDING, status.pcapRecording)
        intent.putExtra(EXTRA_PCAP_PACKETS, status.pcapPackets)
        intent.putExtra(EXTRA_REPLAYING, status.replaying)
        intent.putExtra(EXTRA_REPLAY_PACKETS, status.replayPackets)
        intent.putExtra(EXTRA_DISCOVERED_DEVICES, status.discoveredDevices)
        intent.putExtra(EXTRA_TRUNCATED, status.truncated)
        intent.putExtra(EXTRA_TRANSPORT_DROPPED, status.transportDropped)
        intent.putExtra(EXTRA_PROTOCOL_ERRORS, status.protocolErrors)
        intent.putExtra(EXTRA_TX_REQUESTED, status.txRequested)
        intent.putExtra(EXTRA_TX_SUCCESSFUL, status.txSuccessful)
        intent.putExtra(EXTRA_TX_FAILED, status.txFailed)
        intent.putExtra(EXTRA_CAM_ENABLED, status.camEnabled)
        intent.putExtra(EXTRA_CAM_SENT, status.camSent)
        status.firmwareStatistics?.let { intent.putExtra(EXTRA_FIRMWARE_STATISTICS, it) }
        status.bleDebugParameters?.let { intent.putExtra(EXTRA_BLE_DEBUG_PARAMETERS, it) }
        status.intersectionDiagnostics?.let { intent.putExtra(EXTRA_INTERSECTION_DIAGNOSTICS, it) }
        intent.putExtra(EXTRA_SREM_STATE, status.lastSremState)
        intent.putExtra(EXTRA_SREM_SUMMARY, status.lastSremSummary)
        intent.putExtra(EXTRA_SREM_REQUEST_ID, status.lastSremRequestId)
        intent.putExtra(EXTRA_SREM_INTERSECTION_ID, status.lastSremIntersectionId)
        intent.putExtra(EXTRA_SREM_INBOUND_LANE_ID, status.lastSremInboundLaneId)
        intent.putExtra(EXTRA_SREM_OUTBOUND_LANE_ID, status.lastSremOutboundLaneId)
        intent.putExtra(EXTRA_SREM_UPDATED_AT_MS, status.lastSremUpdatedAtMs)
        intent.putExtra(EXTRA_LAST_TX, status.lastTxSummary)
        intent.putExtra(EXTRA_LAST_PACKET, status.lastPacketSummary)
        intent.putExtra(EXTRA_LAST_ERROR, status.lastError)
        intent.putExtra(EXTRA_INTERSECTION_SNAPSHOTS, IntersectionSnapshotList(intersectionSnapshots))
        intersectionSnapshot?.let { intent.putExtra(EXTRA_INTERSECTION_SNAPSHOT, it) }
        if (shouldPublishStations()) {
            intent.putExtra(
                EXTRA_DISCOVERED_STATIONS,
                DiscoveredStationList(stationStore.snapshot(DiscoveredStationList.BROADCAST_LIMIT)),
            )
            intent.putExtra(EXTRA_ROAD_SIDE_UNITS, DiscoveredStationList(stationStore.roadSideUnits()))
        }
        if (liveMapSnapshotDue()) {
            intent.putExtra(EXTRA_LIVE_MAP_SNAPSHOT, liveMapStore.snapshot(System.currentTimeMillis()))
        }
        if (!log.isNullOrBlank()) intent.putExtra(EXTRA_LOG, log)
        try {
            sendBroadcast(intent)
        } catch (failure: Exception) {
            // This broadcast is the only way the interface learns about the capture, and the service
            // publishes it from onCreate onwards, so a payload Android cannot parcel is reported as
            // an error on the next status rather than being allowed to take the service down.
            status = status.copy(lastError = "Status broadcast failed: ${failure.javaClass.simpleName}")
        }
    }

    private fun refreshIntersectionSnapshots(nowMs: Long = System.currentTimeMillis()) {
        intersectionSnapshots = intersectionStore.activeSnapshots(nowMs, INTERSECTION_MAX_AGE_MS)
        intersectionSnapshot = intersectionSnapshots.firstOrNull()
    }

    /**
     * The station table rides along with the status broadcast, so it is capped to the most recently
     * active stations and throttled. Clearing the table forces the next broadcast to carry it.
     */
    private fun shouldPublishStations(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (
            !stationsPublishForced &&
            lastStationsPublishedElapsedMs != 0L &&
            now - lastStationsPublishedElapsedMs < STATIONS_PUBLISH_INTERVAL_MS
        ) {
            return false
        }
        stationsPublishForced = false
        lastStationsPublishedElapsedMs = now
        return true
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status.summary()))
    }

    private fun sendStationDiscoveryNotification(macAddress: String) {
        // A bridge that has stopped announces nothing, even though its read thread may still be winding
        // down and post one after the teardown has already cleared the shade.
        if (!connectionWanted && !replayWanted) return
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_NOTIFY_STATION_DISCOVERY, true)) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val id = nextStationNotificationId++
        stationNotificationIds += id
        manager.notify(id, buildStationDiscoveryNotification(macAddress))
    }

    private fun startInForeground(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun promoteConnectedDeviceForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(status.summary()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }
    }

    private fun promoteCamForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(status.summary()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, CitsBridgeService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("C-ITS to go")
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    stopPi,
                ).build(),
            )
            .build()
    }

    private fun buildStationDiscoveryNotification(macAddress: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val content = "C-ITS station discovered: $macAddress"
        return Notification.Builder(this, STATION_DISCOVERY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("New C-ITS station")
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "C-ITS bridge",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Keeps USB serial capture and MQTT forwarding active while the phone is locked."
        manager.createNotificationChannel(channel)
        manager.deleteNotificationChannel(LEGACY_DEVICE_DISCOVERY_CHANNEL_ID)
        val stationDiscoveryChannel = NotificationChannel(
            STATION_DISCOVERY_CHANNEL_ID,
            "C-ITS station discovery",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        stationDiscoveryChannel.description = "Alerts when a new C-ITS station is discovered."
        manager.createNotificationChannel(stationDiscoveryChannel)
    }

    /**
     * Ends everything the bridge can own and leaves no trace of it.
     *
     * The Stop action, a task the user swiped away, a receiver unplugged for good, and a command that
     * started no work all funnel here, so that "stopped" has exactly one meaning: no capture, no
     * replay, no CAM, no broker session, no wake lock, no timer left armed, and nothing in the
     * notification shade. It is safe to call twice, which is what a Stop followed by onDestroy needs.
     */
    private fun stopEverything() {
        // Timers first. A maintenance tick or a backoff reconnect that fires after this point would
        // put the notification straight back up and reconnect a broker the user has just left.
        handler.removeCallbacksAndMessages(null)
        // Clearing the callback above also dropped the two flags that were only ever cleared inside it,
        // so a service that is started again without being destroyed in between would otherwise think
        // a drain was still scheduled and never schedule the loop again.
        resetQueueDrainSchedule()
        stopLocationUpdates()
        stopBridge()
        clearForegroundNotification()
        stopSelf()
    }

    /**
     * Starts the refresh loop that keeps the notification, the stats and the spool moving, if it is not
     * already running.
     *
     * It is normally started once, from onCreate, but stopping removes the callback the loop reposts
     * itself through. Without this, a service that is stopped and started again without being destroyed
     * in between would run a capture behind a notification frozen at its last message.
     */
    private fun ensureMaintenanceRunning() {
        handler.removeCallbacks(maintenance)
        handler.post(maintenance)
    }

    /**
     * Takes the ongoing notification and the discovery alerts down explicitly.
     *
     * The service otherwise trusts the framework to remove a foreground-service notification when the
     * service dies. That is the one thing nobody notices until it fails, because what is left on screen
     * is a STOP button for a bridge that already stopped, which reads exactly like a bridge that
     * refused to.
     */
    private fun clearForegroundNotification() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.cancel(NOTIFICATION_ID) }
        // Station alerts are dismissible by hand but nothing dismissed them for us, so they used to
        // outlive the capture that raised them. The posted ids are cancelled by name: walking a range
        // from the base would eventually reach the app update notification, which is not ours to take.
        stationNotificationIds.forEach { runCatching { manager.cancel(it) } }
        stationNotificationIds.clear()
    }

    /**
     * Puts the wake lock where the work is, rather than in whichever call happened to start it.
     *
     * Capture and replay share the one non-reference-counted lock, so acquiring and releasing it in
     * pairs lets whichever of the two finishes second put the phone to sleep underneath the one still
     * running. Deriving it from what is active makes the order of teardown irrelevant.
     */
    private fun updateWakeLock() {
        if (connectionWanted || replayWanted) acquireWakeLock() else releaseWakeLock()
    }

    /**
     * Ends the broker session off the main thread.
     *
     * A graceful close writes a retained status and a DISCONNECT, and a write to a peer that stopped
     * answering holds until the kernel gives up on retransmitting; Java offers no socket write timeout
     * to bound it. The client serialises its own methods, so the next connect waits for this one to
     * finish instead of racing it, while the teardown that asked for it does not.
     */
    private fun closeMqttClient() {
        val client = mqttClient
        Thread({
            val finished = AtomicBoolean(false)
            val abandoned = AtomicBoolean(false)
            Thread({
                runCatching { Thread.sleep(GRACEFUL_MQTT_CLOSE_MS) }
                if (!finished.get()) {
                    abandoned.set(true)
                    client.abandon()
                }
            }, "mqtt-close-watchdog").apply { isDaemon = true }.start()
            runCatching { client.close() }
            finished.set(true)
            if (abandoned.get()) logger.warning("MQTT broker did not answer the goodbye; session closed anyway")
        }, "mqtt-close").apply { isDaemon = true }.start()
    }

    /**
     * Carries a broker session that ended on its own back onto the service thread.
     *
     * The client notices the death on its reader thread. Without this hand-off the state the
     * notification renders keeps whatever it was last told, which is how a bridge that lost the broker
     * minutes ago goes on answering `MQTT Connected`.
     */
    private fun reportMqttLost(reason: String) {
        logger.warning("MQTT connection lost: $reason")
        handler.post {
            if (!mqttEnabled || mqttClient.isConnected()) return@post
            status = status.copy(mqttState = "Reconnecting", lastError = "MQTT connection lost: $reason")
            publishStatus(status.lastError)
            ensureMqttConnected()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "citstogo:bridge").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun loadOrCreateNodeId(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.getString(PREF_NODE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(PREF_NODE_ID, generated).apply()
        return generated
    }

    private fun loadOrCreateCamIdentity(): CamIdentity {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val storedId = prefs.getLong(PREF_CAM_STATION_ID, -1L)
        val storedMac = prefs.getString(PREF_CAM_MAC, null)?.let(::parseMac)
        if (storedId in 0..0xffff_ffffL && storedMac != null) {
            return CamIdentity(storedId, storedMac)
        }
        val identity = randomizedCamIdentity()
        prefs.edit()
            .putLong(PREF_CAM_STATION_ID, identity.stationId)
            .putString(PREF_CAM_MAC, formatMac(identity.macAddress))
            .apply()
        return identity
    }

    private fun formatMac(address: ByteArray): String =
        address.joinToString(":") { "%02x".format(it) }

    private fun parseMac(value: String): ByteArray? {
        val parts = value.split(":")
        if (parts.size != 6) return null
        return runCatching { ByteArray(6) { parts[it].toInt(16).toByte() } }.getOrNull()
    }

    private fun loadCamSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val vehicleTypeCode = when {
            prefs.contains(PREF_VEHICLE_TYPE) ->
                prefs.getInt(PREF_VEHICLE_TYPE, StationType.PEDESTRIAN.code)
            prefs.contains(PREF_SREM_PROFILE) ->
                prefs.getInt(PREF_SREM_PROFILE, StationType.PEDESTRIAN.code)
            else -> prefs.getInt(PREF_CAM_STATION_TYPE, StationType.PEDESTRIAN.code)
        }
        camStationType = StationType.selectableFromCode(vehicleTypeCode)
        camIntervalMs = prefs.getInt(PREF_CAM_INTERVAL_MS, DEFAULT_CAM_INTERVAL_MS)
            .coerceIn(MIN_CAM_INTERVAL_MS, MAX_CAM_INTERVAL_MS)
        camMacRandomizationEnabled = prefs.getBoolean(PREF_CAM_RANDOMIZE_MAC, true)
        camRandomizationIntervalMs = prefs
            .getInt(PREF_CAM_RANDOMIZATION_INTERVAL_MS, DEFAULT_CAM_RANDOMIZATION_INTERVAL_MS)
            .coerceIn(MIN_CAM_RANDOMIZATION_INTERVAL_MS, MAX_CAM_RANDOMIZATION_INTERVAL_MS)
        camVehicleDimensions = VehicleDimensions(
            lengthDm = prefs.getInt(PREF_CAM_VEHICLE_LENGTH_DM, 0).takeIf { it > 0 },
            widthDm = prefs.getInt(PREF_CAM_VEHICLE_WIDTH_DM, 0).takeIf { it > 0 },
            trailer = VehicleDimensions.Trailer.fromCode(prefs.getInt(PREF_CAM_TRAILER, 0)),
        )
        // Never resume RF transmission merely because the process was recreated.
        camEnabled = false
    }

    private fun loadDiscoveredMacAddresses(): Set<String> =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(PREF_DISCOVERED_MAC_ADDRESSES, emptySet())
            ?.filterTo(mutableSetOf()) { it.isNotBlank() }
            ?: emptySet()

    private fun storeDiscoveredMacAddresses() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putStringSet(PREF_DISCOVERED_MAC_ADDRESSES, discoveredMacAddresses.toSet())
            .apply()
    }

    /** Writes the discovery table to storage, but only while a packet changed something. */
    private fun persistStationsIfDue() {
        if (stationStore.generation == storedStationsGeneration) return
        persistStations()
    }

    private fun persistStations() {
        val json = stationStore.encode()
        storedStationsGeneration = stationStore.generation
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PREF_DISCOVERED_STATIONS, json)
            .apply()
    }

    /**
     * Forgets every discovered station: the aggregated table, the stored copy, and the address set
     * behind the home screen counter. Packets received afterwards start the table again. Without
     * [includeRoadSideUnits] the road side units stay, because the map shows them from history.
     */
    private fun clearDiscoveredStations(includeRoadSideUnits: Boolean) {
        stationStore.clear(keepRoadSideUnits = !includeRoadSideUnits)
        val kept = stationStore.snapshot().mapTo(HashSet()) { it.macAddress }
        discoveredMacAddresses.retainAll(kept)
        discoveredDevices = discoveredMacAddresses.size.toLong()
        stationsPublishForced = true
        persistStations()
        storeDiscoveredMacAddresses()
        status = status.copy(discoveredDevices = discoveredDevices)
        publishStatus("Discovery table cleared")
    }

    private fun loadMqttSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        mqttEnabledByUser = prefs.getBoolean(PREF_MQTT_ENABLED, true)
        mqttMaxQueueLength = prefs.getInt(PREF_MQTT_MAX_QUEUE_LENGTH, DEFAULT_MQTT_MAX_QUEUE_LENGTH).coerceAtLeast(1)
        mqttMaxQueueAgeMs = prefs.getLong(PREF_MQTT_MAX_QUEUE_AGE_MS, DEFAULT_MQTT_MAX_QUEUE_AGE_MS).coerceAtLeast(0L)
    }

    private fun updateMqttQueueSettings(maxQueueLength: Int, maxQueueAgeMs: Long) {
        mqttMaxQueueLength = maxQueueLength.coerceAtLeast(1)
        mqttMaxQueueAgeMs = maxQueueAgeMs.coerceAtLeast(0L)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(PREF_MQTT_MAX_QUEUE_LENGTH, mqttMaxQueueLength)
            .putLong(PREF_MQTT_MAX_QUEUE_AGE_MS, mqttMaxQueueAgeMs)
            .apply()
        if (spool.pendingCount() > 0L) updateQueueDrainSchedule()
    }

    private fun hasTxApproval(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_TX_APPROVED, false)

    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    companion object {
        const val ACTION_START = "org.opentrafficmap.citstogo.action.START"
        const val ACTION_AUTO_START = "org.opentrafficmap.citstogo.action.AUTO_START"
        const val ACTION_STOP = "org.opentrafficmap.citstogo.action.STOP"
        const val ACTION_START_PCAP = "org.opentrafficmap.citstogo.action.START_PCAP"
        const val ACTION_STOP_PCAP = "org.opentrafficmap.citstogo.action.STOP_PCAP"
        const val ACTION_START_REPLAY = "org.opentrafficmap.citstogo.action.START_REPLAY"
        const val ACTION_STOP_REPLAY = "org.opentrafficmap.citstogo.action.STOP_REPLAY"
        const val ACTION_SET_MQTT_ENABLED = "org.opentrafficmap.citstogo.action.SET_MQTT_ENABLED"
        const val ACTION_REQUEST_STATUS = "org.opentrafficmap.citstogo.action.REQUEST_STATUS"
        const val ACTION_CONFIGURE_CAM = "org.opentrafficmap.citstogo.action.CONFIGURE_CAM"
        const val ACTION_SEND_SREM = "org.opentrafficmap.citstogo.action.SEND_SREM"
        const val ACTION_REVOKE_TX_APPROVAL = "org.opentrafficmap.citstogo.action.REVOKE_TX_APPROVAL"
        const val ACTION_CLEAR_DISCOVERED_STATIONS = "org.opentrafficmap.citstogo.action.CLEAR_DISCOVERED_STATIONS"
        const val ACTION_STATUS = "org.opentrafficmap.citstogo.action.STATUS"
        const val ACTION_USB_PERMISSION = "org.opentrafficmap.citstogo.action.USB_PERMISSION"

        const val EXTRA_CONNECTION_MODE = "connectionMode"
        const val EXTRA_DEVICE_NAME = "deviceName"
        const val EXTRA_MQTT_URI = "mqttUri"
        const val EXTRA_MQTT_ENABLED = "mqttEnabled"
        const val EXTRA_NODE_ID = "nodeId"
        const val EXTRA_MQTT_MAX_QUEUE_LENGTH = "mqttMaxQueueLength"
        const val EXTRA_MQTT_MAX_QUEUE_AGE_MS = "mqttMaxQueueAgeMs"
        const val EXTRA_PCAP_URI = "pcapUri"
        /** Folder of a tree URI an automatic recording names its file in; blank when it records nowhere. */
        const val EXTRA_AUTO_PCAP_TREE_URI = "autoPcapTreeUri"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_USB_STATE = "usbState"
        const val EXTRA_MQTT_STATE = "mqttState"
        const val EXTRA_PACKET_TOPIC = "packetTopic"
        const val EXTRA_PACKETS = "packets"
        const val EXTRA_MQTT_PUBLISHED = "mqttPublished"
        const val EXTRA_MQTT_QUEUED = "mqttQueued"
        const val EXTRA_PCAP_RECORDING = "pcapRecording"
        const val EXTRA_PCAP_PACKETS = "pcapPackets"
        const val EXTRA_REPLAYING = "replaying"
        const val EXTRA_REPLAY_PACKETS = "replayPackets"
        const val EXTRA_DISCOVERED_DEVICES = "discoveredDevices"
        const val EXTRA_DISCOVERED_STATIONS = "discoveredStations"
        const val EXTRA_ROAD_SIDE_UNITS = "roadSideUnits"
        const val EXTRA_INCLUDE_ROAD_SIDE_UNITS = "includeRoadSideUnits"
        const val EXTRA_TRUNCATED = "truncated"
        const val EXTRA_TRANSPORT_DROPPED = "transportDropped"
        const val EXTRA_FIRMWARE_STATISTICS = "firmwareStatistics"
        const val EXTRA_BLE_DEBUG_PARAMETERS = "bleDebugParameters"
        const val EXTRA_INTERSECTION_DIAGNOSTICS = "intersectionDiagnostics"
        const val EXTRA_PROTOCOL_ERRORS = "protocolErrors"
        const val EXTRA_LAST_PACKET = "lastPacket"
        const val EXTRA_LAST_ERROR = "lastError"
        const val EXTRA_LOG = "log"
        const val EXTRA_TX_REQUESTED = "txRequested"
        const val EXTRA_TX_SUCCESSFUL = "txSuccessful"
        const val EXTRA_TX_FAILED = "txFailed"
        const val EXTRA_LAST_TX = "lastTx"
        const val EXTRA_CAM_ENABLED = "camEnabled"
        const val EXTRA_CAM_SENT = "camSent"
        const val EXTRA_CAM_STATION_TYPE = "camStationType"
        const val EXTRA_CAM_INTERVAL_MS = "camIntervalMs"
        const val EXTRA_CAM_RANDOMIZE_MAC = "camRandomizeMac"
        const val EXTRA_CAM_RANDOMIZATION_INTERVAL_MS = "camRandomizationIntervalMs"
        const val EXTRA_CAM_VEHICLE_LENGTH_DM = "camVehicleLengthDm"
        const val EXTRA_CAM_VEHICLE_WIDTH_DM = "camVehicleWidthDm"
        const val EXTRA_CAM_TRAILER = "camTrailer"
        const val EXTRA_SREM_STATE = "sremState"
        const val EXTRA_SREM_SUMMARY = "sremSummary"
        const val EXTRA_SREM_REQUEST_ID = "sremRequestId"
        const val EXTRA_SREM_REGION = "sremRegion"
        const val EXTRA_SREM_INTERSECTION_ID = "sremIntersectionId"
        const val EXTRA_SREM_INBOUND_LANE_ID = "sremInboundLaneId"
        const val EXTRA_SREM_OUTBOUND_LANE_ID = "sremOutboundLaneId"
        const val EXTRA_SREM_LATITUDE_E7 = "sremLatitudeE7"
        const val EXTRA_SREM_LONGITUDE_E7 = "sremLongitudeE7"
        const val EXTRA_SREM_POSITION_TIME_MS = "sremPositionTimeMs"
        const val EXTRA_SREM_PACKAGE_REQUEST_TIME_MS = "sremPackageRequestTimeMs"
        const val EXTRA_SREM_HEADING = "sremHeading"
        const val EXTRA_SREM_POSITION_ACCURATE = "sremPositionAccurate"
        const val EXTRA_SREM_PROFILE = "sremProfile"
        const val EXTRA_SREM_UPDATED_AT_MS = "sremUpdatedAtMs"
        const val EXTRA_INTERSECTION_SNAPSHOT = "intersectionSnapshot"
        const val EXTRA_INTERSECTION_SNAPSHOTS = "intersectionSnapshots"
        const val EXTRA_LIVE_MAP_SNAPSHOT = "liveMapSnapshot"
        const val SREM_STATE_QUEUED = "queued"
        const val SREM_STATE_TRANSMITTED = "transmitted"
        const val SREM_STATE_ACKNOWLEDGED = "acknowledged"
        const val SREM_STATE_PROCESSING = "processing"
        const val SREM_STATE_WATCH_OTHER_TRAFFIC = "watchOtherTraffic"
        const val SREM_STATE_GRANTED = "granted"
        const val SREM_STATE_REJECTED = "rejected"
        const val SREM_STATE_UNKNOWN_RESPONSE = "unknownResponse"
        const val SREM_STATE_FAILED = "failed"

        const val PREFS = "cits_to_go"
        const val PREF_NODE_ID = "node_id"
        const val PREF_MQTT_URI = "mqtt_uri"
        const val PREF_MQTT_ENABLED = "mqtt_enabled"
        const val PREF_MQTT_MAX_QUEUE_LENGTH = "mqtt_max_queue_length"
        const val PREF_MQTT_MAX_QUEUE_AGE_MS = "mqtt_max_queue_age_ms"
        const val PREF_DISCOVERED_MAC_ADDRESSES = "discovered_mac_addresses"
        const val PREF_DISCOVERED_STATIONS = "discovered_stations"
        const val PREF_CAM_ENABLED = "cam_enabled"
        const val PREF_CAM_STATION_TYPE = "cam_station_type"
        const val PREF_CAM_INTERVAL_MS = "cam_interval_ms"
        const val PREF_CAM_RANDOMIZE_MAC = "cam_randomize_mac"
        const val PREF_CAM_RANDOMIZATION_INTERVAL_MS = "cam_randomization_interval_ms"
        const val PREF_CAM_VEHICLE_LENGTH_DM = "cam_vehicle_length_dm"
        const val PREF_CAM_VEHICLE_WIDTH_DM = "cam_vehicle_width_dm"
        const val PREF_CAM_TRAILER = "cam_trailer"
        const val PREF_CAM_STATION_ID = "cam_station_id"
        const val PREF_CAM_MAC = "cam_mac"
        const val PREF_SREM_PROFILE = "srem_profile"
        const val PREF_VEHICLE_TYPE = "vehicle_type"
        const val PREF_TX_APPROVED = "tx_approved"
        const val PREF_NOTIFY_STATION_DISCOVERY = "notify_station_discovery"
        const val PREF_NOTIFY_APP_UPDATE = "notify_app_update"
        const val DEFAULT_MQTT_URI = "mqtts://cits1.opentrafficmap.org"
        const val DEFAULT_MQTT_MAX_QUEUE_LENGTH = 100
        const val DEFAULT_MQTT_MAX_QUEUE_AGE_MS = 200L
        const val DEFAULT_CAM_INTERVAL_MS = 500
        const val MIN_CAM_INTERVAL_MS = 100
        const val MAX_CAM_INTERVAL_MS = 1_000
        const val DEFAULT_CAM_RANDOMIZATION_INTERVAL_MS = 60_000
        const val MIN_CAM_RANDOMIZATION_INTERVAL_MS = 1_000
        const val MAX_CAM_RANDOMIZATION_INTERVAL_MS = 600_000
        const val INTERSECTION_MAX_AGE_MS = 30_000L

        /** Minimum time between status broadcasts that include the station table. */
        private const val STATIONS_PUBLISH_INTERVAL_MS = 1_000L

        private const val CHANNEL_ID = "cits_bridge"
        private const val STATION_DISCOVERY_CHANNEL_ID = "cits_station_discovery"
        private const val LEGACY_DEVICE_DISCOVERY_CHANNEL_ID = "cits_device_discovery"
        private const val NOTIFICATION_ID = 2301
        private const val STATION_NOTIFICATION_ID_BASE = 2400
        private const val USB_READ_TIMEOUT_MS = 5_000
        /** Grace for the firmware's 1 Hz statistics stream before the pipe is declared dead. */
        private const val USB_SILENCE_TIMEOUT_MS = 15_000
        /** How long an automatic capture waits for a receiver that was pulled out before it ends. */
        private const val AUTO_UNPLUG_STOP_MS = 10_000L
        /** How long a broker gets to be told goodbye before the socket is taken away from it. */
        private const val GRACEFUL_MQTT_CLOSE_MS = 2_000L
        private const val BLE_WRITE_TIMEOUT_MS = 5_000
        private const val USB_WRITE_TIMEOUT_MS = 2_000
        /** Distinct from the activity's permission prompt codes 20..22. */
        private const val USB_PERMISSION_REQUEST_CODE = 23
        private const val MAX_TX_QUEUE = 64
        private const val MAX_LOCATION_AGE_MS = 5_000L
        private const val MAX_SREM_ETA_MS = 60_000L
        private const val PENDING_SREM_MAX_AGE_MS = 60_000L
        private const val CAM_TRIGGER_CHECK_MS = 100L
        private const val MAINTENANCE_INTERVAL_MS = 2_000L
        private const val PACKET_STATUS_INTERVAL_MS = 100L
        private const val PACKET_STATUS_PACKET_INTERVAL = 10L
        private const val LIVE_MAP_SNAPSHOT_INTERVAL_MS = 1_000L
        private const val MQTT_MAX_BATCH = 100
        private const val ESPRESSIF_VENDOR_ID = 0x303A
    }

    private data class TxEnvelope(val requestId: Long, val packet: ByteArray)
    private data class PendingTx(val packet: ByteArray, val kind: TxKind, val sremRequestId: Int?)
    private data class PendingSremRequest(
        val requestId: Int,
        val sequenceNumber: Int,
        val stationId: Long,
        val region: Int?,
        val intersectionId: Int,
        val inboundLaneId: Int,
        val outboundLaneId: Int,
        val sentAtMs: Long,
    ) {
        fun matches(ssem: SsemStatus, requireLaneMatch: Boolean): Boolean {
            if (ssem.requesterStationId != null && ssem.requesterStationId != stationId) return false
            if (ssem.requestId != null && ssem.requestId != requestId) return false
            if (ssem.requestSequenceNumber != null && ssem.requestSequenceNumber != sequenceNumber) return false
            if (ssem.intersectionKey.id != intersectionId) return false
            if (ssem.intersectionKey.region != null && region != null && ssem.intersectionKey.region != region) return false
            if (requireLaneMatch || ssem.inboundLaneId != null) {
                if (ssem.inboundLaneId != null && ssem.inboundLaneId != inboundLaneId) return false
            }
            if (requireLaneMatch || ssem.outboundLaneId != null) {
                if (ssem.outboundLaneId != null && ssem.outboundLaneId != outboundLaneId) return false
            }
            return true
        }
    }

    private enum class TxKind {
        Cam,
        Srem,
    }
}
