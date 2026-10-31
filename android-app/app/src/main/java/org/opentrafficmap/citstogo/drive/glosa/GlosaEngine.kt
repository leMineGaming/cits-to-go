package org.opentrafficmap.citstogo.drive.glosa

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.opentrafficmap.citstogo.drive.profile.DriverProfile
import org.opentrafficmap.citstogo.drive.profile.TargetSpeedBehaviour
import org.opentrafficmap.citstogo.intersection.MovementPhaseState

enum class GlosaStatus {
    Available,
    StopAtLine,
    ImpossibleArrival,
    NoSignal,
    NoTiming,
    StaleData,
    LowConfidence,
    Disabled,
}

enum class GlosaAdvisory {
    MaintainSpeed,
    AccelerateGently,
    Decelerate,
    Stop,
    BrakeNow,
    None,
}

/**
 * A GLOSA result. [targetSpeedKph] is null whenever the engine cannot give a safe numeric target;
 * the UI must then show [reason] rather than invent a speed.
 */
data class GlosaRecommendation(
    val status: GlosaStatus,
    val advisory: GlosaAdvisory,
    val targetSpeedKph: Double?,
    val speedWindowKph: ClosedFloatingPointRange<Double>?,
    val distanceToStopLineM: Double,
    val timeToStopLineSeconds: Double?,
    val greenStartsAtMs: Long?,
    val greenEndsAtMs: Long?,
    val expectedArrivalMs: Long?,
    val confidence: Double,
    val signalState: MovementPhaseState,
    val remainingPhaseSeconds: Double?,
    val reason: String,
    val dataAgeMs: Long,
) {
    val hasRecommendation: Boolean
        get() = status == GlosaStatus.Available || status == GlosaStatus.StopAtLine
}

/**
 * The inputs of one GLOSA calculation. Every value is explicit so the engine has no hidden clock,
 * no Android dependency, and every branch is unit-testable.
 */
data class GlosaInput(
    val distanceToStopLineM: Double,
    val currentSpeedMps: Double,
    /** Road speed limit in km/h from MAPEM, or null when unknown. */
    val roadSpeedLimitKph: Int?,
    val signalState: MovementPhaseState,
    val minEndSeconds: Double?,
    val likelyEndSeconds: Double?,
    val maxEndSeconds: Double?,
    val signalConfidence: Int?,
    val spatReceivedAtMs: Long,
    val nowMs: Long,
    val positionAccuracyM: Double?,
    val laneConfidence: Double,
    val profile: DriverProfile,
    val staleAfterMs: Long = 3_000,
)

/**
 * Computes a safe GLOSA recommendation.
 *
 * Design rules:
 * - Never recommends a speed above the road limit or the profile cap.
 * - Never asks for more acceleration than the profile's comfortable value; GLOSA is advisory and
 *   must not encourage racing a yellow light.
 * - Treats minEndTime as the conservative end of the current phase. Estimated times are advisory,
 *   never guaranteed.
 * - Returns no numeric target when data is stale, lane confidence is low, timing is missing, or a
 *   comfortable stop is no longer possible.
 */
object GlosaEngine {
    private const val TRANSITION_SAFETY_MARGIN_S = 0.8
    private const val RED_ARRIVAL_BUFFER_S = 1.0
    private const val MIN_CRAWL_SPEED_MPS = 2.2
    private const val STOP_LINE_TOLERANCE_M = 2.0

