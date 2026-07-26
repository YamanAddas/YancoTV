package com.yancotv.android.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MB-299 — pins the "may this EPG refresh be dropped while a catalog sync is
 * rebuilding the channel table?" policy.
 *
 * Why this matters beyond wasted work: with a sync in flight,
 * `BulkContentWriter.prepareSource` has already DELETEd the source's content
 * rows, so the importer matches against an empty table. Measured on the Fire TV
 * Stick it downloaded 77,444,321 B, parsed for 64 s, then discarded all 226,822
 * programmes — while overlapping the 279k-item catalog sync that is the app's
 * tightest heap moment.
 *
 * The two `false` cases are the load-bearing ones. POST_SYNC especially: the
 * coordinator fires it from inside its `try`, and `_state` is only cleared in
 * the `finally` afterwards — so at that instant a sync still looks active. Skip
 * it and EPG would never refresh at all, which is strictly worse than the bug
 * being fixed.
 */
class EpgSyncReasonTest {
    @Test
    fun `automatic refresh is dropped while a catalog sync is running`() {
        assertTrue(EpgSyncWorker.shouldSkipForActiveSync(EpgSyncReason.AUTO, catalogSyncActive = true))
    }

    @Test
    fun `automatic refresh runs when no catalog sync is active`() {
        assertFalse(EpgSyncWorker.shouldSkipForActiveSync(EpgSyncReason.AUTO, catalogSyncActive = false))
    }

    @Test
    fun `user-initiated refresh is never dropped - a tap must not silently no-op`() {
        assertFalse(EpgSyncWorker.shouldSkipForActiveSync(EpgSyncReason.USER, catalogSyncActive = true))
        assertFalse(EpgSyncWorker.shouldSkipForActiveSync(EpgSyncReason.USER, catalogSyncActive = false))
    }

    @Test
    fun `post-sync refresh is never dropped - the coordinator fires it before clearing state`() {
        assertFalse(EpgSyncWorker.shouldSkipForActiveSync(EpgSyncReason.POST_SYNC, catalogSyncActive = true))
        assertFalse(EpgSyncWorker.shouldSkipForActiveSync(EpgSyncReason.POST_SYNC, catalogSyncActive = false))
    }

    @Test
    fun `unknown or missing reason decodes to AUTO`() {
        // Work data written by an older build, or a request that predates
        // KEY_REASON, must land on the deferrable branch rather than throw.
        assertEquals(EpgSyncReason.AUTO, EpgSyncReason.fromRaw(null))
        assertEquals(EpgSyncReason.AUTO, EpgSyncReason.fromRaw(""))
        assertEquals(EpgSyncReason.AUTO, EpgSyncReason.fromRaw("NOT_A_REASON"))
    }

    @Test
    fun `every reason round-trips through its work-data encoding`() {
        // enqueueOnce writes `reason.name`; doWork reads it back with fromRaw.
        EpgSyncReason.entries.forEach { reason ->
            assertEquals(reason, EpgSyncReason.fromRaw(reason.name), "round-trip failed for $reason")
        }
    }
}
