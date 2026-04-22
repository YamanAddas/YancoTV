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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Full-screen detail hub for a movie or series. Entire page is a single
 * [LazyColumn] so TV D-pad focus traversal walks straight from the Play
 * button down through the credits, the season chips, and every episode
 * in order — no nested scroll regions fighting each other.
 *
 * Data loads lazily via [ContentDetailService]; the row's cached sync-
 * time metadata paints the title + poster immediately, and the enriched
 * fields (plot, cast, backdrop, episodes) fill in once the provider
 * round-trip completes. Enriched metadata + cover are persisted so the
 * next open is instant.
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
    val scope = rememberCoroutineScope()

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
        } catch (_: Throwable) { /* non-blocking */ }
    }

    val rendered = loaded?.item ?: item
    val metadata = loaded?.metadata ?: ContentMetadata()
    val episodes = loaded?.episodes.orEmpty()
    val seasons = remember(episodes) { episodes.groupBy { it.seasonNumber }.toSortedMap() }
    var selectedSeason by remember(seasons) {
        mutableStateOf(seasons.keys.firstOrNull() ?: 0)
    }
    val visibleEpisodes = remember(seasons, selectedSeason) {
        seasons[selectedSeason].orEmpty()
    }

    // Single LazyColumn governs the whole page so d-pad focus never has
    // to cross a scroll-container boundary. Items are keyed by a stable
    // string so focus survives episode-season swaps without rebuilding
    // every row. rememberLazyListState keeps scroll position across
    // metadata arrivals (plot → backdrop → episodes fill in over ~1s).
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item(key = "hero") {
            HeroBlock(
                item = rendered,
                metadata = metadata,
                episodes = episodes,
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
        }

        if (rendered.type == ContentType.SERIES) {
            item(key = "episodes_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "EPISODES",
                        color = YancoPalette.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    if (loading && episodes.isEmpty()) {
                        CircularProgressIndicator(
                            color = YancoPalette.Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.width(14.dp).height(14.dp),
                        )
                    } else {
                        Text(
                            text = "${episodes.size} total",
                            color = YancoPalette.TextMuted,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            if (seasons.size > 1) {
                item(key = "season_chips") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 48.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        seasons.keys.forEach { season ->
                            SeasonChip(
                                label = if (season == 0) "Specials" else "Season $season",
                                selected = season == selectedSeason,
                                count = seasons[season]?.size ?: 0,
                                onClick = { selectedSeason = season },
                            )
                        }
                    }
                }
            }
            if (visibleEpisodes.isEmpty() && !loading) {
                item(key = "no_episodes") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = "No episodes available.",
                            color = YancoPalette.TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            items(visibleEpisodes, key = { "ep:${it.id}" }) { ep ->
                Box(
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 3.dp),
                ) {
                    EpisodeRow(ep = ep, onClick = { onPlayEpisode(rendered, ep) })
                }
            }
        }
    }

    // Auto-focus Play on open so the user can press OK immediately. The
    // LazyColumn renders the hero eagerly; by the time the effect fires
    // the FocusRequester is attached and ready.
    LaunchedEffect(Unit) {
        runCatching { playFocus.requestFocus() }
    }
}

@Composable
private fun HeroBlock(
    item: ContentItem,
    metadata: ContentMetadata,
    episodes: List<EpisodeInfo>,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onBack: () -> Unit,
    playFocus: FocusRequester,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        BackdropHero(url = backdropUrlOf(item, metadata))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(140.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Poster(url = item.logoUrl ?: metadata.tmdbPosterUrl)
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = item.cleanTitle?.ifBlank { null } ?: item.title,
                        color = YancoPalette.TextPrimary,
                        fontSize = 30.sp,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Bold,
                    )
                    metadata.tagline?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = YancoPalette.AccentGlow,
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                    MetaLine(metadata, item.type, episodeCount = episodes.size)
                    metadata.plot?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = YancoPalette.TextPrimary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            maxLines = 4,
                        )
                    }
                    ActionRow(
                        primaryLabel = when (item.type) {
                            ContentType.SERIES -> if (episodes.isNotEmpty())
                                "Play S${episodes.first().seasonNumber}E${episodes.first().episodeNumber}"
                            else "Play"
                            else -> "Play"
                        },
                        isFavorite = isFavorite,
                        onPlay = onPlay,
                        onFavoriteToggle = onFavoriteToggle,
                        onBack = onBack,
                        playFocus = playFocus,
                    )
                    val cast = metadata.cast?.takeIf { it.isNotBlank() }
                    val director = metadata.director?.takeIf { it.isNotBlank() }
                    if (cast != null || director != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            director?.let { CreditRow(label = "Director", value = it) }
                            cast?.let { CreditRow(label = "Cast", value = it) }
                        }
                    }
                }
            }
        }
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
            .height(380.dp)
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            YancoPalette.BackgroundDeep.copy(alpha = 0.75f),
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
            .width(170.dp)
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
        meta.rating?.takeIf { it.isNotBlank() }?.let { add("\u2605 $it") }
        meta.genre?.takeIf { it.isNotBlank() }?.let { add(it) }
        meta.duration?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (type == ContentType.SERIES && episodeCount > 0) add("$episodeCount episodes")
    }
    if (bits.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        bits.forEachIndexed { i, text ->
            if (i > 0) {
                Text(text = "\u00b7", color = YancoPalette.TextMuted, fontSize = 12.sp)
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
        Text(text = value, color = YancoPalette.TextPrimary, fontSize = 11.sp)
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
            focusRequester = playFocus,
        )
        SecondaryButton(
            label = if (isFavorite) "\u2605 In favourites" else "\u2606 Add to favourites",
            onClick = onFavoriteToggle,
            accent = isFavorite,
        )
        SecondaryButton(label = "Back", onClick = onBack, accent = false)
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, focusRequester: FocusRequester) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) YancoPalette.AccentGlow else YancoPalette.Accent
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .focusRequester(focusRequester)
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
        accent -> YancoPalette.Accent.copy(alpha = 0.18f)
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
        Text(text = count.toString(), color = YancoPalette.TextMuted, fontSize = 11.sp)
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
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        ep.duration?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, color = YancoPalette.TextMuted, fontSize = 11.sp)
        }
    }
}
