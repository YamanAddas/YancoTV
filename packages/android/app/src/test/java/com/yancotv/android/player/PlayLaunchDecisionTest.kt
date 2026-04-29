package com.yancotv.android.player

import com.yancotv.shared.playback.Playable
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlin.test.assertEquals
import org.junit.Test

/**
 * MK.24.E.3 — pin [playLaunchDecision] + [episodeLaunchDecision]
 * (the pure functions that gate `PlaybackController.play`'s two-tap
 * no-op contract). Audit (2026-04-28) flagged "every launch site must
 * guard `controller.currentId == target.id`" as documented in the MK
 * skill checklist + the controller's KDoc, but NO test pinned the
 * controller-side defense-in-depth guard until now.
 *
 * The bug class this protects against: a future refactor that "tidies
 * up" play() into a single fall-through path would re-prepare the
 * MediaItem on every same-id tap, dropping the buffer and forcing a
 * 1–3 s rebuffer. User-facing symptom would be a black flash + spinner
 * on every second tap of the currently-playing tile — same UX we shipped
 * MK.6 specifically to avoid (the second tap should go straight to
 * fullscreen via PlayerLauncher, no rebuffer).
 *
 * Pure unit tests — no Robolectric, no controller, no ExoPlayer.
 * Mirrors the shape of [ResumePointDecisionTest].
 */
class PlayLaunchDecisionTest {
    // ───── playLaunchDecision (the list/index overload) ─────

    @Test fun `empty list rejects`() {
        val d = playLaunchDecision(list = emptyList(), startIndex = 0, currentId = null)
        assertEquals(PlayLaunchDecision.Reject, d, "Empty list = no playable target")
    }

    @Test fun `negative startIndex rejects`() {
        val d = playLaunchDecision(list = listOf(movie()), startIndex = -1, currentId = null)
        assertEquals(PlayLaunchDecision.Reject, d)
    }

    @Test fun `startIndex past end rejects`() {
        val d = playLaunchDecision(list = listOf(movie()), startIndex = 5, currentId = null)
        assertEquals(PlayLaunchDecision.Reject, d)
    }

    @Test fun `series container rejects`() {
        // Series containers fail ContentItem.toPlayable() — they're not
        // playable directly, the user has to drill into an episode.
        val d =
            playLaunchDecision(
                list = listOf(movie(id = "series-1", type = ContentType.SERIES)),
                startIndex = 0,
                currentId = null,
            )
        assertEquals(PlayLaunchDecision.Reject, d, "Series containers must reject — episode-picker is the entry point")
    }

    @Test fun `blank stream URL rejects`() {
        // ContentItem.toPlayable() returns null for blank URLs.
        val d =
            playLaunchDecision(
                list = listOf(movie(streamUrl = "")),
                startIndex = 0,
                currentId = null,
            )
        assertEquals(PlayLaunchDecision.Reject, d)
    }

    @Test fun `null currentId returns NewTarget — first launch`() {
        // Cold start: nothing playing yet, any valid target is a new launch.
        val d =
            playLaunchDecision(
                list = listOf(movie(id = "movie-1")),
                startIndex = 0,
                currentId = null,
            )
        assertEquals(PlayLaunchDecision.NewTarget, d)
    }

    @Test fun `same currentId returns SameTarget — the two-tap no-op contract`() {
        // The load-bearing test. Without this rule, the second tap on
        // the currently-playing tile would re-prepare the MediaItem and
        // the user sees a black flash + 1–3 s rebuffer. Enforces the
        // controller-level defense-in-depth behind the call-site
        // guards documented in the MK skill checklist.
        val d =
            playLaunchDecision(
                list = listOf(movie(id = "movie-1")),
                startIndex = 0,
                currentId = "movie-1",
            )
        assertEquals(
            PlayLaunchDecision.SameTarget,
            d,
            "Two-tap no-op: same id MUST NOT re-prepare the player",
        )
    }

    @Test fun `different currentId returns NewTarget — zap to a different tile`() {
        val d =
            playLaunchDecision(
                list = listOf(movie(id = "movie-2")),
                startIndex = 0,
                currentId = "movie-1",
            )
        assertEquals(PlayLaunchDecision.NewTarget, d)
    }

    @Test fun `startIndex picks correct target from multi-item list`() {
        val list =
            listOf(
                movie(id = "ch-1"),
                movie(id = "ch-2"),
                movie(id = "ch-3"),
            )
        // currentId == ch-2 + startIndex 1 → SameTarget (picked target is ch-2).
        assertEquals(PlayLaunchDecision.SameTarget, playLaunchDecision(list, 1, "ch-2"))
        // currentId == ch-2 + startIndex 0 → NewTarget (picked target is ch-1).
        assertEquals(PlayLaunchDecision.NewTarget, playLaunchDecision(list, 0, "ch-2"))
        // currentId == ch-2 + startIndex 2 → NewTarget (picked target is ch-3).
        assertEquals(PlayLaunchDecision.NewTarget, playLaunchDecision(list, 2, "ch-2"))
    }

    // ───── episodeLaunchDecision (the typed-overload) ─────

    @Test fun `episode with blank URL rejects`() {
        val d = episodeLaunchDecision(episode(streamUrl = ""), currentId = null)
        assertEquals(PlayLaunchDecision.Reject, d)
    }

    @Test fun `episode with null currentId returns NewTarget`() {
        val d = episodeLaunchDecision(episode(id = "ep-1"), currentId = null)
        assertEquals(PlayLaunchDecision.NewTarget, d)
    }

    @Test fun `episode with same id as currentId returns SameTarget`() {
        // Same two-tap protection on the episode path. The controller's
        // _currentItem holds the synthesized "episode-as-MOVIE" view
        // whose id == episode.id, so currentId == ep.id is the
        // canonical "this episode is already playing" condition.
        val d = episodeLaunchDecision(episode(id = "ep-7"), currentId = "ep-7")
        assertEquals(
            PlayLaunchDecision.SameTarget,
            d,
            "Re-launching the same episode MUST NOT re-prepare the player",
        )
    }

    @Test fun `episode with different id returns NewTarget`() {
        val d = episodeLaunchDecision(episode(id = "ep-7"), currentId = "ep-6")
        assertEquals(PlayLaunchDecision.NewTarget, d)
    }

    @Test fun `episode currentId pointing at series id (not episode id) returns NewTarget`() {
        // Defensive: _currentItem.id is the synthesized view's id which
        // == episode.id. If something earlier in the call stack stuffed
        // the series id into _currentItem.id by mistake, the check
        // should treat it as a different target (NOT SameTarget) — a
        // SameTarget verdict here would mean we never re-prepare the
        // player when the user actually wanted the episode after
        // having watched the series detail page.
        val d = episodeLaunchDecision(episode(id = "ep-7", seriesId = "series-7"), currentId = "series-7")
        assertEquals(PlayLaunchDecision.NewTarget, d)
    }

    // ───── Helpers ─────

    private fun movie(id: String = "movie-1", type: ContentType = ContentType.MOVIE, streamUrl: String = "http://example.test/$id.mp4") = ContentItem(
        id = id,
        sourceId = "src-1",
        type = type,
        title = "Test movie",
        streamUrl = streamUrl,
        sortOrder = 0,
        createdAt = 0L,
    )

    private fun episode(id: String = "ep-1", seriesId: String = "series-1", streamUrl: String = "http://example.test/$id.mp4") = Playable.Episode(
        id = id,
        seriesId = seriesId,
        title = "Test episode",
        streamUrl = streamUrl,
    )
}
