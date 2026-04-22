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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.ui.components.focusStyle
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.ShellDim
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.android.ui.theme.YancoType
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
    // to cross a scroll-container boundary.
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep),
        contentPadding = PaddingValues(bottom = Space.section),
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
                EpisodesSectionHeader(
                    loading = loading,
                    episodeCount = episodes.size,
                )
            }
            if (seasons.size > 1) {
                item(key = "season_chips") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.page, vertical = Space.xs),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.page, vertical = Space.md),
                    ) {
                        Text(
                            text = "No episodes available.",
                            color = YancoPalette.TextMuted,
                            style = YancoType.Body,
                        )
                    }
                }
            }
            items(visibleEpisodes, key = { "ep:${it.id}" }) { ep ->
                Box(
                    modifier = Modifier.padding(horizontal = Space.page, vertical = Space.xxs),
                ) {
                    EpisodeRow(ep = ep, onClick = { onPlayEpisode(rendered, ep) })
                }
            }
        }
    }

    // Auto-focus Play on open so the user can press OK immediately.
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
                .padding(horizontal = Space.page, vertical = Space.xxxl),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            // Push the content block below the backdrop gradient so the
            // title sits in the darkest band where the scrim reads best.
            Spacer(modifier = Modifier.height(220.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                Poster(url = item.logoUrl ?: metadata.tmdbPosterUrl)
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    if (!item.groupName.isNullOrBlank()) {
                        Text(
                            text = item.groupName!!.uppercase(),
                            color = YancoPalette.Accent,
                            style = YancoType.Overline,
                        )
                    }
                    Text(
                        text = item.cleanTitle?.ifBlank { null } ?: item.title,
                        color = YancoPalette.TextPrimary,
                        style = YancoType.DisplayCinematic,
                        maxLines = 2,
                    )
                    metadata.tagline?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = YancoPalette.AccentGlow,
                            style = YancoType.BodyLong,
                        )
                    }
                    MetaLine(metadata, item.type, episodeCount = episodes.size)
                    metadata.plot?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = YancoPalette.TextSecondary,
                            style = YancoType.BodyLong,
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
                        Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
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
            .height(ShellDim.heroHeight)
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
        // Dual gradient stack: horizontal left-side darken so the text
        // column reads over any backdrop, plus a vertical bottom fade
        // into the page background for a seamless hand-off.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            YancoPalette.BackgroundDeep.copy(alpha = 0.85f),
                            YancoPalette.BackgroundDeep.copy(alpha = 0.3f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            YancoPalette.BackgroundDeep.copy(alpha = 0.6f),
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
            .width(ShellDim.detailPosterWidth)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(Radius.panel))
            .background(YancoPalette.BackgroundRaised)
            .border(1.dp, YancoPalette.PanelBorder, RoundedCornerShape(Radius.panel)),
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
                style = YancoType.Caption,
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bits.forEachIndexed { i, text ->
            if (i > 0) {
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(YancoPalette.TextFaint),
                )
            }
            Text(
                text = text,
                color = YancoPalette.TextSecondary,
                style = YancoType.CaptionStrong,
            )
        }
    }
}

@Composable
private fun CreditRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            text = label.uppercase(),
            color = YancoPalette.TextMuted,
            style = YancoType.Overline,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            color = YancoPalette.TextPrimary,
            style = YancoType.Body,
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
        modifier = Modifier.padding(top = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        PrimaryButton(
            label = primaryLabel,
            onClick = onPlay,
            focusRequester = playFocus,
        )
        SecondaryButton(
            label = if (isFavorite) "In favourites" else "Add to favourites",
            icon = if (isFavorite) YancoIcons.StarFilled else YancoIcons.StarOutline,
            onClick = onFavoriteToggle,
            accent = isFavorite,
        )
        SecondaryButton(
            label = "Back",
            icon = null,
            onClick = onBack,
            accent = false,
        )
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, focusRequester: FocusRequester) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) YancoPalette.AccentGlow else YancoPalette.Accent
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.control))
            .background(bg)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Space.xxl, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(
            imageVector = YancoIcons.Play,
            contentDescription = null,
            tint = YancoPalette.BackgroundDeep,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            color = YancoPalette.BackgroundDeep,
            style = YancoType.LabelStrong,
        )
    }
}

@Composable
private fun SecondaryButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    accent: Boolean,
) {
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
        else -> YancoPalette.PanelBorder
    }
    val textColor = when {
        accent -> YancoPalette.Accent
        focused -> YancoPalette.TextPrimary
        else -> YancoPalette.TextSecondary
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.control))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(Radius.control))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            color = textColor,
            style = YancoType.Label,
        )
    }
}

@Composable
private fun EpisodesSectionHeader(loading: Boolean, episodeCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.page, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = "EPISODES",
            color = YancoPalette.Accent,
            style = YancoType.Overline,
        )
        if (loading && episodeCount == 0) {
            CircularProgressIndicator(
                color = YancoPalette.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Text(
                text = "$episodeCount total",
                color = YancoPalette.TextMuted,
                style = YancoType.Caption,
            )
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
        else -> YancoPalette.PanelBorder
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(Radius.pill))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected || focused) YancoPalette.Accent else YancoPalette.TextPrimary,
            style = YancoType.Label,
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(YancoPalette.BackgroundDeep.copy(alpha = 0.5f))
                .padding(horizontal = Space.sm, vertical = 2.dp),
        ) {
            Text(
                text = count.toString(),
                color = YancoPalette.TextSecondary,
                style = YancoType.Caption,
            )
        }
    }
}

@Composable
private fun EpisodeRow(ep: EpisodeInfo, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusStyle(focused = focused, radius = Radius.card, liftScale = 1.015f)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(Radius.chip))
                .background(
                    if (focused) YancoPalette.Accent.copy(alpha = 0.22f)
                    else YancoPalette.BackgroundElevated,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "E%02d".format(ep.episodeNumber),
                color = YancoPalette.Accent,
                style = YancoType.LabelStrong,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            Text(
                text = ep.title.takeIf { it.isNotBlank() } ?: "Episode ${ep.episodeNumber}",
                color = YancoPalette.TextPrimary,
                style = YancoType.TitleS,
                maxLines = 1,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Season ${ep.seasonNumber}",
                    color = YancoPalette.TextMuted,
                    style = YancoType.Caption,
                )
                ep.duration?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "\u00b7",
                        color = YancoPalette.TextFaint,
                        style = YancoType.Caption,
                    )
                    Text(
                        text = it,
                        color = YancoPalette.TextMuted,
                        style = YancoType.Caption,
                    )
                }
            }
        }
        Icon(
            imageVector = YancoIcons.Play,
            contentDescription = null,
            tint = if (focused) YancoPalette.Accent else YancoPalette.TextFaint,
            modifier = Modifier.size(16.dp),
        )
    }
}
