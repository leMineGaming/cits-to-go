package org.opentrafficmap.citstogo.drive.net

import org.opentrafficmap.citstogo.drive.config.TcpFraming
import org.opentrafficmap.citstogo.protocol.Cobs
import java.io.ByteArrayOutputStream

/**
 * Reassembles C-ITS frames from a TCP byte stream.
 *
 * A TCP read is not a message boundary. The decoder therefore keeps partial frames between
 * [accept] calls and only emits a frame once its complete delimiter/length has arrived:
 *
 * - [TcpFraming.LengthPrefixed4BigEndian] / [TcpFraming.LengthPrefixed2BigEndian]: the hardware
 *   precedes every frame with its byte length.
 * - [TcpFraming.Cobs]: zero-delimited COBS records, exactly like the CITS-to-go USB firmware.
 * - [TcpFraming.NewlineHex]: one ASCII hexadecimal frame per line.
 *
 * Malformed records are reported and skipped; one bad record never takes the stream down.
 */
class TcpFrameDecoder(
    private val framing: TcpFraming,
    private val maxFrameBytes: Int = MAX_FRAME_BYTES,
) {
    private val pending = ByteArrayOutputStream()
    private var discardedBytes = 0L

    val droppedBytes: Long get() = discardedBytes

    fun reset() {
        pending.reset()
    }

    fun accept(
        bytes: ByteArray,
        count: Int = bytes.size,
        onFrame: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        require(count in 0..bytes.size)
        if (count == 0) return
        pending.write(bytes, 0, count)
        when (framing) {
            TcpFraming.Cobs -> drainCobs(onFrame, onError)
            TcpFraming.NewlineHex -> drainLines(onFrame, onError)
            TcpFraming.LengthPrefixed2BigEndian -> drainLengthPrefixed(2, onFrame, onError)
            TcpFraming.LengthPrefixed4BigEndian -> drainLengthPrefixed(4, onFrame, onError)
        }
        if (pending.size() > MAX_BUFFERED_BYTES) {
            discardedBytes += pending.size()
            pending.reset()
            onError("Frame buffer exceeded $MAX_BUFFERED_BYTES bytes; stream resynchronized")
        }
    }

    private fun drainLengthPrefixed(
        prefixBytes: Int,
        onFrame: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        while (pending.size() >= prefixBytes) {
            val data = pending.toByteArray()
            var length = 0L
            for (index in 0 until prefixBytes) {
                length = (length shl 8) or (data[index].toLong() and 0xff)
            }
            if (length == 0L) {
                // An empty frame is harmless framing noise.
                pending.reset()
                pending.write(data, prefixBytes, data.size - prefixBytes)
                continue
            }
            if (length > maxFrameBytes) {
                discardedBytes += data.size
                pending.reset()
                onError("Declared frame length $length exceeds $maxFrameBytes bytes; dropped buffered data")
                return
            }
            val total = prefixBytes + length.toInt()
            if (data.size < total) return
            onFrame(data.copyOfRange(prefixBytes, total))
            pending.reset()
            pending.write(data, total, data.size - total)
        }
    }

    private fun drainCobs(onFrame: (ByteArray) -> Unit, onError: (String) -> Unit) {
        var data = pending.toByteArray()
        var start = 0
        while (true) {
            val delimiter = data.indexOf(0.toByte(), start)
            if (delimiter < 0) break
            if (delimiter > start) {
                val record = data.copyOfRange(start, delimiter)
                if (record.size > maxFrameBytes) {
                    onError("COBS record exceeds $maxFrameBytes bytes")
                } else {
                    try {
                        val decoded = Cobs.decode(record)
                        if (decoded.isNotEmpty()) onFrame(decoded)
                    } catch (e: IllegalArgumentException) {
                        onError("Malformed COBS record: ${e.message ?: "decode failed"}")
                    }
                }
            }
            start = delimiter + 1
        }
        pending.reset()
        if (start < data.size) pending.write(data, start, data.size - start)
    }

    private fun drainLines(onFrame: (ByteArray) -> Unit, onError: (String) -> Unit) {
        var data = pending.toByteArray()
        var start = 0
        while (true) {
            val newline = data.indexOf('\n'.code.toByte(), start)
            if (newline < 0) break
            var end = newline
            if (end > start && data[end - 1] == '\r'.code.toByte()) end--
            val line = String(data, start, end - start).trim()
            if (line.isNotEmpty()) decodeHexLine(line, onFrame, onError)
            start = newline + 1
        }
        pending.reset()
        if (start < data.size) pending.write(data, start, data.size - start)
    }

    private fun decodeHexLine(line: String, onFrame: (ByteArray) -> Unit, onError: (String) -> Unit) {
        val compact = line.replace(" ", "").replace(":", "").replace("-", "")
        if (compact.length % 2 != 0) {
            onError("Hex line has an odd number of digits")
            return
        }
        val out = ByteArray(compact.length / 2)
        for (index in out.indices) {
            val high = compact[index * 2].digitToIntOrNull(16)
            val low = compact[index * 2 + 1].digitToIntOrNull(16)
            if (high == null || low == null) {
                onError("Hex line contains a non-hexadecimal character")
                return
            }
            out[index] = ((high shl 4) or low).toByte()
        }
        if (out.size > maxFrameBytes) {
            onError("Hex frame exceeds $maxFrameBytes bytes")
        } else if (out.isNotEmpty()) {
            onFrame(out)
        }
    }

    companion object {
        const val MAX_FRAME_BYTES = 65_535
        const val MAX_BUFFERED_BYTES = 1 shl 20

        private fun ByteArray.indexOf(value: Byte, from: Int): Int {
            for (index in from until size) if (this[index] == value) return index
            return -1
        }
    }
}
