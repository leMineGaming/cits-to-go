package org.opentrafficmap.citstogo.drive.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opentrafficmap.citstogo.drive.config.TcpFraming

/**
 * A resilient TCP client for external C-ITS hardware.
 *
 * Responsibilities are deliberately narrow: connect, read bytes, let [TcpFrameDecoder] reassemble
 * frames, report status, reconnect. Decoding and business logic stay outside, so the client can be
 * replaced or tested without touching the driving logic.
 */
class TcpCitsClient(
    private val scope: CoroutineScope,
    private val onFrame: (ByteArray) -> Unit,
    private val onStatus: (ConnectionStatus) -> Unit,
) {
    private var job: Job? = null
    @Volatile private var active = false
    @Volatile private var socket: Socket? = null
    @Volatile private var status = ConnectionStatus()

    fun start(
        host: String,
        port: Int,
        framing: TcpFraming,
        connectTimeoutMs: Int,
        reconnectBaseMs: Int,
    ) {
        stop()
        if (host.isBlank()) {
            publish(status.copy(phase = ConnectionPhase.Error, lastError = "No hardware address configured"))
            return
        }
        active = true
        status = status.copy(
            phase = ConnectionPhase.Connecting,
            host = host,
            port = port,
            framing = framing,
            lastError = null,
        )
        publish(status)
        job = scope.launch(Dispatchers.IO) {
            runLoop(host, port, framing, connectTimeoutMs, reconnectBaseMs)
        }
    }

    fun stop() {
        active = false
        job?.cancel()
        job = null
        closeSocket()
        if (status.phase != ConnectionPhase.Stopped) {
            publish(status.copy(phase = ConnectionPhase.Stopped, connectedAtMs = null))
        }
    }

    fun currentStatus(): ConnectionStatus = status

    /** Closes the current socket without stopping, so the loop reconnects at once. */
    fun reconnectNow() {
        closeSocket()
    }

    private suspend fun runLoop(
        host: String,
        port: Int,
        framing: TcpFraming,
        connectTimeoutMs: Int,
        reconnectBaseMs: Int,
    ) {
        var attempt = 0
        val decoder = TcpFrameDecoder(framing)
        while (scope.isActive && active) {
            val newSocket = Socket()
            try {
                status = status.copy(
                    phase = if (attempt == 0) ConnectionPhase.Connecting else ConnectionPhase.Reconnecting,
                    attempts = status.attempts + 1,
                    lastError = null,
                )
                publish(status)
                newSocket.tcpNoDelay = true
                newSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket = newSocket
                decoder.reset()
                status = status.copy(
                    phase = ConnectionPhase.Connected,
                    connectedAtMs = System.currentTimeMillis(),
                    lastError = null,
                    reconnects = if (attempt > 0) status.reconnects + 1 else status.reconnects,
                )
                publish(status)
                attempt = 0
                readLoop(newSocket, decoder)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (!active) break
                val message = describe(e)
                status = status.copy(
                    phase = ConnectionPhase.Reconnecting,
                    connectedAtMs = null,
                    lastError = message,
                    lastErrorAtMs = System.currentTimeMillis(),
                )
                publish(status)
            } finally {
                closeSocket(newSocket)
                socket = null
            }
            if (!active) break
            attempt = (attempt + 1).coerceAtMost(16)
            delay(ReconnectPolicy.delayMs(attempt - 1, reconnectBaseMs))
        }
        if (status.phase != ConnectionPhase.Stopped) {
            publish(status.copy(phase = ConnectionPhase.Stopped, connectedAtMs = null))
        }
    }

    private suspend fun readLoop(socket: Socket, decoder: TcpFrameDecoder) {
        socket.soTimeout = READ_TIMEOUT_MS
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (active) {
            val read = try {
                socket.getInputStream().read(buffer)
            } catch (_: SocketTimeoutException) {
                // A timeout only wakes the loop so it can observe stop/reconnect requests.
                continue
            }
            if (read < 0) throw IOException("Hardware closed the connection")
            status = status.copy(bytesReceived = status.bytesReceived + read)
            decoder.accept(buffer, read, onFrame = { frame ->
                status = status.copy(framesReceived = status.framesReceived + 1)
                publish(status)
                onFrame(frame)
            }, onError = { message ->
                status = status.copy(
                    lastError = message,
                    lastErrorAtMs = System.currentTimeMillis(),
                )
                publish(status)
            })
        }
    }

    private fun publish(updated: ConnectionStatus) {
        status = updated
        onStatus(updated)
    }

    private fun closeSocket(target: Socket? = socket) {
        try {
            target?.close()
        } catch (_: IOException) {
            // Closing an already broken socket is not an error worth surfacing.
        }
    }

    private fun describe(error: Exception): String = when (error) {
        is java.net.SocketTimeoutException -> "Connect timed out"
        is java.net.ConnectException -> "Connection refused or unreachable: ${error.message.orEmpty()}"
        is java.net.UnknownHostException -> "Unknown host ${error.message.orEmpty()}"
        is IOException -> error.message ?: error.javaClass.simpleName
        else -> error.message ?: error.javaClass.simpleName
    }

    companion object {
        private const val READ_TIMEOUT_MS = 1_000
        private const val READ_BUFFER_BYTES = 8_192
    }
}

/** Result of the setup-screen connection test. */
data class ConnectionTestResult(
    val success: Boolean,
    val message: String,
    val elapsedMs: Long,
    val bytesRead: Int = 0,
)

/**
 * One-shot connect/read test used by onboarding and connection diagnostics. It never keeps the
 * socket open: the caller gets a clear answer, then the normal client owns the connection.
 */
object TcpConnectionTester {
    suspend fun test(
        host: String,
        port: Int,
        timeoutMs: Int = 5_000,
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext ConnectionTestResult(false, "Enter the hardware IP address", 0)
        if (port !in 1..65_535) return@withContext ConnectionTestResult(false, "Port must be 1–65535", 0)
        val started = System.currentTimeMillis()
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host.trim(), port), timeoutMs.coerceIn(500, 30_000))
            socket.soTimeout = 1_500
            val buffer = ByteArray(4_096)
            val read = try {
                socket.getInputStream().read(buffer)
            } catch (_: SocketTimeoutException) {
                0
            }
            val elapsed = System.currentTimeMillis() - started
            val message = when {
                read < 0 -> "Connected to $host:$port, but the hardware closed the stream"
                read == 0 -> "Connected to $host:$port (no data yet within 1.5 s)"
                else -> "Connected to $host:$port and received $read bytes"
            }
            ConnectionTestResult(true, message, elapsed, maxOf(read, 0))
        } catch (e: Exception) {
            ConnectionTestResult(
                false,
                e.message ?: e.javaClass.simpleName,
                System.currentTimeMillis() - started,
            )
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
                // Nothing to clean up.
            }
        }
    }
}
