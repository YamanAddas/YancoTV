package com.yancotv.android.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.content.ContentDetailService
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentMetadata
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.EpisodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Full-screen detail hub for a movie or series. Opens when the user
 * activates a MOVIE / SERIES row instead of kicking straight into
 * playback, matching the desktop app's "show me before you play me"
 * pattern.
 *
 * Layout:
 *  - Backdrop hero (top ~40 %, fades to the page background)
 *  - Poster on the left + title/plot/meta on the right
 *  - Action row: Play / Play episode-1 / Favorite toggle
 *  - Series: season selector + episodes grid under the actions
 *
 * Data loads lazily via [ContentDetailService] — the row's cached
 * metadata paints immediately, and a provider round-trip merges the
 * enriched fields (plot, cast, backdrop, episodes) back in as soon
 * as they arrive. The backdrop + poster persist to the DB so the next
 * open is instant.
 */
@UnstableApi
@Composable
fun ContentDetailScreen(
    item: ContentItem,
    onPlayContent: (ContentItem) -> Unit,
    onPlayEpisode: (ContentItem, EpisodeInfo) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    detailService: ContentDetailService = koinInject(),
    favorites: FavoritesRepository = koinInject(),
) {
    var loaded by remember(item.id) { mutableStateOf<ContentDetailService.Loaded?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var isFav by remember(item.id) { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(item.id) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching { detailService.load(item) }.getOrNull()
        }
        loaded = result
        loading = false
    }

    LaunchedEffect(item.id) {
        try {
            favorites.isFavoriteFlow(item.id).collect { isFav = it }
        } catch (_: Throwable) {
            // Favorite stream failures are non-blocking; we render the
            // detail anyway and let the star default to unstarred.
        }
    }

    val rendered = loaded?.item ?: item
    // Until the detail service finishes (first paint), show an empty
    // metadata stub — we intentionally don't decode the JSON blob here to
    // keep the app module free of kotlinx.serialization. The service
    // shares the same process, runs on IO, and the first paint window is
    // sub-100ms so the user sees the poster + title immediately and the
    // rest fills in.
    val metadata = loaded?.metadata ?: ContentMetadata()
    val episodes = loaded?.episodes.orEmpty()
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep),
    ) {
        BackdropHero(url = backdropUrlOf(rendered, metadata))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Spacer so the poster/title row starts roughly below the
            // backdrop gradient instead of fighting it at the top.
            Spacer(modifier = Modifier.height(120.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Poster(url = rendered.logoUrl ?: metadata.tmdbPosterUrl)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = rendered.cleanTitle?.ifBlank { null } ?: rendered.title,
                        color = YancoPalette.TextPrimary,
                        fontSize = 32.sp,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Bold,
                    )
                    metadata.tagline?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = YancoPalette.AccentGlow,
                            fontSize = 14.sp,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                    MetaLine(metadata, rendered.type, episodeCount = episodes.size)
                    metadata.plot?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = YancoPalette.TextPrimary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                    }
                    ActionRow(
                        primaryLabel = when (rendered.type) {
                            ContentType.SERIES -> if (episodes.isNotEmpty()) "Play S${episodes.first().seasonNumber}E${episodes.first().episodeNumber}" else "Play"
                            else -> "Play"
                        },
                        isFavorite = isFav,
                        onPlay = {
                            when (rendered.type) {
                                ContentType.SERIES -> episodes.firstOrNull()?.let {
                                    onPlayEpisode(rendered, it)
                                } ?: onPlayContent(rendered)
                                else -> onPlayContent(rendered)
                            }
                        },
                        onFavoriteToggle = {
                            val optimistic = !isFav
                            isFav = optimistic
                            scope.launch {
                                val newState = withContext(Dispatchers.IO) {
                                    runCatching { favorites.toggle(rendered.id) }.getOrElse { optimistic }
                                }
                                if (newState != optimistic) isFav = newState
                            }
                        },
                        onBack = onDismiss,
                        playFocus = playFocus,
                    )
                    val cast = metadata.cast?.takeIf { it.isNotBlank() }
                    val director = metadata.director?.takeIf { it.isNotBlank() }
                    if (cast != null || director != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            director?.let {
                                CreditRow(label = "Director", value = it)
                            }
                            cast?.let {
                                CreditRow(label = "Cast", value = it)
                            }
                        }
                    }
                }
            }

            if (rendered.type == ContentType.SERIES) {
                EpisodesSection(
                    episodes = episodes,
                    loading = loading,
                    onPick = { ep -> onPlayEpisode(rendered, ep) },
                )
            }

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        color = YancoPalette.Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.width(24.dp).height(24.dp),
                    )
                }
            }
        }
    }

    // Auto-focus Play on open so a TV user can press OK immediately without
    // hunting for a focus target under the backdrop.
    LaunchedEffect(Unit) {
        runCatching { playFocus.requestFocus() }
    }
}

private fun backdropUrlOf(item: ContentItem, meta: ContentMetadata): String? {
    return meta.backdropUrl?.takeIf { it.isNotBlank() }
        ?: meta.tmdbBackdropUrl?.takeIf { it.isNotBlank() }
        ?: item.logoUrl?.takeIf { it.isNotBlank() }
}

