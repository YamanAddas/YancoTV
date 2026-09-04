package com.yancotv.android.player

/**
 * MK.34.10 — the dock's control order, as data rather than as composition order.
 *
 * The brief specifies an exact sequence, and until now the only place that
 * sequence existed was the order the calls happen to appear in inside
 * `VodDockTransportRow`. Nothing could assert it, and a reorder during a
 * refactor would have been silent — the dock would still build, still focus,
 * still work, and simply be wrong.
 *
 * Making it a list means the focus order the brief cares about is the same
 * object a test can read. The row renders by walking this list, so the test and
 * the screen cannot disagree.
 */
internal enum class DockControl {
    SKIP_BACK,
    PLAY_PAUSE,
    SKIP_FORWARD,

    /**
     * MB-343's next-episode control. Present only when a next episode is
     * actually resolved — see [dockControlOrder].
     */
    NEXT,

    /** The brief's "subtle vertical divider" between the two clusters. */
    DIVIDER,

    SUBTITLES,
    AUDIO,
    SPEED,
    ASPECT,
    FAVORITE,
    MENU,
}

/**
 * The brief's control order:
 *
 *     -10, play/pause, +10, [next], divider, CC, AUDIO, SPEED, FIT, heart, ...
 *
 * [hasNext] gates only [DockControl.NEXT]. There is deliberately no PREVIOUS:
 * the reference dock has neither direction control, and "remove any stray
 * comma/apostrophe button" described the old ‹ and › glyphs exactly. ‹ lost
 * nothing when it went — `play(episode)` synthesises a one-item queue, so it was
 * already dead for every episode — while › is MB-343's control, shipped and
 * device-verified, and the brief separately forbids breaking existing playback
 * actions. That conflict was resolved by the user (2026-08-19): keep ›, drop ‹.
 */
internal fun dockControlOrder(hasNext: Boolean, isLive: Boolean = false): List<DockControl> = buildList {
    add(DockControl.SKIP_BACK)
    add(DockControl.PLAY_PAUSE)
    add(DockControl.SKIP_FORWARD)
    // MK.38 — NEXT is the next EPISODE, which a channel does not have. Every
    // other control means the same thing on live as it does on a film, which is
    // why live now gets this dock rather than Media3's default one: the surface
    // was never VOD-specific, only this one button was.
    //
    // -10 / +10 stay. They are not decoration on live: the player keeps a
    // back-buffer, so rewinding inside it is real, and a provider stream that
    // refuses the seek leaves the position where it was rather than breaking.
    if (hasNext && !isLive) add(DockControl.NEXT)
    add(DockControl.DIVIDER)
    add(DockControl.SUBTITLES)
    add(DockControl.AUDIO)
    add(DockControl.SPEED)
    add(DockControl.ASPECT)
    add(DockControl.FAVORITE)
    add(DockControl.MENU)
}

/**
 * The controls a D-pad actually lands on, in order.
 *
 * The divider is a painted separator, not a focus target, so a focus-order
 * assertion must exclude it — otherwise the test would encode a cursor stop
 * that does not exist and would "pass" a dock where RIGHT skipped a control.
 */
internal fun dockFocusOrder(hasNext: Boolean, isLive: Boolean = false): List<DockControl> =
    dockControlOrder(hasNext, isLive).filterNot { it == DockControl.DIVIDER }
