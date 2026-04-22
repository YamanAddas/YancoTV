package com.yancotv.shared.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [ContentIds] — the FNV-1a hashing + per-type id builder.
 *
 * Contract: IDs must be deterministic (favorites survive re-sync) and
 * scoped to a source (two providers with the same stream URL must not
 * produce colliding IDs).
 */
class ContentIdsTest {

    @Test fun m3uIdIsDeterministic() {
        val a = ContentIds.m3u("src-1", "CNN", "http://host/stream.ts")
        val b = ContentIds.m3u("src-1", "CNN", "http://host/stream.ts")
        assertEquals(a, b, "same inputs must yield same id across runs")
    }

    @Test fun m3uIdScopedBySource() {
        val a = ContentIds.m3u("src-1", "CNN", "http://host/stream.ts")
        val b = ContentIds.m3u("src-2", "CNN", "http://host/stream.ts")
        assertNotEquals(a, b, "same stream on two sources must not collide")
    }

    @Test fun m3uIdChangesWhenTitleChanges() {
        val a = ContentIds.m3u("src-1", "CNN", "http://host/stream.ts")
        val b = ContentIds.m3u("src-1", "BBC", "http://host/stream.ts")
        assertNotEquals(a, b)
    }

    @Test fun m3uIdChangesWhenUrlChanges() {
        val a = ContentIds.m3u("src-1", "CNN", "http://host/a.ts")
        val b = ContentIds.m3u("src-1", "CNN", "http://host/b.ts")
        assertNotEquals(a, b)
    }

    @Test fun m3uHashIsEightHexChars() {
        // Format contract: "$sourceId-$hash", hash is 8-char zero-padded hex.
        val id = ContentIds.m3u("s", "t", "u")
        val hash = id.substringAfter("s-")
        assertEquals(8, hash.length, "hash segment must always be 8 hex chars, was '$hash'")
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun xtreamLiveIdFormat() {
        assertEquals("src-1-xt-live-42", ContentIds.xtreamLive("src-1", "42"))
    }

    @Test fun xtreamVodIdFormat() {
        assertEquals("src-1-xt-vod-42", ContentIds.xtreamVod("src-1", "42"))
    }

    @Test fun xtreamSeriesIdFormat() {
        assertEquals("src-1-xt-series-42", ContentIds.xtreamSeries("src-1", "42"))
    }

    @Test fun stalkerLiveIdFormat() {
        assertEquals("src-1-stk-live-abc", ContentIds.stalkerLive("src-1", "abc"))
    }

    @Test fun xtreamLiveAndVodDoNotCollide() {
        val live = ContentIds.xtreamLive("src-1", "100")
        val vod = ContentIds.xtreamVod("src-1", "100")
        // A provider can legitimately reassign a stream ID across types — the
        // URL prefix (`xt-live-` vs `xt-vod-`) exists to prevent collisions.
        assertNotEquals(live, vod)
    }

    @Test fun m3uHandlesUnicode() {
        // Title with non-ASCII must hash stably, not crash or truncate.
        val a = ContentIds.m3u("src-1", "Canal+ España", "http://h/s.ts")
        val b = ContentIds.m3u("src-1", "Canal+ España", "http://h/s.ts")
        assertEquals(a, b)
    }

    @Test fun m3uEmptyInputsStillProduceValidId() {
        // Degenerate but real: provider sometimes ships nameless rows.
        val id = ContentIds.m3u("src-1", "", "")
        assertTrue(id.startsWith("src-1-"))
        assertEquals(14, id.length, "'src-1-' + 8 char hash = 14 chars")
    }
}
