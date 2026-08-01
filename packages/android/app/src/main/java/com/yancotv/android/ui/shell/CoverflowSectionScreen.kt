package com.yancotv.android.ui.shell

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.R
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.components.ProgressStripe
import com.yancotv.android.ui.components.ResumeBadge
import com.yancotv.android.ui.components.WatchedCheckBadge
import com.yancotv.android.ui.components.formatResumeLabel
import com.yancotv.android.ui.focus.onStartwardKey
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.android.ui.focus.tvLongClickable
import com.yancotv.android.ui.parental.ChannelActionsMenu
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.ShellDim
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.ui.theme.YancoType
import com.yancotv.shared.content.ContentDetailService
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.history.WatchProgress
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentMetadata
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.NowNext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

/**
 * Concept A coverflow section. Shared between Live TV, Movies, and Series —
 * the visual contract is identical:
 *
 *   ┌────────────────────────────────────────────────────────────────┐
 *   │                                                                  │
 *   │  ┌──────────────────────────┐  ┌────────────────────────────┐   │
 *   │  │                          │  │  Title                       │   │
 *   │  │   Preview pane            │  │  EPG / synopsis              │   │
 *   │  │   (MiniPlayer for LIVE,   │  │                              │   │
 *   │  │    poster art for VOD)    │  │  [Watch] [Favorite]         │   │
 *   │  └──────────────────────────┘  └────────────────────────────┘   │
 *   │                                                                  │
 *   ├────────────────────────────────────────────────────────────────┤
 *   │                       3D coverflow wheel                         │
 *   │   ◀ orb  orb  ORB(focused)  orb  orb ▶                          │
 *   └────────────────────────────────────────────────────────────────┘
 *
 * Category selection is hoisted up to [BrowseSection] so the new
 * sidebar→categories→content cascade can drive selection from a vertical
 * [CategoryRail] beside this composable. This composable does NOT render
 * a chip bar of its own.
 *
 * Type-specific behaviour:
 *   - LIVE: MiniPlayer in the preview frame, EPG now/next batch fetch +
 *     minute tick, auto-preview on focus debounce.
 *   - MOVIE / SERIES: poster art in the preview frame (logoUrl). No
 *     EPG, no auto-preview — VOD activation always opens the detail
 *     overlay (handled by the caller's [onActivate]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@UnstableApi
@Composable
fun CoverflowSectionScreen(
    type: ContentType,
    selectedGroup: String,
    onActivate: (List<ContentItem>, Int) -> Unit,
    /**
     * MK.29.3 — start playback immediately, bypassing the detail page.
     * Distinct from [onActivate] because the two entry points diverge for
     * movies: pressing OK on an orb opens detail (where episodes, credits
     * and "play from start" live), while the preview pane's Watch button
     * plays the thing. Both still route through the caller's parental gate.
     */
    onPlayNow: (List<ContentItem>, Int) -> Unit,
    entryFocus: FocusRequester,
    onExitToCategories: () -> Unit,
    onPanelFocusChanged: (Boolean) -> Unit,
    restoreFocusOnWindowRegain: Boolean,
    /**
     * Audit catch — when the catalogue is empty AND the user has no
     * sources, the empty pane offers a focusable "Add a source" CTA
     * that fires this lambda. Caller (HomeScreen via BrowseSection)
     * switches section=Settings + pendingSettingsTab=Sources. Null is
     * accepted so test harnesses / non-shell callers don't need to
     * wire it; the CTA simply doesn't render.
     */
    onAddSource: (() -> Unit)? = null,
    repo: ContentRepository = koinInject(),
    controller: PlaybackController = koinInject(),
    detail: ContentDetailService = koinInject(),
    epg: EpgRepository = koinInject(),
    favorites: FavoritesRepository = koinInject(),
    parental: ParentalRepository = koinInject(),
    prefs: AppPreferences = koinInject(),
    watchHistory: WatchHistoryRepository = koinInject(),
    modifier: Modifier = Modifier,
) {
    val firstItemAnchor = rememberPlacedFocusAnchor()

    // MB-98 — channel context menu (long-press OK / MENU on a coverflow orb).
    // Set by the per-orb tvLongClickable hook via ContentCoverflow's onLongPress;
    // ChannelActionsMenu renders below the wheel when non-null.
    var actionsFor by remember { mutableStateOf<ContentItem?>(null) }

    val items = remember(type) { mutableStateListOf<ContentItem>() }
    var total by remember(type) { mutableStateOf(0L) }
    var loaded by remember(type) { mutableStateOf(0L) }
    var loading by remember(type) { mutableStateOf(false) }
    var hasLoaded by remember(type, selectedGroup) { mutableStateOf(false) }

    // MK.33.1 — carries a playlist id as well as a group name.
    val filter = resolveGroupFilter(selectedGroup)
    val groupFilter = filter.groupName
    val sourceFilter = filter.sourceId
    val isFavoritesFilter = isFavoritesFilter(selectedGroup)

    // Stop playback + reset anchor on category change so the new first orb's
    // onPlaced wins the focus race. Same MB-67 pattern BrowseShell uses.
    LaunchedEffect(type, selectedGroup) {
        if (controller.currentId != null) controller.stop()
        firstItemAnchor.reset()
    }

    if (isFavoritesFilter) {
        LaunchedEffect(type) {
            // MK.8 hard-rule 7: a corrupted favorites row throwing inside
            // the row mapper would propagate out of the collect{} block
            // and crash the screen. Wrap the entire collect so a single
            // bad row falls back to empty state — the user can still
            // exit to another category. Sibling non-favorites path at
            // :182 below already uses runCatching for the page() call.
            try {
                favorites.allFlow().collect { list ->
                    val filtered = list.map { it.content }.filter { it.type == type }
                    items.clear()
                    items.addAll(filtered)
                    total = filtered.size.toLong()
                    loaded = filtered.size.toLong()
                    hasLoaded = true
                }
            } catch (t: Throwable) {
                Log.w("Yanco", "CoverflowSection favorites flow failed: ${t.message}", t)
                items.clear()
                total = 0L
                loaded = 0L
                hasLoaded = true
            }
        }
    } else {
        LaunchedEffect(type, selectedGroup) {
            items.clear()
            hasLoaded = false
            total =
                withContext(Dispatchers.IO) {
                    runCatching { repo.count(type, groupFilter, sourceFilter) }
                        .onFailure { Log.w("Yanco", "CoverflowSection.count failed: ${it.message}", it) }
                        .getOrElse { 0L }
                }
            loaded = 0L
            val first =
                withContext(Dispatchers.IO) {
                    runCatching { repo.page(type, groupFilter, 0L, 100L, sourceFilter) }
                        .onFailure { Log.w("Yanco", "CoverflowSection.page first failed: ${it.message}", it) }
                        .getOrElse { emptyList() }
                }
            items.addAll(first)
            loaded += first.size
            hasLoaded = true
        }
    }

    val lockedIds by parental.lockedIds.collectAsState()
    val hiddenIds by parental.hiddenIds.collectAsState()
    val parentalSettings by parental.settings.collectAsState()

    val visible by remember {
        derivedStateOf {
            applyParentalFilters(
                items = items,
                hiddenIds = hiddenIds,
                hideAdult = parentalSettings.hideAdultContent,
            )
        }
    }

    // MK.28.3 — Tile-progress subscription. Live channels have no resume
    // points (offset in a continuous stream is meaningless), so skip the
    // lookup entirely for type == LIVE. For Movies / Series we subscribe
    // to the visible-window's content IDs and the flow re-emits whenever
    // the player persists a new resume offset — so the bottom-edge
    // progress stripe and corner badge auto-update without any manual
    // refresh. The query is bounded by `items.size` which the screen
    // already caps at 1000 (line 285), well under SQLite's IN-list limit.
    val progressIds by remember {
        derivedStateOf {
            if (type == ContentType.LIVE) {
                emptySet()
            } else {
                items.map { it.id }.toSet()
            }
        }
    }
    val watchProgress by produceState(initialValue = emptyMap<String, WatchProgress>(), progressIds) {
        if (progressIds.isEmpty()) {
            value = emptyMap()
            return@produceState
        }
        runCatching {
            watchHistory.entriesByContentFlow(progressIds).collect { value = it }
        }.onFailure { t ->
            Log.w("Yanco", "CoverflowSection watch-progress flow failed: ${t.message}", t)
            value = emptyMap()
        }
    }

    val focusKey = "coverflow-focus|$type|$selectedGroup"
    var focusedIndex by rememberSaveable(focusKey) { mutableStateOf(0) }
    var focusedItem by remember(type, selectedGroup) { mutableStateOf<ContentItem?>(null) }
    LaunchedEffect(items.size) {
        if (items.isEmpty()) {
            focusedItem = null
            return@LaunchedEffect
        }
        val visibleNow =
            applyParentalFilters(
                items.toList(),
                hiddenIds,
                parentalSettings.hideAdultContent,
            )
        if (focusedItem == null || visibleNow.none { it.id == focusedItem?.id }) {
            val idx = initialFocusIndex(visibleNow, focusedIndex, controller.currentItem.value?.id)
            if (idx >= 0) {
                focusedIndex = idx
                focusedItem = visibleNow[idx]
            }
        }
    }

    // EPG batch fetch + minute tick — LIVE-only.
    var nowNextMap by remember { mutableStateOf<Map<String, NowNext>>(emptyMap()) }
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    if (type == ContentType.LIVE) {
        LaunchedEffect(Unit) {
            snapshotFlow { items.mapNotNull { it.tvgId }.distinct() }
                .distinctUntilChanged()
                .collect { tvgIds ->
                    if (tvgIds.isEmpty()) return@collect
                    val ids = tvgIds.take(60)
                    nowNextMap =
                        withContext(Dispatchers.IO) {
                            runCatching { epg.getNowNextBatch(ids) }
                                .onFailure { Log.w("Yanco", "CoverflowSection EPG batch failed: ${it.message}", it) }
                                .getOrElse { nowNextMap }
                        }
                }
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000L)
                if (!restoreFocusOnWindowRegain) continue
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
                                .onFailure { Log.w("Yanco", "CoverflowSection EPG tick failed: ${it.message}", it) }
                                .getOrElse { nowNextMap }
                        }
                }
            }
        }
    }

    // MK.28.6 (MB-267) — pagination is keyed on BOTH focusedIndex (D-pad)
    // and the LazyRow's last visible index (touch scroll). Pre-fix the
    // guard was focusedIndex-only, and touch never advances focus — so a
    // phone user could never load past the first 100 items of a category
    // (IPTV categories routinely run to thousands).
    var lastVisibleIndex by remember(type, selectedGroup) { mutableStateOf(0) }
    LaunchedEffect(focusedIndex, lastVisibleIndex, total, loaded, isFavoritesFilter) {
        if (isFavoritesFilter) return@LaunchedEffect
        if (loading) return@LaunchedEffect
        if (loaded >= total) return@LaunchedEffect
        if (items.size >= 1000) return@LaunchedEffect
        val reach = maxOf(focusedIndex, lastVisibleIndex)
        if (reach < (loaded - 20)) return@LaunchedEffect
        delay(100L)
        if (loading) return@LaunchedEffect
        loading = true
        val page =
            withContext(Dispatchers.IO) {
                runCatching { repo.page(type, groupFilter, loaded, 100L, sourceFilter) }
                    .onFailure { Log.w("Yanco", "CoverflowSection.page tail failed: ${it.message}", it) }
                    .getOrElse { emptyList() }
            }
        items.addAll(page)
        loaded += page.size
        loading = false
    }

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

    // Auto-preview — LIVE-only. VOD never auto-plays (it would just fail to
    // start the trailer-less stream and burn a player prepare cycle).
    if (type == ContentType.LIVE) {
        LaunchedEffect(focusedItem?.id) {
            resolveAutoPreviewIndex(
                type = ContentType.LIVE,
                focusedId = focusedItem?.id,
                visible = visible,
                lockedIds = lockedIds,
                currentlyPlayingId = controller.currentId,
            ) ?: return@LaunchedEffect
            delay(AUTO_PREVIEW_DEBOUNCE_MS)
            val snapshot = visible.toList()
            val idx =
                resolveAutoPreviewIndex(
                    type = ContentType.LIVE,
                    focusedId = focusedItem?.id,
                    visible = snapshot,
                    lockedIds = lockedIds,
                    currentlyPlayingId = controller.currentId,
                ) ?: return@LaunchedEffect
            controller.play(snapshot, idx)
        }
    }

    val windowInfo = LocalWindowInfo.current
    val canRestore by rememberUpdatedState(restoreFocusOnWindowRegain)
    LaunchedEffect(Unit) {
        var seenUnfocused = false
        snapshotFlow { windowInfo.isWindowFocused }.collect { windowFocused ->
            if (!windowFocused) {
                seenUnfocused = true
            } else if (seenUnfocused) {
                seenUnfocused = false
                if (!canRestore) return@collect
                firstItemAnchor.awaitAndRequest()
            }
        }
    }

    // MB-114: in-process overlay close (Movie/Series detail, search, PIN
    // dialog). The windowInfo handler above only catches OS-level
    // window-focus transitions (e.g. fullscreen PlayerActivity finishing);
    // Compose overlays mounted in the same window don't trigger that, so
    // closing a detail dropped focus to whatever Compose's focus search
    // happened to land on — usually NOT the orb the user opened detail
    // from. The user then had to nudge a D-pad key to "wake" the selector.
    //
    // HomeScreen flips `restoreFocusOnWindowRegain` to false while any
    // overlay is mounted (detailItem != null || searchOverlayVisible ||
    // pendingPlay != null) and back to true once they all clear. Watching
    // that flag for a false → true transition gives us the in-process
    // analogue of the windowInfo regain event: the overlay just left
    // composition, so re-fire `firstItemAnchor` to land focus on the
    // last-focused orb.
    //
    // Why this is safe: the only entry points that toggle the flag false
    // (detail / search / PIN-gated play) all originate from a coverflow
    // user action, so the user was in coverflow before the overlay
    // mounted. Returning focus to the focused orb on close mirrors the
    // mental model. The initial-mount case is a no-op because the flag
    // starts true and `prevRestore` is seeded with the same value, so
    // `turnedOn` is false on the first invocation — a normal mount that
    // lands on the CategoryRail pill is untouched.
    var prevRestore by remember { mutableStateOf(restoreFocusOnWindowRegain) }
    LaunchedEffect(restoreFocusOnWindowRegain) {
        val turnedOn = !prevRestore && restoreFocusOnWindowRegain
        prevRestore = restoreFocusOnWindowRegain
        if (turnedOn) firstItemAnchor.awaitAndRequest()
    }

    var coverflowHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(coverflowHasFocus) { onPanelFocusChanged(coverflowHasFocus) }
    // MK.28.6 (MB-269) — also enabled when the pane is EMPTY: an empty
    // category (pinned Favorites on a fresh install, all-hidden group) has
    // no focusable node, so the focus-gated handler never armed, every
    // handler in the back-chain fell through, and system back CLOSED THE
    // APP from inside a browse screen. With the state gate, BACK steps
    // back to the categories rail regardless of where focus died.
    BackHandler(enabled = coverflowHasFocus || visible.isEmpty()) { onExitToCategories() }

    val playing by controller.currentItem.collectAsState()
    val previewItem = focusedItem
    val isPreviewPlaying = previewItem != null && playing?.id == previewItem.id

    // MK.29.2 — plot / genre / year for the preview meta column.
    //
    // Sourced through ContentDetailService, which is cache-first: it only
    // reaches the provider when a movie has no cached plot (or a series no
    // cached episodes), and persists whatever it fetched back onto the row.
    // Two guards keep that off the network on a fast wheel-scroll:
    //
    //   1. PREVIEW_DETAIL_DEBOUNCE_MS of dwell before anything is loaded, so
    //      spinning past 40 posters issues zero requests.
    //   2. A per-session id → metadata cache. The rows held in `items` keep
    //      their sync-time metadataJson for the life of the screen, so
    //      without this, scrolling back to an already-enriched title would
    //      re-fetch it every time.
    //
    // LIVE never loads — channels have no VOD detail, and the LIVE branch of
    // the meta column renders EPG now/next instead.
    val detailCache = remember(type) { mutableStateMapOf<String, ContentMetadata>() }
    var previewMeta by remember(type) { mutableStateOf<ContentMetadata?>(null) }
    LaunchedEffect(previewItem?.id, type) {
        val target = previewItem
        if (target == null || type == ContentType.LIVE) {
            previewMeta = null
            return@LaunchedEffect
        }
        val cached = detailCache[target.id]
        if (cached != null) {
            previewMeta = cached
            return@LaunchedEffect
        }
        // Paint whatever shipped with the catalog row immediately, then
        // enrich. Without this the description area stays blank for the
        // whole dwell window even when the row already carries a plot.
        val fromRow = parsePreviewMetadata(target)
        previewMeta = fromRow
        // SERIES stops here, deliberately. `ContentDetailService.load`
        // refreshes a series when it has no cached *episodes* — but the
        // preview pane renders the plot, and the series listing already
        // carries that from catalog sync. So enriching here would spend a
        // `get_series_info` round-trip, plus an episode-table upsert, on
        // data this pane never shows, once per title the user rests on.
        // With MB-230's heap ceiling on this device that is not a trade
        // worth making; the detail page still fetches episodes on open.
        if (target.type != ContentType.MOVIE) return@LaunchedEffect
        delay(PREVIEW_DETAIL_DEBOUNCE_MS)
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching { detail.load(target).metadata }
                    .onFailure { Log.w("Yanco", "CoverflowSection detail.load(${target.id}) failed: ${it.message}", it) }
                    .getOrNull()
            } ?: return@LaunchedEffect
        detailCache[target.id] = loaded
        // The wheel may have moved on during the round-trip.
        if (focusedItem?.id == target.id) previewMeta = loaded
    }

    // MK.29.3 — pre-play subtitle pick. Keyed on the focused item's id so
    // moving the wheel drops it: a subtitle resolved for one movie must
    // never survive onto the next. `remember`, not `rememberSaveable` —
    // ResolvedSubtitle points at a cache file that may not outlive process
    // death, and re-picking is one press.
    var previewSubtitle by remember(previewItem?.id) { mutableStateOf<ResolvedSubtitle?>(null) }
    var subtitlePickerOpen by remember(previewItem?.id) { mutableStateOf(false) }
    // Seeds the OpenSubtitles search language from Settings → Playback →
    // Subtitle language; the picker falls back to English when unset.
    val playbackPrefs by prefs.playbackFlow.collectAsState()

    // MK.29.3 — root Box so the subtitle sheet can stack OVER the section.
    // It cannot be emitted as a plain sibling of the Column the way
    // `ChannelActionsMenu` is: that one renders through `Dialog`, i.e. its
    // own window, so it escapes this layout entirely. A bare
    // `fillMaxSize()` overlay emitted as a sibling would instead become a
    // second child of BrowseSection's Row — measured in the unweighted pass,
    // taking all remaining width, and leaving the weighted coverflow Column
    // with zero.
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .onFocusChanged { coverflowHasFocus = it.hasFocus },
        ) {
            // Compose key dispatch order is preview (top-down) → onKeyEvent
            // (bottom-up) → default focus navigation. An .onKeyEvent on the outer
            // Column still fires BEFORE focus-nav, so hoisting the "LEFT exits to
            // categories" handler here intercepts every LEFT and defeats the
            // LazyRow's orb-to-orb scroll. The correct placement is scoped:
            //   1. PreviewPane's Watch CTA — LEFT from leftmost CTA exits (see
            //      onExit wiring below; preview handler only fires when Watch is
            //      the focused node, so Favorite → Watch via focus-nav is
            //      untouched).
            //   2. Coverflow Box — onPreviewKeyEvent gated on shouldExitCoverflowOnLeft
            //      (focusedIndex <= 0). Inter-orb LEFT/RIGHT flow naturally.
            PreviewPane(
                type = type,
                focused = previewItem,
                playing = playing,
                isPlaying = isPreviewPlaying,
                isFavorite = isFav,
                isLocked = previewItem?.let { it.id in lockedIds } == true,
                nowNext = previewItem?.tvgId?.let { nowNextMap[it] },
                nowSeconds = nowSeconds,
                metadata = previewMeta,
                subtitle = previewSubtitle,
                controller = controller,
                onExitLeft = onExitToCategories,
                onOpenSubtitles = { subtitlePickerOpen = true },
                onPlay = {
                    val item = previewItem ?: return@PreviewPane
                    val idx = visible.indexOfFirst { it.id == item.id }
                    if (idx < 0) return@PreviewPane
                    if (type == ContentType.MOVIE) {
                        // MK.29.3 — the preview pane's Watch starts the movie
                        // rather than opening its detail page (the orb's own OK
                        // press still opens detail). Stage the subtitle first:
                        // loadCurrent consumes it while building the very first
                        // MediaItem, so the stream prepares with subtitles
                        // already attached instead of buffering twice.
                        previewSubtitle?.let {
                            controller.stageExternalSubtitle(item.id, it.uri, it.mime, it.label)
                        }
                        onPlayNow(visible.toList(), idx)
                    } else {
                        onActivate(visible.toList(), idx)
                    }
                },
                onToggleFavorite = {
                    val item = previewItem ?: return@PreviewPane
                    val optimistic = !isFav
                    isFav = optimistic
                    scope.launch {
                        val newState =
                            withContext(Dispatchers.IO) {
                                runCatching { favorites.toggle(item.id) }
                                    .onFailure { Log.w("Yanco", "CoverflowSection favorites.toggle failed", it) }
                                    .getOrElse { !optimistic }
                            }
                        if (newState != optimistic) isFav = newState
                    }
                },
                modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(0.62f),
            )

            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(0.38f)
                    // A startward press at the leading orb pops back to the
                    // categories rail. Inter-orb presses flow through to
                    // LazyRow's natural focus traversal — we only intercept
                    // when there's nowhere further to go inside the wheel,
                    // which is why this returns false rather than consuming
                    // unconditionally.
                    //
                    // MK.31.2: startward, not Key.DirectionLeft. In RTL the
                    // LazyRow lays out right-to-left, so the leading orb is
                    // the rightmost one and escaping it is a physical RIGHT.
                    .onStartwardKey {
                        if (shouldExitCoverflowOnLeft(focusedIndex)) {
                            onExitToCategories()
                            true
                        } else {
                            false
                        }
                    }.focusGroup(),
            ) {
                when {
                    visible.isNotEmpty() ->
                        ContentCoverflow(
                            items = visible,
                            type = type,
                            nowNextMap = nowNextMap,
                            lockedIds = lockedIds,
                            watchProgress = watchProgress,
                            focusedIndex =
                            focusedIndex.coerceIn(
                                0,
                                (visible.size - 1).coerceAtLeast(0),
                            ),
                            firstItemAnchor = firstItemAnchor,
                            entryFocus = entryFocus,
                            onFocus = { idx, item ->
                                focusedIndex = idx
                                focusedItem = item
                            },
                            onActivate = { idx -> onActivate(visible.toList(), idx) },
                            onLongPress = { item -> actionsFor = item },
                            onLastVisible = { idx -> lastVisibleIndex = idx },
                        )
                    hasLoaded ->
                        CoverflowEmptyState(
                            type = type,
                            favoritesFilter = isFavoritesFilter,
                            onAddSource = onAddSource,
                        )
                }
            }
        }

        // MK.29.3 — stacked over the section inside the root Box (see the
        // note on that Box), so the scrim covers the whole surface and the
        // sheet's focus trap isn't clipped to the meta column's bounds.
        //
        // The null-item case needs no reset branch: `subtitlePickerOpen` is
        // remembered against `previewItem?.id`, so losing the focused item
        // re-keys the state back to false on its own. Writing to it from
        // here would be a state write during composition.
        previewItem?.takeIf { subtitlePickerOpen }?.let { target ->
            PreviewSubtitleOverlay(
                item = target,
                metadata = previewMeta,
                preferredLanguage = playbackPrefs.subtitleLanguage,
                selected = previewSubtitle,
                onDismiss = { subtitlePickerOpen = false },
                onSelect = { previewSubtitle = it },
            )
        }
    }

    // MB-98 — channel context menu, lifted out of the wheel so the dialog
    // renders over the entire CoverflowSectionScreen surface (not clipped
    // to the LazyRow viewport). Dismissed via Back or any explicit row.
    // Stays outside the root Box: it renders through `Dialog`, i.e. its own
    // window, so layout parentage is irrelevant to it.
    actionsFor?.let { target ->
        ChannelActionsMenu(
            item = target,
            repo = parental,
            onDismiss = { actionsFor = null },
        )
    }
}

