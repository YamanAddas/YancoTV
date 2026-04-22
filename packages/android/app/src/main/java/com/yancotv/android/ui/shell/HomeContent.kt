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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Home landing surface. Two scrollable rails: Continue Watching (VOD
 * resume points from the watch-history table) and Favorites (the most
 * recently-starred items). Empty until the user watches or stars
 * something — then becomes the primary jump-back-in surface.
 *
 * Focus behavior: LazyRow handles horizontal D-pad automatically. The
 * vertical column wraps in [verticalScroll] so a user on a short TV
 * panel can still reach both rails.
 */
@UnstableApi
@Composable
fun HomeContent(
    onPlay: (List<ContentItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
    history: WatchHistoryRepository = koinInject(),
    favorites: FavoritesRepository = koinInject(),
    parental: ParentalRepository = koinInject(),
) {
    val continueWatching = remember { mutableStateListOf<ContentItem>() }
    val favoriteList by favorites.allFlow().collectAsState(initial = emptyList())
    val hiddenIds by parental.hiddenIds.collectAsState()
    val lockedIds by parental.lockedIds.collectAsState()

    LaunchedEffect(Unit) {
        val recent = withContext(Dispatchers.IO) {
            runCatching { history.recent(limit = 20) }.getOrElse { emptyList() }
        }
        continueWatching.clear()
        continueWatching.addAll(
            recent.map { entry -> entry.content to entry }
                .filter { it.first.id !in hiddenIds }
                .distinctBy { it.first.id }
                .take(12)
                .map { it.first },
        )
    }

    val favoriteItems = remember(favoriteList, hiddenIds) {
        favoriteList.map { it.content }
            .filter { it.id !in hiddenIds }
            .take(20)
    }

    val resumeByContent = remember(continueWatching) { mutableStateOf<Map<String, HistoryEntry>>(emptyMap()) }
    LaunchedEffect(continueWatching.size) {
        val map = withContext(Dispatchers.IO) {
            history.recent(limit = 30).associateBy { it.contentId }
        }
        resumeByContent.value = map
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (continueWatching.isEmpty() && favoriteItems.isEmpty()) {
            EmptyHome(modifier = Modifier.padding(horizontal = 24.dp))
            return@Column
        }

        if (continueWatching.isNotEmpty()) {
            Rail(
                title = "Continue watching",
                items = continueWatching,
                lockedIds = lockedIds,
                resumeByContent = resumeByContent.value,
                onPlay = { item ->
                    val idx = continueWatching.indexOfFirst { it.id == item.id }
                    if (idx >= 0) onPlay(continueWatching.toList(), idx)
                },
            )
        }
        if (favoriteItems.isNotEmpty()) {
            Rail(
                title = "Favorites",
                items = favoriteItems,
                lockedIds = lockedIds,
                resumeByContent = resumeByContent.value,
                onPlay = { item ->
                    val idx = favoriteItems.indexOfFirst { it.id == item.id }
                    if (idx >= 0) onPlay(favoriteItems, idx)
                },
            )
        }
    }
}

@Composable
private fun Rail(
    title: String,
    items: List<ContentItem>,
    lockedIds: Set<String>,
    resumeByContent: Map<String, HistoryEntry>,
    onPlay: (ContentItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            color = YancoPalette.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                Tile(
                    item = item,
                    locked = item.id in lockedIds,
                    resume = resumeByContent[item.id],
                    onClick = { onPlay(item) },
                )
            }
        }
    }
}

@Composable
private fun Tile(
    item: ContentItem,
    locked: Boolean,
    resume: HistoryEntry?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = if (focused) YancoPalette.FocusRing else YancoPalette.BorderSubtle
    val progressPct = resume?.let { entry ->
        val dur = entry.durationSeconds ?: return@let 0f
        if (dur <= 0) 0f else (entry.positionSeconds / dur).toFloat().coerceIn(0f, 1f)
    } ?: 0f

    Column(
        modifier = Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(YancoPalette.BackgroundRaised)
            .border(1.5.dp, borderColor, RoundedCornerShape(8.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(YancoPalette.BackgroundDeep),
            contentAlignment = Alignment.Center,
        ) {
            if (!item.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            } else {
                Text(
                    text = (item.cleanTitle?.ifBlank { null } ?: item.title).take(2),
                    color = YancoPalette.TextMuted,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (locked) {
                Text(
                    text = "\uD83D\uDD12",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    fontSize = 18.sp,
                )
            }
        }
        // Resume progress bar — only render when we have duration info.
        if (progressPct > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(YancoPalette.BackgroundHover),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((progressPct).coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(YancoPalette.Accent),
                )
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.cleanTitle?.ifBlank { null } ?: item.title,
                color = YancoPalette.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = when {
                    resume != null && resume.durationSeconds != null -> "Resume " +
                        formatMmSs((resume.positionSeconds).roundToInt())
                    !item.groupName.isNullOrBlank() -> item.groupName!!
                    else -> item.type.name.lowercase().replaceFirstChar(Char::uppercase)
                },
                color = YancoPalette.TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptyHome(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Welcome to YancoTV",
            color = YancoPalette.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Add a source in Settings → Sources, then pick a channel to start watching.",
            color = YancoPalette.TextMuted,
            fontSize = 14.sp,
        )
        Text(
            text = "Your watched + starred items will show up here.",
            color = YancoPalette.TextMuted,
            fontSize = 12.sp,
        )
    }
}

private fun formatMmSs(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val m = s / 60
    val r = s % 60
    return if (m >= 60) {
        val h = m / 60
        val mm = m % 60
        String.format("%d:%02d:%02d", h, mm, r)
    } else {
        String.format("%d:%02d", m, r)
    }
}
