package com.yancotv.android.ui.shell

import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType

/**
 * Browse-shell helpers — pure Kotlin functions and constants shared by
 * [BrowseSection], [CoverflowSectionScreen], [CategoryChipBar], and
 * [FavoritesScreen]. All routing logic, filter logic, and group sorting
 * lives here so it can be unit-tested without spinning up Compose.
 *
 * Historical note: this file used to also host the `BrowseShell`
 * composable orchestrator (the old MK.7 shell). MK.8 superseded it with
 * the coverflow / category-pill / sidebar split (`CoverflowSectionScreen`
 * + `CategoryRail` + `BrowseSection`). The composable was removed
 * 2026-04-25; only its pure helpers stayed because the new screens use
 * them too. Filename retained to keep git history; rename to
 * `BrowseHelpers.kt` is fine in a follow-up if anyone wants it.
 */

/**
 * Debounce before the rail's focused LIVE card commits to actually
 * starting its stream in the hero MiniPlayer. Short enough that a
 * user who *settles* on a channel sees the preview come up almost
 * immediately, long enough that arrow-key scrolling past 6 channels
 * doesn't churn 6 ExoPlayer prepare() calls in a row.
 */
internal const val AUTO_PREVIEW_DEBOUNCE_MS = 400L

/** Synthetic chip id for the default "All" filter. */
const val ALL_GROUPS = "__all__"

/**
 * Synthetic chip id for the pinned "Favorites" filter. Selecting it swaps
 * the content list's data source from the paged `content` query to
 * `FavoritesRepository.allFlow` for the current type.
 */
const val FAVORITES_GROUP = "__favorites__"

/**
 * Translate a chip id into the SQL `group_name` filter argument used by
 * `ContentRepository.page`. The two synthetic chips return null (no group
 * filter); a real group name passes through verbatim.
 */
internal fun resolveGroupFilter(group: String): String? = group.takeIf { it != ALL_GROUPS && it != FAVORITES_GROUP }

/** True when the user has the synthetic "Favorites" chip active. */
internal fun isFavoritesFilter(group: String): Boolean = group == FAVORITES_GROUP

/**
 * Filter the backing group list against the user's hidden-groups set. The
 * chip bar renders whatever this returns, preserving original order.
 */
internal fun visibleGroupsFor(all: List<String>, hidden: Set<String>): List<String> = if (hidden.isEmpty()) all else all.filter { it !in hidden }

/**
 * Apply parental-control filters to a catalogue list. `hiddenIds` drops
 * specific rows the user has hidden; `hideAdult` layers the adult-content
 * heuristic on top. Kept pure so the rail / hero index math works off the
 * same filtered list the rail actually renders.
 */
internal fun applyParentalFilters(items: List<ContentItem>, hiddenIds: Set<String>, hideAdult: Boolean): List<ContentItem> {
    var result = items
    if (hiddenIds.isNotEmpty()) result = result.filterNot { it.id in hiddenIds }
    if (hideAdult) result = result.filterNot(com.yancotv.shared.parental.AdultContentFilter::isAdult)
    return result
}

/**
 * Decide whether the focused LIVE card should auto-start its preview
 * stream. Returns the index into [visible] that the rail should commit
 * to, or null to skip (type isn't LIVE, nothing focused, card is
 * parental-locked, stream is already playing, or the focused id is no
 * longer in the visible list).
 *
 * Pure by design so the auto-preview's pre- and post-debounce checks
 * can share a single decision site and unit tests can exhaustively
 * cover the skip branches.
 */
internal fun resolveAutoPreviewIndex(
    type: ContentType,
    focusedId: String?,
    visible: List<ContentItem>,
    lockedIds: Set<String>,
    currentlyPlayingId: String?,
): Int? {
    if (type != ContentType.LIVE) return null
    if (focusedId == null) return null
    if (focusedId in lockedIds) return null
    if (currentlyPlayingId == focusedId) return null
    val idx = visible.indexOfFirst { it.id == focusedId }
    return if (idx >= 0) idx else null
}

/**
 * Pick the initial focus index when a catalogue finishes loading.
 * Prefers the currently-playing item (so returning to LiveTv while a
 * channel is running in the MiniPlayer lands focus on that channel
 * rather than zapping to items[0] and starting a fresh stream),
 * otherwise falls back to the saved index (clamped to [0, size-1]).
 * Returns -1 for an empty list — the caller should treat that as
 * "no focus yet" and leave focusedItem null.
 */
internal fun initialFocusIndex(items: List<ContentItem>, savedIndex: Int, currentlyPlayingId: String?): Int {
    if (items.isEmpty()) return -1
    if (currentlyPlayingId != null) {
        val playingIdx = items.indexOfFirst { it.id == currentlyPlayingId }
        if (playingIdx >= 0) return playingIdx
    }
    return savedIndex.coerceIn(0, items.size - 1)
}

internal fun heroPlaybackForFocused(focused: ContentItem?, playing: ContentItem?): ContentItem? = playing?.takeIf { focused?.id == it.id }

/**
 * Describes what the app should do when the user activates a content row.
 *
 * @param shouldCallPlay true → call `controller.play(list, index)`.
 *   False when the target is already loaded — calling play() again would
 *   re-prepare the ExoPlayer, rebuffer, and stutter the stream.
 * @param shouldLaunchFullscreen true → open [com.yancotv.android.player.PlayerLauncher].
 *   On TV a first-tap only starts loading (shouldLaunchFullscreen = false);
 *   the second tap (or a phone tap) opens fullscreen.
 */
internal data class ActivationAction(val shouldCallPlay: Boolean, val shouldLaunchFullscreen: Boolean)

/**
 * Pure routing helper for the two-tap TV activation pattern. Extracted
 * so call sites in [com.yancotv.android.ui.shell.FavoritesScreen],
 * [CoverflowSectionScreen], and future screens can share the same logic
 * without duplicating the inline `alreadyPlaying` conditional and so the
 * contract can be unit-tested.
 *
 * Rule (from native-android-mk skill):
 *   "Every launch site checks `controller.currentId == target.id` first.
 *   If already playing, go straight to fullscreen — do NOT call
 *   controller.play() again (re-creates the MediaItem, rebuffers)."
 */
internal fun resolveActivation(currentId: String?, targetId: String, isTv: Boolean): ActivationAction {
    val alreadyPlaying = currentId == targetId
    return ActivationAction(
        // Guard: skip play() when the item is already loaded.
        shouldCallPlay = !alreadyPlaying,
        // TV: first tap → load only. Second tap (alreadyPlaying) → fullscreen.
        // Phone: always launch fullscreen (single-tap UX).
        shouldLaunchFullscreen = !isTv || alreadyPlaying,
    )
}