/**
 * LEFT-key decision for the coverflow wheel. Extracted so the key-dispatch
 * rule is a pure function pinned by a JVM unit test (`CoverflowLeftActionTest`)
 * — the regression fixed by the Watch-scoped preview handler was that LEFT
 * intercepted every press, which wasn't catchable by that test alone; this
 * one locks down the "only at index 0" half of the contract.
 *
 * Returns true when LEFT on the coverflow Box should consume the event and
 * pop back to the categories rail — i.e. focus is on (or before) the first
 * orb, so there's nothing to scroll to on the left.
 */
internal fun shouldExitCoverflowOnLeft(focusedIndex: Int): Boolean = focusedIndex <= 0

/**
 * MK.29.2 — dwell required on a poster before the preview pane loads its
 * detail metadata. Long enough that holding RIGHT through a category issues
 * no provider calls at all, short enough that stopping on a title fills the
 * description in before the user has finished reading the title.
 */
private const val PREVIEW_DETAIL_DEBOUNCE_MS = 450L

/**
 * Soft cap on the preview plot. The hard guarantee that the action row
 * stays on screen comes from the plot's `weight(1f, fill = false)`, not
 * from this number — this only stops a long synopsis from eating the whole
 * pane on a tall phone layout where the weight has room to spare.
 */
