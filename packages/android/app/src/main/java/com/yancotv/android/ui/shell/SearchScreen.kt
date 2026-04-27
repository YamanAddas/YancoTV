package com.yancotv.android.ui.shell

import android.util.Log
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import com.yancotv.android.ui.theme.YancoShapes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.PlayerLauncher
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.android.ui.theme.LocalYancoPalette
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
    parental: com.yancotv.shared.parental.ParentalRepository = koinInject(),
) {
    // rememberSaveable so a rotation / process-death while the user has a
    // query typed doesn't wipe the term. Results are not saved — rerunning
    // the FTS query is cheap and keeps the snapshot small.
    var query by rememberSaveable { mutableStateOf("") }

    // MK.10.3 — voice search / Assistant deep-link. SearchOverlayState
    // stores the recognised query; we drain it on first frame so the
    // user lands on results immediately. Drained, not preserved, so
    // reopening search later starts blank as expected.
    LaunchedEffect(Unit) {
        SearchOverlayState.consumeInitialQuery()?.let { q -> query = q }
    }
    val results = remember { mutableStateListOf<ContentItem>() }
    var searching by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Parental gate — filter hidden IDs out of results, gate play on locked,
    // and apply the adult-content filter when enabled in Settings.
    val lockedIds by parental.lockedIds.collectAsState()
    val hiddenIds by parental.hiddenIds.collectAsState()
    val parentalSettings by parental.settings.collectAsState()
    val visible =
        remember(results.toList(), hiddenIds, parentalSettings.hideAdultContent) {
            results
                .toList()
                .let { if (hiddenIds.isEmpty()) it else it.filter { row -> row.id !in hiddenIds } }
                .let {
                    if (!parentalSettings.hideAdultContent) {
                        it
                    } else {
                        it.filterNot(com.yancotv.shared.parental.AdultContentFilter::isAdult)
                    }
                }
        }
    var pendingPlay by remember { mutableStateOf<(() -> Unit)?>(null) }
    val gatedPlay: (String, () -> Unit) -> Unit = { id, action ->
        if (id in lockedIds) pendingPlay = action else action()
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            results.clear()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(220L)
        val matches =
            withContext(Dispatchers.IO) {
                runCatching { repo.search(trimmed, limit = 100) }
                    .onFailure { Log.w("Yanco", "SearchScreen.search('$trimmed') failed: ${it.message}", it) }
                    .getOrElse { emptyList() }
            }
        results.clear()
        results.addAll(matches)
        searching = false
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(LocalYancoPalette.current.BackgroundDeep)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SearchField(value = query, onValueChange = { query = it })

        when {
            query.isBlank() ->
                EmptyState(
                    title = "Search your library",
                    subtitle = "Type a channel name, movie, or show to begin.",
                )
            searching && results.isEmpty() ->
                EmptyState(
                    title = "Searching…",
                    subtitle = "Searching for \"${query.trim()}\"…",
                )
            results.isEmpty() ->
                EmptyState(
                    title = "No matches",
                    subtitle = "Try a shorter word or a different spelling.",
                )
            else -> {
                // MK.search.rails — partition results by content type and
                // render each in its own horizontal rail of hex orbs.
                // Mirrors the Live TV / Movies / Series sections' visual
                // language (hex capsule + accent glow on focus). Empty
                // type buckets are skipped so the user never lands on a
                // dead row.
                val live = remember(visible) { visible.filter { it.type == ContentType.LIVE } }
                val movies = remember(visible) { visible.filter { it.type == ContentType.MOVIE } }
                val series = remember(visible) { visible.filter { it.type == ContentType.SERIES } }
                val onActivate: (ContentItem, List<ContentItem>) -> Unit = { item, bucket ->
                    gatedPlay(item.id) {
                        val idx = bucket.indexOf(item).coerceAtLeast(0)
                        val alreadyPlaying = controller.currentId == item.id
                        if (!alreadyPlaying) controller.play(bucket, idx)
                        if (!isTv || alreadyPlaying) PlayerLauncher.launch(context)
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (live.isNotEmpty()) {
                        item(key = "rail-live") {
                            SearchRail(
                                title = "Live TV",
                                items = live,
                                onActivate = { onActivate(it, live) },
                            )
                        }
                    }
                    if (movies.isNotEmpty()) {
                        item(key = "rail-movies") {
                            SearchRail(
                                title = "Movies",
                                items = movies,
                                onActivate = { onActivate(it, movies) },
                            )
                        }
                    }
                    if (series.isNotEmpty()) {
                        item(key = "rail-series") {
                            SearchRail(
                                title = "Series",
                                items = series,
                                onActivate = { onActivate(it, series) },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingPlay?.let { action ->
        com.yancotv.android.ui.parental.PinEntryDialog(
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

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = if (focused) LocalYancoPalette.current.FocusRing else LocalYancoPalette.current.BorderSubtle
    val fieldAnchor = rememberPlacedFocusAnchor()
    val keyboard = LocalSoftwareKeyboardController.current

    // Pull focus on entry so phone users land on an active field (IME pops
    // automatically) and TV users see the field highlighted for typing via
    // the remote. awaitAndRequest() suspends until the field's onPlaced hook
    // fires, then issues the focus request once — no delay-ladder race.
    LaunchedEffect(Unit) { fieldAnchor.awaitAndRequest() }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(LocalYancoPalette.current.BackgroundRaised)
                .border(1.dp, border, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = LocalYancoPalette.current.TextPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(LocalYancoPalette.current.Accent),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // Search IME button just hides the keyboard — the LaunchedEffect
            // on `query` already drives the actual FTS call after the 220ms
            // debounce, so there's nothing extra to fire here.
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .placedFocus(fieldAnchor)
                    .semantics { contentDescription = "Search channels, movies, and series" },
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = "Search channels, movies, series…",
                        color = LocalYancoPalette.current.TextMuted,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun EmptyState(
    title: String,
    subtitle: String,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, color = LocalYancoPalette.current.TextPrimary)
            Text(text = subtitle, color = LocalYancoPalette.current.TextMuted)
        }
    }
}

@Composable
private fun SearchRow(
    item: ContentItem,
    onActivate: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) LocalYancoPalette.current.BackgroundHover else LocalYancoPalette.current.BackgroundRaised
    val border = if (focused) LocalYancoPalette.current.FocusRing else LocalYancoPalette.current.BorderSubtle
    val badges = remember(item.id) { QualityBadge.parse(item.title) }
    val displayTitle = remember(item.id) { item.cleanTitle?.ifBlank { null } ?: item.title }

    Row(
        modifier =
            Modifier
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
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(LocalYancoPalette.current.BackgroundDeep),
            contentAlignment = Alignment.Center,
        ) {
            if (!item.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = item.title,
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
                color = LocalYancoPalette.current.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = typeLabel(item.type), color = LocalYancoPalette.current.Accent)
                item.groupName?.let {
                    Text(
                        text = it,
                        color = LocalYancoPalette.current.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                QualityChips(badges = badges)
            }
        }
    }
}

private fun typeLabel(type: ContentType): String =
    when (type) {
        ContentType.LIVE -> "LIVE"
        ContentType.MOVIE -> "MOVIE"
        ContentType.SERIES -> "SERIES"
    }

/**
 * MK.search.rails — per-type horizontal orb rail. Header above, LazyRow
 * of hex orbs below. The LazyRow's own focus traversal handles
 * left/right within the rail; vertical D-pad walks between rails via
 * the parent LazyColumn's focus search.
 */
@Composable
private fun SearchRail(
    title: String,
    items: List<ContentItem>,
    onActivate: (ContentItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
        ) {
            items(items, key = { it.id }) { item ->
                SearchOrb(item = item, onActivate = { onActivate(item) })
            }
        }
    }
}

/**
 * Search-results orb — hex capsule with logo + title. Visual language
 * matches the Live TV / Movies / Series sections' [ContentOrb] in
 * CoverflowSectionScreen, minus the 3D coverflow perspective math
 * (which only makes sense on a focused-index wheel). Focus chrome via
 * shared interactionSource per the native-android-mk skill.
 */
@Composable
private fun SearchOrb(
    item: ContentItem,
    onActivate: () -> Unit,
) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val title = remember(item.id) { item.cleanTitle?.ifBlank { null } ?: item.title }

    Column(
        modifier = Modifier.size(width = 144.dp, height = 188.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(144.dp)
                    .clip(YancoShapes.HexCapsule)
                    .background(
                        Brush.verticalGradient(
                            listOf(palette.BackgroundElevated, palette.BackgroundDeep),
                        ),
                    )
                    .border(
                        width = if (focused) 2.dp else 1.dp,
                        color = if (focused) palette.FocusRing else palette.PanelBorder,
                        shape = YancoShapes.HexCapsule,
                    )
                    .focusable(interactionSource = interaction)
                    .clickable(interactionSource = interaction, indication = null, onClick = onActivate),
            contentAlignment = Alignment.Center,
        ) {
            if (!item.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                )
            } else {
                Text(
                    text = title.take(1).uppercase(),
                    color = palette.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text = title,
            color = if (focused) palette.Accent else palette.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
