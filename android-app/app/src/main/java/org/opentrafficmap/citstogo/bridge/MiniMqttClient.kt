package org.opentrafficmap.citstogo.bridge

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

class MiniMqttClient : Closeable {
    @Volatile private var socket: java.net.Socket? = null
    @Volatile private var input: DataInputStream? = null
    @Volatile private var output: BufferedOutputStream? = null
    private var pingThread: Thread? = null
    private var readerThread: Thread? = null
    @Volatile private var running = false
    /** Monotonic instant of the last byte the broker sent; the only honest proof the session lives. */
    @Volatile private var lastHeardMs = 0L
    private var topicPrefix = ""

    /**
     * Identity of one broker session. The reader and keep-alive threads of a connection that is being
     * replaced outlive it by a moment, and a thread that reports its own death that late must not be
     * allowed to close the socket the next connection has started using.
     */
    private class Session

    @Volatile private var session = Session()

    /**
     * Reports a session the broker or the network ended, with a reason for the log. It runs on a
     * client thread, never for a [close] the caller asked for, and never twice for one session, so a
     * listener can react to a dead socket instead of rediscovering it on the next publish.
     */
    @Volatile var onConnectionLost: ((String) -> Unit)? = null

    @Synchronized
    fun connect(mqttUri: String, nodeId: String, appVersion: String) {
        close()
        val cleanNode = nodeId.trim()
        require(cleanNode.isNotEmpty()) { "Node ID is empty" }
        val uri = parseUri(mqttUri)
        val tls = when (uri.scheme.lowercase(Locale.ROOT)) {
            "mqtt" -> false
            "mqtts", "ssl" -> true
            else -> throw IllegalArgumentException("Use mqtt:// or mqtts:// URI")
        }
        val host = uri.host ?: throw IllegalArgumentException("MQTT host is empty")
        val port = if (uri.port > 0) uri.port else if (tls) 8883 else 1883
        val factory: SocketFactory = if (tls) SSLSocketFactory.getDefault() else SocketFactory.getDefault()
        val s = factory.createSocket(host, port)
        s.tcpNoDelay = true
        s.keepAlive = true
        s.soTimeout = 15_000
        socket = s
        input = DataInputStream(s.getInputStream())
        output = BufferedOutputStream(s.getOutputStream(), 64 * 1024)

        topicPrefix = "its/$cleanNode/"
        val userInfo = uri.rawUserInfo?.let { java.net.URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
        val username = userInfo?.substringBefore(':', missingDelimiterValue = userInfo)
        val password = userInfo?.substringAfter(':', missingDelimiterValue = "")
            ?.takeIf { userInfo.contains(':') }
        val clientId = "cits-android-${clientSafe(cleanNode)}"

        sendConnect(clientId, topicPrefix + "status", "offline".toByteArray(), username, password)
        readConnAck()
        running = true
        lastHeardMs = nowMs()
        val current = Session().also { session = it }
        val sessionSocket = socket
        startReaderThread(current, sessionSocket)
        startPingThread(current, sessionSocket)
        publish(topicPrefix + "status", "online".toByteArray(), retain = true, flush = true)
        publish(topicPrefix + "info", infoPayload(cleanNode, appVersion), retain = false, flush = true)
        publishStats(0)
    }

    @Synchronized
    fun publishPacket(payload: ByteArray) {
        checkConnected()
        publish(topicPrefix + "packet", payload, retain = false, flush = false)
    }

    @Synchronized
    fun publishStats(secondsSinceStart: Long) {
        checkConnected()
        val json = """{"rbt":$secondsSinceStart}"""
        publish(topicPrefix + "stats", json.toByteArray(), retain = false, flush = true)
    }

    @Synchronized
    fun flush() {
        output?.flush()
    }

    fun isConnected(): Boolean {
        val s = socket
        return running && s != null && s.isConnected && !s.isClosed
    }

    /**
     * Ends the session and leaves nothing behind on the broker either.
     *
     * The retained status is rewritten to `offline` before the DISCONNECT goes out. A clean
     * DISCONNECT makes the broker drop the last will and keep the retained `online` it was handed at
     * connect, so stopping the bridge politely would otherwise leave it advertising a station that
     * has just gone away.
     *
     * A broker that is already unreachable blocks these writes until the kernel gives up, which is
     * why the bridge service closes the client off its main thread.
     */
    @Synchronized
    override fun close() {
        running = false
        val out = output
        val prefix = topicPrefix
        if (out != null && prefix.isNotEmpty()) {
            runCatching { publishLocked(out, prefix + "status", "offline".toByteArray(), retain = true) }
        }
        runCatching {
            out?.write(PACKET_DISCONNECT)
            out?.write(0)
            out?.flush()
        }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        runCatching { pingThread?.interrupt() }
        runCatching { readerThread?.interrupt() }
        pingThread = null
        readerThread = null
    }

    /**
     * Marks the session dead and cuts the socket out from under anyone blocked on it.
     *
     * Deliberately unsynchronised: it runs on the reader and keep-alive threads and has to be able to
     * shorten a publish that is waiting for a peer that is no longer there.
     */
    private fun fail(session: Session, owned: java.net.Socket?, reason: String) {
        // The socket this thread was actually working on, closed before the session is even compared:
        // a dying connection must be able to let go of its own socket without ever reaching past it to
        // the one the next connection is using.
        runCatching { owned?.close() }
        if (session !== this.session) return
        val wasRunning = running
        running = false
        if (wasRunning) runCatching { onConnectionLost?.invoke(reason) }
    }

    /**
     * Cuts the socket out from under a [close] that cannot finish.
     *
     * Takes no lock on purpose, because the point is to end a write that is waiting for a peer which
     * stopped answering, and the only thing in Java that ends such a write is the socket going away
     * underneath it. Safe at any time, including once the session is already gone.
     */
    fun abandon() {
        runCatching { socket?.close() }
    }

    /**
     * Reads everything the broker sends.
     *
     * Before this the input stream was read exactly once, for CONNACK, so a PINGRESP, a DISCONNECT
     * from the server, and the peer closing its end all went unnoticed and [isConnected] kept
     * answering `true` about a session that had already ended.
     */
    private fun startReaderThread(session: Session, owned: java.net.Socket?) {
        readerThread = Thread({
            val stream = input ?: return@Thread
            while (running) {
                try {
                    val header = stream.read()
                    if (header == -1) {
                        fail(session, owned, "broker closed the connection")
                        break
                    }
                    val remaining = readRemainingLength(stream)
                    if (remaining > 0) skipExactly(stream, remaining)
                    lastHeardMs = nowMs()
                    if (header and 0xF0 == PACKET_DISCONNECT) {
                        fail(session, owned, "broker sent DISCONNECT")
                        break
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // The read timeout doubles as the clock that notices a silent broker. MQTT expects
                    // an answer inside the keep alive, so two keep alives without a byte is a session
                    // the network has silently dropped, whatever the socket object still believes.
                    if (nowMs() - lastHeardMs > KEEPALIVE_SECONDS * 2_000L) {
                        fail(session, owned, "broker silent for ${KEEPALIVE_SECONDS * 2}s")
                        break
                    }
                } catch (_: Exception) {
                    fail(session, owned, "read failed")
                    break
                }
            }
        }, "mqtt-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun skipExactly(stream: DataInputStream, count: Int) {
        var left = count
        val scratch = ByteArray(minOf(count, 8 * 1024))
        while (left > 0) {
            val read = stream.read(scratch, 0, minOf(left, scratch.size))
            if (read == -1) throw IOException("connection ended mid-packet")
            left -= read
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private fun sendConnect(
        clientId: String,
        willTopic: String,
        willPayload: ByteArray,
        username: String?,
        password: String?,
    ) {
        val variableHeader = ByteArrayOutputStream()
        writeUtf8(variableHeader, "MQTT")
        variableHeader.write(4)
        var flags = 0x02 or 0x04 or 0x20
        if (password != null) flags = flags or 0x40
        if (username != null) flags = flags or 0x80
        variableHeader.write(flags)
        writeShort(variableHeader, KEEPALIVE_SECONDS)

        val payload = ByteArrayOutputStream()
        writeUtf8(payload, clientId)
        writeUtf8(payload, willTopic)
        writeBinary(payload, willPayload)
        if (username != null) writeUtf8(payload, username)
        if (password != null) writeUtf8(payload, password)

        val body = ByteArrayOutputStream()
        body.write(variableHeader.toByteArray())
        body.write(payload.toByteArray())
        writePacket(0x10, body.toByteArray(), flush = true)
    }

    private fun readConnAck() {
        val input = input ?: throw IOException("MQTT input is closed")
        val header = input.readUnsignedByte()
        if (header != 0x20) throw IOException("Expected CONNACK, got 0x${header.toString(16)}")
        val remaining = readRemainingLength(input)
        if (remaining != 2) throw IOException("Bad CONNACK length $remaining")
        val flags = input.readUnsignedByte()
        val code = input.readUnsignedByte()
        if (code != 0) throw IOException("MQTT CONNACK refused, code $code, flags $flags")
    }

    private fun publish(topic: String, payload: ByteArray, retain: Boolean, flush: Boolean) {
        val out = output ?: throw IOException("MQTT output is closed")
        publishLocked(out, topic, payload, retain, flush)
    }

    /** Writes a PUBLISH on a stream the caller already holds, so close() can use it too. */
    private fun publishLocked(
        out: OutputStream,
        topic: String,
        payload: ByteArray,
        retain: Boolean,
        flush: Boolean = true,
    ) {
        val body = ByteArrayOutputStream()
        writeUtf8(body, topic)
        body.write(payload)
        writePacketTo(out, if (retain) PACKET_PUBLISH or 1 else PACKET_PUBLISH, body.toByteArray(), flush)
    }

    private fun writePacket(fixedHeader: Int, body: ByteArray, flush: Boolean) {
        val output = output ?: throw IOException("MQTT output is closed")
        writePacketTo(output, fixedHeader, body, flush)
    }

    private fun writePacketTo(output: OutputStream, fixedHeader: Int, body: ByteArray, flush: Boolean) {
        output.write(fixedHeader)
        writeRemainingLength(output, body.size)
        output.write(body)
        if (flush) output.flush()
    }

    private fun startPingThread(session: Session, owned: java.net.Socket?) {
        pingThread = Thread({
            while (running) {
                try {
                    Thread.sleep(KEEPALIVE_SECONDS * 500L)
                    synchronized(this) {
                        val out = output
                        if (running && out != null) {
                            out.write(PACKET_PINGREQ)
                            out.write(0)
                            out.flush()
                        }
                    }
                } catch (_: InterruptedException) {
                    // close() interrupts on purpose. That is a goodbye, not a dead peer, and calling
                    // it one would report a failure the user just asked for.
                    Thread.currentThread().interrupt()
                    break
                } catch (_: Exception) {
                    // A keep alive that cannot be written means the peer is gone; say so out loud
                    // instead of flipping `running` over in silence.
                    fail(session, owned, "keep alive write failed")
                    break
                }
            }
        }, "mqtt-ping").apply {
            isDaemon = true
            start()
        }
    }

    private fun checkConnected() {
        if (!isConnected()) throw IOException("MQTT is not connected")
    }

    private fun infoPayload(nodeId: String, appVersion: String): ByteArray {
        val emac = if (nodeId.matches(Regex("(?i)[0-9a-f]{12}"))) {
            nodeId.chunked(2).joinToString(":")
        } else {
            nodeId
        }
        val json = """{"emac":"${jsonEscape(emac)}","ver":"${jsonEscape(appVersion)}","hwv":"android-usb-bridge"}"""
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        private const val KEEPALIVE_SECONDS = 60
        private const val PACKET_PUBLISH = 0x30
        private const val PACKET_PINGREQ = 0xC0
        private const val PACKET_DISCONNECT = 0xE0

        private fun parseUri(raw: String): URI {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("MQTT URI is empty")
            return URI.create(if ("://" in trimmed) trimmed else "mqtt://$trimmed")
        }

        private fun clientSafe(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).take(48)

        private fun writeUtf8(out: ByteArrayOutputStream, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            writeShort(out, bytes.size)
            out.write(bytes)
        }

        private fun writeBinary(out: ByteArrayOutputStream, value: ByteArray) {
            writeShort(out, value.size)
            out.write(value)
        }

        private fun writeShort(out: ByteArrayOutputStream, value: Int) {
            out.write((value ushr 8) and 0xff)
            out.write(value and 0xff)
        }

        private fun writeRemainingLength(out: OutputStream, value: Int) {
            var remaining = value
            do {
                var encodedByte = remaining % 128
                remaining /= 128
                if (remaining > 0) encodedByte = encodedByte or 128
                out.write(encodedByte)
            } while (remaining > 0)
        }

        private fun readRemainingLength(input: DataInputStream): Int {
            var multiplier = 1
            var value = 0
            var encodedByte: Int
            do {
                encodedByte = input.readUnsignedByte()
                value += (encodedByte and 127) * multiplier
                multiplier *= 128
                if (multiplier > 128 * 128 * 128) throw IOException("Malformed MQTT remaining length")
            } while ((encodedByte and 128) != 0)
            return value
        }

        private fun jsonEscape(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
