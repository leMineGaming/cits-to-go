package org.opentrafficmap.citstogo.drive.model

/**
 * Driver-facing DENM categories. The mapping uses the ETSI CDD cause codes decoded by
 * [org.opentrafficmap.citstogo.denm.DenmDecoder]; no categories are invented for causes that the
 * standard does not define.
 */
enum class DenmCategory(val label: String, val shortLabel: String) {
    Accident("Accident", "Accident"),
    TrafficJam("Traffic jam / end of queue", "Queue"),
    Roadworks("Roadworks", "Roadworks"),
    Impassable("Road closed / lane closure", "Closure"),
    SlipperyRoad("Slippery road", "Slippery"),
    ReducedVisibility("Reduced visibility", "Visibility"),
    Obstacle("Obstacle on the road", "Obstacle"),
    VulnerableRoadUser("People or animals on the road", "VRU"),
    DangerousConditions("Dangerous road or weather conditions", "Conditions"),
    StationaryVehicle("Stationary or broken-down vehicle", "Breakdown"),
    EmergencyVehicle("Emergency vehicle approaching", "Emergency"),
    WrongWayDriver("Wrong-way driver", "Wrong way"),
    CollisionRisk("Collision risk", "Collision"),
    RailwayCrossing("Railway level crossing", "Railway"),
    Other("Other event", "Other"),
    ;

    companion object {
        /**
         * ETSI CDD v1.3.1 / v2 cause codes. Unknown causes intentionally fall into [Other] so a
         * future cause code still produces a usable, clearly labelled alert.
         */
        fun fromCause(causeCode: Int?): DenmCategory = when (causeCode) {
            1, 27 -> TrafficJam
            2, 92 -> Accident
            3 -> Roadworks
            5 -> Impassable
            6, 7 -> SlipperyRoad
            9, 17, 19, 96 -> DangerousConditions
            10 -> Obstacle
            11, 12 -> VulnerableRoadUser
            14 -> WrongWayDriver
            15, 95 -> EmergencyVehicle
            18 -> ReducedVisibility
            in 91..94 -> StationaryVehicle
            97 -> CollisionRisk
            98 -> DangerousConditions
            99 -> CollisionRisk
            100 -> RailwayCrossing
            else -> Other
        }
    }
}
