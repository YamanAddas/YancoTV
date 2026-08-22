package com.yancotv.android.player

/**
 * MB-344 — when the autoplay-advance guard may be released.
 *
 * `PlayerActivity.autoplayInFlight` is raised before an automatic (or dock-NEXT)
 * advance and blocks a second advance while the next item is preparing. It
 * exists because ExoPlayer occasionally double-fires `STATE_ENDED` in that
 * window, and without it the player would skip an episode.
 *
 * The bug was not the guard, it was that **release was scattered across call
 * sites and only covered the happy path.** It came down on the new item's
 * `STATE_READY` and on the no-next-episode bail-out — nothing else. A target
 * that never prepared therefore never released it, and since the flag lives for
 * the life of the Activity, every later end-of-episode silently declined to
 * advance. To the user, autoplay simply stopped working until they left the
 * player entirely; nothing on screen said why.
 *
 * Centralising the policy here is the actual fix. A boolean written from four
 * places is a rule nobody can read, and adding a fifth writer (MB-343's dock
 * NEXT) is what made the gap obvious.
 *
 * The distinction that matters is **terminal vs recoverable**, not
 * error-vs-success. `onPlayerError` has two recoverable branches —
 * BEHIND_LIVE_WINDOW re-seeks, a transient network error retries — that
 * deliberately re-prepare and are expected to reach READY. Releasing the guard
 * there would drop it while a prepare is still in flight, reopening the exact
 * double-ENDED hole the guard defends. If those retries fail they fall through
 * to the terminal path anyway, so nothing is lost by waiting.
 */
enum class AutoplayGuardEvent {
    /** The advance succeeded and the new item is playing. */
    NEW_ITEM_READY,

    /** A terminal playback error — the stream-error overlay is being shown. */
    TERMINAL_ERROR,

    /** `controller.play(...)` threw, so no MediaItem was ever prepared. */
    ADVANCE_THREW,

    /** There was nothing to advance to; no prepare will happen. */
    NO_NEXT_EPISODE,

    /** A recoverable error that re-prepares (live-window re-seek, network retry). */
    RECOVERABLE_ERROR,

    /** The new item is still loading — the guard is doing its job. */
    STILL_PREPARING,
}

/**
 * True when [event] means no prepare is in flight any more, so a future
 * end-of-episode is allowed to advance again.
 *
 * The rule in one line: **release once nothing further is expected to reach
 * `STATE_READY`.**
 */
fun shouldReleaseAutoplayGuard(event: AutoplayGuardEvent): Boolean = when (event) {
    AutoplayGuardEvent.NEW_ITEM_READY,
    AutoplayGuardEvent.TERMINAL_ERROR,
    AutoplayGuardEvent.ADVANCE_THREW,
    AutoplayGuardEvent.NO_NEXT_EPISODE,
    -> true

    AutoplayGuardEvent.RECOVERABLE_ERROR,
    AutoplayGuardEvent.STILL_PREPARING,
    -> false
}
