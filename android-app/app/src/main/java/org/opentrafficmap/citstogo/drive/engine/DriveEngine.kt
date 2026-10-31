package org.opentrafficmap.citstogo.drive.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.opentrafficmap.citstogo.denm.DenmDecoder
import org.opentrafficmap.citstogo.denm.ReceivedDenm
import org.opentrafficmap.citstogo.drive.config.DriveSettings
import org.opentrafficmap.citstogo.drive.config.DriveSettingsRepository
import org.opentrafficmap.citstogo.drive.diagnostics.DriveDiagnostics
import org.opentrafficmap.citstogo.drive.diagnostics.MessageResult
import org.opentrafficmap.citstogo.drive.diagnostics.RawMessageRecord
import org.opentrafficmap.citstogo.drive.glosa.GlosaEngine
import org.opentrafficmap.citstogo.drive.glosa.GlosaInput
import org.opentrafficmap.citstogo.drive.glosa.GlosaRecommendation
import org.opentrafficmap.citstogo.drive.model.SignalSnapshot
import org.opentrafficmap.citstogo.drive.net.ConnectionStatus
import org.opentrafficmap.citstogo.drive.position.IntersectionMatch
import org.opentrafficmap.citstogo.drive.position.IntersectionMatcher
import org.opentrafficmap.citstogo.drive.position.VehiclePosition
import org.opentrafficmap.citstogo.drive.profile.AlertPriority
import org.opentrafficmap.citstogo.drive.profile.DriverProfile
import org.opentrafficmap.citstogo.drive.profile.DriverProfileRepository
import org.opentrafficmap.citstogo.drive.safety.SafetyAlertManager
import org.opentrafficmap.citstogo.drive.safety.SafetyEvent
import org.opentrafficmap.citstogo.drive.safety.SafetyNotification
import org.opentrafficmap.citstogo.intersection.IntersectionSnapshot
import org.opentrafficmap.citstogo.intersection.ItsExtractionResult
import org.opentrafficmap.citstogo.intersection.ItsFrameExtractor
import org.opentrafficmap.citstogo.intersection.IntersectionStateStore
import org.opentrafficmap.citstogo.intersection.MapIntersection
import org.opentrafficmap.citstogo.intersection.MapSpatDecoder
import org.opentrafficmap.citstogo.intersection.SpatIntersection
import java.util.ArrayDeque

/**
 * Everything the dashboards need in one immutable value. The service writes it, the phone UI and
 * Android Auto read it; neither can mutate the other's state.
 */
data class DriveSnapshot(
    val connection: ConnectionStatus = ConnectionStatus(),
    val networkAvailable: Boolean = true,
    val settings: DriveSettings = DriveSettings(),
    val profile: DriverProfile = DriverProfile.default(),
    val position: VehiclePosition? = null,
    val positionAgeMs: Long? = null,
    val intersection: IntersectionMatch? = null,
    val signal: SignalSnapshot? = null,
    val glosa: GlosaRecommendation? = null,
    val glosaSuppressedBy: String? = null,
    val alerts: List<SafetyEvent> = emptyList(),
    val primaryAlert: SafetyEvent? = null,
    val intersections: List<IntersectionSnapshot> = emptyList(),
    val dataFreshnessMs: Long? = null,
    val dataStale: Boolean = true,
    val simulated: Boolean = false,
    val diagnostics: DriveDiagnostics = DriveDiagnostics(),
    val lastUpdatedMs: Long = 0L,
) {
    val activeAlertCount: Int get() = alerts.count { it.active }
    val criticalAlertCount: Int get() = alerts.count { it.priority == AlertPriority.Critical }
    val hasActiveConnection: Boolean get() = simulated || connection.connected
    val glosaVisible: Boolean get() = glosa != null && glosaSuppressedBy == null
}

/**
 * The application-scoped driving engine.
 *
 * It owns normalized MAPEM/SPATEM/DENM state, position matching, GLOSA and the safety alert
 * manager, and publishes a [DriveSnapshot]. It is deliberately independent of Android UI, Android
 * Auto and the TCP service: all three feed or observe it through simple methods and flows.
 */
