package com.yancotv.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.ui.components.HexSurface
import com.yancotv.android.ui.components.WheelRow
import com.yancotv.android.ui.components.wheelItemTransform
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.ShellDim
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.ui.theme.YancoType
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
 * Home landing surface. Stacks two horizontal rails — Continue watching
 * (recent VOD resume points) and Favorites (most-recently-starred) — on a
 * cinematic canvas. Each rail is a [LazyRow] so D-pad LEFT/RIGHT move
 * horizontally and UP/DOWN between rails; the outer [verticalScroll] lets
 * a short panel still reach Favorites.
 *
 * Empty state is a branded welcome card instead of the old plain-text
 * "Welcome to YancoTV" block — matches the premium shell language.
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
            .padding(top = Space.xxxl, bottom = Space.section),
        verticalArrangement = Arrangement.spacedBy(Space.xxxl),
    ) {
        if (continueWatching.isEmpty() && favoriteItems.isEmpty()) {
            EmptyHome(modifier = Modifier.padding(horizontal = Space.section))
            return@Column
        }

        if (continueWatching.isNotEmpty()) {
            Rail(
                eyebrow = "FOR YOU",
                title = "Continue watching",
                caption = "Jump back where you left off",
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
                eyebrow = "YOUR LIBRARY",
                title = "Favorites",
                caption = "Channels and titles you starred",
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
    eyebrow: String,
    title: String,
    caption: String,
    items: List<ContentItem>,
    lockedIds: Set<String>,
    resumeByContent: Map<String, HistoryEntry>,
    onPlay: (ContentItem) -> Unit,
) {
    val listState = rememberLazyListState()

    // Scroll is driven solely by focus → WheelRow's CenterBringIntoViewSpec
    // centre-snaps the focused card automatically. Do NOT add an
    // animateScrollToItem keyed on a local focused-index state — it races
    // with bringIntoView and stacks two scroll animations.

    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Column(modifier = Modifier.padding(horizontal = Space.section)) {
            Text(
                text = eyebrow,
                color = YancoPalette.Accent,
                style = YancoType.Overline,
            )
            Spacer(Modifier.height(Space.xxs))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = title,
                    color = YancoPalette.TextPrimary,
                    style = YancoType.TitleL,
                )
                Spacer(Modifier.width(Space.md))
                Text(
                    text = caption,
                    color = YancoPalette.TextMuted,
                    style = YancoType.Caption,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        WheelRow(
            itemWidth = ShellDim.posterTile,
            listState = listState,
            horizontalArrangement = Arrangement.spacedBy(Space.lg),
            verticalPadding = Space.lg,
            minSidePadding = Space.section,
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                PosterTile(
                    item = item,
                    locked = item.id in lockedIds,
                    resume = resumeByContent[item.id],
                    onClick = { onPlay(item) },
                    modifier = Modifier.wheelItemTransform(listState = listState, index = index),
                )
            }
        }
    }
}

@Composable
private fun PosterTile(
    item: ContentItem,
    locked: Boolean,
    resume: HistoryEntry?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val progressPct = resume?.let { entry ->
        val dur = entry.durationSeconds ?: return@let 0f
        if (dur <= 0) 0f else (entry.positionSeconds / dur).toFloat().coerceIn(0f, 1f)
    } ?: 0f

    HexSurface(
        shape = YancoShapes.CutCornerCardSmall,
        focused = focused,
        bevelInset = 3.dp,
        modifier = modifier
            .width(ShellDim.posterTile)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ShellDim.posterTileAspect),
            ) {
                Artwork(item = item, focused = focused)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    YancoPalette.BackgroundDeep.copy(alpha = 0.9f),
                                ),
                            ),
                        ),
                )
                if (locked) {
                    LockBadge(modifier = Modifier.align(Alignment.TopStart).padding(Space.sm))
                }
                if (resume != null) {
                    ResumeBadge(
                        resume = resume,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(Space.sm),
                    )
                }
                TypeChip(
                    item = item,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(Space.sm),
                )
                if (progressPct > 0f) {
                    ProgressStripe(
                        progress = progressPct,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(YancoPalette.BackgroundDeep.copy(alpha = 0.55f))
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
            ) {
                Text(
                    text = item.cleanTitle?.ifBlank { null } ?: item.title,
                    color = YancoPalette.TextPrimary,
                    style = YancoType.TitleS,
                    maxLines = 1,
                )
                Text(
                    text = secondaryLine(item, resume),
                    color = YancoPalette.TextMuted,
                    style = YancoType.Caption,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Artwork(item: ContentItem, focused: Boolean) {
    if (!item.logoUrl.isNullOrBlank()) {
        AsyncImage(
            model = item.logoUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        // No artwork — fall back to a gradient-washed monogram so the card
        // still reads premium instead of a grey rectangle with letters.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            YancoPalette.BackgroundHover,
                            YancoPalette.BackgroundElevated,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (item.cleanTitle?.ifBlank { null } ?: item.title).take(2).uppercase(),
                color = if (focused) YancoPalette.Accent else YancoPalette.TextSecondary,
                style = YancoType.DisplayS,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun LockBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(YancoPalette.BackgroundDeep.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = YancoIcons.Lock,
            contentDescription = "Locked",
            tint = YancoPalette.Live,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun ResumeBadge(resume: HistoryEntry, modifier: Modifier = Modifier) {
    val dur = resume.durationSeconds
    val label = if (dur != null && dur > 0) {
        val remainingSec = (dur - resume.positionSeconds).toDouble().coerceAtLeast(0.0).roundToInt()
        val minutes = (remainingSec / 60).coerceAtLeast(1)
        "${minutes}m left"
    } else {
        "Resume"
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(YancoPalette.BackgroundDeep.copy(alpha = 0.75f))
            .padding(horizontal = Space.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(
            imageVector = YancoIcons.Play,
            contentDescription = null,
            tint = YancoPalette.Accent,
            modifier = Modifier.size(10.dp),
        )
        Text(
            text = label,
            color = YancoPalette.TextPrimary,
            style = YancoType.Caption,
        )
    }
}

@Composable
private fun TypeChip(item: ContentItem, modifier: Modifier = Modifier) {
    val raw = item.groupName?.takeIf { it.isNotBlank() }
        ?: item.type.name.lowercase().replaceFirstChar(Char::uppercase)
    val label = raw.take(28)
    Box(
        modifier = modifier
            .clip(YancoShapes.ChipBevel)
            .background(YancoPalette.BackgroundDeep.copy(alpha = 0.72f))
            .padding(horizontal = Space.md, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = YancoPalette.TextSecondary,
            style = YancoType.Caption,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProgressStripe(progress: Float, modifier: Modifier) {
    // Two-layer stripe — dimmed track + accent fill with a soft trailing
    // glow so it reads at 10 ft. Sits flush at the bottom of the artwork.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(YancoPalette.BackgroundDeep.copy(alpha = 0.6f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(YancoPalette.AccentDeep, YancoPalette.Accent, YancoPalette.AccentGlow),
                    ),
                ),
        )
    }
}

private fun secondaryLine(item: ContentItem, resume: HistoryEntry?): String {
    return when {
        resume != null && resume.durationSeconds != null -> {
            val watched = formatMmSs(resume.positionSeconds.roundToInt())
            val total = formatMmSs(resume.durationSeconds!!.roundToInt())
            "$watched / $total"
        }
        !item.groupName.isNullOrBlank() -> item.groupName!!
        else -> item.type.name.lowercase().replaceFirstChar(Char::uppercase)
    }
}

@Composable
private fun EmptyHome(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.panel))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        YancoPalette.BackgroundRaised,
                        YancoPalette.BackgroundElevated,
                    ),
                ),
            )
            .padding(horizontal = Space.section, vertical = Space.section),
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = "YANCOTV+",
                color = YancoPalette.Accent,
                style = YancoType.Overline,
            )
            Text(
                text = "Your cinematic IPTV suite",
                color = YancoPalette.TextPrimary,
                style = YancoType.DisplayS,
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                text = "Add a source in Settings → Sources and pick a channel. Everything you watch or star lands right here.",
                color = YancoPalette.TextSecondary,
                style = YancoType.BodyLong,
            )
        }
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
