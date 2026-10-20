package org.opentrafficmap.citstogo.bridge

import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the client against a real socket on localhost.
 *
 * These exist because two properties were previously invisible: a session the broker ended had to stop
 * being reported as connected, and a session the bridge ended had to stop being advertised as online.
 * Neither could be checked without actually reading and writing MQTT packets.
 */
class MiniMqttClientTest {
    /** Answers CONNECT, records every PUBLISH it sees, and can hang up on the client on command. */
    private class FakeBroker : AutoCloseable {
        val server = ServerSocket(0)
        val published = ConcurrentLinkedQueue<Published>()
        private val peer = AtomicReference<Socket>()

        data class Published(val retain: Boolean, val topic: String, val payload: String)

        init {
            // The listen backlog accepts the client before accept() is reached, so nothing here waits.
            Thread({ runCatching { serve() } }, "fake-broker").apply { isDaemon = true }.start()
        }

        private fun serve() {
            server.use { listening ->
                val socket = listening.accept().also { peer.set(it) }
                socket.use { live ->
                    val input = DataInputStream(live.getInputStream())
                    val out = live.getOutputStream()
                    while (true) {
                        val packet = readPacket(input) ?: break
                        when (packet.first and 0xF0) {
                            0x10 -> {
                                out.write(byteArrayOf(0x20, 0x02, 0x00, 0x00))
                                out.flush()
                            }
                            // PUBLISH: two byte topic length, the topic, then the payload.
                            0x30 -> {
                                val body = packet.second
                                val length = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                                published += Published(
                                    retain = packet.first and 0x01 == 0x01,
                                    topic = String(body, 2, length),
                                    payload = String(body, 2 + length, body.size - 2 - length),
                                )
                            }
                        }
                    }
                }
            }
        }

        fun hangUp() = runCatching { peer.get()?.close() }

        private fun readPacket(input: DataInputStream): Pair<Int, ByteArray>? {
            val header = input.read()
            if (header == -1) return null
            var multiplier = 1
            var remaining = 0
            while (true) {
                val byte = input.read()
                if (byte == -1) return null
                remaining += (byte and 127) * multiplier
                multiplier *= 128
                if (byte and 128 == 0) break
            }
            val body = ByteArray(remaining)
            input.readFully(body)
            return header to body
        }

        override fun close() {
            hangUp()
            runCatching { server.close() }
        }
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun connect(client: MiniMqttClient, broker: FakeBroker) =
        client.connect("mqtt://127.0.0.1:${broker.server.localPort}", "aabbccddeeff", "test")

    @Test
    fun closeTellsTheBrokerItIsOfflineBeforeItLeaves() {
        val broker = FakeBroker()
        try {
            val client = MiniMqttClient()
            connect(client, broker)
            assertTrue(client.isConnected())
            client.close()

            // A bare DISCONNECT makes a broker drop the last will and keep the retained `online` it was
            // handed at connect, so a politely stopped bridge would go on being advertised as online.
            assertTrue(
                "no offline status reached the broker: ${broker.published}",
                awaitUntil { broker.published.any { it.topic == STATUS_TOPIC && it.payload == "offline" } },
            )
            val statuses = broker.published.filter { it.topic == STATUS_TOPIC }
            assertEquals(listOf("online", "offline"), statuses.map { it.payload })
            assertTrue(
                "the status has to be retained for a subscriber that connects later",
                statuses.last().retain,
            )
            assertFalse(client.isConnected())
        } finally {
            broker.close()
        }
    }

    @Test
    fun noticesABrokerThatHangsUp() {
        val broker = FakeBroker()
        try {
            val seen = AtomicReference<String>()
            val client = MiniMqttClient()
            client.onConnectionLost = { reason -> seen.set(reason) }
            connect(client, broker)
            assertTrue(client.isConnected())

            broker.hangUp()

            // Socket.isConnected() stays true for a connection the peer has already closed, so this can
            // only pass if something actually read the end of the stream.
            assertTrue("the client never noticed the broker going away", awaitUntil { seen.get() != null })
            assertFalse(client.isConnected())
            assertNotNull(seen.get())
        } finally {
            broker.close()
        }
    }

    @Test
    fun closingASessionTheBrokerAlreadyEndedCompletesPromptly() {
        val broker = FakeBroker()
        try {
            val client = MiniMqttClient()
            connect(client, broker)
            broker.hangUp()
            assertTrue(awaitUntil { !client.isConnected() })

            val closing = Thread({ runCatching { client.close() } }, "closing").apply {
                isDaemon = true
                start()
            }
            closing.join(3_000)
            assertFalse("close() hung on a session the broker had already ended", closing.isAlive)
        } finally {
            broker.close()
        }
    }

    /** The service watchdog may fire before a handshake ever finished; that must not be an error. */
    @Test
    fun abandonIsSafeBeforeAnySessionExists() {
        val client = MiniMqttClient()
        client.abandon()
        client.abandon()
        assertFalse(client.isConnected())
        client.close()
    }

    @Test
    fun refusesToConnectWithoutANodeId() {
        val broker = FakeBroker()
        try {
            val client = MiniMqttClient()
            assertFalse(client.isConnected())
            var threw = false
            try {
                client.connect("mqtt://127.0.0.1:${broker.server.localPort}", "   ", "test")
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw)
            assertFalse(client.isConnected())
        } finally {
            broker.close()
        }
    }

    private companion object {
        const val STATUS_TOPIC = "its/aabbccddeeff/status"
    }
}