class DriveEngine(
    private val settingsRepository: DriveSettingsRepository,
    private val profileRepository: DriverProfileRepository,
    private val scope: CoroutineScope? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val intersectionStore = IntersectionStateStore()
    private val safetyManager = SafetyAlertManager(
        historyLimit = settingsRepository.current().eventHistoryLimit,
    )
    private val mutableState = MutableStateFlow(
        DriveSnapshot(settings = settingsRepository.current(), profile = profileRepository.current().selected),
    )
    private val mutableNotifications = MutableSharedFlow<SafetyNotification>(extraBufferCapacity = 32)
    private val frameTimes = ArrayDeque<Long>()

    private var connection = ConnectionStatus()
    private var networkAvailable = true
    private var position: VehiclePosition? = null
    private var simulated = false
    private var diagnostics = DriveDiagnostics()
    @Volatile private var lastMessageAtMs: Long? = null
    @Volatile private var lastSpatAtMs: Long? = null
    @Volatile private var lastMapAtMs: Long? = null
    @Volatile private var lastDenmAtMs: Long? = null

    val state: StateFlow<DriveSnapshot> = mutableState
    val notifications: SharedFlow<SafetyNotification> = mutableNotifications

    init {
        scope?.launch {
            settingsRepository.state.collect { recompute() }
        }
        scope?.launch {
            profileRepository.state.collect { recompute() }
        }
    }

    fun current(): DriveSnapshot = mutableState.value

    @Synchronized
    fun onConnectionStatus(status: ConnectionStatus) {
        val firstAttempt = status.attempts > connection.attempts
        connection = status
        diagnostics = diagnostics.copy(
            connectionAttempts = if (firstAttempt) diagnostics.connectionAttempts + 1 else diagnostics.connectionAttempts,
            reconnects = status.reconnects,
            lastConnectionError = status.lastError ?: diagnostics.lastConnectionError,
        )
        recompute()
    }

    @Synchronized
    fun setNetworkAvailable(available: Boolean) {
        networkAvailable = available
        recompute()
    }

    @Synchronized
    fun onPosition(update: VehiclePosition) {
        position = update
        recompute()
    }

    @Synchronized
    fun onLocationUnavailable() {
        position = null
        recompute()
    }

    /** Feeds one reassembled frame from the TCP client. */
    @Synchronized
    fun onFrame(frame: ByteArray, receivedAtMs: Long = clock()) {
        if (simulated) return
        frameTimes.addLast(receivedAtMs)
        pruneFrameTimes(receivedAtMs)
        diagnostics = diagnostics.copy(
            framesReceived = diagnostics.framesReceived + 1,
            bytesReceived = diagnostics.bytesReceived + frame.size,
            messagesPerSecond = messageRate(receivedAtMs),
        )
        when (val extraction = ItsFrameExtractor.extractDetailed(frame)) {
            is ItsExtractionResult.Success -> {
                lastMessageAtMs = receivedAtMs
                diagnostics = diagnostics.copy(itsPackets = diagnostics.itsPackets + 1)
                processIts(extraction.packet, receivedAtMs, frame.size)
            }
            ItsExtractionResult.NotGeoNetworking -> {
                diagnostics = diagnostics.copy(malformedFrames = diagnostics.malformedFrames + 1)
                record(receivedAtMs, "Frame", null, null, frame.size, MessageResult.NotCits, "No GeoNetworking SNAP")
            }
            is ItsExtractionResult.Beacon -> {
                diagnostics = diagnostics.copy(beacons = diagnostics.beacons + 1)
                record(receivedAtMs, "Beacon", null, null, frame.size, MessageResult.Beacon)
            }
            is ItsExtractionResult.Malformed -> {
                diagnostics = diagnostics.copy(
                    malformedFrames = diagnostics.malformedFrames + 1,
                    lastError = extraction.reason,
                    lastErrorAtMs = receivedAtMs,
                )
                record(receivedAtMs, "Frame", null, null, frame.size, MessageResult.Malformed, extraction.reason)
            }
            is ItsExtractionResult.Unsupported -> {
                diagnostics = diagnostics.copy(
                    unsupportedFrames = diagnostics.unsupportedFrames + 1,
                    lastError = extraction.reason,
                    lastErrorAtMs = receivedAtMs,
                )
                record(receivedAtMs, "Frame", null, null, frame.size, MessageResult.Unsupported, extraction.reason)
            }
        }
        recompute(receivedAtMs)
    }

    private fun processIts(
        its: org.opentrafficmap.citstogo.intersection.ItsPacket,
        receivedAtMs: Long,
        frameSize: Int,
    ) {
        val type = when {
            its.destinationPort == MapSpatDecoder.BTP_PORT_MAPEM &&
                its.messageId == MapSpatDecoder.MESSAGE_ID_MAPEM -> "MAPEM"
            its.destinationPort == MapSpatDecoder.BTP_PORT_SPATEM &&
                its.messageId == MapSpatDecoder.MESSAGE_ID_SPATEM -> "SPATEM"
            its.messageId == DenmDecoder.MESSAGE_ID_DENM -> "DENM"
            else -> "ITS ${its.messageId}"
        }
        when (type) {
            "MAPEM" -> {
                diagnostics = diagnostics.copy(mapemSeen = diagnostics.mapemSeen + 1)
                try {
                    intersectionStore.acceptDecoded(its, receivedAtMs)
                    lastMapAtMs = receivedAtMs
                    diagnostics = diagnostics.copy(mapemDecoded = diagnostics.mapemDecoded + 1)
                    record(receivedAtMs, type, its.destinationPort, its.stationId, frameSize, MessageResult.Decoded)
                } catch (e: Exception) {
                    diagnostics = diagnostics.copy(
                        decodeFailures = diagnostics.decodeFailures + 1,
                        lastError = "MAPEM: ${e.message ?: e.javaClass.simpleName}",
                        lastErrorAtMs = receivedAtMs,
                    )
                    record(receivedAtMs, type, its.destinationPort, its.stationId, frameSize, MessageResult.DecodeError, e.message)
                }
            }
            "SPATEM" -> {
                diagnostics = diagnostics.copy(spatemSeen = diagnostics.spatemSeen + 1)
                try {
                    intersectionStore.acceptDecoded(its, receivedAtMs)
                    lastSpatAtMs = receivedAtMs
                    diagnostics = diagnostics.copy(spatemDecoded = diagnostics.spatemDecoded + 1)
                    record(receivedAtMs, type, its.destinationPort, its.stationId, frameSize, MessageResult.Decoded)
                } catch (e: Exception) {
                    diagnostics = diagnostics.copy(
                        decodeFailures = diagnostics.decodeFailures + 1,
                        lastError = "SPATEM: ${e.message ?: e.javaClass.simpleName}",
                        lastErrorAtMs = receivedAtMs,
                    )
                    record(receivedAtMs, type, its.destinationPort, its.stationId, frameSize, MessageResult.DecodeError, e.message)
                }
            }
            "DENM" -> {
                diagnostics = diagnostics.copy(denmSeen = diagnostics.denmSeen + 1)
                try {
                    val denm = DenmDecoder.decode(its)
                    if (denm == null) {
                        diagnostics = diagnostics.copy(decodeFailures = diagnostics.decodeFailures + 1)
                        record(receivedAtMs, type, null, its.stationId, frameSize, MessageResult.DecodeError, "Not a DENM")
                    } else {
                        lastDenmAtMs = receivedAtMs
                        diagnostics = diagnostics.copy(denmDecoded = diagnostics.denmDecoded + 1)
                        record(receivedAtMs, type, null, its.stationId, frameSize, MessageResult.Decoded)
                        val result = safetyManager.accept(
                            denm = denm,
                            receivedAtMs = receivedAtMs,
                            position = position,
                            profile = profileRepository.current().selected,
                            nowMs = receivedAtMs,
                        )
                        result.notification?.let { mutableNotifications.tryEmit(it) }
                    }
                } catch (e: Exception) {
                    diagnostics = diagnostics.copy(
                        decodeFailures = diagnostics.decodeFailures + 1,
                        lastError = "DENM: ${e.message ?: e.javaClass.simpleName}",
                        lastErrorAtMs = receivedAtMs,
                    )
                    record(receivedAtMs, type, null, its.stationId, frameSize, MessageResult.DecodeError, e.message)
                }
            }
            else -> record(receivedAtMs, type, its.destinationPort, its.stationId, frameSize, MessageResult.Unsupported)
        }
    }

    @Synchronized
    fun tick(nowMs: Long = clock()) {
        safetyManager.expire(nowMs)
        recompute(nowMs)
    }

    /** Forces an immediate snapshot; useful after profile edits and in tests. */
    fun recomputeNow(nowMs: Long = clock()) {
        synchronized(this) { recompute(nowMs) }
    }

    fun activeAlerts(nowMs: Long = clock()): List<SafetyEvent> =
        safetyManager.activeEvents(position, profileRepository.current().selected, nowMs)

    fun alertHistory(limit: Int = 200): List<SafetyEvent> = safetyManager.history(limit)

    fun restoreHistory(events: List<SafetyEvent>) {
        safetyManager.restoreHistory(events)
    }

    fun dismissAlert(actionId: org.opentrafficmap.citstogo.drive.safety.ActionId) {
        safetyManager.dismiss(actionId)
        recompute()
    }

    fun diagnostics(): DriveDiagnostics = synchronized(this) { diagnostics }

    fun exportDiagnostics(): String = diagnostics().exportText()

    @Synchronized
    fun setSimulationEnabled(enabled: Boolean) {
        simulated = enabled
        if (!enabled) {
            intersectionStore.clear()
            safetyManager.clear()
            lastMessageAtMs = null
            lastSpatAtMs = null
            lastMapAtMs = null
            lastDenmAtMs = null
        }
        recompute()
    }

    /** Simulation entry points: normalised data, never presented as live traffic. */
    @Synchronized
    fun applySimulatedMap(map: MapIntersection, receivedAtMs: Long = clock()) {
        simulated = true
        lastMapAtMs = receivedAtMs
        lastMessageAtMs = receivedAtMs
        simulatedMaps[map.key] = map
        recompute(receivedAtMs)
    }

    @Synchronized
    fun applySimulatedSpat(spat: SpatIntersection, receivedAtMs: Long = clock()) {
        simulated = true
        lastSpatAtMs = receivedAtMs
        lastMessageAtMs = receivedAtMs
        simulatedSpats[spat.key] = spat
        recompute(receivedAtMs)
    }

    @Synchronized
    fun applySimulatedDenm(denm: ReceivedDenm, receivedAtMs: Long = clock()) {
        simulated = true
        lastDenmAtMs = receivedAtMs
        lastMessageAtMs = receivedAtMs
        val result = safetyManager.accept(
            denm = denm,
            receivedAtMs = receivedAtMs,
            position = position,
            profile = profileRepository.current().selected,
            nowMs = receivedAtMs,
        )
        result.notification?.let { mutableNotifications.tryEmit(it) }
        recompute(receivedAtMs)
    }

    private val simulatedMaps = LinkedHashMap<org.opentrafficmap.citstogo.intersection.IntersectionKey, MapIntersection>()
    private val simulatedSpats = LinkedHashMap<org.opentrafficmap.citstogo.intersection.IntersectionKey, SpatIntersection>()

    @Synchronized
    fun clearSimulation() {
        simulatedMaps.clear()
        simulatedSpats.clear()
        simulated = false
        intersectionStore.clear()
        safetyManager.clear()
        recompute()
    }

    @Synchronized
    private fun recompute(nowMs: Long = clock()) {
        val settings = settingsRepository.current()
        val profile = profileRepository.current().selected
        pruneFrameTimes(nowMs)
        val positionAge = position?.let { (nowMs - it.timestampMs).coerceAtLeast(0L) }
        val usablePosition = position?.takeIf { positionAge != null && positionAge <= POSITION_MAX_AGE_MS }
        val snapshots = if (simulated) simulatedSnapshots() else intersectionStore.activeSnapshots(nowMs, INTERSECTION_MAX_AGE_MS)
        val maps = snapshots.mapNotNull { it.map }
        val spats = snapshots.mapNotNull { it.spat }.associateBy { it.key }
        val match = usablePosition?.let {
            IntersectionMatcher.match(it, maps, spats)
        }
        val signal = match?.let { matched ->
            matched.lane.signalGroup?.let { group ->
                matched.spat?.let { spat -> SignalSnapshot.from(spat, group, nowMs) }
            }
        }
        val laneLimit = match?.let { matched ->
            matched.map.lanes.firstOrNull { it.id == matched.lane.laneId }
                ?.speedLimitsKph?.minOrNull()
        }
        // A lane-specific limit is more precise than the intersection-level list.
        val roadLimit = laneLimit ?: match?.map?.speedLimitsKph?.minOrNull()
        val rawGlosa = if (match != null && signal != null) {
            GlosaEngine.calculate(
                GlosaInput(
                    distanceToStopLineM = match.lane.distanceToStopLineM,
                    currentSpeedMps = usablePosition?.speedMetersPerSecond?.toDouble() ?: 0.0,
                    roadSpeedLimitKph = roadLimit ?: settings.defaultSpeedLimitKph,
                    signalState = signal.state,
                    minEndSeconds = signal.minEndSeconds,
                    likelyEndSeconds = signal.likelyEndSeconds,
                    maxEndSeconds = signal.maxEndSeconds,
                    signalConfidence = signal.timingConfidence,
                    spatReceivedAtMs = signal.spatReceivedAtMs,
                    nowMs = nowMs,
                    positionAccuracyM = usablePosition?.accuracyM?.toDouble(),
                    laneConfidence = match.lane.confidence,
                    profile = profile,
                    staleAfterMs = settings.staleDataSeconds * 1_000L,
                ),
            )
        } else {
            null
        }
        val alerts = safetyManager.activeEvents(usablePosition, profile, nowMs)
        val primary = alerts.firstOrNull()
        val freshness = listOfNotNull(lastMessageAtMs, lastSpatAtMs, lastMapAtMs).maxOrNull()?.let {
            (nowMs - it).coerceAtLeast(0L)
        }
        val dataStale = when {
            lastSpatAtMs == null -> true
            else -> (nowMs - lastSpatAtMs!!) > settings.staleDataSeconds * 1_000L
        }
        val suppression = when {
            !simulated && !connection.connected &&
                connection.phase != org.opentrafficmap.citstogo.drive.net.ConnectionPhase.Connecting &&
                rawGlosa != null -> "Hardware connection lost"
            !simulated && !connection.connected &&
                connection.phase == org.opentrafficmap.citstogo.drive.net.ConnectionPhase.Connecting &&
                rawGlosa != null -> "Connecting to the C-ITS receiver"
            dataStale && lastSpatAtMs != null -> "C-ITS data is stale"
            profile.suppressGlosaForCriticalHazard &&
                primary != null && primary.priority == AlertPriority.Critical &&
                (primary.distanceM ?: Double.MAX_VALUE) <= profile.criticalDistanceM &&
                primary.isAhead != false -> "Critical hazard ahead takes priority"
            else -> null
        }
        val glosa = rawGlosa.takeIf { suppression == null }
        val snapshot = DriveSnapshot(
            connection = connection,
            networkAvailable = networkAvailable,
            settings = settings,
            profile = profile,
            position = usablePosition,
            positionAgeMs = positionAge,
            intersection = match,
            signal = signal,
            glosa = glosa,
            glosaSuppressedBy = suppression,
            alerts = alerts,
            primaryAlert = primary,
            intersections = snapshots,
            dataFreshnessMs = freshness,
            dataStale = dataStale,
            simulated = simulated,
            diagnostics = diagnostics.copy(messagesPerSecond = messageRate(nowMs)),
            lastUpdatedMs = nowMs,
        )
        mutableState.value = snapshot
    }

    private fun simulatedSnapshots(): List<IntersectionSnapshot> {
        val keys = LinkedHashSet<org.opentrafficmap.citstogo.intersection.IntersectionKey>()
        keys.addAll(simulatedMaps.keys)
        keys.addAll(simulatedSpats.keys)
        return keys.map { key ->
            val map = simulatedMaps[key]
            val spat = simulatedSpats[key]
            IntersectionSnapshot(
                map = map,
                spat = spat,
                source = org.opentrafficmap.citstogo.intersection.SelectionSource.LatestObserved,
                updatedAtMs = maxOf(map?.receivedAtMs ?: 0L, spat?.receivedAtMs ?: 0L),
            )
        }
    }

    private fun record(
        receivedAtMs: Long,
        type: String,
        port: Int?,
        stationId: Long?,
        length: Int,
        result: MessageResult,
        detail: String? = null,
    ) {
        val updated = diagnostics.records + RawMessageRecord(
            receivedAtMs = receivedAtMs,
            messageType = type,
            destinationPort = port,
            stationId = stationId,
            lengthBytes = length,
            result = result,
            detail = detail,
        )
        diagnostics = diagnostics.copy(
            records = if (updated.size > DriveDiagnostics.MAX_RECORDS) {
                updated.takeLast(DriveDiagnostics.MAX_RECORDS)
            } else {
                updated
            },
            lastMessageAtMs = lastMessageAtMs,
        )
    }

    private fun pruneFrameTimes(nowMs: Long) {
        while (frameTimes.isNotEmpty() && nowMs - frameTimes.first() > RATE_WINDOW_MS) {
            frameTimes.removeFirst()
        }
    }

    private fun messageRate(nowMs: Long): Double {
        pruneFrameTimes(nowMs)
        if (frameTimes.isEmpty()) return 0.0
        val span = (nowMs - frameTimes.first()).coerceAtLeast(1_000L)
        return frameTimes.size * 1_000.0 / span
    }

    companion object {
        const val POSITION_MAX_AGE_MS = 15_000L
        const val INTERSECTION_MAX_AGE_MS = 60_000L
        private const val RATE_WINDOW_MS = 5_000L
    }
}