    fun calculate(input: GlosaInput): GlosaRecommendation {
        val age = (input.nowMs - input.spatReceivedAtMs).coerceAtLeast(0L)
        val profile = input.profile
        val limitKph = profile.effectiveSpeedLimitKph(input.roadSpeedLimitKph).toDouble()
        val limitMps = limitKph / 3.6
        val distance = input.distanceToStopLineM.coerceAtLeast(0.0)
        val speed = input.currentSpeedMps.coerceAtLeast(0.0)
        val baseConfidence = baseConfidence(input, age)

        fun result(
            status: GlosaStatus,
            advisory: GlosaAdvisory,
            target: Double? = null,
            window: ClosedFloatingPointRange<Double>? = null,
            reason: String,
            greenStart: Long? = null,
            greenEnd: Long? = null,
            arrival: Long? = null,
            timeToLine: Double? = null,
            remaining: Double? = null,
            confidence: Double = baseConfidence,
        ) = GlosaRecommendation(
            status = status,
            advisory = advisory,
            targetSpeedKph = target,
            speedWindowKph = window,
            distanceToStopLineM = distance,
            timeToStopLineSeconds = timeToLine,
            greenStartsAtMs = greenStart,
            greenEndsAtMs = greenEnd,
            expectedArrivalMs = arrival,
            confidence = confidence.coerceIn(0.0, 1.0),
            signalState = input.signalState,
            remainingPhaseSeconds = remaining,
            reason = reason,
            dataAgeMs = age,
        )

        if (!profile.glosaEnabled) {
            return result(GlosaStatus.Disabled, GlosaAdvisory.None, reason = "GLOSA is disabled in this profile")
        }
        if (age > input.staleAfterMs) {
            return result(
                GlosaStatus.StaleData,
                GlosaAdvisory.None,
                reason = "C-ITS data is ${age / 1000.0}s old; no speed recommendation",
            )
        }
        if (input.laneConfidence < 0.45) {
            return result(
                GlosaStatus.LowConfidence,
                GlosaAdvisory.None,
                reason = "Lane and signal group not identified with enough confidence",
                confidence = input.laneConfidence,
            )
        }
        if (distance <= STOP_LINE_TOLERANCE_M) {
            return result(
                GlosaStatus.StopAtLine,
                GlosaAdvisory.Stop,
                target = 0.0,
                window = 0.0..0.0,
                reason = "At the stop line; obey the signal",
                timeToLine = 0.0,
            )
        }

        val state = input.signalState
        val phaseSeconds = conservativeRemainingSeconds(input)
        if (state.isGreen()) {
            if (phaseSeconds == null) {
                return result(
                    GlosaStatus.NoTiming,
                    GlosaAdvisory.None,
                    reason = "Green is shown but no phase end time is available",
                )
            }
            if (phaseSeconds <= TRANSITION_SAFETY_MARGIN_S) {
                return result(
                    GlosaStatus.StopAtLine,
                    GlosaAdvisory.Stop,
                    target = 0.0,
                    window = 0.0..0.0,
                    reason = "Green is expected to end; prepare to stop",
                    greenEnd = input.nowMs + (phaseSeconds * 1_000).toLong(),
                    remaining = phaseSeconds,
                )
            }
            return greenRecommendation(input, limitMps, distance, speed, phaseSeconds, baseConfidence) { parameters ->
                result(
                    status = parameters.status,
                    advisory = parameters.advisory,
                    target = parameters.targetKph,
                    window = parameters.windowKph,
                    reason = parameters.reason,
                    greenStart = input.nowMs,
                    greenEnd = input.nowMs + (phaseSeconds * 1_000).toLong(),
                    arrival = parameters.arrivalAtMs,
                    timeToLine = parameters.timeToLineSeconds,
                    remaining = phaseSeconds,
                    confidence = parameters.confidence,
                )
            }
        }

        if (state.isStop()) {
            return redRecommendation(input, limitMps, distance, speed, phaseSeconds, baseConfidence) { parameters ->
                result(
                    status = parameters.status,
                    advisory = parameters.advisory,
                    target = parameters.targetKph,
                    window = parameters.windowKph,
                    reason = parameters.reason,
                    greenStart = phaseSeconds?.let { input.nowMs + (it * 1_000).toLong() },
                    arrival = parameters.arrivalAtMs,
                    timeToLine = parameters.timeToLineSeconds,
                    remaining = phaseSeconds,
                    confidence = parameters.confidence,
                )
            }
        }

        return result(
            GlosaStatus.NoSignal,
            GlosaAdvisory.None,
            reason = "No usable signal state for this movement",
            remaining = phaseSeconds,
        )
    }

