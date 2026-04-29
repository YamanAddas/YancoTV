package com.yancotv.shared.recording

import com.yancotv.shared.recording.StorageCapPlanner.Plan
import com.yancotv.shared.recording.StorageCapPlanner.StoredRecording
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [StorageCapPlanner]. Pure logic; no Android deps. Covers
 * the three plan outcomes (Proceed / EvictThenProceed / NoSpace) plus
 * the low-storage warning gate.
 */
class StorageCapPlannerTest {
    private val planner = StorageCapPlanner()
    private val gb = 1024L * 1024L * 1024L

    @Test fun proceedWhenCapAndDiskHaveRoom() {
        val plan =
            planner.planForNewRecording(
                capBytes = 16 * gb,
                existingRecordings = listOf(rec("a", 1 * gb, startedAt = 0)),
                newRecordingExpectedBytes = 2 * gb,
                freeDiskBytes = 50 * gb,
            )
        val proceed = assertIs<Plan.Proceed>(plan)
        // Cap remaining = 16 GB - 1 GB = 15 GB; effective budget = min(15, 50) = 15 GB.
        assertEquals(15 * gb, proceed.remainingBudgetBytes)
    }

    @Test fun proceedBudgetClampedByActualDiskSpace() {
        // High cap, low free disk — disk wins.
        val plan =
            planner.planForNewRecording(
                capBytes = 100 * gb,
                existingRecordings = emptyList(),
                newRecordingExpectedBytes = 2 * gb,
                freeDiskBytes = 5 * gb,
            )
        val proceed = assertIs<Plan.Proceed>(plan)
        assertEquals(5 * gb, proceed.remainingBudgetBytes)
    }

    @Test fun evictsOldestFirstUntilEnoughSpace() {
        // Cap full; need to evict.
        val plan =
            planner.planForNewRecording(
                capBytes = 10 * gb,
                existingRecordings =
                listOf(
                    rec("oldest", 4 * gb, startedAt = 100),
                    rec("middle", 3 * gb, startedAt = 200),
                    rec("newest", 3 * gb, startedAt = 300),
                ),
                newRecordingExpectedBytes = 5 * gb,
                freeDiskBytes = 100 * gb, // disk has plenty; cap is the constraint
            )
        val ev = assertIs<Plan.EvictThenProceed>(plan)
        // Cap remaining = 10 - 10 = 0; need 5 GB. Oldest = 4 GB, still need 1 GB
        // → middle = 3 GB. Total freed = 7 GB after evicting oldest + middle.
        assertEquals(listOf("oldest", "middle"), ev.evictionTargets)
        assertEquals(7 * gb, ev.bytesFreed)
    }

    @Test fun stopsEvictingOnceEnoughSpaceFreed() {
        val plan =
            planner.planForNewRecording(
                capBytes = 10 * gb,
                existingRecordings =
                listOf(
                    rec("a", 1 * gb, startedAt = 100),
                    rec("b", 1 * gb, startedAt = 200),
                    rec("c", 8 * gb, startedAt = 300),
                ),
                newRecordingExpectedBytes = 2 * gb,
                freeDiskBytes = 100 * gb,
            )
        // Cap remaining = 0; need 2 GB. Evicting "a" (1 GB) and "b" (1 GB)
        // frees exactly 2 GB; "c" stays.
        val ev = assertIs<Plan.EvictThenProceed>(plan)
        assertEquals(listOf("a", "b"), ev.evictionTargets)
        assertEquals(2 * gb, ev.bytesFreed)
    }

    @Test fun inflightRecordingsAreNotEvictable() {
        val plan =
            planner.planForNewRecording(
                capBytes = 10 * gb,
                existingRecordings =
                listOf(
                    rec("inflight", 8 * gb, startedAt = 100, evictable = false),
                    rec("done", 2 * gb, startedAt = 200, evictable = true),
                ),
                newRecordingExpectedBytes = 5 * gb,
                freeDiskBytes = 100 * gb,
            )
        // Cap remaining = 0; need 5 GB. Only "done" is evictable (2 GB).
        // Even after evicting it, we're 3 GB short → NoSpace.
        val nope = assertIs<Plan.NoSpace>(plan)
        assertEquals(3 * gb, nope.shortfallBytes)
        assertEquals(2 * gb, nope.evictableBytes)
        assertEquals(5 * gb, nope.totalRequiredBytes)
    }

    @Test fun noSpaceWhenDiskItselfIsTooSmall() {
        // Cap is fine, but disk has < requested.
        val plan =
            planner.planForNewRecording(
                capBytes = 100 * gb,
                existingRecordings = emptyList(),
                newRecordingExpectedBytes = 5 * gb,
                freeDiskBytes = 1 * gb,
            )
        val nope = assertIs<Plan.NoSpace>(plan)
        assertEquals(4 * gb, nope.shortfallBytes)
    }

    @Test fun lowStorageWarningFiresWhenAvailableLessThanTwiceEstimate() {
        // 1 GB available, 1 GB estimated → ratio 1.0 < 2.0 → warn.
        assertTrue(planner.shouldWarnBeforeScheduledFire(availableBytesAfterEviction = 1 * gb, estimatedRecordingBytes = 1 * gb))
        // 3 GB available, 1 GB estimated → ratio 3.0 > 2.0 → no warn.
        assertFalse(planner.shouldWarnBeforeScheduledFire(availableBytesAfterEviction = 3 * gb, estimatedRecordingBytes = 1 * gb))
        // Edge: exactly 2x → no warn (the spec says "<", not "<=").
        assertFalse(planner.shouldWarnBeforeScheduledFire(availableBytesAfterEviction = 2 * gb, estimatedRecordingBytes = 1 * gb))
    }

    @Test fun emptyExistingRecordingsProceedsCleanly() {
        val plan =
            planner.planForNewRecording(
                capBytes = 16 * gb,
                existingRecordings = emptyList(),
                newRecordingExpectedBytes = 1 * gb,
                freeDiskBytes = 100 * gb,
            )
        val proceed = assertIs<Plan.Proceed>(plan)
        assertEquals(16 * gb, proceed.remainingBudgetBytes)
    }

    private fun rec(id: String, bytesOnDisk: Long, startedAt: Long, evictable: Boolean = true): StoredRecording = StoredRecording(
        id = id,
        bytesOnDisk = bytesOnDisk,
        startedAt = startedAt,
        evictable = evictable,
    )
}
