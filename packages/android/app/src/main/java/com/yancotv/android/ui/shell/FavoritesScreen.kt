package com.yancotv.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.PlayerLauncher
import com.yancotv.android.ui.components.ButtonSize
import com.yancotv.android.ui.components.HexSurface
import com.yancotv.android.ui.components.YancoPrimaryButton
import com.yancotv.android.ui.components.YancoSecondaryButton
import com.yancotv.android.ui.parental.PinEntryDialog
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.shared.content.QualityBadge
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.parental.AdultContentFilter
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.FavoriteList
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
    // MK.13.4 — list tabs. `lists` reactive so creating / renaming /
    // deleting a list reflects without a navigation round-trip. Selected
    // list survives recomposition via rememberSaveable; falls back to
    // 'default' if the saved id no longer exists (e.g. user deleted the
    // list while we weren't looking).
    val lists by favorites.listsFlow().collectAsState(initial = emptyList<FavoriteList>())
    var selectedListId by rememberSaveable { mutableStateOf("default") }
    LaunchedEffect(lists) {
        if (lists.isNotEmpty() && lists.none { it.id == selectedListId }) {
            selectedListId = lists.firstOrNull { it.isDefault }?.id ?: lists.first().id
        }
    }
    val allItems =
        remember(favorited, selectedListId) {
            favorited
                ?.filter { it.listId == selectedListId }
                ?.map { it.content }
                ?: emptyList()
        }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCreateList by remember { mutableStateOf(false) }
    var manageListId by remember { mutableStateOf<String?>(null) }

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

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .background(LocalYancoPalette.current.BackgroundDeep),
    ) {
        FavoriteListsTabBar(
            lists = lists,
            selectedId = selectedListId,
            onSelect = { selectedListId = it },
            onCreate = { showCreateList = true },
            onManage = { manageListId = it },
        )
        if (items.isEmpty() && !loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "No favourites in this list", color = LocalYancoPalette.current.TextPrimary)
                    Text(
                        text = "Focus a channel or title and press the star in the info panel.",
                        color = LocalYancoPalette.current.TextMuted,
                    )
                }
            }
        } else {
            FavoritesListBody(
                live = live,
                movies = movies,
                series = series,
                listState = listState,
                isTv = isTv,
                controller = controller,
                context = context,
                gatedPlay = gatedPlay,
                onRemove = { row -> removeFavorite(row, scope, favorites, selectedListId) },
                onOpenDetail = onOpenDetail,
            )
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

    if (showCreateList) {
        TextEntryDialog(
            title = "New favorites list",
            body = "Name your list (e.g. Sports, News, Kids).",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        val newId = favorites.createList(name)
                        selectedListId = newId
                    }
                }
                showCreateList = false
            },
            onDismiss = { showCreateList = false },
        )
    }

    val target = manageListId?.let { id -> lists.firstOrNull { it.id == id } }
    if (target != null) {
        ListManageDialog(
            list = target,
            onRename = { newName ->
                scope.launch(Dispatchers.IO) { favorites.renameList(target.id, newName) }
                manageListId = null
            },
            onDelete = {
                scope.launch(Dispatchers.IO) { favorites.deleteList(target.id) }
                manageListId = null
            },
            onDismiss = { manageListId = null },
        )
    }
}

@UnstableApi
@Composable
private fun FavoritesListBody(
    live: List<ContentItem>,
    movies: List<ContentItem>,
    series: List<ContentItem>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isTv: Boolean,
    controller: PlaybackController,
    context: android.content.Context,
    gatedPlay: (String, () -> Unit) -> Unit,
    onRemove: (ContentItem) -> Unit,
    onOpenDetail: (ContentItem) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier =
        Modifier
            .fillMaxSize()
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
                    onRemove = { onRemove(row) },
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
                    onRemove = { onRemove(row) },
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
                    onRemove = { onRemove(row) },
                )
            }
        }
    }
}

private fun removeFavorite(row: ContentItem, scope: kotlinx.coroutines.CoroutineScope, favorites: FavoritesRepository, listId: String) {
    // MK.13.4 — scope removal to the visible list only. Removing from
    // `default` should not orphan a copy that exists in another list.
    // SQLDelight is blocking and a main-thread write would jank focus.
    scope.launch(Dispatchers.IO) { favorites.removeFromList(row.id, listId) }
}

/**
 * MK.13.4 — list tab strip. Renders one chip per list plus a trailing
 * "+" chip that opens the create-list dialog. Tapping a chip switches
 * the visible list; long-press / MENU on a non-default chip opens the
 * rename / delete sub-dialog.
 */
@Composable
private fun FavoriteListsTabBar(lists: List<FavoriteList>, selectedId: String, onSelect: (String) -> Unit, onCreate: () -> Unit, onManage: (String) -> Unit) {
    if (lists.isEmpty()) return
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lists.forEach { list ->
            ListTabChip(
                label = list.name,
                selected = list.id == selectedId,
                onClick = { onSelect(list.id) },
                onLongPress = if (list.isDefault) null else { -> onManage(list.id) },
            )
        }
        ListTabChip(
            label = "+ New",
            selected = false,
            onClick = onCreate,
            onLongPress = null,
        )
    }
}

