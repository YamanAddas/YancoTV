package com.yancotv.shared.catchup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlBuilderTest {
    // 2026-04-15T14:30:00Z in Unix seconds
    private val apr15At1430 = 1776263400L

    // 2026-01-01T00:00:00Z
    private val jan1At0000 = 1767225600L

    // 2026-06-01T10:00:00Z
    private val jun1At1000 = 1780308000L

    // 2026-04-15T14:30:45Z
    private val apr15At143045 = 1776263445L

    @Test fun buildsStandardXtreamTimeshiftUrl() {
        val url =
            buildXtreamTimeshiftUrl(
                "http://provider.com",
                "user1",
                "pass1",
                "http://provider.com/live/user1/pass1/12345.ts",
                apr15At1430,
                3600,
            )
        assertEquals(
            "http://provider.com/timeshift/user1/pass1/60/2026-04-15:14-30/12345.ts",
            url,
        )
    }

    @Test fun extractsStreamIdFromUrl() {
        val url =
            buildXtreamTimeshiftUrl(
                "http://host.com",
                "u",
                "p",
                "http://host.com/live/u/p/99999.ts",
                jan1At0000,
                1800,
            )
        assertTrue(url.contains("/99999.ts"))
        assertTrue(url.contains("/30/"))
    }

    @Test fun defaultsStreamIdToZeroWhenNoNumericId() {
        val url =
            buildXtreamTimeshiftUrl(
                "http://host.com",
                "u",
                "p",
                "http://host.com/stream",
                jan1At0000,
                600,
            )
        assertTrue(url.contains("/0.ts"))
    }

    @Test fun doesNotStripTrailingSlashes() {
        val url =
            buildXtreamTimeshiftUrl(
                "http://host.com///",
                "u",
                "p",
                "http://host.com/live/u/p/1.ts",
                jun1At1000,
                120,
            )
        assertTrue(Regex("""^http://host\.com////timeshift/""").containsMatchIn(url))
    }

    @Test fun roundsDurationUpToNextMinute() {
        val url =
            buildXtreamTimeshiftUrl(
                "http://host.com",
                "u",
                "p",
                "http://host.com/live/u/p/1.ts",
                jan1At0000,
                61,
            )
        assertTrue(url.contains("/2/"))
    }

    // --- buildM3uCatchupUrl ---

    private val start = 1713189000L
    private val duration = 3600L

    private val now = 1713200000L

    @Test fun returnsNullWhenNoCatchupMetadata() {
        assertNull(buildM3uCatchupUrl("http://stream.com/ch1", emptyMap(), start, duration, now))
    }

    @Test fun buildsAppendStyleCatchupUrl() {
        val url =
            buildM3uCatchupUrl(
                "http://stream.com/ch1",
                mapOf("catchupType" to "append"),
                start,
                duration,
                now,
            )
        assertEquals(
            // MB-388 — lutc is "now", not the programme start.
            "http://stream.com/ch1?utc=$start&lutc=$now&duration=$duration",
            url,
        )
    }

    @Test fun buildsShiftStyleCatchupUrl() {
        val recentStart = now - 600
        val url =
            buildM3uCatchupUrl(
                "http://stream.com/ch1",
                mapOf("catchupType" to "shift"),
                recentStart,
                duration,
                nowSecs = now,
            )
        assertNotNull(url)
        assertTrue(url.contains("utc=$recentStart"))
        assertTrue(url.contains("lutc=$now"), "MB-388: lutc is now, was: $url")
        assertTrue(url.contains("shift=600"))
    }

    @Test fun buildsAppendForDefaultType() {
        // MB-385 — "default" is the common Kodi keyword and means append; the
        // old code returned the LIVE url for it (silently played live).
        val url = buildM3uCatchupUrl("http://stream.com/ch1", mapOf("catchupType" to "default"), start, duration, now)
        assertEquals("http://stream.com/ch1?utc=$start&lutc=$now&duration=$duration", url)
    }

    @Test fun returnsNullForUnknownTypeWithoutTemplate() {
        // MB-385 — a type we can't build (no source template) must return null
        // so the caller shows "unavailable", NOT silently play the live stream.
        assertNull(buildM3uCatchupUrl("http://stream.com/ch1", mapOf("catchupType" to "flussonic"), start, duration, now))
    }

    @Test fun usesAmpersandWhenLiveUrlAlreadyHasQuery() {
        // MB-388 — no double '?', which the provider parses as one value and
        // drops the utc params.
        val url = buildM3uCatchupUrl("http://stream.com/ch1?token=abc", mapOf("catchupType" to "append"), start, duration, now)
        assertEquals("http://stream.com/ch1?token=abc&utc=$start&lutc=$now&duration=$duration", url)
    }

    @Test fun substitutesOffsetTokenInTemplate() {
        // MB-388 — {offset} = seconds back from now; was left literal.
        val url = buildM3uCatchupUrl(
            "http://stream.com/ch1",
            mapOf("catchupSource" to "http://archive.com/play?offset={offset}&d={duration}"),
            start,
            duration,
            now,
        )
        assertEquals("http://archive.com/play?offset=${now - start}&d=$duration", url)
    }

    @Test fun replacesPlaceholdersInCatchupSourceTemplate() {
        val url =
            buildM3uCatchupUrl(
                "http://stream.com/live/1234.ts",
                mapOf("catchupSource" to "http://archive.com/timeshift/{stream_id}/{start}/{duration}"),
                start,
                duration,
                now,
            )
        assertEquals("http://archive.com/timeshift/1234/$start/$duration", url)
    }

    @Test fun replacesDateComponentPlaceholders() {
        val url =
            buildM3uCatchupUrl(
                "http://stream.com/live/1.ts",
                mapOf("catchupSource" to "http://archive.com/{Y}-{m}-{d}/{H}:{M}:{S}"),
                apr15At143045,
                3600,
                now,
            )
        assertEquals("http://archive.com/2026-04-15/14:30:45", url)
    }

    @Test fun replacesEndPlaceholder() {
        val url =
            buildM3uCatchupUrl(
                "http://stream.com/live/1.ts",
                mapOf("catchupSource" to "http://archive.com/?start={start}&end={end}"),
                start,
                duration,
                now,
            )
        assertEquals("http://archive.com/?start=$start&end=${start + duration}", url)
    }

    @Test fun replacesTimestampAndUtcAliases() {
        val url =
            buildM3uCatchupUrl(
                "http://stream.com/live/1.ts",
                mapOf("catchupSource" to "http://archive.com/?ts={timestamp}&u={utc}"),
                start,
                duration,
                now,
            )
        assertEquals("http://archive.com/?ts=$start&u=$start", url)
    }

    @Test fun usesCatchupSourceTemplateOverOriginalUrlWhenBothTypesPresent() {
        val url =
            buildM3uCatchupUrl(
                "http://stream.com/ch1",
                mapOf(
                    "catchupType" to "append",
                    "catchupSource" to "http://archive.com/{start}",
                ),
                start,
                duration,
                now,
            )
        assertEquals("http://archive.com/$start", url)
    }
}
