package com.yancotv.android.ui.shell

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.prefs.AppPreferences
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
internal fun resolveGroupFilter(group: String): String? =
    group.takeIf { it != ALL_GROUPS && it != FAVORITES_GROUP }

/** True when the user has the synthetic "Favorites" chip active. */
internal fun isFavoritesFilter(group: String): Boolean = group == FAVORITES_GROUP

/**
 * Filter the backing group list against the user's hidden-groups set. The
 * chip bar renders whatever this returns, preserving original order.
 */
internal fun visibleGroupsFor(all: List<String>, hidden: Set<String>): List<String> =
    if (hidden.isEmpty()) all else all.filter { it !in hidden }

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
    railFocus: FocusRequester,
    controller: PlaybackController = koinInject(),
    epg: EpgRepository = koinInject(),
    favorites: FavoritesRepository = koinInject(),
    parental: ParentalRepository = koinInject(),
    history: WatchHistoryRepository = koinInject(),
    sources: SourceRepository = koinInject(),
    prefs: AppPreferences = koinInject(),
    modifier: Modifier = Modifier,
) {
    // Catalogue state — rail data source depends on whether the user has
    // the synthetic "Favorites" chip active.
    val groupsState = remember(type) { mutableStateListOf<String>() }
    val items = remember(type) { mutableStateListOf<ContentItem>() }
    var total by remember(type) { mutableStateOf(0L) }
    var loaded by remember(type) { mutableStateOf(0L) }
    var loading by remember(type) { mutableStateOf(false) }

    // Group load — drives the chip bar.
    LaunchedEffect(type) {
        val loadedGroups = withContext(Dispatchers.IO) { repo.groups(type) }
        groupsState.clear()
        groupsState.addAll(loadedGroups)
    }

    val hiddenGroups by prefs.hiddenGroupsFlow.collectAsState()
    val visibleGroups = remember(groupsState.toList(), hiddenGroups) {
        visibleGroupsFor(groupsState.toList(), hiddenGroups)
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
    val isFavoritesFilter = isFavoritesFilter(group)

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
            }
        }
    } else {
        LaunchedEffect(type, group) {
            items.clear()
            total = withContext(Dispatchers.IO) { repo.count(type, groupFilter) }
            loaded = 0L
            val first = withContext(Dispatchers.IO) {
                repo.page(type, groupFilter, 0L, PAGE_SIZE)
            }
            items.addAll(first)
            loaded += first.size
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
        } else if (focusedItem == null || focusedItem !in items) {
            val idx = focusedIndex.coerceIn(0, items.size - 1)
            focusedItem = items[idx]
        }
    }

    // Source-name lookup for the focused card (surfaces as a hero meta chip
    // for movies/series). Runs off-main so we don't block the frame the
    // hero recomposes on focus change.
    var sourceName by remember(focusedItem?.sourceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedItem?.sourceId) {
        val sid = focusedItem?.sourceId
        sourceName = if (sid.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching { sources.getById(sid)?.name }.getOrNull()
        }
    }

    // Now/Next for live channels — keep it responsive so the hero reflects
    // the right program even as the user scrolls past a channel.
    var nowNextMap by remember(type) { mutableStateOf<Map<String, NowNext>>(emptyMap()) }
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    if (type == ContentType.LIVE) {
        LaunchedEffect(type, items.size) {
            snapshotFlow { focusedItem?.tvgId to items.map { it.tvgId }.filterNotNull().distinct() }
                .distinctUntilChanged()
                .collect { (_, tvgIds) ->
                    if (tvgIds.isEmpty()) return@collect
                    nowSeconds = System.currentTimeMillis() / 1000L
                    val ids = tvgIds.take(60) // cap the batch — no reason to fetch >60 at once
                    nowNextMap = withContext(Dispatchers.IO) { epg.getNowNextBatch(ids) }
                }
        }
        LaunchedEffect(type) {
            while (true) {
                delay(EPG_TICK_MS)
                nowSeconds = System.currentTimeMillis() / 1000L
                val ids = items.mapNotNull { it.tvgId?.takeIf { id -> id.isNotBlank() } }
                    .distinct()
                    .take(60)
                if (ids.isNotEmpty()) {
                    nowNextMap = withContext(Dispatchers.IO) { epg.getNowNextBatch(ids) }
                }
            }
        }
    }

    // Favorite state for the hero's secondary CTA. Reactive so toggling
    // elsewhere (e.g. long-press menu) updates the hero without a focus
    // round-trip.
    var isFav by remember(focusedItem?.id) { mutableStateOf(false) }
    LaunchedEffect(focusedItem?.id) {
        val id = focusedItem?.id ?: run { isFav = false; return@LaunchedEffect }
        favorites.isFavoriteFlow(id).collect { isFav = it }
    }
    val scope = rememberCoroutineScope()

    // Visible item list after parental filters — rail + hero only see
    // these. Index math for activation must be done against this filtered
    // list, not the raw `items`, otherwise lockedIds → hidden rows would
    // shift the activation target.
    val visible = applyParentalFilters(
        items = items,
        hiddenIds = hiddenIds,
        hideAdult = parentalSettings.hideAdultContent,
    )

    val firstItemFocus = remember(type, group) { FocusRequester() }

    // Tail-prefetch for paged catalogues. Triggered on focus position (not
    // scroll position) so D-pad users who over-shoot via OK's restore don't
    // sit on a loading tail longer than needed.
    LaunchedEffect(focusedIndex, total, loaded, isFavoritesFilter) {
        if (isFavoritesFilter) return@LaunchedEffect
        if (loading) return@LaunchedEffect
        if (loaded >= total) return@LaunchedEffect
        if (focusedIndex < (loaded - PREFETCH_THRESHOLD)) return@LaunchedEffect
        loading = true
        val page = withContext(Dispatchers.IO) {
            repo.page(type, groupFilter, loaded, PAGE_SIZE)
        }
        items.addAll(page)
        loaded += page.size
        loading = false
    }

    val playing by controller.currentItem.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        // Top: categories. Thin and airy — dominating filter UI is the
        // old shell's sin.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.hasFocus) onChipsFocusChanged(true) },
        ) {
            CategoryChipBar(
                groups = visibleGroups,
                selected = group,
                onSelect = { picked ->
                    group = picked
                    focusedIndex = 0
                },
            )
        }

        // Middle: the feature hero takes the majority of the vertical
        // space. weight(1f) so it flexes with screen height and the rail
        // keeps its fixed ~230dp footprint.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            FeatureHero(
                focused = focusedItem,
                playing = playing,
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
                        val newState = withContext(Dispatchers.IO) { favorites.toggle(item.id) }
                        if (newState != optimistic) isFav = newState
                    }
                },
            )
        }

        // Bottom: rail of cards. Fixed height so the hero always has room.
        // Live TV cards are 120dp tall + chrome; posters are 124dp + title
        // line — 230dp accommodates both with focusStyle's scale lift.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .focusRequester(railFocus)
                .focusGroup()
                .onFocusChanged {
                    if (it.hasFocus) onRailFocusChanged(true)
                },
        ) {
            if (visible.isEmpty()) {
                BrowseEmptyState(type = type, favoritesFilter = isFavoritesFilter)
            } else {
                ContentRail(
                    type = type,
                    items = visible,
                    nowNextMap = nowNextMap,
                    nowSeconds = nowSeconds,
                    lockedIds = lockedIds,
                    focusedIndex = focusedIndex.coerceIn(0, (visible.size - 1).coerceAtLeast(0)),
                    firstItemFocus = firstItemFocus,
                    onFocus = { index, item ->
                        focusedIndex = index
                        focusedItem = item
                    },
                    onActivate = { index -> onActivate(visible.toList(), index) },
                    onLongPress = { actionsFor = it },
                )
            }
        }
    }

    actionsFor?.let { item ->
        ChannelActionsMenu(
            item = item,
            repo = parental,
            onDismiss = { actionsFor = null },
        )
    }
}

@Composable
private fun BrowseEmptyState(type: ContentType, favoritesFilter: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Space.page),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(Modifier.height(Space.xl))
        Text(
            text = when {
                favoritesFilter -> "No favorites yet"
                type == ContentType.LIVE -> "No channels"
                type == ContentType.MOVIE -> "No movies"
                else -> "No series"
            },
            color = YancoPalette.TextPrimary,
            style = YancoType.TitleL,
        )
        Text(
            text = when {
                favoritesFilter -> "Star something from the hero and it'll land here."
                else -> "Add a source in Settings → Sources to begin."
            },
            color = YancoPalette.TextMuted,
            style = YancoType.Body,
        )
    }
}
