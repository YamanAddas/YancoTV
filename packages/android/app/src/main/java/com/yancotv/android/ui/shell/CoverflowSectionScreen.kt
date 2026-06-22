package com.yancotv.android.ui.shell

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.components.ProgressStripe
import com.yancotv.android.ui.components.ResumeBadge
import com.yancotv.android.ui.components.WatchedCheckBadge
import com.yancotv.android.ui.components.formatResumeLabel
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.android.ui.focus.tvLongClickable
import com.yancotv.android.ui.parental.ChannelActionsMenu
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.ui.theme.YancoType
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.history.WatchProgress
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.NowNext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    entryFocus: FocusRequester,
    onExitToCategories: () -> Unit,
    onPanelFocusChanged: (Boolean) -> Unit,
    restoreFocusOnWindowRegain: Boolean,
    repo: ContentRepository = koinInject(),
    controller: PlaybackController = koinInject(),
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

    val groupFilter = resolveGroupFilter(selectedGroup)
    val isFavoritesFilter = isFavoritesFilter(selectedGroup)

    // Stop playback + reset anchor on category change so the new first orb's
    // onPlaced wins the focus race. Same MB-67 pattern BrowseShell uses.
    LaunchedEffect(type, selectedGroup) {
        if (controller.currentId != null) controller.stop()
        firstItemAnchor.reset()
    }

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
        LaunchedEffect(type, selectedGroup) {
            items.clear()
            hasLoaded = false
            total =
                withContext(Dispatchers.IO) {
                    runCatching { repo.count(type, groupFilter) }
                        .onFailure { Log.w("Yanco", "CoverflowSection.count failed: ${it.message}", it) }
                        .getOrElse { 0L }
                }
            loaded = 0L
            val first =
                withContext(Dispatchers.IO) {
                    runCatching { repo.page(type, groupFilter, 0L, 100L) }
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
            if (type == ContentType.LIVE) emptySet()
            else items.map { it.id }.toSet()
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

    LaunchedEffect(focusedIndex, total, loaded, isFavoritesFilter) {
        if (isFavoritesFilter) return@LaunchedEffect
        if (loading) return@LaunchedEffect
        if (loaded >= total) return@LaunchedEffect
        if (items.size >= 1000) return@LaunchedEffect
        if (focusedIndex < (loaded - 20)) return@LaunchedEffect
        delay(100L)
        if (loading) return@LaunchedEffect
        loading = true
        val page =
            withContext(Dispatchers.IO) {
                runCatching { repo.page(type, groupFilter, loaded, 100L) }
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
    BackHandler(enabled = coverflowHasFocus) { onExitToCategories() }

    val playing by controller.currentItem.collectAsState()
    val previewItem = focusedItem
    val isPreviewPlaying = previewItem != null && playing?.id == previewItem.id

    Column(
        modifier =
        modifier
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
            controller = controller,
            onExitLeft = onExitToCategories,
            onPlay = {
                val item = previewItem ?: return@PreviewPane
                val idx = visible.indexOfFirst { it.id == item.id }
                if (idx >= 0) onActivate(visible.toList(), idx)
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
                // D-pad LEFT at the leftmost orb pops back to the categories
                // rail. Inter-orb LEFT/RIGHT presses flow through to
                // LazyRow's natural focus traversal — we only intercept
                // when there's nowhere left to scroll inside the wheel.
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown &&
                        ev.key == Key.DirectionLeft &&
                        shouldExitCoverflowOnLeft(focusedIndex)
                    ) {
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
                    )
                hasLoaded ->
                    CoverflowEmptyState(
                        type = type,
                        favoritesFilter = isFavoritesFilter,
                    )
            }
        }
    }

    // MB-98 — channel context menu, lifted out of the wheel so the dialog
    // renders over the entire CoverflowSectionScreen surface (not clipped
    // to the LazyRow viewport). Dismissed via Back or any explicit row.
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
    controller: PlaybackController,
    onExitLeft: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
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
        Box(
            modifier =
            Modifier
                .weight(0.6f)
                .fillMaxHeight()
                .clip(YancoShapes.CutCornerCardLarge)
                .background(LocalYancoPalette.current.BackgroundDeep)
                .border(
                    width = 1.dp,
                    brush =
                    Brush.verticalGradient(
                        listOf(
                            LocalYancoPalette.current.Accent.copy(alpha = 0.45f),
                            LocalYancoPalette.current.PanelBorder,
                        ),
                    ),
                    shape = YancoShapes.CutCornerCardLarge,
                ),
        ) {
            when {
                // LIVE preview — share the running ExoPlayer surface.
                type == ContentType.LIVE && isPlaying ->
                    MiniPlayer(
                        modifier = Modifier.fillMaxSize(),
                        controller = controller,
                    )
                // Either no logo at all or LIVE-pre-preview: show artwork.
                focused?.logoUrl?.isNotBlank() == true ->
                    AsyncImage(
                        model = focused.logoUrl,
                        contentDescription = focused.cleanTitle?.ifBlank { null } ?: focused.title,
                        contentScale = if (type == ContentType.LIVE) ContentScale.Fit else ContentScale.Crop,
                        modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(if (type == ContentType.LIVE) Space.section else 0.dp),
                    )
                else -> PreviewIdleArtwork(type = type)
            }
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

        MetaColumn(
            type = type,
            focused = focused,
            isPlaying = isPlaying,
            isFavorite = isFavorite,
            isLocked = isLocked,
            nowNext = nowNext,
            nowSeconds = nowSeconds,
            onExitLeft = onExitLeft,
            onPlay = onPlay,
            onToggleFavorite = onToggleFavorite,
            modifier =
            Modifier
                .weight(0.4f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun PreviewIdleArtwork(type: ContentType) {
    val brand =
        when (type) {
            ContentType.LIVE -> "YANCOTV+"
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MetaColumn(
    type: ContentType,
    focused: ContentItem?,
    isPlaying: Boolean,
    isFavorite: Boolean,
    isLocked: Boolean,
    nowNext: NowNext?,
    nowSeconds: Long,
    onExitLeft: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (focused == null) {
        EmptyMetaPrompt(type = type, modifier = modifier)
        return
    }
    val title = focused.cleanTitle?.ifBlank { null } ?: focused.title
    val nowProg = nowNext?.now
    val nextProg = nowNext?.next
    val overline =
        when (type) {
            ContentType.LIVE -> "LIVE CHANNEL"
            ContentType.MOVIE -> "MOVIE"
            ContentType.SERIES -> "SERIES"
        }
    val watchLabel =
        when {
            type == ContentType.LIVE && isPlaying -> "Open fullscreen"
            type == ContentType.LIVE -> "Watch"
            type == ContentType.MOVIE -> "Watch"
            else -> "Open"
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
            style = YancoType.DisplayM,
            maxLines = 2,
        )
        if (type == ContentType.LIVE && nowProg != null) {
            Text(
                text = "Now: ${nowProg.title}",
                color = LocalYancoPalette.current.TextPrimary,
                style = YancoType.TitleM,
                maxLines = 2,
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
                )
            }
        }
        if (type == ContentType.LIVE && nextProg != null) {
            Text(
                text = "Up next: ${nextProg.title}",
                color = LocalYancoPalette.current.TextMuted,
                style = YancoType.Caption,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(Space.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            // Only the Watch CTA gets the LEFT-exits-to-categories preview
            // handler. Scoping it to this subtree (not MetaColumn / Row) is
            // deliberate: onPreviewKeyEvent fires top-down along the focused
            // path, so wrapping the whole row would swallow Favorite→Watch
            // focus-nav too. Wrapped at Watch-only level, the handler is
            // only reachable when Watch itself owns focus.
            Box(
                modifier =
                Modifier.onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                        onExitLeft()
                        true
                    } else {
                        false
                    }
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
                label = if (isFavorite) "In favorites" else "Favorite",
                icon = if (isFavorite) YancoIcons.StarFilled else YancoIcons.StarOutline,
                primary = false,
                highlighted = isFavorite,
                onClick = onToggleFavorite,
            )
        }
    }
}

@Composable
private fun EmptyMetaPrompt(type: ContentType, modifier: Modifier) {
    val (overline, title, body) =
        when (type) {
            ContentType.LIVE ->
                Triple(
                    "LIVE TV",
                    "Pick a channel from the wheel",
                    "The focused channel previews here. Press OK to go fullscreen.",
                )
            ContentType.MOVIE ->
                Triple(
                    "MOVIES",
                    "Pick a movie from the wheel",
                    "The focused movie shows here. Press OK to open details.",
                )
            ContentType.SERIES ->
                Triple(
                    "SERIES",
                    "Pick a series from the wheel",
                    "The focused series shows here. Press OK to open episodes.",
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
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            text = body,
            color = LocalYancoPalette.current.TextSecondary,
            style = YancoType.BodyLong,
            maxLines = 3,
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
            text = if (remainingMin > 0) "$remainingMin min left" else "Ending now",
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
        Text(text = "LIVE", color = Color.White, style = YancoType.Overline)
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
        Text(text = "LOCKED", color = LocalYancoPalette.current.Accent, style = YancoType.Overline)
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
) {
    val listState = rememberLazyListState()
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
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }

    val isCenter = distance == 0
    val absD = kotlin.math.abs(distance).coerceAtMost(6)
    val rotationY = (-distance.toFloat() * 16f).coerceIn(-58f, 58f)
    val scaleBase = if (isCenter) 1.18f else (1.0f - absD * 0.07f).coerceAtLeast(0.62f)
    val scale = if (focused) scaleBase * 1.04f else scaleBase
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
                // TvContextActionState). Touch long-press is unsupported on
                // the coverflow path — TV-only surface.
                .tvLongClickable(onLongPress)
                .focusable(interactionSource = interaction)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onActivate,
                ).semantics(mergeDescendants = true) { contentDescription = title },
            contentAlignment = Alignment.Center,
        ) {
            if (item.logoUrl?.isNotBlank() == true) {
                AsyncImage(
                    model = item.logoUrl,
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
                        label = formatResumeLabel(progress),
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
            )
        }
    }
}

@Composable
private fun CoverflowEmptyState(type: ContentType, favoritesFilter: Boolean) {
    val title =
        when {
            favoritesFilter ->
                when (type) {
                    ContentType.LIVE -> "No favorite channels"
                    ContentType.MOVIE -> "No favorite movies"
                    ContentType.SERIES -> "No favorite series"
                }
            else ->
                when (type) {
                    ContentType.LIVE -> "No channels"
                    ContentType.MOVIE -> "No movies"
                    ContentType.SERIES -> "No series"
                }
        }
    val body =
        when {
            favoritesFilter -> "Star something from the preview pane and it'll land here."
            else -> "Add an IPTV source in Settings → Sources to start watching."
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
    }
}