private const val PREVIEW_PLOT_MAX_LINES = 4

private val previewMetadataJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Decode the catalog row's own `metadata_json` for the immediate paint,
 * before [ContentDetailService] has had a chance to enrich it. Returns null
 * rather than throwing on a malformed blob — a bad row must not take the
 * browse screen down (native-android-mk hard rule 7).
 */
private fun parsePreviewMetadata(item: ContentItem): ContentMetadata? {
    val raw = item.metadataJson?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { previewMetadataJson.decodeFromString(ContentMetadata.serializer(), raw) }
        .onFailure { Log.w("Yanco", "CoverflowSection metadata parse failed for ${item.id}: ${it.message}") }
        .getOrNull()
}

// ─────────────────────────────────────────────────────────────────────────────
// Preview pane — type-aware. LIVE shows the MiniPlayer (or focused channel's
// logo when not yet playing); VOD shows the focused poster + meta + Open CTA.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@UnstableApi
@Composable
private fun PreviewPane(
    type: ContentType,
    focused: ContentItem?,
    playing: ContentItem?,
    isPlaying: Boolean,
    isFavorite: Boolean,
    isLocked: Boolean,
    nowNext: NowNext?,
    nowSeconds: Long,
    metadata: ContentMetadata?,
    subtitle: ResolvedSubtitle?,
    controller: PlaybackController,
    onExitLeft: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSubtitles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
        modifier.padding(
            start = Space.page,
            end = Space.page,
            top = Space.lg,
            bottom = Space.lg,
        ),
        horizontalArrangement = Arrangement.spacedBy(Space.xxxl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (type == ContentType.LIVE) {
            LivePreviewFrame(
                focused = focused,
                isPlaying = isPlaying,
                controller = controller,
                modifier = Modifier.weight(0.6f).fillMaxHeight(),
            )
        } else {
            PosterPreviewFrame(
                type = type,
                focused = focused,
                modifier =
                Modifier
                    .weight(ShellDim.posterSlotWeight)
                    .fillMaxHeight(),
            )
        }

        MetaColumn(
            type = type,
            focused = focused,
            isPlaying = isPlaying,
            isFavorite = isFavorite,
            isLocked = isLocked,
            nowNext = nowNext,
            nowSeconds = nowSeconds,
            metadata = metadata,
            subtitle = subtitle,
            onExitLeft = onExitLeft,
            onPlay = onPlay,
            onToggleFavorite = onToggleFavorite,
            onOpenSubtitles = onOpenSubtitles,
            modifier =
            Modifier
                .weight(
                    if (type == ContentType.LIVE) 0.4f else 1f - ShellDim.posterSlotWeight,
                ).fillMaxHeight(),
        )
    }
}

