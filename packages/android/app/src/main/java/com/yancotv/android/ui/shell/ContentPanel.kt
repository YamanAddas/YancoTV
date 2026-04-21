package com.yancotv.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.yancotv.shared.content.QualityBadge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.NowNext
import com.yancotv.shared.types.NowNextMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private const val PAGE_SIZE = 100L
private const val PREFETCH_THRESHOLD = 20

// EPG re-poll cadence. A minute is plenty — the progress-bar tick moves by
// ~1/3600 per second so a 60s refresh produces a barely-perceptible jump,
// and it keeps us cheap on slower TV devices where recomposing a 100-row
// LazyColumn every second is wasteful.
private const val EPG_TICK_MS = 60_000L

/**
 * Paged content list. Loads in chunks of [PAGE_SIZE] from SQLDelight to
 * keep initial paint cheap; when the user approaches the tail, the next
 * page is prefetched on IO. Reloads whenever [type] or [group] changes.
 *
 * Focus memory: [rememberSaveable] keyed by `"$type|$group"` restores the
 * previously focused index when returning to a filter.
 */
@Composable
fun ContentPanel(
    type: ContentType,
    group: String?,
    onItemFocus: (ContentItem) -> Unit,
    onItemActivate: (List<ContentItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
    repo: ContentRepository = koinInject(),
    epg: EpgRepository = koinInject(),
) {
    val items = remember(type, group) { mutableStateListOf<ContentItem>() }
    var total by remember(type, group) { mutableStateOf(0L) }
    var loaded by remember(type, group) { mutableStateOf(0L) }
    var loading by remember(type, group) { mutableStateOf(false) }

    LaunchedEffect(type, group) {
        items.clear()
        total = withContext(Dispatchers.IO) { repo.count(type, group) }
        loaded = 0L
        loadNextPage(repo, type, group, loaded) { page ->
            items.addAll(page)
            loaded += page.size
        }
    }

    val focusKey = "content-focus|${type.name}|${group ?: "_all"}"
    var focusedIndex by rememberSaveable(focusKey) { mutableStateOf(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusedIndex)

    LaunchedEffect(listState, total) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= items.size - PREFETCH_THRESHOLD && items.size < total && !loading }
            .collect {
                loading = true
                loadNextPage(repo, type, group, loaded) { page ->
                    items.addAll(page)
                    loaded += page.size
                    loading = false
                }
            }
    }

    // Now/next lookup for live channels only. We fetch for visible items
    // (with a small padding window) and re-poll every EPG_TICK_MS so the
    // progress bar advances. Skipped for movies/series where tvgId is null.
    var nowNextMap by remember(type) { mutableStateOf<NowNextMap>(emptyMap()) }
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    if (type == ContentType.LIVE) {
        LaunchedEffect(listState, items.size) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo }
                .map { visible ->
                    if (visible.isEmpty()) return@map emptyList<String>()
                    val first = (visible.first().index - PREFETCH_THRESHOLD).coerceAtLeast(0)
                    val last = (visible.last().index + PREFETCH_THRESHOLD).coerceAtMost(items.size - 1)
                    (first..last).mapNotNull { items.getOrNull(it)?.tvgId?.takeIf { id -> id.isNotBlank() } }
                }
                .distinctUntilChanged()
                .collect { tvgIds ->
                    nowSeconds = System.currentTimeMillis() / 1000L
                    nowNextMap = withContext(Dispatchers.IO) { epg.getNowNextBatch(tvgIds) }
                }
        }
        LaunchedEffect(type) {
            while (true) {
                delay(EPG_TICK_MS)
                nowSeconds = System.currentTimeMillis() / 1000L
                val tvgIds = listState.layoutInfo.visibleItemsInfo.mapNotNull {
                    items.getOrNull(it.index)?.tvgId?.takeIf { id -> id.isNotBlank() }
                }
                if (tvgIds.isNotEmpty()) {
                    nowNextMap = withContext(Dispatchers.IO) { epg.getNowNextBatch(tvgIds) }
                }
            }
        }
    }

    if (items.isEmpty() && !loading) {
        EmptyState(type = type, modifier = modifier)
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items, key = { it.id }) { item ->
            ContentRow(
                item = item,
                nowNext = item.tvgId?.let { nowNextMap[it] },
                nowSeconds = nowSeconds,
                onFocus = {
                    focusedIndex = items.indexOfFirst { it.id == item.id }
                    onItemFocus(item)
                },
                onActivate = {
                    val idx = items.indexOfFirst { it.id == item.id }
                    if (idx >= 0) onItemActivate(items.toList(), idx)
                },
            )
        }
    }
}

private suspend fun loadNextPage(
    repo: ContentRepository,
    type: ContentType,
    group: String?,
    offset: Long,
    onLoaded: (List<ContentItem>) -> Unit,
) {
    val page = withContext(Dispatchers.IO) { repo.page(type, group, offset, PAGE_SIZE) }
    onLoaded(page)
}

@Composable
private fun ContentRow(
    item: ContentItem,
    nowNext: NowNext?,
    nowSeconds: Long,
    onFocus: () -> Unit,
    onActivate: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    LaunchedEffect(focused) { if (focused) onFocus() }

    val bg = if (focused) YancoPalette.BackgroundHover else YancoPalette.BackgroundRaised
    val borderColor = if (focused) YancoPalette.FocusRing else YancoPalette.BorderSubtle

    val badges = remember(item.id) { QualityBadge.parse(item.title) }
    val displayTitle = remember(item.id) { item.cleanTitle?.ifBlank { null } ?: item.title }

    // Grow the row when a now/next line is rendered so the progress bar
    // doesn't crowd the title.
    val hasEpg = nowNext?.let { it.now != null || it.next != null } == true
    val rowHeight = if (hasEpg) 76.dp else 64.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onActivate)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LogoBox(url = item.logoUrl)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = displayTitle, color = YancoPalette.TextPrimary, maxLines = 1)
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.groupName?.let {
                    Text(text = it, color = YancoPalette.TextMuted, maxLines = 1)
                }
                QualityChips(badges = badges)
            }
            if (hasEpg) {
                NowNextLine(nowNext = nowNext, nowSeconds = nowSeconds)
            }
        }
    }
}

@Composable
private fun LogoBox(url: String?) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(YancoPalette.BackgroundDeep),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(2.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(type: ContentType, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when (type) {
                    ContentType.LIVE -> "No channels yet"
                    ContentType.MOVIE -> "No movies yet"
                    ContentType.SERIES -> "No series yet"
                },
                color = YancoPalette.TextPrimary,
            )
            Text(
                text = "Add a source from the Sources tab to begin.",
                color = YancoPalette.TextMuted,
            )
        }
    }
}
