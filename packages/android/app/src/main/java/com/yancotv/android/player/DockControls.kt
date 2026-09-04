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
internal fun dockFocusOrder(shown: List<DockControl>): List<DockControl> = shown.filterNot { it == DockControl.DIVIDER }

/**
 * Convenience for the dock that shows everything.
 *
 * MK.38.2 — kept, but no longer the whole story: a narrow screen renders
 * [DockFit.shown] rather than the full order, so anything reasoning about where
 * the cursor actually goes must pass that list to the overload above. This one
 * answers "what would the focus order be if it all fitted", which is the right
 * question for the brief and the wrong one for a phone.
 */
internal fun dockFocusOrder(hasNext: Boolean, isLive: Boolean = false): List<DockControl> = dockFocusOrder(dockControlOrder(hasNext, isLive))

/**
 * MK.38.2 — what the dock shows, and what it hands to the ⋯ menu.
 *
 * ### The bug this closes
 *
 * `VodDockTransportRow` renders a plain `Row` and nothing has ever measured it.
 * Compose's `Row` does not wrap or scroll: once the incoming width is used up,
 * every remaining child is measured at **zero** and simply is not there. No
 * warning, no ellipsis, no clipped edge to notice — the control is gone.
 *
 * It is gone today, not hypothetically. Every one of the brief's sizes is a
 * fraction of a 1920 px television, so on a phone in landscape they all fall to
 * their floors, and the floor for a touched control is 48 dp:
 *
 * | Screen (landscape) | Locale | Row needs | Row has | Result |
 * |---|---|---|---|---|
 * | 731 dp | English | ~579 dp | 635 dp | fits |
 * | 731 dp | Spanish | ~610 dp | 635 dp | fits, barely |
 * | 640 dp | English | ~571 dp | 544 dp | **27 dp cut** |
 * | 640 dp | Spanish | ~601 dp | 544 dp | **57 dp cut** |
 *
 * Spanish is not an edge case, it is `VELOCIDAD` where English has `SPEED`.
 * And the row is ordered with ⋯ **last**, so the first thing a narrow screen
 * deletes is the menu — the one control that can reach everything else. The
 * dock does not degrade, it locks.
 *
 * ### The rule
 *
 * The owner's brief, from the iOS side of the same problem: a **More** button
 * that surfaces what does not fit, rather than hiding controls and hoping.
 * Android already has that button — ⋯ opens the options root, and the root
 * already holds Subtitles, Audio, Speed, Aspect and Favourites. So a dropped
 * secondary is not lost, it is one press away in the place it already lived.
 *
 * That makes the fix "pin ⋯ and drop around it", not "add a second ⋯". Two
 * three-dot buttons side by side would be the confusing version of this.
 *
 * [DROP_ORDER] is least-useful-first while a stream is playing. Transport and
 * ⋯ are absent from it and so can never be dropped: without transport there is
 * no player, and without ⋯ there is no way back to what was dropped.
 */
internal data class DockFit(
    /** Rendered in the row, in [dockControlOrder]'s order. */
    val shown: List<DockControl>,
    /**
     * Dropped for want of width, in [dockControlOrder]'s order — not in the
     * order they were dropped. This is what the ⋯ menu is standing in for.
     */
    val overflow: List<DockControl>,
)

/**
 * Dropped first to last. Favourite goes first because saving a title is the one
 * action here that keeps until playback ends; subtitles go last because a
 * viewer who needs them needs them now.
 */
private val DROP_ORDER = listOf(
    DockControl.FAVORITE,
    DockControl.ASPECT,
    DockControl.SPEED,
    DockControl.AUDIO,
    DockControl.SUBTITLES,
)

/**
 * Fit [order] into [availableDp], dropping by [DROP_ORDER] until it does.
 *
 * @param widthsDp each control's rendered width in dp. A control missing from
 *   the map counts as zero, which under-counts rather than throwing — a dock
 *   that renders slightly too wide beats a player that crashes.
 * @param gapDp the space between two adjacent controls; `n` controls have
 *   `n - 1` of them, which is the part an eyeballed calculation gets wrong.
 *
 * If even the pinned controls do not fit, they are returned anyway: there is no
 * useful smaller dock, and clipping transport is at least a visible failure.
 */
internal fun fitDockControls(order: List<DockControl>, widthsDp: Map<DockControl, Float>, gapDp: Float, availableDp: Float): DockFit {
    fun measure(list: List<DockControl>): Float = if (list.isEmpty()) {
        0f
    } else {
        list.fold(0f) { acc, c -> acc + (widthsDp[c] ?: 0f) } + gapDp * (list.size - 1)
    }

    val shown = order.toMutableList()
    val dropped = mutableSetOf<DockControl>()
    for (candidate in DROP_ORDER) {
        if (measure(shown) <= availableDp) break
        if (shown.remove(candidate)) dropped += candidate
    }

    // The divider separates the transport cluster from the secondary one. With
    // every secondary gone it stands between a cluster and nothing, so it is
    // painted noise taking width the transport controls could use.
    if (dropped.isNotEmpty() && shown.none { it in DROP_ORDER }) {
        shown.remove(DockControl.DIVIDER)
    }

    return DockFit(shown = shown, overflow = order.filter { it in dropped })
}
