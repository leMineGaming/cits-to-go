package org.opentrafficmap.citstogo.drive.net

import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.drive.config.TcpFraming

class TcpCitsClientTest {
    @Test
    fun reconnectsAfterTheHardwareClosesTheStream() {
        val frames = LinkedBlockingQueue<ByteArray>()
        val received = CountDownLatch(2)
        val accepted = AtomicInteger(0)
        val server = ServerSocket(0)
        val serverThread = Thread {
            try {
                repeat(2) { index ->
                    server.accept().use { socket ->
                        accepted.incrementAndGet()
                        val out = DataOutputStream(socket.getOutputStream())
                        val payload = if (index == 0) byteArrayOf(1, 2, 3) else byteArrayOf(4, 5, 6)
                        out.writeInt(payload.size)
                        out.write(payload)
                        out.flush()
                        // Closing after the first frame forces the client to reconnect.
                        if (index == 0) socket.close()
                    }
                }
            } catch (_: Exception) {
                // The test assertion reports the missing frames.
            }
        }.also { it.isDaemon = true; it.start() }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val statuses = LinkedBlockingQueue<ConnectionStatus>()
        val client = TcpCitsClient(
            scope = scope,
            onFrame = {
                frames.offer(it)
                received.countDown()
            },
            onStatus = { statuses.offer(it) },
        )
        try {
            client.start(
                host = "127.0.0.1",
                port = server.localPort,
                framing = TcpFraming.LengthPrefixed4BigEndian,
                connectTimeoutMs = 2_000,
                reconnectBaseMs = 50,
            )
            assertTrue("expected two frames, got ${frames.size}", received.await(8, TimeUnit.SECONDS))
            assertEquals(1, frames.poll()!!.toList().first().toInt())
            assertEquals(4, frames.poll()!!.toList().first().toInt())
            assertEquals(2, accepted.get())
            assertTrue(statuses.any { it.phase == ConnectionPhase.Connected })
            assertTrue(statuses.any { it.reconnects >= 1 })
        } finally {
            client.stop()
            scope.cancel()
            server.close()
        }
    }
}
