package org.opentrafficmap.citstogo.bridge

import java.util.concurrent.TimeUnit
import org.opentrafficmap.citstogo.protocol.CitsPacket

/**
 * The handoff between the thread that drains the transport and the thread that works on packets.
 *
 * Draining has one duty nothing else can do: keep asking the transport for bytes, because the receiver
 * gives each record about a hundred milliseconds before it drops it, and a host that stops reading
 * loses the link rather than the backlog. Everything a packet is worth — the decode, the station table,
 * the live map, the PCAP file, the spool — belongs on a thread of its own, where a slow moment costs a
 * stale packet instead of a dead connection.
 *
 * When the worker falls behind, the oldest packets leave first. A C-ITS position nobody looked at for a
 * second is not data anyone still wants, and a worker that insists on finishing the backlog in order is
 * a worker that stays behind. [dropped] counts what went away; the same loss shows up as **Stream gaps**,
 * because the discarded records take their sequence numbers with them.
 */
class CaptureBacklog(private val capacity: Int) {
    init {
        require(capacity > 0)
    }

    private val queue = java.util.concurrent.LinkedBlockingQueue<CitsPacket>(capacity)

    @Volatile
    var dropped: Long = 0L
        private set

    /** Hands one packet to the worker, discarding the oldest waiting packets until it fits. */
    fun offer(packet: CitsPacket) {
        while (!queue.offer(packet)) {
            // A consumer may have taken the slot back since the offer failed; nothing left to discard
            // means there is nothing left to drop, and the next offer simply waits for it.
            if (queue.poll() == null) continue
            dropped += 1
        }
    }

    /**
     * Waits for the next packet and takes whatever is already queued with it, or returns nothing once
     * [waitMs] has passed.
     *
     * The batch is what keeps a burst cheap: the status broadcast, the spool append and the PCAP writer
     * all cost less per packet when a hundred of them arrive together than when each wakes the worker.
     */
    fun nextBatch(limit: Int, waitMs: Long): List<CitsPacket> {
        val first = poll(waitMs) ?: return emptyList()
        if (limit <= 1) return listOf(first)
        val batch = ArrayList<CitsPacket>(minOf(limit, queue.size + 1))
        batch += first
        queue.drainTo(batch, limit - 1)
        return batch
    }

    /** One packet, or null after [waitMs]. An interrupt is reported as no packet and left pending. */
    fun poll(waitMs: Long): CitsPacket? = try {
        queue.poll(waitMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    /** Packets waiting right now, for a diagnostic or a test rather than for a decision. */
    fun size(): Int = queue.size

    /**
     * Forgets everything waiting. A transport that went away has nothing worth handing to the next one,
     * and replaying a previous session's packets into a fresh capture would date the map and the PCAP.
     */
    fun clear() {
        queue.clear()
    }
}