    private data class AdviceParameters(
        val status: GlosaStatus,
        val advisory: GlosaAdvisory,
        val targetKph: Double?,
        val windowKph: ClosedFloatingPointRange<Double>?,
        val reason: String,
        val arrivalAtMs: Long?,
        val timeToLineSeconds: Double?,
        val confidence: Double,
    )

    private fun greenRecommendation(
        input: GlosaInput,
        limitMps: Double,
        distance: Double,
        speed: Double,
        greenSeconds: Double,
        baseConfidence: Double,
        build: (AdviceParameters) -> GlosaRecommendation,
    ): GlosaRecommendation {
        val usableGreen = (greenSeconds - TRANSITION_SAFETY_MARGIN_S).coerceAtLeast(0.1)
        val requiredSpeed = distance / usableGreen
        val comfortAccel = input.profile.comfortableAccelerationMps2.toDouble() *
            if (input.profile.targetSpeedBehaviour == TargetSpeedBehaviour.Economy) 0.6 else 1.0
        val comfortablyReachable = sqrt(speed * speed + 2 * comfortAccel * distance)
        val feasible = requiredSpeed <= limitMps + 0.01 && requiredSpeed <= comfortablyReachable + 0.01

        if (!feasible) {
            // The green cannot be reached at a legal, comfortable speed. Recommend a stop rather
            // than speeding up; if stopping is already impossible, say so explicitly.
            val stopDistance = speed * speed / (2 * input.profile.comfortableDecelerationMps2)
            val canStop = distance >= stopDistance - 1.0
            return build(
                AdviceParameters(
                    status = if (canStop) GlosaStatus.StopAtLine else GlosaStatus.ImpossibleArrival,
                    advisory = if (canStop) GlosaAdvisory.Stop else GlosaAdvisory.BrakeNow,
                    targetKph = if (canStop) 0.0 else null,
                    windowKph = if (canStop) 0.0..0.0 else null,
                    reason = if (canStop) {
                        "Cannot reach the green within the speed limit; prepare to stop"
                    } else {
                        "Green ends before you can arrive and a comfortable stop is no longer possible"
                    },
                    arrivalAtMs = null,
                    timeToLineSeconds = if (speed > 0.3) distance / speed else null,
                    confidence = baseConfidence * 0.9,
                ),
            )
        }

        val marginFactor = when (input.profile.targetSpeedBehaviour) {
            TargetSpeedBehaviour.KeepCurrent -> 1.0
            TargetSpeedBehaviour.SmoothArrival -> 1.04
            TargetSpeedBehaviour.Economy -> 1.0
        }
        var targetMps = max(requiredSpeed * marginFactor, speed.coerceAtMost(limitMps))
        targetMps = min(targetMps, limitMps)
        // Even after the margin, never ask for more than comfortable acceleration.
        targetMps = min(targetMps, comfortablyReachable)
        targetMps = max(targetMps, 0.0)

        val targetKph = min(
            targetMps * 3.6,
            input.profile.effectiveSpeedLimitKph(input.roadSpeedLimitKph).toDouble(),
        )
        val minimumKph = max(speed * 3.6, requiredSpeed * 3.6).coerceAtMost(targetKph)
        val advisory = when {
            targetMps - speed > 0.7 -> GlosaAdvisory.AccelerateGently
            speed - targetMps > 0.7 -> GlosaAdvisory.Decelerate
            else -> GlosaAdvisory.MaintainSpeed
        }
        val arrival = input.nowMs + ((distance / targetMps.coerceAtLeast(0.5)) * 1_000).toLong()
        val confidence = (baseConfidence * timingQuality(input) * 0.98).coerceIn(0.0, 1.0)
        return build(
            AdviceParameters(
                status = GlosaStatus.Available,
                advisory = advisory,
                targetKph = targetKph,
                windowKph = (minimumKph - 1.0).coerceAtLeast(0.0)..targetKph,
                reason = "Green arrival is feasible at a legal, comfortable speed",
                arrivalAtMs = arrival,
                timeToLineSeconds = distance / targetMps.coerceAtLeast(0.5),
                confidence = confidence,
            ),
        )
    }

