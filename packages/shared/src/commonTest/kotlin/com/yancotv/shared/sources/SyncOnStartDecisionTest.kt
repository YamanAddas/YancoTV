package com.yancotv.shared.sources

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MB-363 — the catalogue used to be wiped and rebuilt on every cold start,
 * roughly fifteen minutes of empty app each time. These pin the gate that
 * stops it.
 */
class SyncOnStartDecisionTest {
    private val hour = 60L * 60L * 1000L
    private val now = 1_700_000_000_000L

    @Test fun neverSyncedAlwaysSyncs() {
        assertTrue(SyncOnStartDecision.shouldSync(null, 0L, now))
        assertTrue(SyncOnStartDecision.shouldSync(0L, 0L, now), "0 means never synced, not epoch")
    }

    @Test fun freshCatalogueIsNotRebuilt() {
        // the regression: an hour-old catalogue was being wiped and rebuilt
        assertFalse(SyncOnStartDecision.shouldSync(now - hour, 0L, now))
    }

    @Test fun staleCatalogueSyncs() {
        assertTrue(SyncOnStartDecision.shouldSync(now - 13L * hour, 0L, now))
    }

    @Test fun exactlyAtTheIntervalSyncs() {
        assertTrue(SyncOnStartDecision.shouldSync(now - SyncOnStartDecision.DEFAULT_INTERVAL_MS, 0L, now))
    }

    @Test fun explicitIntervalOverridesTheDefault() {
        // 2h interval: 3h old is stale even though the 12h default would not be
        assertTrue(SyncOnStartDecision.shouldSync(now - 3L * hour, 2L * hour, now))
        assertFalse(SyncOnStartDecision.shouldSync(now - 1L * hour, 2L * hour, now))
    }

    @Test fun negativeOrZeroIntervalFallsBackToTheDefault() {
        assertFalse(SyncOnStartDecision.shouldSync(now - hour, -5L, now))
        assertTrue(SyncOnStartDecision.shouldSync(now - 13L * hour, -5L, now))
    }

    @Test fun clockMovedBackwardsDoesNotResync() {
        // NTP correction or a timezone edit can leave last_synced in the
        // future; that must not mean "sync on every launch until it catches up"
        assertFalse(SyncOnStartDecision.shouldSync(now + 5L * hour, 0L, now))
    }
}
