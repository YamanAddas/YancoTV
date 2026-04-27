package com.yancotv.android.recording

import org.junit.Test
import kotlin.test.assertEquals

/**
 * MB-213 — pin the live-tee vs fresh-GET decision table. Real
 * RecordingService inlines the same comparison; this exercises the
 * logic in isolation so a regression here is caught at the unit-test
 * level instead of via Fire TV hands-on.
 */
class RecordingRoutingTest {
    @Test fun sameUrl_routesToLiveTee() {
        val r = RecordingRouting.decide(playingUrl = "http://x/y", requestedUrl = "http://x/y")
        assertEquals(RecordingPath.LiveTee, r)
    }

    @Test fun differentUrl_routesToFreshGet() {
        val r = RecordingRouting.decide(playingUrl = "http://x/a", requestedUrl = "http://x/b")
        assertEquals(RecordingPath.FreshGet, r)
    }

    @Test fun nullPlayingUrl_routesToFreshGet() {
        // Headless / standby case (MB-209): no active playback,
        // route to fresh-GET so the recorder opens its own connection.
        val r = RecordingRouting.decide(playingUrl = null, requestedUrl = "http://x/y")
        assertEquals(RecordingPath.FreshGet, r)
    }

    @Test fun trailingSlashMismatch_routesToFreshGet() {
        // Deliberately strict: providers do treat trailing slashes
        // differently sometimes; we don't normalise. If a future need
        // emerges, this test pins the current behaviour so the change
        // is intentional.
        val r =
            RecordingRouting.decide(
                playingUrl = "http://x/y",
                requestedUrl = "http://x/y/",
            )
        assertEquals(RecordingPath.FreshGet, r)
    }

    @Test fun queryParamMismatch_routesToFreshGet() {
        // Some Xtream providers rotate per-request tokens via query
        // params; technically the same stream, but the URL strings
        // differ. Strict equality routes to fresh-GET, which is the
        // safer default (avoids accidentally tapping a stale stream).
        val r =
            RecordingRouting.decide(
                playingUrl = "http://x/y?token=abc",
                requestedUrl = "http://x/y?token=def",
            )
        assertEquals(RecordingPath.FreshGet, r)
    }

    @Test fun emptyAndNullAndBlankAllRouteToFreshGet() {
        // Defensive: if either side is empty/blank, treat as no match.
        // Real call sites should never produce empty requestedUrl
        // (RecordInput requires a non-blank URL), but pinning the
        // boundary keeps anyone refactoring the rule honest.
        assertEquals(RecordingPath.FreshGet, RecordingRouting.decide(null, ""))
        assertEquals(RecordingPath.FreshGet, RecordingRouting.decide("", ""))
        assertEquals(RecordingPath.FreshGet, RecordingRouting.decide("http://x/y", ""))
    }

    @Test fun caseDifference_routesToFreshGet() {
        // Provider URLs are case-sensitive on the wire; we don't
        // normalise. Pinning to make the rule explicit.
        val r =
            RecordingRouting.decide(
                playingUrl = "http://X/Y",
                requestedUrl = "http://x/y",
            )
        assertEquals(RecordingPath.FreshGet, r)
    }
}