    private fun redRecommendation(
        input: GlosaInput,
        limitMps: Double,
        distance: Double,
        speed: Double,
        redSeconds: Double?,
        baseConfidence: Double,
        build: (AdviceParameters) -> GlosaRecommendation,
    ): GlosaRecommendation {
        val stopDistance = speed * speed / (2 * input.profile.comfortableDecelerationMps2)
        val canStop = distance >= stopDistance - 1.0
        if (redSeconds == null) {
            return build(
                AdviceParameters(
                    status = if (canStop) GlosaStatus.StopAtLine else GlosaStatus.ImpossibleArrival,
                    advisory = if (canStop) GlosaAdvisory.Stop else GlosaAdvisory.BrakeNow,
                    targetKph = if (canStop) 0.0 else null,
                    windowKph = if (canStop) 0.0..0.0 else null,
                    reason = "Red light; stop time unknown",
                    arrivalAtMs = null,
                    timeToLineSeconds = if (speed > 0.3) distance / speed else null,
                    confidence = baseConfidence * 0.8,
                ),
            )
        }
        if (redSeconds <= 0.5) {
            // Transition is imminent: do not accelerate into it.
            return build(
                AdviceParameters(
                    status = if (canStop) GlosaStatus.StopAtLine else GlosaStatus.ImpossibleArrival,
                    advisory = if (canStop) GlosaAdvisory.Stop else GlosaAdvisory.BrakeNow,
                    targetKph = if (canStop) 0.0 else null,
                    windowKph = if (canStop) 0.0..0.0 else null,
                    reason = "Signal is changing; hold a safe approach",
                    arrivalAtMs = null,
                    timeToLineSeconds = if (speed > 0.3) distance / speed else null,
                    confidence = baseConfidence * 0.8,
                ),
            )
        }

        val arrivalWait = redSeconds + RED_ARRIVAL_BUFFER_S
        val targetMps = distance / arrivalWait
        if (targetMps >= limitMps) {
            // Even at the limit the vehicle arrives after green starts; no need to slow down.
            val cappedSpeed = speed.coerceAtMost(limitMps)
            return build(
                AdviceParameters(
                    status = GlosaStatus.Available,
                    advisory = if (speed > limitMps + 0.5) GlosaAdvisory.Decelerate else GlosaAdvisory.MaintainSpeed,
                    targetKph = min(
                        cappedSpeed * 3.6,
                        input.profile.effectiveSpeedLimitKph(input.roadSpeedLimitKph).toDouble(),
                    ),
                    windowKph = (cappedSpeed * 3.6)..(limitMps * 3.6),
                    reason = "Red now, but the green is expected to start before arrival",
                    arrivalAtMs = input.nowMs + ((distance / cappedSpeed.coerceAtLeast(0.1)) * 1_000).toLong(),
                    timeToLineSeconds = distance / cappedSpeed.coerceAtLeast(0.1),
                    confidence = (baseConfidence * timingQuality(input) * 0.9).coerceIn(0.0, 1.0),
                ),
            )
        }
        if (targetMps < MIN_CRAWL_SPEED_MPS) {
            return build(
                AdviceParameters(
                    status = if (canStop) GlosaStatus.StopAtLine else GlosaStatus.ImpossibleArrival,
                    advisory = if (canStop) GlosaAdvisory.Stop else GlosaAdvisory.BrakeNow,
                    targetKph = if (canStop) 0.0 else null,
                    windowKph = if (canStop) 0.0..0.0 else null,
                    reason = "Wait at the stop line for the next green",
                    arrivalAtMs = null,
                    timeToLineSeconds = if (speed > 0.3) distance / speed else null,
                    confidence = baseConfidence * 0.85,
                ),
            )
        }

        val requiredDecel = (targetMps * targetMps - speed * speed) / (2 * distance)
        if (requiredDecel < -input.profile.comfortableDecelerationMps2 * 1.05 ||
            requiredDecel > input.profile.comfortableAccelerationMps2 * 1.05
        ) {
            return build(
                AdviceParameters(
                    status = if (canStop) GlosaStatus.StopAtLine else GlosaStatus.ImpossibleArrival,
                    advisory = if (canStop) GlosaAdvisory.Stop else GlosaAdvisory.BrakeNow,
                    targetKph = if (canStop) 0.0 else null,
                    windowKph = if (canStop) 0.0..0.0 else null,
                    reason = "Red light; a comfortable arrival at green is no longer possible",
                    arrivalAtMs = null,
                    timeToLineSeconds = if (speed > 0.3) distance / speed else null,
                    confidence = baseConfidence * 0.85,
                ),
            )
        }

        val targetKph = min(
            targetMps * 3.6,
            input.profile.effectiveSpeedLimitKph(input.roadSpeedLimitKph).toDouble(),
        )
        return build(
            AdviceParameters(
                status = GlosaStatus.Available,
                advisory = if (speed - targetMps > 0.7) GlosaAdvisory.Decelerate else GlosaAdvisory.MaintainSpeed,
                targetKph = targetKph,
                windowKph = targetKph..max(targetKph, speed * 3.6),
                reason = "Roll towards the stop line to meet the expected green",
                arrivalAtMs = input.nowMs + (arrivalWait * 1_000).toLong(),
                timeToLineSeconds = arrivalWait,
                confidence = (baseConfidence * timingQuality(input) * 0.85).coerceIn(0.0, 1.0),
            ),
        )
    }

