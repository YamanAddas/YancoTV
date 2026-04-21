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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.PlayerLauncher
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.content.QualityBadge
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * FTS4-backed search. Debounced 220ms so each keystroke doesn't re-hit
 * SQLite while the user is still typing; short enough that a committed
 * word produces a result before they reach for the D-pad.
 *
 * Results stay mixed (live / movie / series) intentionally — the FTS
 * ranking already groups by content type via [ContentRepository.search]
 * and users searching for "marvel" want the whole corpus.
 */
@UnstableApi
@Composable
fun SearchScreen(
    isTv: Boolean,
    modifier: Modifier = Modifier,
    repo: ContentRepository = koinInject(),
    controller: PlaybackController = koinInject(),
) {
    var query by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<ContentItem>() }
    var searching by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            results.clear()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(220L)
        val matches = withContext(Dispatchers.IO) { repo.search(trimmed, limit = 100) }
        results.clear()
        results.addAll(matches)
        searching = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SearchField(value = query, onValueChange = { query = it })

        when {
            query.isBlank() -> EmptyState(
                title = "Search your library",
                subtitle = "Type a channel name, movie, or show to begin.",
            )
            searching && results.isEmpty() -> EmptyState(
                title = "Searching…",
                subtitle = "Looking through ${query.trim()}.",
            )
            results.isEmpty() -> EmptyState(
                title = "No matches",
                subtitle = "Try a shorter word or a different spelling.",
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results, key = { it.id }) { row ->
                    SearchRow(
                        item = row,
                        onActivate = {
                            val idx = results.indexOf(row)
                            val alreadyPlaying = controller.currentId == row.id
                            if (!alreadyPlaying) controller.play(results.toList(), idx)
                            if (!isTv || alreadyPlaying) PlayerLauncher.launch(context)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = if (focused) YancoPalette.FocusRing else YancoPalette.BorderSubtle
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(YancoPalette.BackgroundRaised)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = "Search channels, movies, series…",
                color = YancoPalette.TextMuted,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = YancoPalette.TextPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(YancoPalette.Accent),
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, color = YancoPalette.TextPrimary)
            Text(text = subtitle, color = YancoPalette.TextMuted)
        }
    }
}

@Composable
private fun SearchRow(item: ContentItem, onActivate: () -> Unit) {
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
            modifier = Modifier.fillMaxWidth(0.78f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = displayTitle,
                color = YancoPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = typeLabel(item.type), color = YancoPalette.Accent)
                item.groupName?.let {
                    Text(
                        text = it,
                        color = YancoPalette.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                QualityChips(badges = badges)
            }
        }
    }
}

private fun typeLabel(type: ContentType): String = when (type) {
    ContentType.LIVE -> "LIVE"
    ContentType.MOVIE -> "MOVIE"
    ContentType.SERIES -> "SERIES"
}
