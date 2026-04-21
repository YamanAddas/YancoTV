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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.PlayerLauncher
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.content.QualityBadge
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import org.koin.compose.koinInject

/**
 * Dedicated Favorites section. Grouped by content type so a user flipping
 * between live channels and movies doesn't have to scan a mixed list.
 * Reloads on entry — favorites changes from InfoPanel in other sections
 * are picked up immediately without a manual refresh.
 */
@UnstableApi
@Composable
fun FavoritesScreen(
    isTv: Boolean,
    modifier: Modifier = Modifier,
    favorites: FavoritesRepository = koinInject(),
    controller: PlaybackController = koinInject(),
) {
    val items = remember { mutableStateListOf<ContentItem>() }
    var loading by remember { mutableStateOf(true) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        loading = true
        val loaded = withContext(Dispatchers.IO) { favorites.all().map { it.content } }
        items.clear()
        items.addAll(loaded)
        loading = false
    }

    val live = items.filter { it.type == ContentType.LIVE }
    val movies = items.filter { it.type == ContentType.MOVIE }
    val series = items.filter { it.type == ContentType.SERIES }

    if (items.isEmpty() && !loading) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(YancoPalette.BackgroundDeep)
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "No favourites yet", color = YancoPalette.TextPrimary)
                Text(
                    text = "Focus a channel or title and press the star in the info panel.",
                    color = YancoPalette.TextMuted,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Keep sections in a stable order (Live first — fastest to consume).
        if (live.isNotEmpty()) {
            item(key = "header-live") { SectionHeader("Live channels") }
            items(live, key = { "live:${it.id}" }) { row ->
                FavoriteRow(
                    item = row,
                    onActivate = {
                        val alreadyPlaying = controller.currentId == row.id
                        if (!alreadyPlaying) controller.play(live, live.indexOf(row))
                        if (!isTv || alreadyPlaying) PlayerLauncher.launch(context)
                    },
                    onRemove = {
                        favorites.remove(row.id)
                        items.removeAll { it.id == row.id }
                    },
                )
            }
        }
        if (movies.isNotEmpty()) {
            item(key = "header-movies") { SectionHeader("Movies") }
            items(movies, key = { "movie:${it.id}" }) { row ->
                FavoriteRow(
                    item = row,
                    onActivate = {
                        controller.play(movies, movies.indexOf(row))
                        PlayerLauncher.launch(context)
                    },
                    onRemove = {
                        favorites.remove(row.id)
                        items.removeAll { it.id == row.id }
                    },
                )
            }
        }
        if (series.isNotEmpty()) {
            item(key = "header-series") { SectionHeader("Series") }
            items(series, key = { "series:${it.id}" }) { row ->
                FavoriteRow(
                    item = row,
                    onActivate = {
                        controller.play(series, series.indexOf(row))
                        PlayerLauncher.launch(context)
                    },
                    onRemove = {
                        favorites.remove(row.id)
                        items.removeAll { it.id == row.id }
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = YancoPalette.TextMuted,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun FavoriteRow(
    item: ContentItem,
    onActivate: () -> Unit,
    onRemove: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) YancoPalette.BackgroundHover else YancoPalette.BackgroundRaised
    val border = if (focused) YancoPalette.FocusRing else YancoPalette.BorderSubtle
    val badges = remember(item.id) { QualityBadge.parse(item.title) }
    val displayTitle = remember(item.id) { item.cleanTitle?.ifBlank { null } ?: item.title }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onActivate)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(YancoPalette.BackgroundDeep),
            contentAlignment = Alignment.Center,
        ) {
            if (!item.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(0.7f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = displayTitle, color = YancoPalette.TextPrimary, maxLines = 1)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.groupName?.let {
                    Text(text = it, color = YancoPalette.TextMuted, maxLines = 1)
                }
                QualityChips(badges = badges)
            }
        }
        UnstarButton(onClick = onRemove)
    }
}

@Composable
private fun UnstarButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) YancoPalette.BackgroundHover else Color.Transparent
    val border = if (focused) YancoPalette.FocusRing else Color.Transparent
    Text(
        text = "\u2605 Remove",
        color = YancoPalette.Accent,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
