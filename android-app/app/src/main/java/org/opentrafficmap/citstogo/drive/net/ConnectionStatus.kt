package org.opentrafficmap.citstogo.drive.net

import org.opentrafficmap.citstogo.drive.config.TcpFraming
import kotlin.math.min
import kotlin.random.Random

/** Connection phase shown on the dashboard and in diagnostics. */
enum class ConnectionPhase(val label: String) {
    Stopped("Stopped"),
    Connecting("Connecting"),
    Connected("Connected"),
    Reconnecting("Reconnecting"),
    Error("Connection error"),
    ;

    val isActive: Boolean get() = this == Connecting || this == Connected || this == Reconnecting
}

data class ConnectionStatus(
    val phase: ConnectionPhase = ConnectionPhase.Stopped,
    val host: String = "",
    val port: Int = 0,
    val framing: TcpFraming = TcpFraming.LengthPrefixed4BigEndian,
    val connectedAtMs: Long? = null,
    val attempts: Long = 0,
    val reconnects: Long = 0,
    val bytesReceived: Long = 0,
    val framesReceived: Long = 0,
    val lastError: String? = null,
    val lastErrorAtMs: Long? = null,
) {
    val connected: Boolean get() = phase == ConnectionPhase.Connected
    val address: String get() = if (host.isBlank()) "not configured" else "$host:$port"
}

/**
 * Bounded exponential backoff for reconnects. The delay grows from the configured base to 60 s and
 * is jittered so several devices do not reconnect in lockstep. Never returns 0 and never grows
 * without bound.
 */
object ReconnectPolicy {
    const val MAX_DELAY_MS = 60_000L

    fun delayMs(attempt: Int, baseDelayMs: Int, jitter: Double = 0.2, random: Random = Random.Default): Long {
        val safeBase = baseDelayMs.toLong().coerceAtLeast(1L)
        val exponent = attempt.coerceIn(0, 16)
        val raw = safeBase * (1L shl exponent)
        val capped = min(raw, MAX_DELAY_MS)
        if (jitter <= 0.0) return capped
        val spread = (capped * jitter).toLong().coerceAtLeast(1L)
        return (capped - spread + random.nextLong(2 * spread + 1)).coerceIn(1L, MAX_DELAY_MS)
    }
}