@Composable
private fun BackdropHero(url: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(YancoPalette.BackgroundRaised),
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Vertical gradient over the backdrop so the title/plot text always
        // has enough contrast no matter how bright the hero image is.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            YancoPalette.BackgroundDeep.copy(alpha = 0.65f),
                            YancoPalette.BackgroundDeep,
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun Poster(url: String?) {
    Box(
        modifier = Modifier
            .width(180.dp)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .background(YancoPalette.BackgroundRaised)
            .border(1.dp, YancoPalette.BorderSubtle, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "No artwork",
                color = YancoPalette.TextMuted,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun MetaLine(meta: ContentMetadata, type: ContentType, episodeCount: Int) {
    val bits = buildList {
        meta.releaseDate?.takeIf { it.isNotBlank() }?.let { add(it.take(4)) }
        meta.rating?.takeIf { it.isNotBlank() }?.let { add("★ $it") }
        meta.genre?.takeIf { it.isNotBlank() }?.let { add(it) }
        meta.duration?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (type == ContentType.SERIES && episodeCount > 0) add("$episodeCount episodes")
    }
    if (bits.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        bits.forEachIndexed { i, text ->
            if (i > 0) {
                Text(
                    text = "·",
                    color = YancoPalette.TextMuted,
                    fontSize = 12.sp,
                )
            }
            Text(
                text = text,
                color = YancoPalette.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CreditRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            color = YancoPalette.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            color = YancoPalette.TextPrimary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ActionRow(
    primaryLabel: String,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onBack: () -> Unit,
    playFocus: FocusRequester,
) {
    Row(
        modifier = Modifier.padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PrimaryButton(
            label = primaryLabel,
            onClick = onPlay,
            modifier = Modifier.focusRequester(playFocus),
        )
        SecondaryButton(
            label = if (isFavorite) "\u2605 In favourites" else "\u2606 Add to favourites",
            onClick = onFavoriteToggle,
            accent = isFavorite,
        )
        SecondaryButton(
            label = "Back",
            onClick = onBack,
            accent = false,
        )
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) YancoPalette.AccentGlow else YancoPalette.Accent
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = YancoPalette.BackgroundDeep,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit, accent: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        focused -> YancoPalette.BackgroundHover
        accent -> YancoPalette.Accent.copy(alpha = 0.15f)
        else -> Color.Transparent
    }
    val border = when {
        focused -> YancoPalette.FocusRing
        accent -> YancoPalette.Accent.copy(alpha = 0.5f)
        else -> YancoPalette.BorderSubtle
    }
    val textColor = if (accent) YancoPalette.Accent else YancoPalette.TextPrimary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EpisodesSection(
    episodes: List<EpisodeInfo>,
    loading: Boolean,
    onPick: (EpisodeInfo) -> Unit,
) {
    if (episodes.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        ) {
            Text(
                text = if (loading) "Loading episodes…" else "No episodes available.",
                color = YancoPalette.TextMuted,
                fontSize = 13.sp,
            )
        }
        return
    }
    // Group episodes by season so the user can pick a season first on long
    // multi-season shows. Keep the first season expanded by default; only
    // one season at a time so the list doesn't get unmanageably tall.
    val seasons = remember(episodes) { episodes.groupBy { it.seasonNumber }.toSortedMap() }
    var selectedSeason by remember(seasons) {
        mutableStateOf(seasons.keys.firstOrNull() ?: 0)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            seasons.keys.forEach { season ->
                val selected = season == selectedSeason
                SeasonChip(
                    label = if (season == 0) "Episodes" else "Season $season",
                    selected = selected,
                    count = seasons[season]?.size ?: 0,
                    onClick = { selectedSeason = season },
                )
            }
        }
        val current = seasons[selectedSeason].orEmpty()
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightWithCap(current.size),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(current, key = { _, ep -> ep.id }) { _, ep ->
                EpisodeRow(ep = ep, onClick = { onPick(ep) })
            }
        }
    }
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, count: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        focused -> YancoPalette.BackgroundHover
        selected -> YancoPalette.Accent.copy(alpha = 0.22f)
        else -> YancoPalette.BackgroundRaised
    }
    val border = when {
        focused -> YancoPalette.FocusRing
        selected -> YancoPalette.Accent.copy(alpha = 0.5f)
        else -> YancoPalette.BorderSubtle
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) YancoPalette.Accent else YancoPalette.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = count.toString(),
            color = YancoPalette.TextMuted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun EpisodeRow(ep: EpisodeInfo, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) YancoPalette.BackgroundHover else YancoPalette.BackgroundRaised
    val border = if (focused) YancoPalette.FocusRing else YancoPalette.BorderSubtle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "E%02d".format(ep.episodeNumber),
            color = YancoPalette.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(40.dp),
        )
        Text(
            text = ep.title.takeIf { it.isNotBlank() } ?: "Episode ${ep.episodeNumber}",
            color = YancoPalette.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        ep.duration?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = YancoPalette.TextMuted,
                fontSize = 11.sp,
            )
        }
    }
}

// LazyColumn inside a verticalScroll column needs an explicit height, but
// sizing it to match every single episode makes long shows (100+ eps)
// push the whole page off-screen. Cap at ~12 rows; the inner LazyColumn
// scrolls internally from there.
private fun Modifier.heightWithCap(itemCount: Int): Modifier {
    val rows = itemCount.coerceAtMost(12)
    // 52dp per row (height) + 4dp spacing — keep in sync with EpisodeRow.
    return this.height((rows * 56).dp.coerceAtLeast(56.dp))
}
