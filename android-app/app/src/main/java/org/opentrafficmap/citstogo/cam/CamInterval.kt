package org.opentrafficmap.citstogo.cam

import kotlin.math.min

/**
 * The CAM generation interval as the CAM page holds it and the bridge applies it, in whole
 * milliseconds.
 *
 * ETSI EN 302 637-2 gives a CAM station 1 to 10 generations per second, so the standard range is
 * 100..1000 ms. The debug menu in Settings unlocks a stress-test floor of 1 ms — 1000 CAMs per
 * second — for load tests of receivers and maps. Nothing below [STANDARD_MIN_MS] is standard
 * behaviour, so [outsideStandard] exists to let the UI warn about it wherever such an interval is
 * offered, typed, stored, or applied.
 */
object CamInterval {
    /** 10 CAMs per second, the fastest rate the standard allows. */
    const val STANDARD_MIN_MS = 100

    /** 1 CAM per second, the slowest rate the standard allows. */
    const val MAX_MS = 1_000

    const val DEFAULT_MS = 500

    /** 1000 CAMs per second, reachable only with the debug menu on and outside the standard. */
    const val STRESS_TEST_MIN_MS = 1

    /** How long the broadcaster sleeps between looks at the clock at standard rates. */
    const val TRIGGER_CHECK_MS = 100L

    /** The floor the caller may offer: the standard one, or the stress-test one with the debug menu on. */
    fun minMs(stressTestEnabled: Boolean): Int =
        if (stressTestEnabled) STRESS_TEST_MIN_MS else STANDARD_MIN_MS

    fun rangeMs(stressTestEnabled: Boolean): IntRange = minMs(stressTestEnabled)..MAX_MS

    /** Limits an interval to the range the caller is allowed to use. */
    fun clamp(intervalMs: Int, stressTestEnabled: Boolean): Int = intervalMs.coerceIn(rangeMs(stressTestEnabled))

    /** Reads the page's field: a number is limited to the allowed range, anything else is the default. */
    fun parse(text: String, stressTestEnabled: Boolean): Int =
        text.trim().toIntOrNull()?.let { clamp(it, stressTestEnabled) } ?: DEFAULT_MS

    /** True when an interval asks for more CAMs per second than the standard allows. */
    fun outsideStandard(intervalMs: Int): Boolean = intervalMs < STANDARD_MIN_MS

    /** Same question for the text field, where an unusable entry asks for nothing out of the ordinary. */
    fun outsideStandard(text: String): Boolean = text.trim().toIntOrNull()?.let(::outsideStandard) ?: false

    /**
     * The sleep between two looks at the clock. It follows the interval down, because a broadcaster
     * that wakes every 100 ms could never deliver a stress-test interval of 1 ms; at standard rates
     * it stays at [TRIGGER_CHECK_MS].
     */
    fun triggerCheckMs(intervalMs: Int): Long =
        min(TRIGGER_CHECK_MS, intervalMs.coerceAtLeast(STRESS_TEST_MIN_MS).toLong())

    /**
     * The shortest gap the dynamics triggers may close before the interval is due. The standard's cap
     * of [STANDARD_MIN_MS] holds for every interval inside the standard; below it the requested rate
     * is the cap, so a stress test sends at its interval and not faster.
     */
    fun motionTriggerFloorMs(intervalMs: Int): Long =
        min(STANDARD_MIN_MS.toLong(), intervalMs.coerceAtLeast(STRESS_TEST_MIN_MS).toLong())
}
