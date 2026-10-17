package org.opentrafficmap.citstogo.livemap

/** A station on the map, in display units. */
data class StationMarker(
    val stationId: Long,
    val stationType: Int,
    val typeLabel: String,
    val latitude: Double,
    val longitude: Double,
    /** Degrees clockwise from north, as the latest CAM reports it. */
    val headingDeg: Double?,
    /** Where the pointer shows: the last heading, which a vehicle standing still keeps. Without one, no pointer. */
    val directionDeg: Double?,
    val speedMps: Double?,
    /** Drawn behind the icon, opposite the pointer; so like the pointer only with a direction. */
    val trailer: Boolean,
    val icon: String,
    val ageMs: Long,
    val stale: Boolean,
)

/** A position in degrees. */
data class GeoPosition(val latitude: Double, val longitude: Double)

/** A DENM event on the map, at its event position. */
data class DenmMarker(
    /** The actionId as `<originatingStationId>/<sequenceNumber>`. */
    val id: String,
    val originatingStationId: Long,
    val stationType: Int,
    val stationTypeLabel: String,
    val latitude: Double,
    val longitude: Double,
    /** The cause and subcause, for the detail card. */
    val label: String,
    /** The cause alone, under the marker. */
    val causeLabel: String,
    val iconGroup: DenmIconGroup,
    /** The event zone from the event position on, empty without one. */
    val eventZone: List<GeoPosition>,
    /** The traces, each from the event position on. */
    val traces: List<List<GeoPosition>>,
    val causeCode: Int?,
    val subCauseCode: Int?,
    val informationQuality: Int?,
    /**
     * Infrastructure resets the detection time with every update, so [sinceDetectionMs] reads "updated n s ago"
     * instead of "since n s".
     */
    val infrastructure: Boolean,
    val sinceDetectionMs: Long,
    val sinceReceivedMs: Long,
    val expiresAtMs: Long,
)

data class LiveMap(
    val stations: List<StationMarker>,
    val denms: List<DenmMarker>,
)

/** What the map shows of a [LiveMapSnapshot] at a given time. */
object LiveMapModel {
    const val DEFAULT_STATION_TIMEOUT_MS = 10_000L
    const val MIN_STATION_TIMEOUT_MS = 5_000L
    const val MAX_STATION_TIMEOUT_MS = 300_000L

    /** A single lost CAM at 1 Hz leaves a gap of 2 s. */
    const val STALE_AFTER_MS = 3_000L

    /** Road side units do not move, and no specification limits their CAM interval. */
    const val MIN_ROAD_SIDE_UNIT_TIMEOUT_MS = 30_000L

    // Trailers and special vehicles of the road operator, and road side units: C-Roads infrastructure DENM senders.
    private val INFRASTRUCTURE_STATION_TYPES = setOf(9, 10, 15)

    /**
     * Stations heard within [stationTimeoutMs], at least [MIN_ROAD_SIDE_UNIT_TIMEOUT_MS] for road side units, and the
     * DENM events that have not expired at [nowMs]. Ages count from [nowMs] and are never negative, should the clock
     * jump back.
     */
    fun build(snapshot: LiveMapSnapshot, nowMs: Long, stationTimeoutMs: Long): LiveMap {
        val timeoutMs = stationTimeoutMs.coerceIn(MIN_STATION_TIMEOUT_MS, MAX_STATION_TIMEOUT_MS)
        return LiveMap(
            stations = snapshot.stations.mapNotNull { station(it, nowMs, timeoutMs) },
            denms = snapshot.denms.filter { it.expiresAtMs > nowMs }.map { denm(it, nowMs) },
        )
    }

    private fun station(station: LiveMapStation, nowMs: Long, timeoutMs: Long): StationMarker? {
        val ageMs = (nowMs - station.receivedAtMs).coerceAtLeast(0L)
        val stationTimeoutMs = if (station.roadSideUnit) maxOf(timeoutMs, MIN_ROAD_SIDE_UNIT_TIMEOUT_MS) else timeoutMs
        if (ageMs > stationTimeoutMs) return null
        return StationMarker(
            stationId = station.stationId,
            stationType = station.stationType,
            typeLabel = StationTypes.label(station.stationType),
            latitude = station.latitude.degrees(),
            longitude = station.longitude.degrees(),
            headingDeg = station.heading?.let { it / 10.0 },
            directionDeg = station.lastHeading?.let { it / 10.0 },
            speedMps = station.speedCms?.let { it / 100.0 },
            trailer = station.trailer,
            icon = StationTypes.iconGroup(station.stationType).icon,
            ageMs = ageMs,
            stale = ageMs >= STALE_AFTER_MS,
        )
    }

    private fun denm(denm: LiveMapDenm, nowMs: Long): DenmMarker {
        val causeCode = denm.causeCode
        val subCauseCode = denm.subCauseCode
        return DenmMarker(
            id = "${denm.originatingStationId}/${denm.sequenceNumber}",
            originatingStationId = denm.originatingStationId,
            stationType = denm.stationType,
            stationTypeLabel = StationTypes.label(denm.stationType),
            latitude = denm.latitude.degrees(),
            longitude = denm.longitude.degrees(),
            label = if (causeCode == null || subCauseCode == null) {
                UNKNOWN_CAUSE
            } else {
                DenmCauses.label(causeCode, subCauseCode)
            },
            causeLabel = causeCode?.let { DenmCauses.causeLabel(it) ?: "Cause $it" } ?: UNKNOWN_CAUSE,
            iconGroup = causeCode?.let(DenmCauses::iconGroup) ?: DenmIconGroup.Hazard,
            eventZone = denm.eventZone?.positions().orEmpty(),
            traces = denm.traces.map { it.positions() },
            causeCode = causeCode,
            subCauseCode = subCauseCode,
            informationQuality = denm.informationQuality,
            infrastructure = denm.stationType in INFRASTRUCTURE_STATION_TYPES,
            sinceDetectionMs = (nowMs - denm.detectedAtMs).coerceAtLeast(0L),
            sinceReceivedMs = (nowMs - denm.receivedAtMs).coerceAtLeast(0L),
            expiresAtMs = denm.expiresAtMs,
        )
    }

    private fun PositionLine.positions(): List<GeoPosition> =
        List(size) { GeoPosition(latitude(it).degrees(), longitude(it).degrees()) }

    private fun Int.degrees(): Double = this / 10_000_000.0

    private const val UNKNOWN_CAUSE = "Unknown cause"
}
