package com.yancotv.android.ui.shell

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.android.ui.parental.ChannelActionsMenu
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.android.ui.theme.YancoType
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.NowNext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private const val PAGE_SIZE = 100L
private const val PREFETCH_THRESHOLD = 20
private const val EPG_TICK_MS = 60_000L

/**
 * Hard cap on how many [ContentItem]s the rail keeps in its
 * SnapshotStateList. Beyond this we stop paginating — long sessions on a
 * 5000-item Movies catalog otherwise pile up the full list of refs +
 * Compose snapshot records, which compounds with the EPG / focus / hero
 * recompositions the rail already runs. Users with catalogs larger than
 * this are expected to narrow with category chips or search; this is
 * the same UX trade desktop Live TV has used since v0.1.
 */
private const val MAX_ITEMS_IN_MEMORY = 1000

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
internal fun visibleGroupsFor(
    all: List<String>,
    hidden: Set<String>,
): List<String> = if (hidden.isEmpty()) all else all.filter { it !in hidden }

/**
 * Concept A category-priority sort: float Arabic + English (and their
 * localised variants) to the front so the chip bar opens on the user's
 * most-likely first picks, then preserve playlist declaration order for
 * the rest. Pure / stable so the chip-bar scroll position stays sane.
 */
internal fun prioritizedGroupsFor(visible: List<String>): List<String> {
    if (visible.isEmpty()) return visible
    val priority: (String) -> Int = { g ->
        val lower = g.lowercase()
        when {
            lower.contains("arabic") || lower.contains("عربي") -> 0
            lower.contains("english") || lower.contains(" uk") || lower.contains(" usa") || lower.contains(" us ") -> 1
            else -> 2
        }
    }
    return visible
        .withIndex()
        .sortedWith(compareBy({ priority(it.value) }, { it.index }))
        .map { it.value }
}

/**
 * Apply parental-control filters to a catalogue list. `hiddenIds` drops
 * specific rows the user has hidden; `hideAdult` layers the adult-content
 * heuristic on top. Kept pure so the rail / hero index math works off the
 * same filtered list the rail actually renders.
 */
