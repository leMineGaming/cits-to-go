package org.opentrafficmap.citstogo.drive.safety

import kotlin.math.abs
import org.opentrafficmap.citstogo.denm.DenmAlacarte
import org.opentrafficmap.citstogo.denm.DenmTermination
import org.opentrafficmap.citstogo.denm.ReceivedDenm
import org.opentrafficmap.citstogo.drive.model.DenmCategory
import org.opentrafficmap.citstogo.drive.position.GeoMath
import org.opentrafficmap.citstogo.drive.position.VehiclePosition
import org.opentrafficmap.citstogo.drive.profile.AlertPriority
import org.opentrafficmap.citstogo.drive.profile.DriverProfile
import org.opentrafficmap.citstogo.livemap.DenmCauses
import org.opentrafficmap.citstogo.protocol.ItsTime
import kotlin.math.cos

/** ETSI ActionID: originating station plus sequence number. */
data class ActionId(val originatingStationId: Long, val sequenceNumber: Int)

enum class SafetyEventStatus(val label: String) {
    New("New"),
    Updated("Updated"),
    Cancelled("Cancelled"),
    Negated("Negated"),
    Expired("Expired"),
}

enum class RelevanceTrafficDirection(val code: Int, val label: String) {
    AllTraffic(0, "All traffic"),
    Upstream(1, "Approaching the event"),
    Downstream(2, "Moving away from the event"),
    Opposite(3, "Opposite direction"),
    ;

    companion object {
        fun fromCode(code: Int?): RelevanceTrafficDirection? =
            entries.firstOrNull { it.code == code }
    }
}

enum class RelativeDirection(val label: String) {
    Ahead("Ahead"),
    Behind("Behind"),
    Left("Left"),
    Right("Right"),
    Unknown("Direction unknown"),
}

enum class EventRelevance(val label: String) {
    High("Highly relevant"),
    Medium("Relevant"),
    Low("Low relevance"),
    Irrelevant("Not relevant"),
}

/**
 * A normalised, driver-facing DENM event. Raw cause/subcause and information quality are retained
 * so new ETSI causes can still be displayed even when they have no dedicated category yet.
 */
data class SafetyEvent(
    val actionId: ActionId,
    val category: DenmCategory,
    val causeCode: Int?,
    val subCauseCode: Int?,
    val title: String,
    val description: String,
    val priority: AlertPriority,
    val status: SafetyEventStatus,
    val latitudeE7: Int?,
    val longitudeE7: Int?,
    val detectionAtMs: Long,
    val referenceAtMs: Long,
    val receivedAtMs: Long,
    val expiresAtMs: Long,
    val validitySeconds: Int,
    val informationQuality: Int?,
    val relevanceDistance: Int?,
    val trafficDirection: RelevanceTrafficDirection?,
    val eventSpeedKph: Double?,
    val eventHeadingDeg: Double?,
    val stationType: Int,
    val alacartePresent: Boolean = false,
    val alacarte: DenmAlacarte? = null,
    val traces: List<List<Pair<Int, Int>>> = emptyList(),
    val eventZone: List<Pair<Int, Int>> = emptyList(),
    val distanceM: Double? = null,
    val relativeDirection: RelativeDirection = RelativeDirection.Unknown,
    val relevance: EventRelevance = EventRelevance.Medium,
    val confidence: Double = 0.5,
    val ageSeconds: Long = 0,
    val remainingValiditySeconds: Long = 0,
    val version: Int = 1,
    val isAhead: Boolean? = null,
) {
    val active: Boolean get() = status == SafetyEventStatus.New || status == SafetyEventStatus.Updated
    val sourceLabel: String get() = "Station ${actionId.originatingStationId} #${actionId.sequenceNumber}"
    val remainingValidityLabel: String
        get() = when {
            !active -> status.label
            remainingValiditySeconds <= 0 -> "Expired"
            remainingValiditySeconds >= 60 -> "${remainingValiditySeconds / 60} min left"
            else -> "${remainingValiditySeconds}s left"
        }
}

