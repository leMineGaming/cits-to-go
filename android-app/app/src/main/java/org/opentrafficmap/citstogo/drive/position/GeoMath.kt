package org.opentrafficmap.citstogo.drive.position

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Geographic helpers shared by the lane matcher and the safety alert relevance calculation. */
object GeoMath {
    const val EARTH_RADIUS_M = 6_371_000.0
    private const val METERS_PER_DEGREE_LAT = 111_320.0

    fun distanceMeters(lat1E7: Int, lon1E7: Int, lat2E7: Int, lon2E7: Int): Double {
        val lat1 = Math.toRadians(lat1E7 / 1e7)
        val lat2 = Math.toRadians(lat2E7 / 1e7)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(lon2E7 / 1e7 - lon1E7 / 1e7)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from the first to the second point, 0…360 with 0 = north. */
    fun bearingDegrees(lat1E7: Int, lon1E7: Int, lat2E7: Int, lon2E7: Int): Double {
        val lat1 = Math.toRadians(lat1E7 / 1e7)
        val lat2 = Math.toRadians(lat2E7 / 1e7)
        val dLon = Math.toRadians(lon2E7 / 1e7 - lon1E7 / 1e7)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest absolute difference between two bearings, 0…180. */
    fun bearingDifference(a: Double, b: Double): Double {
        val difference = abs((a - b) % 360.0)
        return if (difference > 180.0) 360.0 - difference else difference
    }

    /** Converts a MAPEM east/north offset in centimetres into 0.1 µdegrees around a reference. */
    fun offsetE7(refLatE7: Int, refLonE7: Int, eastCm: Int, northCm: Int): Pair<Int, Int> {
        val latDeltaE7 = northCm / 100.0 / METERS_PER_DEGREE_LAT * 1e7
        val lonScale = cos(Math.toRadians(refLatE7 / 1e7)).coerceAtLeast(0.01)
        val lonDeltaE7 = eastCm / 100.0 / (METERS_PER_DEGREE_LAT * lonScale) * 1e7
        return (refLatE7 + latDeltaE7).toInt() to (refLonE7 + lonDeltaE7).toInt()
    }

    /** East/north offset in metres from a reference point to a coordinate. */
    fun localOffsetMeters(refLatE7: Int, refLonE7: Int, latE7: Int, lonE7: Int): Pair<Double, Double> {
        val north = (latE7 - refLatE7) / 1e7 * METERS_PER_DEGREE_LAT
        val east = (lonE7 - refLonE7) / 1e7 * METERS_PER_DEGREE_LAT *
            cos(Math.toRadians(refLatE7 / 1e7)).coerceAtLeast(0.01)
        return east to north
    }

    data class Projection(
        val east: Double,
        val north: Double,
        val distanceM: Double,
        val alongFraction: Double,
    )

    fun projectOnSegment(
        pointEast: Double,
        pointNorth: Double,
        startEast: Double,
        startNorth: Double,
        endEast: Double,
        endNorth: Double,
    ): Projection {
        val dx = endEast - startEast
        val dy = endNorth - startNorth
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 1e-9) {
            0.0
        } else {
            (((pointEast - startEast) * dx + (pointNorth - startNorth) * dy) / lengthSquared)
                .coerceIn(0.0, 1.0)
        }
        val east = startEast + t * dx
        val north = startNorth + t * dy
        return Projection(east, north, hypot(pointEast - east, pointNorth - north), t)
    }

    fun speedMetersPerSecond(kph: Double): Double = kph / 3.6
    fun speedKph(metersPerSecond: Double): Double = metersPerSecond * 3.6
}