/**
 * LIVE preview surface — the running ExoPlayer's output, or the focused
 * channel's logo before playback starts. Keeps the wide 16:9-ish box
 * (weight 0.6 of the row) because that is the shape video actually is.
 */
@UnstableApi
@Composable
private fun LivePreviewFrame(focused: ContentItem?, isPlaying: Boolean, controller: PlaybackController, modifier: Modifier = Modifier) {
    Box(modifier = modifier.previewFrameChrome()) {
        when {
            isPlaying ->
                MiniPlayer(
                    modifier = Modifier.fillMaxSize(),
                    controller = controller,
                )
            focused?.displayLogoUrl?.isNotBlank() == true ->
                AsyncImage(
                    model = focused.displayLogoUrl,
                    contentDescription = focused.displayTitle,
                    contentScale = ContentScale.Fit,
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(Space.section),
                )
            else -> PreviewIdleArtwork(type = ContentType.LIVE)
        }
        PreviewScrim()
    }
}

/**
 * MB-303 — VOD preview surface. Sizes a [ShellDim.posterAspect] frame to
 * the pane height and centres it inside [modifier]'s slot, so the entire
 * poster is on screen instead of the middle 45% the old 444x303 dp
 * landscape box cropped to.
 *
 * [BoxWithConstraints] rather than `fillMaxHeight().aspectRatio(...)`
 * deliberately: `fillMaxHeight` pins minHeight == maxHeight, and when the
 * height-derived width doesn't fit (phone portrait — a 500 dp-tall pane
 * wants a 333 dp-wide frame out of a ~110 dp slot) every branch of
 * `aspectRatio`'s constraint search fails and it silently falls through to
 * measuring with the raw constraints. Clamping the height against the
 * slot width here has no such degenerate case on any viewport.
 */