internal fun applyParentalFilters(
    items: List<ContentItem>,
    hiddenIds: Set<String>,
    hideAdult: Boolean,
): List<ContentItem> {
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
internal fun initialFocusIndex(
    items: List<ContentItem>,
    savedIndex: Int,
    currentlyPlayingId: String?,
): Int {
    if (items.isEmpty()) return -1
    if (currentlyPlayingId != null) {
        val playingIdx = items.indexOfFirst { it.id == currentlyPlayingId }
        if (playingIdx >= 0) return playingIdx
    }
    return savedIndex.coerceIn(0, items.size - 1)
}

internal fun heroPlaybackForFocused(
    focused: ContentItem?,
    playing: ContentItem?,
): ContentItem? = playing?.takeIf { focused?.id == it.id }

/**
 * Browse orchestrator for Live / Movies / Series. Composes the three pieces
 * of the new shell — category chips, a cinematic feature hero driven by the
 * focused rail card, and the horizontal content rail — into a single column
 * on the right of the app sidebar.
 *
 * Responsibilities here:
 *   - paged catalogue load + prefetch as the user nears the rail tail
 *   - group list load + filtered chip selection (hidden groups applied)
 *   - parental hide / lock filters on the rendered rail
 *   - EPG now/next batch lookups for visible live cards
 *   - one "focused" [ContentItem] driving the hero's backdrop + copy
 *   - long-press → channel actions menu
 *
 * Data loading is kept identical in behaviour to the old ContentArea — same
 * page size, same prefetch threshold, same EPG tick — so existing provider
 * throughput and battery characteristics carry over. Only the presentation
 * has changed.
 */
@OptIn(ExperimentalComposeUiApi::class)
@UnstableApi
@Composable
fun BrowseShell(
    type: ContentType,
    repo: ContentRepository,
    onActivate: (List<ContentItem>, Int) -> Unit,
    onChipsFocusChanged: (Boolean) -> Unit,
    onRailFocusChanged: (Boolean) -> Unit,
    entryFocus: FocusRequester,
    onExitToSidebar: () -> Unit,
    restoreFocusOnWindowRegain: Boolean,
    controller: PlaybackController = koinInject(),
    epg: EpgRepository = koinInject(),
    favorites: FavoritesRepository = koinInject(),
    parental: ParentalRepository = koinInject(),
    history: WatchHistoryRepository = koinInject(),
    sources: SourceRepository = koinInject(),
    prefs: AppPreferences = koinInject(),
    modifier: Modifier = Modifier,
) {
    // Declared first: both the stop LaunchedEffect(type, group) and the
    // window-focus restore effect reference this anchor. Must precede any
    // LaunchedEffect that calls firstItemAnchor.reset() / awaitAndRequest().
    val firstItemAnchor = rememberPlacedFocusAnchor()

    // Catalogue state — rail data source depends on whether the user has
    // the synthetic "Favorites" chip active.
    val groupsState = remember(type) { mutableStateListOf<String>() }
    val items = remember(type) { mutableStateListOf<ContentItem>() }
    var total by remember(type) { mutableStateOf(0L) }
    var loaded by remember(type) { mutableStateOf(0L) }
    var loading by remember(type) { mutableStateOf(false) }

    // Group load — drives the chip bar.
    LaunchedEffect(type) {
        val loadedGroups =
            withContext(Dispatchers.IO) {
                runCatching { repo.groups(type) }
                    .onFailure { Log.w("Yanco", "BrowseShell.groups($type) failed: ${it.message}", it) }
                    .getOrElse { emptyList() }
            }
        groupsState.clear()
        groupsState.addAll(loadedGroups)
    }

    val hiddenGroups by prefs.hiddenGroupsFlow.collectAsState()
    val visibleGroups =
        remember(groupsState.toList(), hiddenGroups) {
            prioritizedGroupsFor(visibleGroupsFor(groupsState.toList(), hiddenGroups))
        }

    // Chip selection — persisted per section via rememberSaveable. If the
    // saved selection goes hidden we snap back to All rather than stranding
    // the user on an invisible filter.
    var group by rememberSaveable(type) { mutableStateOf(ALL_GROUPS) }
    LaunchedEffect(hiddenGroups) {
        if (group != ALL_GROUPS && group != FAVORITES_GROUP && group in hiddenGroups) {
            group = ALL_GROUPS
        }
    }
    val groupFilter = resolveGroupFilter(group)
    // MB-96: suppress empty-state until the first page finishes loading.
    var hasLoaded by remember(type, group) { mutableStateOf(false) }
    val isFavoritesFilter = isFavoritesFilter(group)

    // Stop any active playback immediately when the visible catalogue changes
    // (section/type switch or group chip change). Prevents audio from a
    // previous preview bleeding into the new category while items reload and
    // the auto-preview debounce hasn't fired yet. Fires for both the paged
    // and favorites catalogue branches because it is outside the if/else.
    //
    // Also resets firstItemAnchor so awaitAndRequest() waits for the new
    // card's onPlaced instead of firing against a stale isPlaced=true from
    // the previous group — MB-67.
    LaunchedEffect(type, group) {
        if (controller.currentId != null) controller.stop()
        firstItemAnchor.reset()
    }

    val lockedIds by parental.lockedIds.collectAsState()
    val hiddenIds by parental.hiddenIds.collectAsState()
    val parentalSettings by parental.settings.collectAsState()
    var actionsFor by remember { mutableStateOf<ContentItem?>(null) }

    // Catalogue load — two flavours. Favorites is a reactive flow from the
    // favorites repo so star toggles update the rail live; paged load for
    // everything else.
    if (isFavoritesFilter) {
        LaunchedEffect(type) {
            favorites.allFlow().collect { list ->
                val filtered = list.map { it.content }.filter { it.type == type }
                items.clear()
                items.addAll(filtered)
                total = filtered.size.toLong()
                loaded = filtered.size.toLong()
                hasLoaded = true
            }
        }
    } else {
        LaunchedEffect(type, group) {
            items.clear()
            hasLoaded = false
            total =
                withContext(Dispatchers.IO) {
                    runCatching { repo.count(type, groupFilter) }
                        .onFailure { Log.w("Yanco", "BrowseShell.count($type) failed: ${it.message}", it) }
                        .getOrElse { 0L }
                }
            loaded = 0L
            val first =
                withContext(Dispatchers.IO) {
                    runCatching { repo.page(type, groupFilter, 0L, PAGE_SIZE) }
                        .onFailure { Log.w("Yanco", "BrowseShell.page($type, first) failed: ${it.message}", it) }
                        .getOrElse { emptyList() }
                }
            items.addAll(first)
            loaded += first.size
            hasLoaded = true
        }
    }

    // Focused-item state — single source of truth for what the hero
    // previews. When items reload we snap to index 0 so the hero reflects
    // the new first card immediately (otherwise it would keep painting a
    // stale previous-category item until the user moves focus).
    val focusKey = "browse-focus|${type.name}|$group"
    var focusedIndex by rememberSaveable(focusKey) { mutableStateOf(0) }
    var focusedItem by remember(type, group) { mutableStateOf<ContentItem?>(null) }
    LaunchedEffect(items.size) {
        if (items.isEmpty()) {
            focusedItem = null
            return@LaunchedEffect
        }
        // Use the parental-filtered list so focusedItem is never set to a
        // hidden item and the hero never briefly paints a hidden card.
        val visibleNow = applyParentalFilters(items.toList(), hiddenIds, parentalSettings.hideAdultContent)
        // Compare by id (not reference) — items list reallocates from DB
        // after group/type changes, so the old ContentItem reference won't
        // be `===` to any new object even when the same channel is present.
        if (focusedItem == null || visibleNow.none { it.id == focusedItem?.id }) {
            // Prefer the currently-playing item (paused or active) so
            // returning to Live TV while a stream is running doesn't zap
            // focus to items[0] and restart a different stream.
            val idx = initialFocusIndex(visibleNow, focusedIndex, controller.currentItem.value?.id)
            if (idx >= 0) {
                focusedIndex = idx
                focusedItem = visibleNow[idx]
            }
        }
    }

    // Source-name lookup for the focused card (surfaces as a hero meta chip
    // for movies/series). Runs off-main so we don't block the frame the
    // hero recomposes on focus change.
    var sourceName by remember(focusedItem?.sourceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedItem?.sourceId) {
        val sid = focusedItem?.sourceId
        sourceName =
            if (sid.isNullOrBlank()) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { sources.getById(sid)?.name }.getOrNull()
                }
            }
    }

    // Now/Next for live channels — keep it responsive so the hero reflects
    // the right program even as the user scrolls past a channel.
    var nowNextMap by remember(type) { mutableStateOf<Map<String, NowNext>>(emptyMap()) }
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    if (type == ContentType.LIVE) {
        // MB-95: key only on type (not items.size) so this effect doesn't restart
        // on every paginated page load. The snapshotFlow observes items directly
        // and distinctUntilChanged suppresses duplicate EPG fetches.
        LaunchedEffect(type) {
            snapshotFlow { items.map { it.tvgId }.filterNotNull().distinct() }
                .distinctUntilChanged()
                .collect { tvgIds ->
                    if (tvgIds.isEmpty()) return@collect
                    val ids = tvgIds.take(60) // cap the batch — no reason to fetch >60 at once
                    nowNextMap =
                        withContext(Dispatchers.IO) {
                            runCatching { epg.getNowNextBatch(ids) }
                                .onFailure { Log.w("Yanco", "BrowseShell.getNowNextBatch failed: ${it.message}", it) }
                                .getOrElse { nowNextMap }
                        }
                }
        }
        LaunchedEffect(type) {
            while (true) {
                delay(EPG_TICK_MS)
                if (!restoreFocusOnWindowRegain) continue // overlay is up — skip tick
                nowSeconds = System.currentTimeMillis() / 1000L
                val ids =
                    items
                        .mapNotNull { it.tvgId?.takeIf { id -> id.isNotBlank() } }
                        .distinct()
                        .take(60)
                if (ids.isNotEmpty()) {
                    nowNextMap =
                        withContext(Dispatchers.IO) {
                            runCatching { epg.getNowNextBatch(ids) }
                                .onFailure { Log.w("Yanco", "BrowseShell.getNowNextBatch tick failed: ${it.message}", it) }
                                .getOrElse { nowNextMap }
                        }
                }
            }
        }
    }

    // Favorite state for the hero's secondary CTA. Reactive so toggling
    // elsewhere (e.g. long-press menu) updates the hero without a focus
    // round-trip.
    var isFav by remember(focusedItem?.id) { mutableStateOf(false) }
    LaunchedEffect(focusedItem?.id) {
        val id =
            focusedItem?.id ?: run {
                isFav = false
                return@LaunchedEffect
            }
        favorites.isFavoriteFlow(id).collect { isFav = it }
    }
    val scope = rememberCoroutineScope()

    // Visible item list after parental filters — rail + hero only see
    // these. Index math for activation must be done against this filtered
    // list, not the raw `items`, otherwise lockedIds → hidden rows would
    // shift the activation target.
    //
    // MB-97: derivedStateOf tracks only items/hiddenIds/hideAdultContent as
    // snapshot dependencies, so EPG ticks, nowSeconds updates, and other
    // unrelated state changes don't re-run this O(n) filter.
    //
    // Keyed on `type` because `items` is `remember(type) { ... }` — when the
    // user switches Live → Movies a NEW SnapshotStateList is allocated and
    // the derivedStateOf lambda must rebind to it, otherwise it keeps
    // observing the stale previous-type list and the rail shows old data
    // under new category chips.
    val visible by remember(type) {
        derivedStateOf {
            applyParentalFilters(
                items = items,
                hiddenIds = hiddenIds,
                hideAdult = parentalSettings.hideAdultContent,
            )
        }
    }

    // firstItemAnchor is declared at the top of this function — it must
    // precede LaunchedEffect(type, group) which calls anchor.reset().
    // The anchor is stable (never re-keyed) so the requester it holds is
    // never disposed while the BrowseShell is in composition. MB-67.

    // HomeScreen owns entryFocus and requests it on section/detail changes.
    // Attach that requester directly to the selected chip; wiring it to a
    // non-focusable wrapper can silently no-op and leave the selector dark.
    val selectedChipFocus = entryFocus

    // Which zone owns focus right now — the chip bar or the rail. Drives
    // the hierarchical BackHandler chain: rail → chips → (reset group) →
    // sidebar. Both values can be false briefly (during transitions) which
    // disables the handlers — good, the system BACK then falls through to
    // whatever HomeScreen has registered.
    var chipsHasFocus by remember { mutableStateOf(false) }
    var railHasFocus by remember { mutableStateOf(false) }

    // Tail-prefetch for paged catalogues. Triggered on focus position (not
    // scroll position) so D-pad users who over-shoot via OK's restore don't
    // sit on a loading tail longer than needed.
    LaunchedEffect(focusedIndex, total, loaded, isFavoritesFilter) {
        if (isFavoritesFilter) return@LaunchedEffect
        if (loading) return@LaunchedEffect
        if (loaded >= total) return@LaunchedEffect
        // Memory cap — see MAX_ITEMS_IN_MEMORY doc.
        if (items.size >= MAX_ITEMS_IN_MEMORY) return@LaunchedEffect
        if (focusedIndex < (loaded - PREFETCH_THRESHOLD)) return@LaunchedEffect
        delay(100L) // debounce — cancelled if focus moves again before it fires
        if (loading) return@LaunchedEffect
        loading = true
        val page =
            withContext(Dispatchers.IO) {
                runCatching { repo.page(type, groupFilter, loaded, PAGE_SIZE) }
                    .onFailure { Log.w("Yanco", "BrowseShell.page($type, $loaded) failed: ${it.message}", it) }
                    .getOrElse { emptyList() }
            }
        items.addAll(page)
        loaded += page.size
        loading = false
    }

    val playing by controller.currentItem.collectAsState()
    val heroPlaying = heroPlaybackForFocused(focusedItem, playing)

    // Auto-preview on focus for LIVE channels. Moving the rail focus to a
    // channel card implicitly starts its stream in the hero MiniPlayer —
    // pressing OK goes straight to fullscreen (see HomeScreen.onBrowseActivate
    // LIVE branch). The AUTO_PREVIEW_DEBOUNCE_MS delay prevents scroll-past
    // channels from churning N prepare() calls in a row; only the channel
    // the user actually settles on commits to a stream. Locked channels are
    // skipped — the PIN gate fires through onActivate instead, so a locked
    // row never opens a stream silently in the background. Movies/series
    // don't auto-preview (they're files, not broadcasts — scrubbing past
    // would be disruptive).
    //
    // The resolver runs twice: once as an early cheap bail-out to avoid
    // scheduling delay() work for ineligible cards, and again post-delay so
    // state that drifted during the 400ms window (user tapped OK mid-delay,
    // parental lock landed, filter changed) cleanly aborts the preview.
    LaunchedEffect(focusedItem?.id) {
        resolveAutoPreviewIndex(
            type = type,
            focusedId = focusedItem?.id,
            visible = visible,
            lockedIds = lockedIds,
            currentlyPlayingId = controller.currentId,
        ) ?: return@LaunchedEffect
        delay(AUTO_PREVIEW_DEBOUNCE_MS)
        val snapshot = visible.toList()
        val idx =
            resolveAutoPreviewIndex(
                type = type,
                focusedId = focusedItem?.id,
                visible = snapshot,
                lockedIds = lockedIds,
                currentlyPlayingId = controller.currentId,
            ) ?: return@LaunchedEffect
        controller.play(snapshot, idx)
    }

    // Post-fullscreen focus restoration. When PlayerActivity claims window
    // focus and then releases it, Compose's MutableInteractionSource nodes go
    // stale: the focused card still reports isFocused=true internally but no
    // new Focus event is emitted, so the visual rim stays dark and the card's
    // LaunchedEffect(focused){onFocus()} never re-fires to sync focusedItem.
    //
    // The previous fix in HomeScreen called mainContentFocus.requestFocus()
    // on a Box that has .focusGroup() — which sets canFocus=false. A
    // requestFocus() on a canFocus=false node does NOT propagate down into
    // a real focusable leaf; it silently no-ops. The user still had to
    // press a key to light up any card.
    //
    // Fix: use firstItemAnchor.awaitAndRequest() — waits for the card's
    // onPlaced hook before calling requestFocus(), so the request is
    // deterministic even when the WheelRow recomposes immediately on
    // window regain (controller state + EPG tick). MB-67.
    val windowInfo = LocalWindowInfo.current
    // `restoreFocusOnWindowRegain` flips false while an overlay (detail,
    // search) is live — without this gate BrowseShell would steal focus
    // out of the overlay when PlayerActivity finishes, and the user'd
    // land on a rail card instead of the detail Play button.
    val canRestore by rememberUpdatedState(restoreFocusOnWindowRegain)
    LaunchedEffect(Unit) {
        var seenUnfocused = false
        snapshotFlow { windowInfo.isWindowFocused }.collect { windowFocused ->
            if (!windowFocused) {
                seenUnfocused = true
            } else if (seenUnfocused) {
                seenUnfocused = false
                if (!canRestore) return@collect
                // MB-67: awaitAndRequest() waits for the focused card's onPlaced
                // hook before calling requestFocus — deterministic, no delay-ladder.
                firstItemAnchor.awaitAndRequest()
            }
        }
    }

    // Hierarchical BACK chain inside the browse shell. Each handler is
    // guarded by the zone that currently owns focus so only one fires at
    // a time:
    //   rail focused                      → move focus up to the chip bar
    //   chips focused on a non-All group  → reset to "All" (selector snaps
    //                                        to the All chip via selectedChipFocus)
    //   chips focused on "All" / favorites → leave the shell entirely,
    //                                        handing focus back to the sidebar
    // When neither zone has focus (sidebar has it, or an overlay is up),
    // all handlers are disabled and BACK falls through to HomeScreen /
    // system default. The handlers register LIFO so the later ones — the
    // chip-level escapes — take precedence over the rail handler when
    // their conditions are true; that's fine because chipsHasFocus and
    // railHasFocus are mutually exclusive.
    BackHandler(enabled = railHasFocus) {
        runCatching { selectedChipFocus.requestFocus() }
    }
    val scopeBack = rememberCoroutineScope()
    BackHandler(enabled = chipsHasFocus && group != ALL_GROUPS) {
        group = ALL_GROUPS
        focusedIndex = 0
        // Re-assert focus on the "All" chip after the chip bar recomposes
        // with the new selection (focusRequester is attached to whichever
        // chip has `isSelected = true`). The small delay lets the new node
        // finish onPlaced before we call requestFocus on it.
        scopeBack.launch {
            for (delayMs in longArrayOf(40L, 120L, 280L)) {
                delay(delayMs)
                if (runCatching { selectedChipFocus.requestFocus() }.isSuccess) break
            }
        }
    }
    BackHandler(enabled = chipsHasFocus && group == ALL_GROUPS) {
        onExitToSidebar()
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Top: categories. Thin and airy — dominating filter UI is the
        // old shell's sin. `entryFocus` is attached to the selected chip so
        // the sidebar's forward-from-section handoff lands on a real leaf
        // (hierarchical forward: sidebar → chips → rail → detail → player).
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        val has = it.hasFocus
                        chipsHasFocus = has
                        if (has) onChipsFocusChanged(true)
                    },
        ) {
            CategoryChipBar(
                groups = visibleGroups,
                selected = group,
                onSelect = { picked ->
                    group = picked
                    focusedIndex = 0
                },
                externalSelectedFocus = selectedChipFocus,
            )
        }

        // Middle: the feature hero takes the majority of the vertical
        // space. weight(1f) so it flexes with screen height and the rail
        // keeps its fixed ~230dp footprint.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        ) {
            FeatureHero(
                focused = focusedItem,
                playing = heroPlaying,
                nowNext = focusedItem?.tvgId?.let { nowNextMap[it] },
                nowSeconds = nowSeconds,
                sourceName = sourceName,
                isFavorite = isFav,
                isLocked = focusedItem?.let { it.id in lockedIds } == true,
                controller = controller,
                onPlay = {
                    val item = focusedItem ?: return@FeatureHero
                    val idx = visible.indexOfFirst { it.id == item.id }
                    if (idx >= 0) onActivate(visible.toList(), idx)
                },
                onToggleFavorite = {
                    val item = focusedItem ?: return@FeatureHero
                    val optimistic = !isFav
                    isFav = optimistic
                    scope.launch {
                        val newState =
                            withContext(Dispatchers.IO) {
                                runCatching { favorites.toggle(item.id) }
                                    .onFailure { Log.w("Yanco", "BrowseShell.favorites.toggle(${item.id}) failed: ${it.message}", it) }
                                    .getOrElse { !optimistic }
                            }
                        if (newState != optimistic) isFav = newState
                    }
                },
            )
        }

        // Bottom: rail of cards. Fixed height so the hero always has room.
        // Live TV cards are 120dp tall + chrome; posters are 124dp + title
        // line — 230dp accommodates both with focusStyle's scale lift.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .focusGroup()
                    .onFocusChanged {
                        val has = it.hasFocus
                        railHasFocus = has
                        if (has) onRailFocusChanged(true)
                    },
        ) {
            when {
                visible.isNotEmpty() ->
                    ContentRail(
                        type = type,
                        items = visible,
                        nowNextMap = nowNextMap,
                        nowSeconds = nowSeconds,
                        lockedIds = lockedIds,
                        focusedIndex = focusedIndex.coerceIn(0, (visible.size - 1).coerceAtLeast(0)),
                        firstItemAnchor = firstItemAnchor,
                        onFocus = { index, item ->
                            focusedIndex = index
                            focusedItem = item
                        },
                        onActivate = { index -> onActivate(visible.toList(), index) },
                        onLongPress = { actionsFor = it },
                    )
                // MB-96: only show the true empty state after the first page
                // has loaded — suppresses the false "no videos" flash on entry.
                hasLoaded -> BrowseEmptyState(type = type, favoritesFilter = isFavoritesFilter)
            }
        }
    }

    actionsFor?.let { item ->
        ChannelActionsMenu(
            item = item,
            repo = parental,
            onDismiss = {
                actionsFor = null
                scope.launch { firstItemAnchor.awaitAndRequest() }
            },
        )
    }
}

@Composable
private fun BrowseEmptyState(
    type: ContentType,
    favoritesFilter: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(Space.page),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(Modifier.height(Space.xl))
        Text(
            text =
                when {
                    favoritesFilter -> "No favorites yet"
                    type == ContentType.LIVE -> "No channels"
                    type == ContentType.MOVIE -> "No movies"
                    else -> "No series"
                },
            color = YancoPalette.TextPrimary,
            style = YancoType.TitleL,
        )
        Text(
            text =
                when {
                    favoritesFilter -> "Star something from the hero and it'll land here."
                    else -> "Add a source in Settings → Sources to begin."
                },
            color = YancoPalette.TextMuted,
            style = YancoType.Body,
        )
    }
}
