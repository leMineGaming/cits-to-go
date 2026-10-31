package org.opentrafficmap.citstogo.denm

/**
 * Normalised DENM à-la-carte information (ETSI EN 302 637-3 / TS 103 831).
 *
 * Only the fields that have a driving-assistance meaning are exposed; raw optional extension additions
 * are counted rather than guessed. Values unavailable in the standard encoding are represented as
 * `null`; the cause and lane enumerations keep their transmitted codes so unknown future values can
 * still be presented.
 */
data class DenmAlacarte(
    /** ETSI LanePosition: -1 off the road, 0 inner hard shoulder, 14 outer hard shoulder, 1..13 lane. */
    val lanePosition: Int? = null,
    val impactReduction: DenmImpactReduction? = null,
    /** External temperature in degrees Celsius, -60..67. */
    val externalTemperatureC: Int? = null,
    val roadWorks: DenmRoadWorks? = null,
    /** ETSI PositioningSolutionType 0..5. */
    val positioningSolution: Int? = null,
    val stationaryVehicle: DenmStationaryVehicle? = null,
    /** A partial decode: the core DENM remains usable, but the optional container could not be read. */
    val parseError: String? = null,
) {
    val lanePositionLabel: String get() = lanePositionLabel(lanePosition)

    companion object {
        fun lanePositionLabel(lanePosition: Int?): String = when (lanePosition) {
            null -> "Lane position unavailable"
            -1 -> "Off the road"
            0 -> "Inner hard shoulder"
            14 -> "Outer hard shoulder"
            in 1..13 -> "Lane $lanePosition"
            else -> "Lane position $lanePosition"
        }
    }
}

/** ImpactReductionContainer: the vehicle dimensions and mass reported by a DENM sender. */
data class DenmImpactReduction(
    val heightLeftM: Double? = null,
    val heightRightM: Double? = null,
    val positionLeftM: Double? = null,
    val positionRightM: Double? = null,
    val pillarOffsetsM: List<Double> = emptyList(),
    val centreOfMassM: Double? = null,
    val wheelbaseM: Double? = null,
    val turningRadiusM: Double? = null,
    val frontAxleM: Double? = null,
    /** Bit 0 is row 1 left, following PositionOfOccupants. */
    val occupantsBits: Int = 0,
    val vehicleMassKg: Int? = null,
    /** 0 request, 1 response. */
    val requestResponse: Int? = null,
)

data class DenmRoadWorks(
    /** Bit 0 light bar, bit 1 siren. */
    val lightBarSirenBits: Int? = null,
    val closedLanes: DenmClosedLanes? = null,
    val restrictedStationTypes: List<Int> = emptyList(),
    val speedLimitKph: Int? = null,
    val incidentCauseCode: Int? = null,
    val incidentSubCauseCode: Int? = null,
    /** Recommended path in 0.1 microdegrees, absolute positions. */
    val recommendedPath: List<Pair<Int, Int>> = emptyList(),
    /** Starting point of the speed limit, absolute position. */
    val startingPointSpeedLimit: Pair<Int, Int>? = null,
    /** ETSI TrafficRule 0..3. */
    val trafficFlowRule: Int? = null,
    /** Reference DENMs as originating station and sequence number. */
    val referenceActionIds: List<Pair<Long, Int>> = emptyList(),
)

data class DenmClosedLanes(
    /** ETSI HardShoulderStatus 0..2. */
    val innerHardShoulderStatus: Int? = null,
    val outerHardShoulderStatus: Int? = null,
    /** DrivingLaneStatus bits; bit 0 is the inner-most driving lane. */
    val drivingLaneStatusBits: Int? = null,
)

data class DenmStationaryVehicle(
    /** ETSI StationarySince 0..3: less than 1, 2, 15 or at least 15 minutes. */
    val stationarySince: Int? = null,
    val causeCode: Int? = null,
    val subCauseCode: Int? = null,
    val dangerousGoodsType: Int? = null,
    val dangerousGoodsUnNumber: Int? = null,
    val numberOfOccupants: Int? = null,
    /** WMI and VDS concatenated when the sender provides them. */
    val vehicleIdentification: String? = null,
    /** EnergyStorageType bits: hydrogen, electric, LPG, CNG, diesel, gasoline, ammonia. */
    val energyStorageBits: Int? = null,
) {
    val stationarySinceLabel: String
        get() = when (stationarySince) {
            0 -> "Stationary for less than 1 minute"
            1 -> "Stationary for less than 2 minutes"
            2 -> "Stationary for less than 15 minutes"
            3 -> "Stationary for 15 minutes or more"
            else -> "Stationary duration unknown"
        }
}
