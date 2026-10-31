package org.opentrafficmap.citstogo.drive.model

import org.opentrafficmap.citstogo.intersection.IntersectionKey
import org.opentrafficmap.citstogo.intersection.MovementPhaseState
import org.opentrafficmap.citstogo.intersection.SignalEvent
import org.opentrafficmap.citstogo.intersection.SpatIntersection

/**
 * A signal group's current phase, decoupled from the SPATEM wire model. Times are seconds remaining
 * at [computedAtMs], derived from the SPATEM minute-of-year + millisecond timestamps and the local
 * reception time.
 */
data class SignalSnapshot(
    val intersectionKey: IntersectionKey,
    val signalGroup: Int,
    val state: MovementPhaseState,
    val minEndSeconds: Double?,
    val likelyEndSeconds: Double?,
    val maxEndSeconds: Double?,
    val timingConfidence: Int?,
    val spatReceivedAtMs: Long,
    val computedAtMs: Long,
) {
    val isGreen: Boolean
        get() = state == MovementPhaseState.ProtectedAllowed ||
            state == MovementPhaseState.PermissiveAllowed

    val isRed: Boolean
        get() = state == MovementPhaseState.StopAndRemain ||
            state == MovementPhaseState.Dark

    val isTransition: Boolean
        get() = state == MovementPhaseState.PermissiveClearance ||
            state == MovementPhaseState.ProtectedClearance ||
            state == MovementPhaseState.CautionConflictingTraffic ||
            state == MovementPhaseState.StopThenProceed

    val ageMs: Long get() = (computedAtMs - spatReceivedAtMs).coerceAtLeast(0L)

    /** Most conservative end time; [minEndSeconds] wins over an estimate. */
    val conservativeEndSeconds: Double?
        get() = listOfNotNull(minEndSeconds, likelyEndSeconds, maxEndSeconds).firstOrNull()

    companion object {
        private const val TIME_MARK_UNAVAILABLE = 36_000
        private const val TENTHS_PER_MINUTE = 600
        private const val TENTHS_PER_HOUR = 36_000
        private const val MINUTES_PER_LEAP_YEAR = 527_040
        private const val MILLIS_PER_MINUTE = 60_000

        fun from(
            spat: SpatIntersection,
            signalGroup: Int,
            nowMs: Long,
        ): SignalSnapshot? {
            val movement = spat.movementsBySignalGroup[signalGroup] ?: return null
            val event = movement.events.firstOrNull() ?: return null
            return SignalSnapshot(
                intersectionKey = spat.key,
                signalGroup = signalGroup,
                state = event.state,
                minEndSeconds = remainingSeconds(spat, event.minEndTime, nowMs),
                likelyEndSeconds = remainingSeconds(spat, event.likelyTime, nowMs),
                maxEndSeconds = remainingSeconds(spat, event.maxEndTime, nowMs),
                timingConfidence = event.confidence,
                spatReceivedAtMs = spat.receivedAtMs,
                computedAtMs = nowMs,
            )
        }

        fun remainingSeconds(
            spat: SpatIntersection,
            targetTenths: Int?,
            nowMs: Long,
        ): Double? {
            if (targetTenths == null || targetTenths !in 0 until TIME_MARK_UNAVAILABLE) return null
            val moy = spat.moy?.takeIf { it in 0 until MINUTES_PER_LEAP_YEAR } ?: return null
            val timestamp = spat.timestampMs?.takeIf { it in 0 until MILLIS_PER_MINUTE } ?: return null
            val messageTenths = (moy % 60) * TENTHS_PER_MINUTE + timestamp / 100
            var remainingTenths = targetTenths - messageTenths
            if (remainingTenths < 0) {
                val crossesHour = messageTenths >= TENTHS_PER_HOUR - TENTHS_PER_MINUTE &&
                    targetTenths < TENTHS_PER_MINUTE
                if (!crossesHour) return null
                remainingTenths += TENTHS_PER_HOUR
            }
            val elapsedTenths = ((nowMs - spat.receivedAtMs).coerceAtLeast(0L) / 100L).toInt()
            return (remainingTenths - elapsedTenths).coerceAtLeast(0) / 10.0
        }
    }
}
