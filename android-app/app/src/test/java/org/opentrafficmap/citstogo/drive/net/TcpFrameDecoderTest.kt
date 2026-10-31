package org.opentrafficmap.citstogo.drive.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.drive.config.TcpFraming
import org.opentrafficmap.citstogo.protocol.Cobs

class TcpFrameDecoderTest {
    @Test
    fun lengthPrefixedFramesSplitAcrossReadsAreReassembled() {
        val frames = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6, 7))
        val stream = frames.fold(ByteArray(0)) { acc, frame ->
            acc + intBytes(frame.size) + frame
        }
        val decoder = TcpFrameDecoder(TcpFraming.LengthPrefixed4BigEndian)
        val received = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()
        // Split every possible boundary to prove that a read is not a frame.
        for (index in stream.indices) {
            decoder.accept(byteArrayOf(stream[index]), 1, { received += it }, { errors += it })
        }
        assertEquals(2, received.size)
        assertArrayEquals(frames[0], received[0])
        assertArrayEquals(frames[1], received[1])
        assertTrue(errors.isEmpty())
    }

    @Test
    fun multipleFramesInOneReadAreEmittedSeparately() {
        val one = byteArrayOf(9, 8, 7)
        val two = byteArrayOf(6, 5)
        val stream = intBytes(one.size) + one + intBytes(two.size) + two
        val decoder = TcpFrameDecoder(TcpFraming.LengthPrefixed4BigEndian)
        val received = mutableListOf<ByteArray>()
        decoder.accept(stream, stream.size, { received += it }, {})
        assertEquals(2, received.size)
        assertArrayEquals(one, received[0])
        assertArrayEquals(two, received[1])
    }

    @Test
    fun twoBytePrefixIsSupported() {
        val frame = byteArrayOf(0x11, 0x22, 0x33)
        val stream = byteArrayOf(0, frame.size.toByte()) + frame
        val decoder = TcpFrameDecoder(TcpFraming.LengthPrefixed2BigEndian)
        val received = mutableListOf<ByteArray>()
        decoder.accept(stream, stream.size, { received += it }, {})
        assertArrayEquals(frame, received.single())
    }

    @Test
    fun absurdLengthIsReportedAndBufferIsDropped() {
        val decoder = TcpFrameDecoder(TcpFraming.LengthPrefixed4BigEndian, maxFrameBytes = 16)
        val errors = mutableListOf<String>()
        val stream = intBytes(1_000) + byteArrayOf(1, 2, 3)
        decoder.accept(stream, stream.size, {}, { errors += it })
        assertTrue(errors.single().contains("exceeds"))
        assertEquals(stream.size.toLong(), decoder.droppedBytes)
    }

    @Test
    fun cobsRecordsMatchTheFirmwareEncoding() {
        val payloads = listOf(byteArrayOf(1, 0, 2), byteArrayOf(3, 4, 5))
        val stream = payloads.fold(ByteArray(0)) { acc, payload -> acc + Cobs.encode(payload) + 0 }
        val decoder = TcpFrameDecoder(TcpFraming.Cobs)
        val received = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()
        stream.forEach { byte ->
            decoder.accept(byteArrayOf(byte), 1, { received += it }, { errors += it })
        }
        assertEquals(2, received.size)
        assertArrayEquals(payloads[0], received[0])
        assertArrayEquals(payloads[1], received[1])
        assertTrue(errors.isEmpty())
    }

    @Test
    fun hexLinesAcceptSpacesAndColons() {
        val decoder = TcpFrameDecoder(TcpFraming.NewlineHex)
        val received = mutableListOf<ByteArray>()
        val text = "aa:bb cc\r\n01 02 03\n"
        decoder.accept(text.toByteArray(), text.toByteArray().size, { received += it }, {})
        assertEquals(2, received.size)
        assertArrayEquals(byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte()), received[0])
        assertArrayEquals(byteArrayOf(1, 2, 3), received[1])
    }

    @Test
    fun malformedHexLineDoesNotStopFollowingLines() {
        val decoder = TcpFrameDecoder(TcpFraming.NewlineHex)
        val received = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()
        val text = "zz\n0a0b\n"
        decoder.accept(text.toByteArray(), text.length, { received += it }, { errors += it })
        assertEquals(1, errors.size)
        assertArrayEquals(byteArrayOf(0x0a, 0x0b), received.single())
    }

    @Test
    fun reconnectDelayIsBoundedAndGrows() {
        val first = ReconnectPolicy.delayMs(0, 1_000, jitter = 0.0)
        val second = ReconnectPolicy.delayMs(1, 1_000, jitter = 0.0)
        val huge = ReconnectPolicy.delayMs(20, 1_000, jitter = 0.0)
        assertEquals(1_000L, first)
        assertEquals(2_000L, second)
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, huge)
        assertTrue(ReconnectPolicy.delayMs(3, 1_000, jitter = 0.2) in 1..ReconnectPolicy.MAX_DELAY_MS)
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