@Composable
private fun PosterPreviewFrame(type: ContentType, focused: ContentItem?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // Height-first, clamped by what the slot width can carry.
        val frameHeight = minOf(maxHeight, maxWidth / ShellDim.posterAspect)
        val frameWidth = frameHeight * ShellDim.posterAspect
        Box(
            modifier =
            Modifier
                .size(width = frameWidth, height = frameHeight)
                .previewFrameChrome(),
        ) {
            val art = focused?.displayLogoUrl
            if (art?.isNotBlank() == true) {
                AsyncImage(
                    model = art,
                    contentDescription = focused.displayTitle,
                    // Fit, not Crop: the frame is already poster-shaped, and
                    // providers that ship 16:9 grabs or square art under the
                    // same field letterbox cleanly instead of being cropped
                    // a second time.
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PreviewIdleArtwork(type = type)
            }
            PreviewScrim()
        }
    }
}

/** Shared cut-corner card chrome for both preview frames. */
@Composable
private fun Modifier.previewFrameChrome(): Modifier {
    val palette = LocalYancoPalette.current
    return this
        .clip(YancoShapes.CutCornerCardLarge)
        .background(palette.BackgroundDeep)
        .border(
            width = 1.dp,
            brush =
            Brush.verticalGradient(
                listOf(
                    palette.Accent.copy(alpha = 0.45f),
                    palette.PanelBorder,
                ),
            ),
            shape = YancoShapes.CutCornerCardLarge,
        )
}

/** Bottom fade so the frame's lower edge sits into the pane background. */
@Composable
private fun PreviewScrim() {
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.85f to Color.Transparent,
                    1f to LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.55f),
                ),
            ),
    )
}

