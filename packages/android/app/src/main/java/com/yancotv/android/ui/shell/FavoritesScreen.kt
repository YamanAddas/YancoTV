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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.PlayerLauncher
import com.yancotv.android.ui.components.HexSurface
import com.yancotv.android.ui.parental.PinEntryDialog
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.shared.content.QualityBadge
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.parental.AdultContentFilter
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    onOpenDetail: (ContentItem) -> Unit,
    modifier: Modifier = Modifier,
    favorites: FavoritesRepository = koinInject(),
    controller: PlaybackController = koinInject(),
    parental: ParentalRepository = koinInject(),
) {
    // Reactive list: SQLDelight drives recomposition only when the favorites
    // table actually changes, so unstarring from InfoPanel on another screen
    // updates this list without a navigation round-trip.
    val favorited by favorites.allFlow().collectAsState(initial = null)
    val loading = favorited == null
    val allItems = remember(favorited) { favorited?.map { it.content } ?: emptyList() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Parental filters: hidden_ids drop out of favourites entirely (a hide
    // should feel consistent everywhere), lockedIds flag the row + gate the
    // play handler. The adult filter layers on top when the user has
    // opted into "Hide adult-tagged content" in Settings → Parental.
    val lockedIds by parental.lockedIds.collectAsState()
    val hiddenIds by parental.hiddenIds.collectAsState()
    val parentalSettings by parental.settings.collectAsState()
    val items =
        remember(allItems, hiddenIds, parentalSettings.hideAdultContent) {
            allItems
                .let { if (hiddenIds.isEmpty()) it else it.filterNot { row -> row.id in hiddenIds } }
                .let { if (!parentalSettings.hideAdultContent) it else it.filterNot(AdultContentFilter::isAdult) }
        }
    var pendingPlay by remember { mutableStateOf<(() -> Unit)?>(null) }
    val gatedPlay: (String, () -> Unit) -> Unit = { id, action ->
        if (id in lockedIds) pendingPlay = action else action()
    }

    // Re-partition only when the flow emits a new list, not on every recomp.
    val live = remember(items) { items.filter { it.type == ContentType.LIVE } }
    val movies = remember(items) { items.filter { it.type == ContentType.MOVIE } }
    val series = remember(items) { items.filter { it.type == ContentType.SERIES } }
    // Hoist scroll state so a section becoming empty and re-added (e.g.
    // unstarring the last movie, then re-starring one) keeps the user's
    // scroll position rather than snapping back to the top.
    val listState = rememberLazyListState()

    if (items.isEmpty() && !loading) {
        Box(
            modifier =
                modifier
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
        state = listState,
        modifier =
            modifier
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
                        gatedPlay(row.id) {
                            val action = resolveActivation(controller.currentId, row.id, isTv)
                            if (action.shouldCallPlay) controller.play(live, live.indexOf(row))
                            if (action.shouldLaunchFullscreen) PlayerLauncher.launch(context)
                        }
                    },
                    onRemove = { removeFavorite(row, scope, favorites) },
                )
            }
        }
        if (movies.isNotEmpty()) {
            item(key = "header-movies") { SectionHeader("Movies") }
            items(movies, key = { "movie:${it.id}" }) { row ->
                FavoriteRow(
                    item = row,
                    onActivate = {
                        gatedPlay(row.id) {
                            val action = resolveActivation(controller.currentId, row.id, isTv)
                            if (action.shouldCallPlay) controller.play(movies, movies.indexOf(row))
                            if (action.shouldLaunchFullscreen) PlayerLauncher.launch(context)
                        }
                    },
                    onRemove = { removeFavorite(row, scope, favorites) },
                )
            }
        }
        if (series.isNotEmpty()) {
            item(key = "header-series") { SectionHeader("Series") }
            items(series, key = { "series:${it.id}" }) { row ->
                FavoriteRow(
                    item = row,
                    onActivate = {
                        // Series containers are not Playable — calling
                        // controller.play would silently no-op (toPlayable
                        // returns null) but PlayerLauncher.launch would
                        // still open an empty fullscreen surface. Route
                        // through the host's detail overlay instead, same
                        // as HomeScreen.onBrowseActivate's SERIES branch.
                        gatedPlay(row.id) { onOpenDetail(row) }
                    },
                    onRemove = { removeFavorite(row, scope, favorites) },
                )
            }
        }
    }

    // MK.8.7.b — PIN gate for locked favourites. Same pattern as HomeScreen:
    // gatedPlay stashes the deferred action into pendingPlay, the dialog
    // runs it on success.
    pendingPlay?.let { action ->
        PinEntryDialog(
            title = "Channel locked",
            body = "Enter your PIN to watch this channel.",
            repo = parental,
            onSuccess = {
                action()
                pendingPlay = null
            },
            onDismiss = { pendingPlay = null },
        )
    }
}

private fun removeFavorite(
    row: ContentItem,
    scope: kotlinx.coroutines.CoroutineScope,
    favorites: FavoritesRepository,
) {
    // SQLDelight is blocking and a main-thread write would jank focus. The
    // UI-side list updates automatically via `favorites.allFlow()` once the
    // delete commits — no manual list mutation needed here.
    scope.launch(Dispatchers.IO) { favorites.remove(row.id) }
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
    val badges = remember(item.id) { QualityBadge.parse(item.title) }
    val displayTitle = remember(item.id) { item.cleanTitle?.ifBlank { null } ?: item.title }

    HexSurface(
        shape = YancoShapes.HexCapsule,
        focused = focused,
        bevelInset = 2.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .focusable(interactionSource = interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onActivate),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(52.dp)
                        .clip(YancoShapes.HexCapsule)
                        .background(YancoPalette.BackgroundDeep),
                contentAlignment = Alignment.Center,
            ) {
                if (!item.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.logoUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
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
}

@Composable
private fun UnstarButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) YancoPalette.BackgroundHover else Color.Transparent
    val border = if (focused) YancoPalette.FocusRing else Color.Transparent
    // Hollow star glyph (\u2606) semantically matches the remove action —
    // tapping it will make the row "no longer favorited". The filled \u2605
    // is reserved for "currently starred" state in InfoPanel.
    Text(
        text = "\u2606 Remove",
        color = YancoPalette.Accent,
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(6.dp))
                .focusable(interactionSource = interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