data class SafetyNotification(
    val actionId: ActionId,
    val title: String,
    val message: String,
    val priority: AlertPriority,
    val category: DenmCategory,
    val isUpdate: Boolean,
    val event: SafetyEvent,
)

data class SafetyAcceptResult(
    val event: SafetyEvent?,
    val notification: SafetyNotification? = null,
    val ignoredReason: String? = null,
)

private data class StoredEvent(
    val denm: ReceivedDenm,
    val detectionAtMs: Long,
    val referenceAtMs: Long,
    val expiresAtMs: Long,
    var receivedAtMs: Long,
    var status: SafetyEventStatus,
    var version: Int,
    val category: DenmCategory,
    val priority: AlertPriority,
    val traces: List<List<Pair<Int, Int>>>,
    val eventZone: List<Pair<Int, Int>>,
)

/**
 * Turns decoded DENMs into deduplicated, prioritised safety events.
 *
 * The manager implements the receiving rules of EN 302 637-3: ActionID identifies an event, older
 * repetitions are ignored, equal repetitions only refresh reception, cancellation/negation hides
 * the event, and events expire with their validity duration. Notifications are emitted once per
 * material change, with a cooldown, so a 1 Hz DENM cannot spam the driver.
 */
class SafetyAlertManager(
    private val historyLimit: Int = 200,
) {
    private val active = LinkedHashMap<ActionId, StoredEvent>()
    private val notifications = HashMap<ActionId, NotificationRecord>()
    private val history = ArrayDeque<SafetyEvent>()

    private data class NotificationRecord(
        val atMs: Long,
        val priority: AlertPriority,
        val cause: Int?,
        val status: SafetyEventStatus,
        val version: Int,
    )

    fun accept(
        denm: ReceivedDenm,
        receivedAtMs: Long,
        position: VehiclePosition?,
        profile: DriverProfile,
        nowMs: Long = receivedAtMs,
    ): SafetyAcceptResult {
        val actionId = ActionId(denm.originatingStationId, denm.sequenceNumber)
        val detectionAtMs = ItsTime.toUnixMs(denm.detectionTimeIts)
        val referenceAtMs = ItsTime.toUnixMs(denm.referenceTimeIts)
        val expiresAtMs = detectionAtMs + denm.validityDurationSeconds.coerceAtLeast(0) * 1_000L
        if (expiresAtMs <= nowMs && denm.termination == null) {
            return SafetyAcceptResult(null, ignoredReason = "Event is already expired")
        }

        val existing = active[actionId]?.takeIf { it.expiresAtMs > nowMs }
        if (existing == null) {
            // A termination for an event we never saw is not actionable.
            if (denm.termination != null) {
                return SafetyAcceptResult(null, ignoredReason = "Termination for an unknown event")
            }
        } else {
            if (referenceAtMs < existing.referenceAtMs || detectionAtMs < existing.detectionAtMs) {
                return SafetyAcceptResult(null, ignoredReason = "Outdated repetition")
            }
            val sameContent = referenceAtMs == existing.referenceAtMs &&
                detectionAtMs == existing.detectionAtMs &&
                denm.termination == existing.denm.termination
            if (sameContent) {
                existing.receivedAtMs = receivedAtMs
                val event = snapshot(existing, position, profile, nowMs)
                return SafetyAcceptResult(event, ignoredReason = "Repeated event")
            }
        }

        val category = DenmCategory.fromCause(denm.situation?.causeCode)
        val priority = classifyPriority(category, denm.situation?.causeCode, denm.situation?.subCauseCode)
        val status = when (denm.termination) {
            DenmTermination.Cancellation -> SafetyEventStatus.Cancelled
            DenmTermination.Negation -> SafetyEventStatus.Negated
            null -> if (existing == null) SafetyEventStatus.New else SafetyEventStatus.Updated
        }
        val tracePositions = buildTraces(denm)
        val eventZone = buildEventZone(denm)
        val stored = StoredEvent(
            denm = denm,
            detectionAtMs = detectionAtMs,
            referenceAtMs = referenceAtMs,
            expiresAtMs = maxOf(expiresAtMs, existing?.expiresAtMs ?: 0L),
            receivedAtMs = receivedAtMs,
            status = status,
            version = (existing?.version ?: 0) + 1,
            category = category,
            priority = priority,
            traces = tracePositions,
            eventZone = eventZone,
        )
        if (active.size >= MAX_ACTIVE && actionId !in active) {
            active.values.minByOrNull { it.expiresAtMs }?.let { oldest ->
                val oldestId = active.entries.firstOrNull { it.value === oldest }?.key
                if (oldestId != null) active.remove(oldestId)
            }
        }
        active[actionId] = stored
        val event = snapshot(stored, position, profile, nowMs)
        recordHistory(event)
        val notification = notificationFor(event, stored, existing, profile, nowMs)
        return SafetyAcceptResult(event, notification)
    }

    /** Active, unexpired events ordered by priority and relevance. */
    fun activeEvents(
        position: VehiclePosition?,
        profile: DriverProfile,
        nowMs: Long,
    ): List<SafetyEvent> {
        expire(nowMs)
        return active.values
            .filter { it.status == SafetyEventStatus.New || it.status == SafetyEventStatus.Updated }
            .map { snapshot(it, position, profile, nowMs) }
            .filter { it.relevance != EventRelevance.Irrelevant }
            .sortedWith(
                compareBy<SafetyEvent> { it.priority.ordinal }
                    .thenBy { it.distanceM ?: Double.MAX_VALUE },
            )
    }

    /** Event history, newest first, including cancelled, negated and expired events. */
    fun history(limit: Int = historyLimit): List<SafetyEvent> = history.take(limit)

    fun event(actionId: ActionId): SafetyEvent? = active[actionId]?.let { snapshot(it, null, DriverProfile.default(), it.receivedAtMs) }

    /** Removes an event from the active list without treating the DENM as cancelled. */
    fun dismiss(actionId: ActionId) {
        active.remove(actionId)
    }

    fun expire(nowMs: Long) {
        val expired = active.values.filter { it.expiresAtMs <= nowMs }
        expired.forEach { stored ->
            stored.status = SafetyEventStatus.Expired
            recordHistory(snapshot(stored, null, DriverProfile.default(), nowMs))
        }
        active.values.removeAll { it.expiresAtMs <= nowMs }
    }

    fun clear() {
        active.clear()
        notifications.clear()
        history.clear()
    }

    /** Restores persisted history from a previous process, newest first and without duplicates. */
    fun restoreHistory(events: List<SafetyEvent>) {
        events.asReversed().forEach { event ->
            if (history.none { existing ->
                    existing.actionId == event.actionId && existing.version == event.version
                }
            ) {
                history.addFirst(event)
            }
        }
        while (history.size > historyLimit) history.removeLast()
    }

    private fun notificationFor(
        event: SafetyEvent,
        stored: StoredEvent,
        previous: StoredEvent?,
        profile: DriverProfile,
        nowMs: Long,
    ): SafetyNotification? {
        if (!event.active) return null
        if (!profile.notificationsEnabled) return null
        if (!profile.isCategoryEnabled(event.category)) return null
        if (event.distanceM != null) {
            val threshold = profile.alertDistanceFor(event.category, event.priority)
            if (event.distanceM!! > threshold) return null
        }
        if (event.isAhead == false && event.category !in ALWAYS_NOTIFY_BEHIND) return null
        if (event.relevance == EventRelevance.Irrelevant) return null

        val last = notifications[event.actionId]
        val cooldownMs = if (profile.repeatAlerts) REPEAT_COOLDOWN_MS else UPDATE_COOLDOWN_MS
        if (last != null) {
            val newerState = event.version != last.version
            val escalated = event.priority.ordinal < last.priority.ordinal
            val sameContent = event.causeCode == last.cause && event.status == last.status
            if (!escalated && sameContent) return null
            if (!escalated && newerState && nowMs - last.atMs < cooldownMs) return null
            if (nowMs - last.atMs < cooldownMs) return null
        }
        val isUpdate = previous != null && previous.category == event.category
        notifications[event.actionId] = NotificationRecord(
            atMs = nowMs,
            priority = event.priority,
            cause = event.causeCode,
            status = event.status,
            version = event.version,
        )
        val prefix = when {
            event.priority == AlertPriority.Critical -> "Critical"
            event.priority == AlertPriority.Advisory -> "Advisory"
            else -> "Notice"
        }
        val distance = event.distanceM?.let { " · ${formatDistance(it)}" }.orEmpty()
        return SafetyNotification(
            actionId = event.actionId,
            title = "$prefix: ${event.title}",
            message = "${event.description}$distance",
            priority = event.priority,
            category = event.category,
            isUpdate = isUpdate,
            event = event,
        )
    }

    private fun snapshot(
        stored: StoredEvent,
        position: VehiclePosition?,
        profile: DriverProfile,
        nowMs: Long,
    ): SafetyEvent {
        val denm = stored.denm
        val situation = denm.situation
        val points = buildList {
            if (denm.latitude != null && denm.longitude != null) add(denm.latitude to denm.longitude)
            addAll(stored.eventZone)
            stored.traces.forEach { trace -> addAll(trace) }
        }
        val nearest = position?.let { vehicle ->
            points.minByOrNull { (latitude, longitude) ->
                GeoMath.distanceMeters(vehicle.latitudeE7, vehicle.longitudeE7, latitude, longitude)
            }?.let { (latitude, longitude) ->
                val distance = GeoMath.distanceMeters(vehicle.latitudeE7, vehicle.longitudeE7, latitude, longitude)
                val bearing = GeoMath.bearingDegrees(
                    vehicle.latitudeE7, vehicle.longitudeE7, latitude, longitude,
                )
                Triple(distance, bearing, latitude to longitude)
            }
        }
        val distance = nearest?.first
        val bearing = nearest?.second
        val heading = position?.headingDegrees?.toDouble()
        val relative = when {
            bearing == null || heading == null -> RelativeDirection.Unknown
            GeoMath.bearingDifference(bearing, heading) <= 45 -> RelativeDirection.Ahead
            GeoMath.bearingDifference(bearing, heading) >= 135 -> RelativeDirection.Behind
            ((bearing - heading + 540) % 360) - 180 > 0 -> RelativeDirection.Right
            else -> RelativeDirection.Left
        }
        val isAhead = when (relative) {
            RelativeDirection.Ahead -> true
            RelativeDirection.Behind -> false
            RelativeDirection.Left, RelativeDirection.Right -> true
            RelativeDirection.Unknown -> null
        }
        val remaining = (stored.expiresAtMs - nowMs).coerceAtLeast(0L)
        val age = (nowMs - stored.detectionAtMs).coerceAtLeast(0L)
        val infoQuality = situation?.informationQuality
        val qualityConfidence = infoQuality?.let { (1.0 - it.coerceIn(0, 7) / 7.0) } ?: 0.5
        val accuracyConfidence = position?.accuracyM?.let {
            (1.0 - (it - 5f) / 40f).coerceIn(0.2, 1.0)
        } ?: 0.7
        val validityFactor = if (stored.denm.validityDurationSeconds <= 0) {
            0.3
        } else {
            (remaining.toDouble() / (stored.denm.validityDurationSeconds * 1_000.0)).coerceIn(0.15, 1.0)
        }
        val distanceFactor = distance?.let { (1.0 - it / 2_000.0).coerceIn(0.25, 1.0) } ?: 0.6
        val confidence = (qualityConfidence * 0.4 + accuracyConfidence * 0.2 +
            validityFactor * 0.2 + distanceFactor * 0.2).coerceIn(0.0, 1.0)
        val relevance = relevanceOf(stored, distance, relative, profile)
        val traffic = RelevanceTrafficDirection.fromCode(denm.awarenessTrafficDirection)
        val title = situation?.let { DenmCauses.label(it.causeCode, it.subCauseCode) }
            ?: "C-ITS event without a situation container"
        val event = SafetyEvent(
            actionId = ActionId(denm.originatingStationId, denm.sequenceNumber),
            category = stored.category,
            causeCode = situation?.causeCode,
            subCauseCode = situation?.subCauseCode,
            title = title,
            description = descriptionFor(stored.category, title, traffic),
            priority = stored.priority,
            status = stored.status,
            latitudeE7 = denm.latitude,
            longitudeE7 = denm.longitude,
            detectionAtMs = stored.detectionAtMs,
            referenceAtMs = stored.referenceAtMs,
            receivedAtMs = stored.receivedAtMs,
            expiresAtMs = stored.expiresAtMs,
            validitySeconds = denm.validityDurationSeconds,
            informationQuality = infoQuality,
            relevanceDistance = denm.awarenessDistance,
            trafficDirection = traffic,
            eventSpeedKph = denm.eventSpeedCms?.let { it / 100.0 * 3.6 },
            eventHeadingDeg = denm.eventHeadingTenths?.takeIf { it in 0..3_600 }?.let { it / 10.0 },
            stationType = denm.stationType,
            alacartePresent = denm.alacartePresent,
            alacarte = denm.alacarte,
            traces = stored.traces,
            eventZone = stored.eventZone,
            distanceM = distance,
            relativeDirection = relative,
            relevance = relevance,
            confidence = confidence,
            ageSeconds = age / 1_000,
            remainingValiditySeconds = remaining / 1_000,
            version = stored.version,
            isAhead = isAhead,
        )
        return event
    }

    private fun relevanceOf(
        stored: StoredEvent,
        distance: Double?,
        relative: RelativeDirection,
        profile: DriverProfile,
    ): EventRelevance {
        if (distance == null) return EventRelevance.Medium
        val maxDistance = profile.alertDistanceFor(stored.category, stored.priority)
        if (distance > maxDistance) return EventRelevance.Irrelevant
        val direction = RelevanceTrafficDirection.fromCode(stored.denm.awarenessTrafficDirection)
        if (direction == RelevanceTrafficDirection.Opposite && relative != RelativeDirection.Ahead) {
            return EventRelevance.Low
        }
        if (relative == RelativeDirection.Behind && distance > BEHIND_RELEVANCE_M) {
            return EventRelevance.Low
        }
        return when {
            distance <= maxDistance * 0.35 && relative == RelativeDirection.Ahead -> EventRelevance.High
            distance <= maxDistance * 0.7 -> EventRelevance.Medium
            else -> EventRelevance.Low
        }
    }

    private fun recordHistory(event: SafetyEvent) {
        history.addFirst(event)
        while (history.size > historyLimit) history.removeLast()
    }

    private fun buildEventZone(denm: ReceivedDenm): List<Pair<Int, Int>> {
        val baseLatitude = denm.latitude ?: return emptyList()
        val baseLongitude = denm.longitude ?: return emptyList()
        var latitude = baseLatitude
        var longitude = baseLongitude
        return denm.situation?.eventZone.orEmpty().mapNotNull { delta ->
            if (delta.latitude == org.opentrafficmap.citstogo.denm.DeltaPosition.UNAVAILABLE ||
                delta.longitude == org.opentrafficmap.citstogo.denm.DeltaPosition.UNAVAILABLE
            ) {
                return@mapNotNull null
            }
            latitude += delta.latitude
            longitude += delta.longitude
            latitude to longitude
        }
    }

    private fun buildTraces(denm: ReceivedDenm): List<List<Pair<Int, Int>>> {
        val baseLatitude = denm.latitude ?: return emptyList()
        val baseLongitude = denm.longitude ?: return emptyList()
        return denm.traces.mapNotNull { trace ->
            if (trace.isEmpty()) return@mapNotNull null
            var latitude = baseLatitude
            var longitude = baseLongitude
            trace.mapNotNull { delta ->
                if (delta.latitude == org.opentrafficmap.citstogo.denm.DeltaPosition.UNAVAILABLE ||
                    delta.longitude == org.opentrafficmap.citstogo.denm.DeltaPosition.UNAVAILABLE
                ) {
                    return@mapNotNull null
                }
                latitude += delta.latitude
                longitude += delta.longitude
                latitude to longitude
            }.takeIf { it.isNotEmpty() }
        }
    }

    private fun descriptionFor(
        category: DenmCategory,
        title: String,
        direction: RelevanceTrafficDirection?,
    ): String {
        val suffix = when (direction) {
            RelevanceTrafficDirection.Upstream -> " — affects approaching traffic"
            RelevanceTrafficDirection.Downstream -> " — affects traffic moving away"
            RelevanceTrafficDirection.Opposite -> " — opposite direction"
            else -> ""
        }
        return when (category) {
            DenmCategory.CollisionRisk -> "$title. Increase following distance.$suffix"
            DenmCategory.Accident -> "$title. Expect delays and emergency vehicles.$suffix"
            DenmCategory.TrafficJam -> "$title. Reduce speed smoothly.$suffix"
            DenmCategory.Roadworks -> "$title. Watch for workers and lane shifts.$suffix"
            DenmCategory.Impassable -> "$title. Consider an alternative route.$suffix"
            DenmCategory.SlipperyRoad -> "$title. Reduce speed and avoid sudden inputs.$suffix"
            DenmCategory.ReducedVisibility -> "$title. Use lights and increase distance.$suffix"
            DenmCategory.Obstacle -> "$title. Be prepared to stop.$suffix"
            DenmCategory.VulnerableRoadUser -> "$title. Give people and animals room.$suffix"
            DenmCategory.StationaryVehicle -> "$title. Change lane safely if possible.$suffix"
            DenmCategory.EmergencyVehicle -> "$title. Make way.$suffix"
            DenmCategory.WrongWayDriver -> "$title. Keep right and reduce speed.$suffix"
            DenmCategory.RailwayCrossing -> "$title. Obey the crossing.$suffix"
            DenmCategory.DangerousConditions -> "$title. Adapt speed to conditions.$suffix"
            DenmCategory.Other -> "$title.$suffix"
        }
    }

    companion object {
        private const val MAX_ACTIVE = 1_000
        private const val UPDATE_COOLDOWN_MS = 30_000L
        private const val REPEAT_COOLDOWN_MS = 90_000L
        private const val BEHIND_RELEVANCE_M = 150.0

        private val ALWAYS_NOTIFY_BEHIND = setOf(
            DenmCategory.CollisionRisk,
            DenmCategory.EmergencyVehicle,
            DenmCategory.WrongWayDriver,
        )

        fun classifyPriority(
            category: DenmCategory,
            causeCode: Int?,
            subCauseCode: Int?,
        ): AlertPriority = when (category) {
            DenmCategory.Accident,
            DenmCategory.CollisionRisk,
            DenmCategory.WrongWayDriver,
            DenmCategory.Obstacle,
            DenmCategory.VulnerableRoadUser,
            DenmCategory.EmergencyVehicle,
            -> AlertPriority.Critical

            DenmCategory.TrafficJam -> if (causeCode == 27) AlertPriority.Critical else AlertPriority.Advisory
            DenmCategory.StationaryVehicle -> if (causeCode in 91..93) AlertPriority.Critical else AlertPriority.Advisory
            DenmCategory.Roadworks,
            DenmCategory.Impassable,
            DenmCategory.SlipperyRoad,
            DenmCategory.ReducedVisibility,
            DenmCategory.DangerousConditions,
            DenmCategory.RailwayCrossing,
            -> AlertPriority.Advisory
            DenmCategory.Other -> AlertPriority.Informational
        }

        fun formatDistance(meters: Double): String = when {
            meters < 1_000 -> "${meters.toInt()} m"
            else -> String.format(java.util.Locale.US, "%.1f km", meters / 1_000.0)
        }
    }
}
