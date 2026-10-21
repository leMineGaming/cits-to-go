package org.opentrafficmap.citstogo.bridge

import android.content.Context
import android.content.SharedPreferences
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Where a [MqttSpool] keeps the cursor into its own file between process deaths.
 *
 * It is a seam rather than a `SharedPreferences` field because the cursor is the only part of the
 * spool that has to outlive the process, and the cadence of writing it is the whole point of the
 * spool's cost on the capture thread.
 */
interface SpoolOffsets {
    fun load(): Long
    fun save(readOffset: Long)
}

private class SharedPreferencesSpoolOffsets(private val prefs: SharedPreferences) : SpoolOffsets {
    override fun load(): Long = prefs.getLong(KEY_READ_OFFSET, 0L)

    override fun save(readOffset: Long) {
        prefs.edit().putLong(KEY_READ_OFFSET, readOffset).apply()
    }

    private companion object {
        const val KEY_READ_OFFSET = "read_offset"
    }
}

/**
 * An append-only spool of packet payloads waiting for a broker session, with a read cursor that
 * survives a restart.
 *
 * [append] runs on the thread that drains the radio transport, so the cost of a record is the cost
 * of keeping that transport alive: the payload goes into a buffered stream, and the cursor is
 * persisted on a cadence rather than per record. The cursor is also the only piece of state the
 * prefs hold, because [pending] is recomputed from the file by [recover] and storing it alongside
 * would only add a second answer to the same question.
 */
