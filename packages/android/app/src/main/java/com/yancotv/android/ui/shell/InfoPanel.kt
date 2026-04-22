package com.yancotv.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Right-side info strip. Docks the mini preview at the top, then shows
 * metadata for whichever row is currently focused, with a star button to
 * favorite/unfavorite that row. The star reads synchronously against the
 * favorites repo on focus change — favorite state is a single COUNT(*) so
 * the query is negligible, and doing it in a snapshotFlow just for style
 * would add a test surface for no win.
 */
@UnstableApi
@Composable
fun InfoPanel(
    item: ContentItem?,
    modifier: Modifier = Modifier,
    controller: PlaybackController = koinInject(),
    favorites: FavoritesRepository = koinInject(),
    history: WatchHistoryRepository = koinInject(),
    sources: SourceRepository = koinInject(),
) {
    val playing by controller.currentItem.collectAsState()
    var isFav by remember(item?.id) { mutableStateOf(false) }
    var resumeSeconds by remember(item?.id) { mutableStateOf<Long?>(null) }
    var sourceName by remember(item?.sourceId) { mutableStateOf<String?>(null) }
    var loadError by remember(item?.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // One-shot loaders for resume position + source name. These don't change
    // behind our back while the panel is open, so a Flow would be overkill.
    LaunchedEffect(item?.id) {
        val id = item?.id ?: run {
            resumeSeconds = null
            sourceName = null
            loadError = false
            return@LaunchedEffect
        }
        try {
            val resumeResult = if (item.type != ContentType.LIVE) {
                withContext(Dispatchers.IO) { history.positionFor(id) }?.takeIf { it >= 5L }
            } else null
            val nameResult = withContext(Dispatchers.IO) { sources.getById(item.sourceId)?.name }
            resumeSeconds = resumeResult
            sourceName = nameResult
            loadError = false
        } catch (t: Throwable) {
            Log.w("InfoPanel", "metadata load failed for $id: ${t.message}", t)
            loadError = true
        }
    }

    // Favorite state flows via SQLDelight — unstarring from FavoritesScreen
    // (or any other surface) updates the star here without a focus round-trip.
    LaunchedEffect(item?.id) {
        val id = item?.id ?: run { isFav = false; return@LaunchedEffect }
        try {
            favorites.isFavoriteFlow(id).collect { isFav = it }
        } catch (t: Throwable) {
            Log.w("InfoPanel", "favorites flow failed for $id: ${t.message}", t)
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(260.dp)
            .background(YancoPalette.BackgroundRaised)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (playing != null) {
            MiniPlayer(controller = controller)
            Spacer(Modifier.height(4.dp))
            Text(text = "Now playing", color = YancoPalette.TextMuted)
            Text(
                text = playing?.cleanTitle?.ifBlank { null } ?: playing?.title.orEmpty(),
                color = YancoPalette.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (item == null) {
            Text(text = "No selection", color = YancoPalette.TextMuted)
            return@Column
        }
        Text(text = item.title, color = YancoPalette.TextPrimary)
        item.groupName?.let { Text(text = it, color = YancoPalette.TextMuted) }
        Text(
            text = "Source: ${sourceName ?: "Unknown source"}",
            color = YancoPalette.TextMuted,
        )
        resumeSeconds?.let { seconds ->
            Text(
                text = "Resumes at ${formatTimestamp(seconds)}",
                color = YancoPalette.Accent,
            )
        }
        if (loadError) {
            Text(
                text = "Some details couldn't load.",
                color = YancoPalette.TextMuted,
            )
        }

        Spacer(Modifier.height(8.dp))
        FavoriteButton(
            starred = isFav,
            onToggle = {
                // Optimistic flip on main so the UI reacts instantly; the
                // actual insert/delete runs on IO. If the DB call fails
                // the next focus change will re-read true state.
                val optimistic = !isFav
                isFav = optimistic
                scope.launch {
                    val newState = withContext(Dispatchers.IO) { favorites.toggle(item.id) }
                    if (newState != optimistic) isFav = newState
                }
            },
        )
    }
}

private fun formatTimestamp(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(m, s)
    }
}

@Composable
private fun FavoriteButton(starred: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        focused -> YancoPalette.BackgroundHover
        starred -> YancoPalette.Accent.copy(alpha = 0.15f)
        else -> Color.Transparent
    }
    val border = if (focused) YancoPalette.FocusRing else YancoPalette.BorderSubtle
    val label = if (starred) "Remove from favourites" else "Add to favourites"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Plain-text star — we avoid emoji and haven't imported a vector set yet.
        // "★" is a basic-plane codepoint so it renders reliably on every TV font.
        Text(
            text = if (starred) "\u2605" else "\u2606",
            color = if (starred) YancoPalette.Accent else YancoPalette.TextPrimary,
        )
        Text(
            text = if (starred) "In favourites" else "Add to favourites",
            color = YancoPalette.TextPrimary,
        )
    }
}
