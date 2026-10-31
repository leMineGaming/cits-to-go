package org.opentrafficmap.citstogo.drive.position

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.opentrafficmap.citstogo.intersection.IntersectionKey
import org.opentrafficmap.citstogo.intersection.LaneNode
import org.opentrafficmap.citstogo.intersection.LaneType
import org.opentrafficmap.citstogo.intersection.MapIntersection
import org.opentrafficmap.citstogo.intersection.MapLane
import org.opentrafficmap.citstogo.intersection.SpatIntersection

/** A GPS fix in the units used throughout the drive layer. */
data class VehiclePosition(
    val latitudeE7: Int,
    val longitudeE7: Int,
    val accuracyM: Float?,
    val speedMetersPerSecond: Float?,
    /** Degrees, 0…360 with 0 = north. Null when the fix has no reliable bearing. */
    val headingDegrees: Float?,
    val timestampMs: Long,
) {
    val speedKph: Double? get() = speedMetersPerSecond?.let { it * 3.6 }
}

/**
 * The result of matching the vehicle to a MAPEM lane and the lane's signal group.
 * [confidence] is 0…1; GLOSA is prohibited below [MIN_GLOSA_CONFIDENCE].
 */
data class LaneMatch(
    val intersectionKey: IntersectionKey,
    val intersectionName: String?,
    val laneId: Int,
    val signalGroup: Int?,
    val distanceToStopLineM: Double,
    val distanceToIntersectionM: Double,
    val lateralOffsetM: Double,
    val headingErrorDeg: Double,
    val laneHeadingDeg: Double,
    val confidence: Double,
    val approaching: Boolean,
    val signalGroupSource: SignalGroupSource,
)

enum class SignalGroupSource {
    None,
    LaneConnection,
    MovementFallback,
}

data class IntersectionMatch(
    val map: MapIntersection,
    val spat: SpatIntersection?,
    val lane: LaneMatch,
    val alternatives: List<LaneMatch> = emptyList(),
)

/**
 * Matches a position to the closest inbound vehicle lane of a MAPEM intersection.
 *
 * The matcher is intentionally conservative. If the position cannot be placed on an inbound lane
 * with a plausible heading, it returns no match (or a below-threshold confidence), which the GLOSA
 * engine then turns into "no recommendation" rather than a misleading speed.
 */
object IntersectionMatcher {
    const val MIN_GLOSA_CONFIDENCE = 0.45
    const val DEFAULT_LANE_WIDTH_M = 3.5

    fun match(
        position: VehiclePosition,
        maps: List<MapIntersection>,
        spats: Map<IntersectionKey, SpatIntersection>,
        maxIntersectionDistanceM: Double = 250.0,
    ): IntersectionMatch? {
        var best: IntersectionMatch? = null
        for (map in maps) {
            val intersectionDistance = map.distanceTo(position.latitudeE7, position.longitudeE7)
            if (intersectionDistance > maxIntersectionDistanceM) continue
            val spat = spats[map.key]?.takeIf { it.key == map.key }
            val candidates = map.lanes
                .asSequence()
                .filter { it.ingress && it.laneType == LaneType.Vehicle }
                .mapNotNull { lane ->
                    matchLane(position, map, lane, spat, intersectionDistance)
                }
                .sortedWith(
                    compareByDescending<LaneMatch> { it.confidence }
                        .thenBy { it.lateralOffsetM },
                )
                .toList()
            val lane = candidates.firstOrNull() ?: continue
            val candidate = IntersectionMatch(map, spat, lane, candidates.drop(1).take(2))
            if (best == null || candidate.lane.confidence > best.lane.confidence) {
                best = candidate
            }
        }
        return best
    }

