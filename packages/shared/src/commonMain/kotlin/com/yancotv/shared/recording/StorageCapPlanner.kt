package com.yancotv.shared.recording

/**
 * Pure planning logic for the user-set storage cap. The Android side
 * supplies real numbers (current recording sizes, free disk, the cap
 * setting) and consumes the planner's verdict — auto-evict these
 * rows, refuse the new recording, or proceed.
 *
 * Lives in commonMain so the eviction policy is unit-testable without
 * stubbing Android's `MediaStore` and `StatFs`. iOS will reuse it
 * unchanged.
 *
 * See `docs/design/recording-spec.md` §2 Q11–Q13 for the user-facing
 * behaviour this implements.
 */
class StorageCapPlanner {
    /**
     * Decide what to do when a new recording wants to start.
     *
     * @param capBytes user-set max-disk cap for recordings (default 16 GB; configurable 1–256 GB).
     * @param existingRecordings completed + in-flight rows we're managing. The planner doesn't
     *        differentiate by status — every row counts toward the cap.
     * @param newRecordingExpectedBytes estimated size of the recording about to start. For
     *        scheduled recordings: `(end - start) * peak_bitrate * 1.2 safety`. For record-now:
     *        a 1 GB default is fine if we don't know the bitrate.
     * @param freeDiskBytes free space currently on the storage volume backing recordings.
     *        Independent of [capBytes] — even a high cap can't help if the disk is physically full.
     * @return [Plan.Proceed] (no eviction needed); [Plan.EvictThenProceed] (delete listed rows
     *         first; their `fileSizeBytes` sum + current free will fit the new recording);
     *         [Plan.NoSpace] (even after evicting every evictable row, can't fit).
     */
    fun planForNewRecording(capBytes: Long, existingRecordings: List<StoredRecording>, newRecordingExpectedBytes: Long, freeDiskBytes: Long): Plan {
        val totalCurrent = existingRecordings.sumOf { it.bytesOnDisk }
        val capRemaining = (capBytes - totalCurrent).coerceAtLeast(0)
        val effectiveBudget = minOf(capRemaining, freeDiskBytes)

        if (effectiveBudget >= newRecordingExpectedBytes) {
            return Plan.Proceed(remainingBudgetBytes = effectiveBudget)
        }

        // Need to evict. Order: oldest completed first, never evict
        // in-flight rows (those represent active recorder coroutines).
        val evictable =
            existingRecordings
                .filter { it.evictable }
                .sortedBy { it.startedAt }
        val needed = newRecordingExpectedBytes - effectiveBudget
        val toEvict = mutableListOf<StoredRecording>()
        var freed = 0L
        for (row in evictable) {
            if (freed >= needed) break
            toEvict += row
            freed += row.bytesOnDisk
        }
        if (freed < needed) {
            return Plan.NoSpace(
                shortfallBytes = needed - freed,
                evictableBytes = freed,
                totalRequiredBytes = newRecordingExpectedBytes,
            )
        }
        return Plan.EvictThenProceed(
            evictionTargets = toEvict.map { it.id },
            bytesFreed = freed,
            remainingBudgetAfterEvictionBytes = effectiveBudget + freed,
        )
    }

    /**
     * Decide whether to fire a low-storage warning notification
     * before a scheduled recording. Per design spec §2 Q13: warn if
     * available capacity is < 2 × estimated_size.
     */
    fun shouldWarnBeforeScheduledFire(availableBytesAfterEviction: Long, estimatedRecordingBytes: Long): Boolean =
        availableBytesAfterEviction < estimatedRecordingBytes * 2

    /**
     * Snapshot of one stored recording row, projected to the fields
     * the planner needs. Repo callers map [RecordingEntry] →
     * [StoredRecording] before calling.
     */
    data class StoredRecording(
        val id: String,
        val bytesOnDisk: Long,
        val startedAt: Long,
        /**
         * In-flight recordings are never evicted — they represent an
         * active recorder coroutine and yanking the file out from
         * under it would corrupt the partial output. Set this to
         * [RecordingStatus.RECORDING] rows even if you'd otherwise
         * include them in eviction lists.
         */
        val evictable: Boolean,
    )

    sealed interface Plan {
        /** Cap allows the new recording without evicting anything. */
        data class Proceed(val remainingBudgetBytes: Long) : Plan

        /**
         * Need to delete [evictionTargets] (in order) before starting
         * the new recording; resulting budget will be enough.
         */
        data class EvictThenProceed(val evictionTargets: List<String>, val bytesFreed: Long, val remainingBudgetAfterEvictionBytes: Long) : Plan

        /**
         * Even with every evictable row gone, [shortfallBytes] still
         * can't be freed. Caller surfaces a "free space" prompt and
         * refuses the recording.
         */
        data class NoSpace(val shortfallBytes: Long, val evictableBytes: Long, val totalRequiredBytes: Long) : Plan
    }

    companion object {
        /** Default cap when the user hasn't set one — 16 GB per design spec. */
        const val DEFAULT_CAP_BYTES: Long = 16L * 1024L * 1024L * 1024L

        /** Default expected size when no bitrate estimate is available — 1 GB. */
        const val DEFAULT_EXPECTED_BYTES: Long = 1L * 1024L * 1024L * 1024L
    }
}
