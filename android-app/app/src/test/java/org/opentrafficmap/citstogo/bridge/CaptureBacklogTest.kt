package org.opentrafficmap.citstogo.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.protocol.CitsPacket

/**
 * The backlog is what stands between a busy radio and a dead link, so what is worth pinning down is what
 * it gives up and in which order it hands the rest on.
 */
class CaptureBacklogTest {
    private fun packet(sequence: Long) = CitsPacket(
        sequence = sequence,
        timestampUs = 1_000L,
        frequencyMhz = 5_900,
        rssiDbm = -50,
        wifiType = 0,
        rxState = 0,
        flags = 0,
        originalLength = 1,
        capturedLength = 1,
        payload = byteArrayOf(sequence.toByte()),
    )

    private fun sequences(batch: List<CitsPacket>): List<Long> = batch.map { it.sequence }

    @Test
    fun packetsWaitInOrderUpToTheCapacity() {
        val backlog = CaptureBacklog(capacity = 4)

        repeat(4) { backlog.offer(packet((it + 1).toLong())) }

        assertEquals(4, backlog.size())
        assertEquals(0L, backlog.dropped)
        assertEquals(listOf(1L, 2L, 3L, 4L), sequences(backlog.nextBatch(limit = 10, waitMs = 0)))
    }

    @Test
    fun aBacklogThatOverflowsLosesTheOldestFirst() {
        val backlog = CaptureBacklog(capacity = 3)

        repeat(7) { backlog.offer(packet((it + 1).toLong())) }

        assertEquals(3, backlog.size())
        assertEquals(4L, backlog.dropped)
        assertEquals(listOf(5L, 6L, 7L), sequences(backlog.nextBatch(limit = 10, waitMs = 0)))
    }

    @Test
    fun aBatchStopsAtItsLimitAndLeavesTheRestQueued() {
        val backlog = CaptureBacklog(capacity = 10)
        repeat(6) { backlog.offer(packet((it + 1).toLong())) }

        val first = backlog.nextBatch(limit = 2, waitMs = 0)
        val rest = backlog.nextBatch(limit = 10, waitMs = 0)

        assertEquals(listOf(1L, 2L), sequences(first))
        assertEquals(listOf(3L, 4L, 5L, 6L), sequences(rest))
        assertEquals(0, backlog.size())
    }

    @Test
    fun anEmptyBacklogAnswersWithNothingRatherThanBlockingForever() {
        val backlog = CaptureBacklog(capacity = 2)

        val startedAt = System.nanoTime()
        val batch = backlog.nextBatch(limit = 8, waitMs = 40)
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000L

        assertTrue("expected the empty wait to return, waited ${waitedMs}ms", waitedMs >= 30L)
        assertTrue(batch.isEmpty())
    }

    @Test
    fun clearingForgetsWhatWasWaitingButNotWhatWasLost() {
        val backlog = CaptureBacklog(capacity = 1)
        backlog.offer(packet(1))
        backlog.offer(packet(2))

        backlog.clear()

        assertEquals(0, backlog.size())
        assertEquals(1L, backlog.dropped)
        assertTrue(backlog.nextBatch(limit = 4, waitMs = 0).isEmpty())
    }

    @Test
    fun aCapacityOfOneStillHandsEveryPacketItIsGivenLast() {
        val backlog = CaptureBacklog(capacity = 1)

        repeat(50) { backlog.offer(packet((it + 1).toLong())) }

        assertEquals(listOf(50L), sequences(backlog.nextBatch(limit = 5, waitMs = 0)))
        assertEquals(49L, backlog.dropped)
    }
}