    private fun matchLane(
        position: VehiclePosition,
        map: MapIntersection,
        lane: MapLane,
        spat: SpatIntersection?,
        intersectionDistanceM: Double,
    ): LaneMatch? {
        val nodes = lane.nodes
        if (nodes.size < 2) return null
        val (vehicleEast, vehicleNorth) = GeoMath.localOffsetMeters(
            map.latitude,
            map.longitude,
            position.latitudeE7,
            position.longitudeE7,
        )

        var bestProjection: SegmentProjection? = null
        for (index in 0 until nodes.lastIndex) {
            val start = nodes[index]
            val end = nodes[index + 1]
            val startEast = start.xCm / 100.0
            val startNorth = start.yCm / 100.0
            val endEast = end.xCm / 100.0
            val endNorth = end.yCm / 100.0
            val projection = GeoMath.projectOnSegment(
                vehicleEast,
                vehicleNorth,
                startEast,
                startNorth,
                endEast,
                endNorth,
            )
            val heading = laneHeadingDegrees(start, end)
            val candidate = SegmentProjection(
                index = index,
                distanceM = projection.distanceM,
                alongFraction = projection.alongFraction,
                headingDeg = heading,
                projectedEast = projection.east,
                projectedNorth = projection.north,
            )
            if (bestProjection == null || candidate.distanceM < bestProjection.distanceM) {
                bestProjection = candidate
            }
        }
        val projection = bestProjection ?: return null

        val headingError = position.headingDegrees?.let { heading ->
            GeoMath.bearingDifference(heading.toDouble(), projection.headingDeg)
        }
        // A stationary fix often has no bearing; allow it, but with less confidence.
        val headingConfidence = when {
            headingError == null -> 0.6
            headingError <= 20 -> 1.0
            headingError <= 45 -> 1.0 - (headingError - 20) / 25 * 0.45
            headingError <= 90 -> 0.35
            else -> 0.05
        }
        if (headingError != null && headingError > 75) return null

        val laneWidth = (map.laneWidthCm ?: (DEFAULT_LANE_WIDTH_M * 100).toInt()) / 100.0
        val plausibleWidth = laneWidth.coerceIn(2.5, 5.0)
        val lateralConfidence = when {
            projection.distanceM <= plausibleWidth * 0.5 -> 1.0
            projection.distanceM <= plausibleWidth -> 0.85
            projection.distanceM <= plausibleWidth * 2 -> 0.45
            projection.distanceM <= plausibleWidth * 3.5 -> 0.15
            else -> return null
        }

        val stopLineIndex = nodes.indexOfLast { it.stopLine }
        val stopLineDistance = distanceToStopLine(nodes, projection, stopLineIndex)
        val pastStopLine = isPastStopLine(nodes, stopLineIndex, vehicleEast, vehicleNorth)
        val approaching = stopLineDistance >= 0 && !pastStopLine
        if (!approaching) return null

        val signalGroup = resolveSignalGroup(lane, spat)
        val accuracy = position.accuracyM?.toDouble()
        val accuracyConfidence = when {
            accuracy == null -> 0.7
            accuracy <= 5 -> 1.0
            accuracy <= 10 -> 0.85
            accuracy <= 20 -> 0.55
            accuracy <= 40 -> 0.25
            else -> 0.05
        }
        val distanceConfidence = (1.0 - intersectionDistanceM / 400.0).coerceIn(0.2, 1.0)
        val signalConfidence = when (signalGroup.source) {
            SignalGroupSource.LaneConnection -> 1.0
            SignalGroupSource.MovementFallback -> 0.6
            SignalGroupSource.None -> 0.25
        }
        val confidence = (
            0.35 * headingConfidence +
                0.25 * lateralConfidence +
                0.15 * accuracyConfidence +
                0.10 * distanceConfidence +
                0.15 * signalConfidence
            ).coerceIn(0.0, 1.0)

        return LaneMatch(
            intersectionKey = map.key,
            intersectionName = map.name,
            laneId = lane.id,
            signalGroup = signalGroup.group,
            distanceToStopLineM = stopLineDistance,
            distanceToIntersectionM = intersectionDistanceM,
            lateralOffsetM = projection.distanceM,
            headingErrorDeg = headingError ?: Double.NaN,
            laneHeadingDeg = projection.headingDeg,
            confidence = confidence,
            approaching = approaching,
            signalGroupSource = signalGroup.source,
        )
    }