class MqttSpool(
    private val file: File,
    private val offsets: SpoolOffsets,
    private val maxRecords: Long = MAX_RECORDS,
    private val maxBytes: Long = MAX_BYTES,
) : Closeable {
    data class Record(val payload: ByteArray, val nextOffset: Long)

    constructor(context: Context) : this(
        File(context.filesDir, FILE_NAME),
        SharedPreferencesSpoolOffsets(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
    )

    private var appendOut: BufferedOutputStream? = null
    private var readOffset = 0L
    private var writeOffset = 0L
    private var pending = 0L
    private var unflushed = 0
    private var capacityDropped = 0L

    init {
        readOffset = offsets.load()
        recover()
    }

    @Synchronized
    fun append(payload: ByteArray) {
        if (payload.isEmpty()) return
        if (payload.size > MAX_PAYLOAD_LEN) throw IOException("MQTT payload too large: ${payload.size}")
        ensureAppendOpen()
        val out = appendOut ?: throw IOException("Spool is closed")
        writeIntLE(out, payload.size)
        out.write(payload)
        writeOffset += 4L + payload.size
        pending += 1
        unflushed += 1
        trimToCapacity()
        // One cursor write per PERSIST_EVERY_RECORDS records. A crash in between costs at most that
        // many duplicated publishes, which QoS 0 tolerates; a write per record costs a synchronous
        // prefs file per packet on the thread that has to keep the USB port drained.
        if (unflushed >= PERSIST_EVERY_RECORDS) {
            flush()
            persistOffsets()
        }
    }

    @Synchronized
    fun readBatch(maxPackets: Int): List<Record> {
        flush()
        // One length for the whole batch, so the bounds cannot move under the loop and a record
        // appended while this batch is being read simply lands past the end of it.
        val limit = file.length()
        if (maxPackets <= 0 || readOffset >= limit) return emptyList()
        val records = ArrayList<Record>(maxPackets)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(readOffset)
            var offset = readOffset
            repeat(maxPackets) {
                if (offset + 4 > limit) return@repeat
                val len = readIntLE(raf)
                offset += 4
                if (len <= 0 || len > MAX_PAYLOAD_LEN) throw IOException("Corrupt MQTT spool record length $len")
                if (offset + len > limit) return@repeat
                val payload = ByteArray(len)
                raf.readFully(payload)
                offset += len
                records += Record(payload, offset)
            }
        }
        return records
    }

    @Synchronized
    fun ack(nextOffset: Long, count: Int) {
        if (count <= 0 || nextOffset <= readOffset) return
        readOffset = nextOffset.coerceAtMost(file.length())
        pending = (pending - count).coerceAtLeast(0L)
        compactIfUseful()
        persistOffsets()
    }

    @Synchronized
    fun pendingCount(): Long = pending.coerceAtLeast(0L)

    /** Packets thrown away because the spool was full; a broker outage this long is worth knowing. */
    @Synchronized
    fun capacityDrops(): Long = capacityDropped

    @Synchronized
    fun clear() {
        closeStream()
        if (file.exists()) file.delete()
        readOffset = 0
        writeOffset = 0
        pending = 0
        unflushed = 0
        persistOffsets()
    }

    @Synchronized
    fun flush() {
        appendOut?.flush()
        unflushed = 0
    }

    @Synchronized
    override fun close() {
        closeStream()
        persistOffsets()
    }

    private fun closeStream() {
        runCatching { appendOut?.flush() }
        runCatching { appendOut?.close() }
        appendOut = null
    }

    private fun ensureAppendOpen() {
        if (appendOut != null) return
        file.parentFile?.mkdirs()
        appendOut = BufferedOutputStream(FileOutputStream(file, true), 64 * 1024)
        writeOffset = file.length()
    }

    private fun recover() {
        if (!file.exists()) {
            readOffset = 0
            writeOffset = 0
            pending = 0
            persistOffsets()
            return
        }
        var len = file.length()
        if (readOffset < 0 || readOffset > len) readOffset = 0
        // A record the process did not finish writing is discarded here rather than repaired, which
        // is what makes a capture that died mid-record start clean on the next run.
        val goodEnd = scanGoodEnd(0, len)
        if (goodEnd < len) {
            RandomAccessFile(file, "rw").use { it.setLength(goodEnd) }
            len = goodEnd
        }
        writeOffset = len
        pending = countRecords(readOffset, writeOffset)
        trimToCapacity()
        compactIfUseful()
        persistOffsets()
    }

    /**
     * Forgets the oldest waiting packets until the spool fits again, by moving the cursor over them.
     *
     * Nothing else bounds this file. The drain path gives up when there is no session to publish through,
     * so neither the queue length nor the queue age the user set ever applies during an outage, and an
     * afternoon of capture with no data connection is an afternoon of appends - a rate of tens of
     * kilobytes a second next to a PCAP recording that wants the same storage. What goes is the oldest
     * data, which for C-ITS is the data nobody wanted anyway: the PCAP file is the record of what was
     * heard, and this is only a buffer for a broker that was briefly unreachable.
     *
     * The two limits are resource limits rather than policy, and they are checked in constant time on
     * every append, so a healthy spool pays nothing.
     */
    private fun trimToCapacity() {
        if (pending <= maxRecords && writeOffset - readOffset <= maxBytes) return
        // The cursor can only be moved over records that have reached the file, and the append stream
        // keeps the last few back. This flushes the bytes without disturbing [unflushed], which counts
        // something else: how long since the cursor was persisted.
        runCatching { appendOut?.flush() }
        var dropped = 0L
        RandomAccessFile(file, "r").use { raf ->
            // Only what has reached the file can be measured, and the difference is at most the records
            // still sitting in the append buffer.
            val end = minOf(writeOffset, raf.length())
            while (readOffset + 4 <= end && (pending > maxRecords || end - readOffset > maxBytes)) {
                raf.seek(readOffset)
                val len = readIntLE(raf)
                if (len <= 0 || len > MAX_PAYLOAD_LEN || readOffset + 4L + len > end) break
                readOffset += 4L + len
                pending -= 1
                dropped += 1
            }
        }
        if (dropped > 0) {
            capacityDropped += dropped
            persistOffsets()
        }
    }

    private fun scanGoodEnd(start: Long, max: Long): Long {
        var offset = start
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            while (offset + 4 <= max) {
                val len = readIntLE(raf)
                if (len <= 0 || len > MAX_PAYLOAD_LEN || offset + 4L + len > max) break
                offset += 4L + len
                raf.seek(offset)
            }
        }
        return offset
    }

    private fun countRecords(start: Long, max: Long): Long {
        var count = 0L
        var offset = start
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            while (offset + 4 <= max) {
                val len = readIntLE(raf)
                if (len <= 0 || len > MAX_PAYLOAD_LEN || offset + 4L + len > max) break
                offset += 4L + len
                raf.seek(offset)
                count += 1
            }
        }
        return count
    }

    private fun compactIfUseful() {
        val len = if (file.exists()) file.length() else 0L
        if (readOffset <= COMPACT_AFTER_BYTES || readOffset <= len / 2) return
        flush()
        closeStream()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileInputStream(file).use { input ->
            FileOutputStream(tmp).use { output ->
                var skipped = 0L
                while (skipped < readOffset) {
                    val n = input.skip(readOffset - skipped)
                    if (n <= 0) throw EOFException("Could not skip spool prefix")
                    skipped += n
                }
                input.copyTo(output, 64 * 1024)
            }
        }
        if (!file.delete() || !tmp.renameTo(file)) throw IOException("Could not compact MQTT spool")
        readOffset = 0
        writeOffset = file.length()
        pending = countRecords(0, writeOffset)
    }

    private fun persistOffsets() {
        offsets.save(readOffset)
    }

    companion object {
        private const val PREFS = "mqtt_spool"
        private const val FILE_NAME = "mqtt-packet-spool.bin"

        /** The largest payload a record can carry; [append] refuses anything bigger. */
        const val MAX_PAYLOAD_LEN = 65_535
        private const val COMPACT_AFTER_BYTES = 1024L * 1024L

        /** Records between cursor persists, and therefore the widest duplicate window after a crash. */
        const val PERSIST_EVERY_RECORDS = 32

        /** How much may wait for a broker that has gone away before the oldest of it is dropped. */
        const val MAX_RECORDS = 50_000L
        const val MAX_BYTES = 32L * 1024L * 1024L

        private fun writeIntLE(out: BufferedOutputStream, value: Int) {
            out.write(value and 0xff)
            out.write((value ushr 8) and 0xff)
            out.write((value ushr 16) and 0xff)
            out.write((value ushr 24) and 0xff)
        }

        /**
         * One read for the four length bytes. The per-byte form asked the file for four separate
         * reads, and [scanGoodEnd] walks the whole file with it, which is the work a restart pays
         * after a spool has grown during a long broker outage.
         */
        private fun readIntLE(raf: RandomAccessFile): Int {
            val header = ByteArray(4)
            raf.readFully(header)
            return (header[0].toInt() and 0xff) or
                ((header[1].toInt() and 0xff) shl 8) or
                ((header[2].toInt() and 0xff) shl 16) or
                ((header[3].toInt() and 0xff) shl 24)
        }
    }
}
