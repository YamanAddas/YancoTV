package com.yancotv.android.player

import com.yancotv.shared.playback.Playable
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MK.23.C.1 — pin [resumePointDecision]'s contract. The function gates
 * resume-point persistence across every transition in the app
 * (`play()`, `play(episode)`, `stop()`, `release()`, `step()`,
 * `applyExternalSubtitle()`); before extraction it lived inside
 * [PlaybackController.persistResumePoint] which is main-thread-only
 * and holds an ExoPlayer, making the rules untestable in JVM unit
 * tests. Audit (2026-04-28) flagged "zero tests today" as the highest-
 * priority gap. These tests exercise the rule matrix:
 *
 *   - LIVE channels never persist (no resume concept).
 *   - Synthetic local-recording items (`_rec_` prefix) never persist —
 *     no `content` row, FK violation otherwise.
 *   - Positions under 5 seconds never persist — bailing out shouldn't
 *     leave a "resume" card on the home shelf.
 *   - Episode sessions write the *series* id as `content_id` (FK
 *     target — episode rows aren't FK-clean).
 *   - Movie sessions write `item.id` as `content_id`, episodeId = null.
 *
 * Pure unit tests — no Robolectric, no controller, no DB.
 */
class ResumePointDecisionTest {
    // ───── Skip rules ─────

    @Test fun `null item returns null`() {
        val write = resumePointDecision(item = null, episode = null, positionSeconds = 30L, durationSeconds = null)
        assertNull(write)
    }

    @Test fun `LIVE channel returns null`() {
        val write =
            resumePointDecision(
                item = liveChannel(),
                episode = null,
                positionSeconds = 30L,
                durationSeconds = null,
            )
        assertNull(write, "LIVE channels have no resume concept; must return null")
    }

    @Test fun `local-recording prefix returns null`() {
        val write =
            resumePointDecision(
                item = movie(id = "${PlaybackController.LOCAL_RECORDING_ID_PREFIX}123"),
                episode = null,
                positionSeconds = 60L,
                durationSeconds = 3600L,
            )
        assertNull(write, "Synthetic _rec_ items have no content row; persisting would FK-violate")
    }

    @Test fun `position below 5 seconds returns null`() {
        val write =
            resumePointDecision(
                item = movie(),
                episode = null,
                positionSeconds = 4L,
                durationSeconds = 7200L,
            )
        assertNull(write, "Sub-5s positions must skip — bailing out shouldn't leave a resume card")
    }

    @Test fun `position at exactly 5 seconds is the boundary - persists`() {
        val write =
            resumePointDecision(
                item = movie(),
                episode = null,
                positionSeconds = 5L,
                durationSeconds = 7200L,
            )
        assertEquals("movie-1", write?.contentId)
        assertEquals(5L, write?.positionSeconds)
    }

    // ───── Movie path ─────

    @Test fun `movie writes item id with null episode id`() {
        val write =
            resumePointDecision(
                item = movie(id = "movie-42"),
                episode = null,
                positionSeconds = 1234L,
                durationSeconds = 7200L,
            )
        assertEquals("movie-42", write?.contentId)
        assertNull(write?.episodeId, "Movies must not write an episode id")
        assertEquals(1234L, write?.positionSeconds)
        assertEquals(7200L, write?.durationSeconds)
    }

    @Test fun `movie with null duration is allowed`() {
        // ExoPlayer reports duration <= 0 for not-yet-prepared sources; the
        // controller maps that to null. Persist still happens (we want the
        // resume position) — UI handles null duration gracefully.
        val write =
            resumePointDecision(
                item = movie(),
                episode = null,
                positionSeconds = 30L,
                durationSeconds = null,
            )
        assertEquals(30L, write?.positionSeconds)
        assertNull(write?.durationSeconds)
    }

    // ───── Episode path — the FK invariant ─────

    @Test fun `episode writes seriesId as contentId and episode id in episode_id`() {
        // The load-bearing test. content.id has an FK constraint pointing at
        // the `content` table — series rows live there, episode rows do not.
        // Writing an episode's own id as content_id would FK-violate. The
        // controller writes the *series* id and stashes the episode id in
        // the nullable `episode_id` column.
        val write =
            resumePointDecision(
                item = movie(id = "series-7", type = ContentType.SERIES),
                episode = episode(id = "ep-3", seriesId = "series-7"),
                positionSeconds = 600L,
                durationSeconds = 2400L,
            )
        assertEquals("series-7", write?.contentId, "contentId must be the series id, not the episode id")
        assertEquals("ep-3", write?.episodeId, "episode id must be in the dedicated nullable column")
        assertEquals(600L, write?.positionSeconds)
        assertEquals(2400L, write?.durationSeconds)
    }

    @Test fun `episode wins over item id - even when item is the episode itself`() {
        // Defensive: if the controller's `_currentItem.value` accidentally
        // gets set to an episode-as-ContentItem (rare but possible in the
        // typed-overload path), the episode field still controls the write
        // shape. Without this rule, we'd write episode-id as content_id and
        // crash on FK.
        val write =
            resumePointDecision(
                item = movie(id = "ep-3"),
                episode = episode(id = "ep-3", seriesId = "series-7"),
                positionSeconds = 60L,
                durationSeconds = null,
            )
        assertEquals("series-7", write?.contentId)
        assertEquals("ep-3", write?.episodeId)
    }

    // ───── Skip rules outrank episode rule ─────

    @Test fun `LIVE outranks episode rule`() {
        // Pathological: an episode set on a LIVE item. LIVE is the harder
        // skip — guards against any future code path that drops _currentEpisode
        // last but updates _currentItem to LIVE.
        val write =
            resumePointDecision(
                item = liveChannel(),
                episode = episode(),
                positionSeconds = 30L,
                durationSeconds = null,
            )
        assertNull(write)
    }

    @Test fun `local-recording prefix outranks episode rule`() {
        val write =
            resumePointDecision(
                item = movie(id = "${PlaybackController.LOCAL_RECORDING_ID_PREFIX}xyz"),
                episode = episode(),
                positionSeconds = 30L,
                durationSeconds = null,
            )
        assertNull(write)
    }

    // ───── Helpers ─────

    private fun movie(
        id: String = "movie-1",
        type: ContentType = ContentType.MOVIE,
    ) = ContentItem(
        id = id,
        sourceId = "src-1",
        type = type,
        title = "Test movie",
        streamUrl = "http://example.test/$id.mp4",
        sortOrder = 0,
        createdAt = 0L,
    )

    private fun liveChannel(id: String = "ch-1") =
        ContentItem(
            id = id,
            sourceId = "src-1",
            type = ContentType.LIVE,
            title = "Test channel",
            streamUrl = "http://example.test/$id.ts",
            sortOrder = 0,
            createdAt = 0L,
        )

    private fun episode(
        id: String = "ep-1",
        seriesId: String = "series-1",
    ) = Playable.Episode(
        id = id,
        seriesId = seriesId,
        title = "Test episode",
        streamUrl = "http://example.test/$id.mp4",
    )
}