    private data class SegmentProjection(
        val index: Int,
        val distanceM: Double,
        val alongFraction: Double,
        val headingDeg: Double,
        val projectedEast: Double,
        val projectedNorth: Double,
    )

    private fun laneHeadingDegrees(start: LaneNode, end: LaneNode): Double {
        val east = (end.xCm - start.xCm) / 100.0
        val north = (end.yCm - start.yCm) / 100.0
        return (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
    }

    /** Remaining distance from the projection to the stop line; negative once past it. */
    private fun distanceToStopLine(
        nodes: List<LaneNode>,
        projection: SegmentProjection,
        stopLineIndex: Int,
    ): Double {
        val targetIndex = if (stopLineIndex >= 0) stopLineIndex else nodes.lastIndex
        val segmentEnd = projection.index + 1
        var distance = 0.0
        if (targetIndex >= segmentEnd) {
            // Finish the current segment, then follow the remaining nodes to the stop line.
            val start = nodes[projection.index]
            val end = nodes[segmentEnd]
            val segmentLength = hypot(
                (end.xCm - start.xCm) / 100.0,
                (end.yCm - start.yCm) / 100.0,
            )
            distance += segmentLength * (1.0 - projection.alongFraction)
            for (index in segmentEnd until targetIndex) {
                distance += segmentLength(nodes[index], nodes[index + 1])
            }
        } else {
            // Projection is beyond the stop line: report how far past it the vehicle is.
            val start = nodes[targetIndex]
            val end = nodes[projection.index.coerceAtMost(nodes.lastIndex)]
            distance = -abs(hypot(
                (end.xCm - start.xCm) / 100.0,
                (end.yCm - start.yCm) / 100.0,
            ))
        }
        return distance
    }

    private fun segmentLength(start: LaneNode, end: LaneNode): Double =
        hypot((end.xCm - start.xCm) / 100.0, (end.yCm - start.yCm) / 100.0)

    /** True when the vehicle has crossed the stop line (or the end of a lane without one). */
    private fun isPastStopLine(
        nodes: List<LaneNode>,
        stopLineIndex: Int,
        vehicleEast: Double,
        vehicleNorth: Double,
    ): Boolean {
        val targetIndex = if (stopLineIndex >= 0) stopLineIndex else nodes.lastIndex
        if (targetIndex <= 0) return false
        val target = nodes[targetIndex]
        val previous = nodes[targetIndex - 1]
        val directionEast = (target.xCm - previous.xCm) / 100.0
        val directionNorth = (target.yCm - previous.yCm) / 100.0
        val length = hypot(directionEast, directionNorth)
        if (length <= 1e-6) return false
        val offsetEast = vehicleEast - target.xCm / 100.0
        val offsetNorth = vehicleNorth - target.yCm / 100.0
        val along = (offsetEast * directionEast + offsetNorth * directionNorth) / length
        return along > 0.5
    }

    private data class SignalGroupResolution(val group: Int?, val source: SignalGroupSource)

    private fun resolveSignalGroup(lane: MapLane, spat: SpatIntersection?): SignalGroupResolution {
        val declared = lane.connections.mapNotNull { it.signalGroup }.distinct()
        val movementGroups = spat?.movements?.map { it.signalGroup }?.toSet().orEmpty()
        val signaled = declared.filter { it in movementGroups }
        if (signaled.size == 1) return SignalGroupResolution(signaled.first(), SignalGroupSource.LaneConnection)
        if (declared.size == 1) {
            return SignalGroupResolution(
                declared.first(),
                if (spat == null) SignalGroupSource.LaneConnection else SignalGroupSource.MovementFallback,
            )
        }
        if (declared.size > 1) {
            val match = signaled.firstOrNull() ?: declared.first()
            return SignalGroupResolution(match, SignalGroupSource.MovementFallback)
        }
        return SignalGroupResolution(null, SignalGroupSource.None)
    }
}
