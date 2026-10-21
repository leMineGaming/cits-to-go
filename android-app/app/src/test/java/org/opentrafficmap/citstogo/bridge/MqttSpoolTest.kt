package org.opentrafficmap.citstogo.bridge

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The spool holds state on disk and a cursor that has to survive a process death, which is the state
 * a capture is judged by: what the broker was told, and what it will be told again after a crash.
 */
class MqttSpoolTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class RecordingOffsets : SpoolOffsets {
        var value = 0L
        var writes = 0
        override fun load(): Long = value
        override fun save(readOffset: Long) {
            value = readOffset
            writes += 1
        }
    }

    private fun spoolFile(): File = File(folder.newFolder(), "spool.bin")

    @Test
    fun recordsRoundTripThroughTheCursor() {
        val store = RecordingOffsets()
        val spool = MqttSpool(spoolFile(), store)

        spool.append(byteArrayOf(1, 2, 3))
        spool.append(byteArrayOf(4, 5))
        assertEquals(2L, spool.pendingCount())

        val batch = spool.readBatch(10)
        assertEquals(2, batch.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), batch[0].payload)
        assertArrayEquals(byteArrayOf(4, 5), batch[1].payload)

        spool.ack(batch.last().nextOffset, batch.size)
        assertEquals(0L, spool.pendingCount())
        assertEquals(0, spool.readBatch(10).size)
        spool.close()
    }

    @Test
    fun theCursorIsPersistedOnACadenceRatherThanOncePerRecord() {
        val store = RecordingOffsets()
        val spool = MqttSpool(spoolFile(), store)
        val records = MqttSpool.PERSIST_EVERY_RECORDS * 2 + 1

        repeat(records) { spool.append(byteArrayOf(it.toByte())) }
        spool.close()

        // One write per record would mean `records + 1` here. The capture thread pays for every
        // persisted cursor while it is supposed to be draining the radio transport.
        val budget = 1 + records / MqttSpool.PERSIST_EVERY_RECORDS + 2
        assertTrue(
            "expected at most $budget cursor writes for $records records, saw ${store.writes}",
            store.writes <= budget,
        )
        assertTrue(store.writes > 0)
    }

    @Test
    fun closingKeepsTheCursorSoAReopenResumesWhereTheBrokerLeftOff() {
        val file = spoolFile()
        val store = RecordingOffsets()
        val first = MqttSpool(file, store)
        first.append(byteArrayOf(1))
        first.append(byteArrayOf(2))
        first.append(byteArrayOf(3))
        val batch = first.readBatch(2)
        first.ack(batch.last().nextOffset, batch.size)
        first.close()

        val reopened = MqttSpool(file, store)
        val rest = reopened.readBatch(10)

        assertEquals(1, rest.size)
        assertArrayEquals(byteArrayOf(3), rest[0].payload)
        assertEquals(1L, reopened.pendingCount())
        reopened.close()
    }

    @Test
    fun aRecordTornByACrashIsDiscardedRatherThanKeptAsGarbage() {
        val file = spoolFile()
        MqttSpool(file, RecordingOffsets()).use {
            it.append(byteArrayOf(1, 2, 3, 4))
            it.append(byteArrayOf(5, 6))
        }
        // What a kill mid-write leaves behind: a declared length with a payload that never arrived.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(byteArrayOf(50, 0, 0, 0, 9, 9))
        }

        val reopened = MqttSpool(file, RecordingOffsets())
        val batch = reopened.readBatch(10)

        assertEquals(2L, reopened.pendingCount())
        assertEquals(2, batch.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), batch[0].payload)
        assertArrayEquals(byteArrayOf(5, 6), batch[1].payload)
        reopened.close()
    }

    @Test
    fun aCursorPastTheEndOfTheFileStartsOver() {
        val file = spoolFile()
        val store = RecordingOffsets()
        MqttSpool(file, store).use { it.append(byteArrayOf(7)) }
        // The file was replaced or compacted underneath a stored cursor.
        store.value = file.length() + 4_096

        val reopened = MqttSpool(file, store)

        assertEquals(1L, reopened.pendingCount())
        assertArrayEquals(byteArrayOf(7), reopened.readBatch(10).single().payload)
        reopened.close()
    }

    @Test
    fun anEmptyPayloadIsNotSpooledAndAnOversizedOneIsRefused() {
        val spool = MqttSpool(spoolFile(), RecordingOffsets())

        spool.append(ByteArray(0))

        assertEquals(0L, spool.pendingCount())
        assertTrue(spool.readBatch(10).isEmpty())
        val failure = runCatching { spool.append(ByteArray(MqttSpool.MAX_PAYLOAD_LEN + 1)) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        spool.close()
    }

    @Test
    fun clearingForgetsEverythingThatWasWaiting() {
        val store = RecordingOffsets()
        val spool = MqttSpool(spoolFile(), store)
        spool.append(byteArrayOf(1, 2))
        spool.readBatch(10)

        spool.clear()

        assertEquals(0L, spool.pendingCount())
        assertEquals(0, spool.readBatch(10).size)
        assertEquals(0L, store.value)
        spool.close()
    }

    @Test
    fun aSpoolFullOfUnsentPacketsDropsTheOldestOnes() {
        val store = RecordingOffsets()
        val spool = MqttSpool(spoolFile(), store, maxRecords = 5L)

        repeat(8) { spool.append(byteArrayOf(it.toByte())) }
        val batch = spool.readBatch(20)

        assertEquals(5L, spool.pendingCount())
        assertEquals(3L, spool.capacityDrops())
        assertEquals(listOf(3, 4, 5, 6, 7), batch.map { it.payload[0].toInt() })
        spool.close()
    }

    @Test
    fun aSpoolOverItsByteBudgetDropsWholeRecordsFromTheFront() {
        val spool = MqttSpool(spoolFile(), RecordingOffsets(), maxRecords = Long.MAX_VALUE, maxBytes = 24L)

        // Three eight-byte records fit; every append past that pushes the cursor over the oldest one,
        // and only whole records, so that the reader never sees a length without its payload.
        repeat(6) { spool.append(ByteArray(4)) }

        assertEquals(3L, spool.pendingCount())
        assertEquals(3L, spool.capacityDrops())
        assertEquals(3, spool.readBatch(20).size)
        spool.close()
    }

    @Test
    fun aRestartOverAnOversizedSpoolTrimsItOnTheWayIn() {
        val file = spoolFile()
        MqttSpool(file, RecordingOffsets(), maxRecords = 100L).use { spool ->
            repeat(10) { spool.append(byteArrayOf(it.toByte())) }
        }

        val reopened = MqttSpool(file, RecordingOffsets(), maxRecords = 4L)

        assertEquals(4L, reopened.pendingCount())
        assertEquals(6L, reopened.capacityDrops())
        assertEquals(4, reopened.readBatch(20).size)
        reopened.close()
    }

    @Test
    fun aSpoolUnderItsCapacityDropsNothing() {
        val spool = MqttSpool(spoolFile(), RecordingOffsets(), maxRecords = 50L, maxBytes = MqttSpool.MAX_BYTES)

        repeat(40) { spool.append(byteArrayOf(1, 2, 3)) }

        assertEquals(0L, spool.capacityDrops())
        assertEquals(40L, spool.pendingCount())
        spool.close()
    }
}