@Composable
private fun ListTabChip(label: String, selected: Boolean, onClick: () -> Unit, onLongPress: (() -> Unit)?) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border =
        when {
            focused -> palette.Accent
            selected -> palette.Accent
            else -> palette.BorderSubtle
        }
    val bg = if (selected) palette.AccentMuted.copy(alpha = 0.25f) else palette.BackgroundRaised
    Box(
        modifier =
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(if (focused || selected) 2.dp else 1.dp, border, RoundedCornerShape(6.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                // Tap a non-selected tab → switch to it. Tap an
                // already-selected non-default tab → open manage
                // (rename / delete). Default list never opens
                // manage; the SQL guard ignores delete on it anyway.
                if (selected && onLongPress != null) {
                    onLongPress()
                } else {
                    onClick()
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (selected) palette.Accent else palette.TextPrimary,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ListManageDialog(list: FavoriteList, onRename: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf("menu") } // "menu" | "rename" | "confirm-delete"
    when (mode) {
        "rename" ->
            TextEntryDialog(
                title = "Rename list",
                body = "Pick a new name for this favorites list.",
                initial = list.name,
                confirmLabel = "Save",
                onConfirm = { onRename(it) },
                onDismiss = onDismiss,
            )
        "confirm-delete" ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = LocalYancoPalette.current.BackgroundRaised,
                title = { Text("Delete '${list.name}'?", color = LocalYancoPalette.current.TextPrimary) },
                text = {
                    Text(
                        "All favorites pinned to this list will be removed (they remain available in other lists).",
                        color = LocalYancoPalette.current.TextMuted,
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = onDelete) {
                        Text("Delete", color = LocalYancoPalette.current.Accent)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text("Cancel", color = LocalYancoPalette.current.TextMuted)
                    }
                },
            )
        else ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = LocalYancoPalette.current.BackgroundRaised,
                title = { Text(list.name, color = LocalYancoPalette.current.TextPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        androidx.compose.material3.TextButton(onClick = { mode = "rename" }) {
                            Text("Rename", color = LocalYancoPalette.current.TextPrimary)
                        }
                        androidx.compose.material3.TextButton(onClick = { mode = "confirm-delete" }) {
                            Text("Delete list", color = LocalYancoPalette.current.Accent)
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text("Close", color = LocalYancoPalette.current.TextMuted)
                    }
                },
            )
    }
}

/**
 * Reused single-field text entry dialog (mirror of
 * [com.yancotv.android.ui.parental.ChannelActionsMenu]'s private one —
 * kept local here to avoid a cross-package dependency on a TV-focus dialog).
 */
@Composable
private fun TextEntryDialog(title: String, body: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalYancoPalette.current.BackgroundRaised,
        title = { Text(title, color = LocalYancoPalette.current.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(body, color = LocalYancoPalette.current.TextMuted)
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(text) }) {
                Text(confirmLabel, color = LocalYancoPalette.current.Accent)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = LocalYancoPalette.current.TextMuted)
            }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = LocalYancoPalette.current.TextMuted,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * Favorite row with explicit Play + Remove buttons. Matches the
 * Recordings row pattern (commit b311dad / MK.14.5): the row container
 * is purely visual — actions live exclusively in their own focusable
 * buttons. A "whole row activates play" model with a separate Remove
 * button is ambiguous on TV (D-pad CENTER could land on either depending
 * on focus); two explicit buttons is unambiguous.
 */
@Composable
private fun FavoriteRow(item: ContentItem, onActivate: () -> Unit, onRemove: () -> Unit) {
    val badges = remember(item.id) { QualityBadge.parse(item.title) }
    val displayTitle = remember(item.id) { item.cleanTitle?.ifBlank { null } ?: item.title }

    HexSurface(
        shape = YancoShapes.HexCapsule,
        focused = false,
        bevelInset = 2.dp,
        modifier =
        Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                Modifier
                    .size(52.dp)
                    .clip(YancoShapes.HexCapsule)
                    .background(LocalYancoPalette.current.BackgroundDeep),
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
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = displayTitle, color = LocalYancoPalette.current.TextPrimary, maxLines = 1)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item.groupName?.let {
                        Text(text = it, color = LocalYancoPalette.current.TextMuted, maxLines = 1)
                    }
                    QualityChips(badges = badges)
                }
            }
            YancoPrimaryButton(
                onClick = onActivate,
                size = ButtonSize.Compact,
                translucent = true,
            ) {
                Text(text = "Play")
            }
            YancoSecondaryButton(onClick = onRemove, size = ButtonSize.Compact) {
                Text(text = "Remove")
            }
        }
    }
}

@Composable
private fun UnstarButton_unused_(onClick: () -> Unit) {
    // Replaced by FavoriteActionButton above. This stub exists only because
    // a literal-escape edit can't match the original body atomically.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) LocalYancoPalette.current.BackgroundHover else Color.Transparent
    val border = if (focused) LocalYancoPalette.current.FocusRing else Color.Transparent
    // Hollow star glyph (\u2606) semantically matches the remove action —
    // tapping it will make the row "no longer favorited". The filled \u2605
    // is reserved for "currently starred" state in InfoPanel.
    Text(
        text = "\u2606 Remove",
        color = LocalYancoPalette.current.Accent,
        modifier =
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