    private fun baseConfidence(input: GlosaInput, ageMs: Long): Double {
        val ageFactor = (1.0 - ageMs.toDouble() / input.staleAfterMs.coerceAtLeast(1L)).coerceIn(0.2, 1.0)
        val accuracyFactor = input.positionAccuracyM?.let { accuracy ->
            (1.0 - ((accuracy - 5.0) / 35.0)).coerceIn(0.25, 1.0)
        } ?: 0.75
        return (input.laneConfidence * 0.6 + 0.4) * ageFactor * accuracyFactor
    }

    private fun timingQuality(input: GlosaInput): Double {
        val confidence = input.signalConfidence?.let { (it.coerceIn(0, 15) / 15.0) } ?: 0.7
        val spread = if (input.minEndSeconds != null && input.maxEndSeconds != null) {
            (input.maxEndSeconds - input.minEndSeconds).coerceAtLeast(0.0)
        } else {
            3.0
        }
        val spreadFactor = (1.0 - spread / 20.0).coerceIn(0.35, 1.0)
        return (0.55 + confidence * 0.45) * spreadFactor
    }

    private fun conservativeRemainingSeconds(input: GlosaInput): Double? =
        listOfNotNull(input.minEndSeconds, input.likelyEndSeconds, input.maxEndSeconds)
            .firstOrNull { it.isFinite() && it >= 0.0 }

    private fun MovementPhaseState.isGreen(): Boolean = when (this) {
        MovementPhaseState.ProtectedAllowed,
        MovementPhaseState.PermissiveAllowed,
        -> true
        else -> false
    }

    private fun MovementPhaseState.isStop(): Boolean = when (this) {
        MovementPhaseState.StopAndRemain,
        MovementPhaseState.StopThenProceed,
        MovementPhaseState.PreMovement,
        MovementPhaseState.PermissiveClearance,
        MovementPhaseState.ProtectedClearance,
        MovementPhaseState.CautionConflictingTraffic,
        MovementPhaseState.Dark,
        -> true
        else -> false
    }
}