@Composable
private fun PreviewIdleArtwork(type: ContentType) {
    val brand =
        when (type) {
            ContentType.LIVE -> stringResource(R.string.brand_plus)
            ContentType.MOVIE -> "MOVIES"
            ContentType.SERIES -> "SERIES"
        }
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        LocalYancoPalette.current.BackgroundElevated,
                        LocalYancoPalette.current.BackgroundDeep,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = brand,
            color = LocalYancoPalette.current.Accent.copy(alpha = 0.45f),
            style = YancoType.DisplayS,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MetaColumn(
    type: ContentType,
    focused: ContentItem?,
    isPlaying: Boolean,
    isFavorite: Boolean,
    isLocked: Boolean,
    nowNext: NowNext?,
    nowSeconds: Long,
    metadata: ContentMetadata?,
    subtitle: ResolvedSubtitle?,
    onExitLeft: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSubtitles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (focused == null) {
        EmptyMetaPrompt(type = type, modifier = modifier)
        return
    }
    // MK.13.2 `displayTitle` — was re-implementing the cleanTitle fallback
    // by hand, which skipped the user's name override; the preview frame's
    // own contentDescription already used displayTitle, so a renamed item
    // announced one name and rendered another.
    val title = focused.displayTitle
    val nowProg = nowNext?.now
    val nextProg = nowNext?.next
    val overline =
        when (type) {
            ContentType.LIVE -> stringResource(R.string.cf_kicker_live_channel)
            ContentType.MOVIE -> "MOVIE"
            ContentType.SERIES -> "SERIES"
        }
    // MK.29.3 — a series container has no stream of its own, so its primary
    // action opens the episode list rather than starting playback.
    val watchLabel =
        when {
            type == ContentType.LIVE && isPlaying -> stringResource(R.string.cf_open_fullscreen)
            type == ContentType.LIVE -> stringResource(R.string.cf_watch)
            type == ContentType.MOVIE -> stringResource(R.string.cf_watch)
            else -> stringResource(R.string.cf_episodes)
        }
    // No widthIn cap here — the column needs to use whatever horizontal
    // space the meta side of the preview row has, otherwise a 520dp cap
    // pinches the two CTAs (Watch + Favorite) into a clipped row when the
    // CategoryRail is mounted alongside (rail eats 240dp of viewport).
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text(text = overline, color = LocalYancoPalette.current.Accent, style = YancoType.Overline)
            if (isPlaying) MetaLivePill()
            if (isLocked) MetaLockChip()
        }
        Text(
            text = title,
            color = LocalYancoPalette.current.TextPrimary,
            style = YancoType.DisplayS,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (type == ContentType.LIVE) {
            if (nowProg != null) {
                Text(
                    text = stringResource(R.string.cf_now, nowProg.title),
                    color = LocalYancoPalette.current.TextPrimary,
                    style = YancoType.TitleM,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ProgressLine(start = nowProg.startTime, end = nowProg.endTime, now = nowSeconds)
            } else {
                // Smart-cast across module boundary needs a local val.
                val grp = focused.groupName
                if (!grp.isNullOrBlank()) {
                    Text(
                        text = grp,
                        color = LocalYancoPalette.current.TextSecondary,
                        style = YancoType.Body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (nextProg != null) {
                Text(
                    text = stringResource(R.string.cf_up_next, nextProg.title),
                    color = LocalYancoPalette.current.TextMuted,
                    style = YancoType.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            // MK.29.2 — VOD: facts line, then the plot. `metadata` is null
            // until the debounced detail load lands, so both blocks are
            // absent-tolerant; the column simply grows as data arrives
            // rather than reserving a fixed gap that renders as dead space
            // on titles the provider ships no plot for.
            PreviewFactsLine(item = focused, meta = metadata, type = type)
            val plot =
                metadata?.plot?.takeIf { it.isNotBlank() }
                    ?: metadata?.description?.takeIf { it.isNotBlank() }
            if (plot != null) {
                Text(
                    text = plot,
                    color = LocalYancoPalette.current.TextSecondary,
                    style = YancoType.BodyLong,
                    maxLines = PREVIEW_PLOT_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    // The plot is the ONLY flexible row in this column.
                    // Column measures unweighted children first, so the
                    // action row below always gets its full height and the
                    // description absorbs whatever is left over — on a short
                    // pane the text truncates instead of pushing Watch /
                    // Favorite / Subtitles off the bottom. MB-300 shipped
                    // exactly that failure on the player's error overlay
                    // (RETRY and BACK measured at height 0); `fill = false`
                    // keeps a short plot from padding the column out.
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Spacer(Modifier.height(Space.xs))
        // FlowRow, not Row: with the Subtitles control the action strip can
        // exceed the meta column on a narrow pane (rail mounted, phone
        // portrait, or a large font-scale preset), and a plain Row would
        // measure the overflowing child at zero width — the MB-300 failure
        // shape where a control is present in the tree but unreachable.
        // Wrapping costs a line of height, which the plot above gives up.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            // Only the Watch CTA gets the LEFT-exits-to-categories preview
            // handler. Scoping it to this subtree (not MetaColumn / Row) is
            // deliberate: onPreviewKeyEvent fires top-down along the focused
            // path, so wrapping the whole row would swallow Favorite→Watch
            // focus-nav too. Wrapped at Watch-only level, the handler is
            // only reachable when Watch itself owns focus.
            //
            // MK.31.2: startward, not Key.DirectionLeft. See DirectionalNav.
            Box(
                modifier =
                Modifier.onStartwardKey {
                    onExitLeft()
                    true
                },
            ) {
                HexCta(
                    label = watchLabel,
                    icon = YancoIcons.Play,
                    primary = true,
                    onClick = onPlay,
                )
            }
            HexCta(
                label = if (isFavorite) stringResource(R.string.cf_in_favorites) else stringResource(R.string.cf_favorite),
                icon = if (isFavorite) YancoIcons.StarFilled else YancoIcons.StarOutline,
                primary = false,
                highlighted = isFavorite,
                onClick = onToggleFavorite,
            )
            // MK.29.3 — movies only. A series container has no stream, so
            // "which episode's subtitle?" has no answer here; the player's
            // own subtitle menu covers episodes once one is playing.
            if (type == ContentType.MOVIE) {
                HexCta(
                    label = subtitle?.label ?: stringResource(R.string.cf_subtitles),
                    icon = YancoIcons.Subtitles,
                    primary = false,
                    highlighted = subtitle != null,
                    onClick = onOpenSubtitles,
                )
            }
        }
    }
}

/**
 * MK.29.2 — one-line facts strip under the preview title: year, rating,
 * genre, runtime, plus the provider group as a fallback so the row is
 * never empty on titles with no enriched metadata at all. Dot-separated,
 * single line, ellipsised — it is orientation, not content.
 */
@Composable
private fun PreviewFactsLine(item: ContentItem, meta: ContentMetadata?, type: ContentType) {
    val palette = LocalYancoPalette.current
    // MK.31.22 — captured outside `remember`, which is not composable scope.
    val ctx = LocalContext.current
    val facts =
        remember(item.id, meta, type) {
            buildList {
                meta?.releaseDate?.takeIf { it.isNotBlank() }?.let { add(it.take(4)) }
                meta?.rating?.takeIf { it.isNotBlank() }?.let { add("★ $it") }
                meta?.genre?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (type == ContentType.SERIES) {
                    meta?.episodes?.size?.takeIf { it > 0 }?.let {
                        add(ctx.resources.getQuantityString(R.plurals.cf_episodes_count, it, it))
                    }
                } else {
                    meta?.duration?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                // Fallback only — a provider group name is better than a
                // blank strip, but it is redundant next to real metadata.
                if (isEmpty()) item.groupName?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    if (facts.isEmpty()) return
    Text(
        text = facts.joinToString("  ·  "),
        color = palette.TextSecondary,
        style = YancoType.CaptionStrong,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EmptyMetaPrompt(type: ContentType, modifier: Modifier) {
    val (overline, title, body) =
        when (type) {
            ContentType.LIVE ->
                Triple(
                    stringResource(R.string.cf_empty_live_kicker),
                    stringResource(R.string.cf_empty_live_title),
                    stringResource(R.string.cf_empty_live_body),
                )
            ContentType.MOVIE ->
                Triple(
                    stringResource(R.string.cf_empty_movies_kicker),
                    stringResource(R.string.cf_empty_movies_title),
                    stringResource(R.string.cf_empty_movies_body),
                )
            ContentType.SERIES ->
                Triple(
                    stringResource(R.string.cf_empty_series_kicker),
                    stringResource(R.string.cf_empty_series_title),
                    stringResource(R.string.cf_empty_series_body),
                )
        }
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        Text(text = overline, color = LocalYancoPalette.current.Accent, style = YancoType.Overline)
        Spacer(Modifier.height(Space.sm))
        Text(
            text = title,
            color = LocalYancoPalette.current.TextPrimary,
            style = YancoType.DisplayS,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            text = body,
            color = LocalYancoPalette.current.TextSecondary,
            style = YancoType.BodyLong,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProgressLine(start: Long, end: Long, now: Long) {
    val span = remember(start, end) { (end - start).coerceAtLeast(1) }
    val pct = ((now - start).toFloat() / span).coerceIn(0f, 1f)
    val remainingMin = ((end - now).coerceAtLeast(0) / 60).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(LocalYancoPalette.current.BorderSubtle),
        ) {
            Box(
                modifier =
                Modifier
                    .fillMaxWidth(pct)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                LocalYancoPalette.current.AccentDeep,
                                LocalYancoPalette.current.Accent,
                                LocalYancoPalette.current.AccentGlow,
                            ),
                        ),
                    ),
            )
        }
        Text(
            text = if (remainingMin > 0) stringResource(R.string.cf_min_left, remainingMin) else stringResource(R.string.cf_ending_now),
            color = LocalYancoPalette.current.TextMuted,
            style = YancoType.Caption,
        )
    }
}

@Composable
private fun HexCta(label: String, icon: ImageVector, primary: Boolean, highlighted: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = YancoShapes.ButtonBevel
    val bg by animateColorAsState(
        targetValue =
        when {
            primary && focused -> LocalYancoPalette.current.AccentGlow
            primary -> LocalYancoPalette.current.Accent
            focused -> LocalYancoPalette.current.Accent.copy(alpha = 0.22f)
            highlighted -> LocalYancoPalette.current.Accent.copy(alpha = 0.14f)
            else -> LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.6f)
        },
        label = "hexCtaBg",
    )
    val border =
        when {
            focused -> LocalYancoPalette.current.FocusRing
            primary -> LocalYancoPalette.current.AccentDeep
            highlighted -> LocalYancoPalette.current.Accent.copy(alpha = 0.55f)
            else -> LocalYancoPalette.current.PanelBorder
        }
    val fg =
        when {
            primary -> LocalYancoPalette.current.BackgroundDeep
            highlighted -> LocalYancoPalette.current.Accent
            else -> LocalYancoPalette.current.TextPrimary
        }
    Row(
        modifier =
        Modifier
            .shadow(
                elevation = if (focused) 14.dp else 0.dp,
                shape = shape,
                ambientColor = LocalYancoPalette.current.Accent,
                spotColor = LocalYancoPalette.current.Accent,
            ).clip(shape)
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, border, shape)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            // xl horizontal padding (was xxxl) so two CTAs side-by-side
            // always fit when the categories rail is mounted. Buttons stay
            // generous-looking because the hex bevel + emerald glow do most
            // of the visual lifting; the chrome doesn't need 32dp gutters.
            .padding(horizontal = Space.xl, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(
            text = label,
            color = fg,
            style = YancoType.LabelStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}

@Composable
private fun MetaLivePill() {
    Row(
        modifier =
        Modifier
            .clip(YancoShapes.ChipBevel)
            .background(LocalYancoPalette.current.Live)
            .padding(horizontal = Space.md, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(
            modifier =
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(Color.White),
        )
        Text(text = stringResource(R.string.badge_live), color = Color.White, style = YancoType.Overline)
    }
}

@Composable
private fun MetaLockChip() {
    Row(
        modifier =
        Modifier
            .clip(YancoShapes.ChipBevel)
            .background(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.85f))
            .border(1.dp, LocalYancoPalette.current.Accent.copy(alpha = 0.45f), YancoShapes.ChipBevel)
            .padding(horizontal = Space.md, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(
            imageVector = YancoIcons.Lock,
            contentDescription = null,
            tint = LocalYancoPalette.current.Accent,
            modifier = Modifier.size(10.dp),
        )
        Text(text = stringResource(R.string.badge_locked), color = LocalYancoPalette.current.Accent, style = YancoType.Overline)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3D coverflow wheel — same plumbing as the original LiveTvScreen, generalised
// for any ContentType.
// ─────────────────────────────────────────────────────────────────────────────

private val OrbWidth = 140.dp
private val OrbHeight = 200.dp
private val OrbSpacing = 28.dp

@Composable
private fun ContentCoverflow(
    items: List<ContentItem>,
    type: ContentType,
    nowNextMap: Map<String, NowNext>,
    lockedIds: Set<String>,
    watchProgress: Map<String, WatchProgress>,
    focusedIndex: Int,
    firstItemAnchor: com.yancotv.android.ui.focus.PlacedFocusAnchor,
    entryFocus: FocusRequester,
    onFocus: (Int, ContentItem) -> Unit,
    onActivate: (Int) -> Unit,
    onLongPress: (ContentItem) -> Unit,
    onLastVisible: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    // MK.28.6 (MB-267) — report the scroll frontier so the parent's
    // pagination effect fires for touch scrolling, not just D-pad focus.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { onLastVisible(it) }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportPx = with(LocalDensity.current) { maxWidth.toPx() }
        val orbPx = with(LocalDensity.current) { OrbWidth.toPx() }
        val sidePaddingPx = ((viewportPx - orbPx) / 2f).coerceAtLeast(0f)
        val sidePaddingDp = with(LocalDensity.current) { sidePaddingPx.toDp() }

        LaunchedEffect(focusedIndex, items.size) {
            if (items.isEmpty()) return@LaunchedEffect
            val safe = focusedIndex.coerceIn(0, items.size - 1)
            runCatching { listState.animateScrollToItem(safe, 0) }
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
            PaddingValues(
                start = sidePaddingDp,
                end = sidePaddingDp,
                top = Space.lg,
                bottom = Space.lg,
            ),
            horizontalArrangement = Arrangement.spacedBy(OrbSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                ContentOrb(
                    item = item,
                    type = type,
                    distance = index - focusedIndex,
                    isLocked = item.id in lockedIds,
                    nowNext = item.tvgId?.let { nowNextMap[it] },
                    progress = watchProgress[item.id],
                    placedAnchor = if (index == focusedIndex) firstItemAnchor else null,
                    entryFocus = if (index == focusedIndex) entryFocus else null,
                    onFocus = { onFocus(index, item) },
                    onActivate = { onActivate(index) },
                    onLongPress = { onLongPress(item) },
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ContentOrb(
    item: ContentItem,
    type: ContentType,
    distance: Int,
    isLocked: Boolean,
    nowNext: NowNext?,
    progress: WatchProgress?,
    placedAnchor: com.yancotv.android.ui.focus.PlacedFocusAnchor?,
    entryFocus: FocusRequester?,
    onFocus: () -> Unit,
    onActivate: () -> Unit,
    onLongPress: () -> Unit,
) {
    // MK.31.22 — the semantics builder below is not composable scope.
    val ctx = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // MK.28.6 (MB-270) — pressed-state feedback: a touch press dips the orb
    // so taps visibly register (focus never moves in touch mode, so the
    // focus ring alone gave phone users zero response between finger-down
    // and the resulting action).
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }

    val isCenter = distance == 0
    val absD = kotlin.math.abs(distance).coerceAtMost(6)
    val rotationY = (-distance.toFloat() * 16f).coerceIn(-58f, 58f)
    val scaleBase = if (isCenter) 1.18f else (1.0f - absD * 0.07f).coerceAtLeast(0.62f)
    val scale =
        when {
            pressed -> scaleBase * 0.94f
            focused -> scaleBase * 1.04f
            else -> scaleBase
        }
    val alpha = if (isCenter) 1f else (1f - absD * 0.18f).coerceAtLeast(0.32f)
    val translationXDp = -distance.toFloat() * 4f
    val translationX = with(LocalDensity.current) { translationXDp.dp.toPx() }
    val cameraDist = with(LocalDensity.current) { 16.dp.toPx() } * 8f

    val title = item.cleanTitle?.ifBlank { null } ?: item.title

    Column(
        modifier =
        Modifier
            .width(OrbWidth)
            .height(OrbHeight)
            .graphicsLayer {
                this.rotationY = rotationY
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                this.translationX = translationX
                this.cameraDistance = cameraDist
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(
            modifier =
            Modifier
                .size(OrbWidth)
                .shadow(
                    elevation = if (focused) 28.dp else 6.dp,
                    shape = YancoShapes.HexCapsule,
                    ambientColor = LocalYancoPalette.current.Accent,
                    spotColor = LocalYancoPalette.current.Accent,
                ).clip(YancoShapes.HexCapsule)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            LocalYancoPalette.current.BackgroundElevated,
                            LocalYancoPalette.current.BackgroundDeep,
                        ),
                    ),
                ).border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) LocalYancoPalette.current.FocusRing else LocalYancoPalette.current.PanelBorder,
                    shape = YancoShapes.HexCapsule,
                ).then(placedAnchor?.let { Modifier.placedFocus(it) } ?: Modifier)
                .then(entryFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                // MB-98 — registers `onLongPress` as the active context-menu
                // action while this orb holds focus. The actual long-press
                // timer + KEYCODE_MENU dispatch live in MainActivity (see
                // TvContextActionState).
                .tvLongClickable(onLongPress)
                .focusable(interactionSource = interaction)
                // MK.28.6 (MB-266/268) — combinedClickable so a TOUCH
                // long-press also opens the 6-action context menu (rename /
                // logo / lock / hide / share had no phone path at all; the
                // Guide already pairs combinedClickable with the TV key
                // timer, and MainActivity's onKeyUp swallow was designed to
                // coexist with it). Tap = select-then-activate: a tap on a
                // non-centered orb SELECTS it (touch never moves Compose
                // focus, so pre-fix the preview pane / Favorite CTA /
                // auto-preview were pinned to a stale item forever); a tap
                // on the centered orb activates. On TV the clicked orb is
                // always the centered one (focus recenters the wheel), so
                // CENTER behaviour is unchanged.
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = {
                        if (distance == 0) onActivate() else onFocus()
                    },
                    onLongClick = onLongPress,
                )
                // MK.28.8 (MB-280) — fold locked + watch state into the
                // description: the explicit contentDescription overrides
                // merged descendant text, so the icon-only lock badge and
                // the watched / resume badges were dropped from the
                // TalkBack announcement.
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        buildString {
                            append(title)
                            if (isLocked) append(ctx.getString(R.string.cf_locked_suffix))
                            when {
                                progress?.isFinished() == true ->
                                    append(ctx.getString(R.string.cf_watched_suffix))
                                progress != null ->
                                    append(ctx.getString(R.string.cf_in_progress))
                            }
                        }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (item.displayLogoUrl?.isNotBlank() == true) {
                AsyncImage(
                    // MK.13.2 `displayLogoUrl`, matching the preview frame:
                    // the orb was reading the raw `logoUrl`, so a user logo
                    // override showed in the preview but not on the tile it
                    // came from.
                    model = item.displayLogoUrl,
                    contentDescription = null,
                    contentScale = if (type == ContentType.LIVE) ContentScale.Fit else ContentScale.Crop,
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(if (type == ContentType.LIVE) Space.lg else 0.dp),
                )
            } else {
                Text(
                    text = title.take(2).uppercase(),
                    color = LocalYancoPalette.current.Accent,
                    style = YancoType.TitleL,
                )
            }
            if (isLocked) {
                Box(
                    modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Space.xs)
                        .size(22.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.85f))
                        .border(
                            1.dp,
                            LocalYancoPalette.current.Accent.copy(alpha = 0.55f),
                            RoundedCornerShape(Radius.pill),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = YancoIcons.Lock,
                        contentDescription = null,
                        tint = LocalYancoPalette.current.Accent,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            // MK.28.3 — Watch-state overlay. TopStart corner (lock owns TopEnd
            // when present, so the two never collide). Live channels pass
            // progress=null so this short-circuits — channels have no resume.
            //
            // ProgressStripe sits at the BottomCenter of the orb hex itself
            // so the stripe reads as part of the tile, not a divider below
            // it. Finished rows get the WatchedCheckBadge instead of a
            // full-bar stripe so the user can tell at a glance which titles
            // they've already seen.
            if (progress != null) {
                if (progress.isFinished()) {
                    WatchedCheckBadge(
                        modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(Space.xs),
                    )
                } else {
                    ResumeBadge(
                        label = formatResumeLabel(ctx, progress),
                        modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(Space.xs),
                    )
                    if (progress.ratio > 0f) {
                        ProgressStripe(
                            progress = progress.ratio,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
        Text(
            text = title,
            color = if (isCenter) LocalYancoPalette.current.TextPrimary else LocalYancoPalette.current.TextSecondary,
            style = if (isCenter) YancoType.LabelStrong else YancoType.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Secondary line: EPG now-title for LIVE, group name otherwise.
        val sub =
            when (type) {
                ContentType.LIVE ->
                    nowNext?.now?.title?.takeIf { it.isNotBlank() }
                        ?: item.groupName.orEmpty()
                else -> item.groupName.orEmpty()
            }
        if (sub.isNotBlank()) {
            Text(
                text = sub,
                color = LocalYancoPalette.current.TextMuted,
                style = YancoType.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CoverflowEmptyState(type: ContentType, favoritesFilter: Boolean, onAddSource: (() -> Unit)? = null) {
    val title =
        when {
            favoritesFilter ->
                when (type) {
                    ContentType.LIVE -> stringResource(R.string.cf_no_fav_channels)
                    ContentType.MOVIE -> stringResource(R.string.cf_no_fav_movies)
                    ContentType.SERIES -> stringResource(R.string.cf_no_fav_series)
                }
            else ->
                when (type) {
                    ContentType.LIVE -> stringResource(R.string.cf_no_channels)
                    ContentType.MOVIE -> stringResource(R.string.cf_no_movies)
                    ContentType.SERIES -> stringResource(R.string.cf_no_series)
                }
        }
    val body =
        when {
            favoritesFilter -> stringResource(R.string.cf_favorites_empty)
            else -> stringResource(R.string.cf_add_source)
        }
    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .padding(Space.page),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = title,
            color = LocalYancoPalette.current.TextPrimary,
            style = YancoType.TitleL,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            text = body,
            color = LocalYancoPalette.current.TextMuted,
            style = YancoType.Body,
        )
        // Audit catch — pre-fix the empty pane had ZERO focusable children,
        // so a first-run user pressing the sidebar Live TV / Movies /
        // Series icon hit a dead pane with D-pad going nowhere. Mirror
        // EmptyHome's pattern: when this is the "no sources yet" case
        // (not the "no favourites" case), surface a focusable button
        // that opens Settings → Sources directly.
        if (!favoritesFilter && onAddSource != null) {
            Spacer(Modifier.height(Space.xl))
            com.yancotv.android.ui.components.YancoPrimaryButton(
                onClick = onAddSource,
                size = com.yancotv.android.ui.components.ButtonSize.Standard,
            ) {
                Icon(
                    imageVector = YancoIcons.Link,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Text(text = stringResource(R.string.common_add_a_source))
            }
        }
    }
}
