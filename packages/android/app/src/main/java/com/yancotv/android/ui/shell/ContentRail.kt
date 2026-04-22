package com.yancotv.android.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yancotv.android.ui.components.focusStyle
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.android.ui.theme.YancoType
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.NowNext

/**
 * Horizontal rail of content cards docked beneath the hero. Live channels
 * render as wide "broadcast" cards (logo + name + now-playing); movies and
 * series render as 16:9 poster cards. Rail handles its own focus group +
 * auto-scroll so the focused card always stays near the centre.
 *
 * The hero above consumes this rail's [onFocus] callback to swap its
 * backdrop / metadata — that coupling is why the rail lives in the same
 * package as the hero.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ContentRail(
    type: ContentType,
    items: List<ContentItem>,
    nowNextMap: Map<String, NowNext>,
    nowSeconds: Long,
    lockedIds: Set<String>,
    focusedIndex: Int,
    firstItemFocus: FocusRequester,
    onFocus: (Int, ContentItem) -> Unit,
    onActivate: (Int) -> Unit,
    onLongPress: (ContentItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Keep the focused card centred-ish so the user isn't scrolling blind
    // when returning to a rail. Offset by 2 cards so the hero preview has
    // context on both sides when possible.
    LaunchedEffect(focusedIndex, items.size) {
        if (focusedIndex in items.indices) {
            runCatching { listState.scrollToItem(maxOf(0, focusedIndex - 2)) }
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .focusRestorer { firstItemFocus }
            .focusGroup(),
        contentPadding = PaddingValues(
            start = Space.page,
            end = Space.page,
            top = Space.sm,
            bottom = Space.lg,
        ),
        horizontalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
            val attach = if (index == focusedIndex.coerceAtLeast(0)
                    .coerceAtMost((items.size - 1).coerceAtLeast(0)))
                firstItemFocus else null
            when (type) {
                ContentType.LIVE -> LiveCard(
                    item = item,
                    nowNext = item.tvgId?.let { nowNextMap[it] },
                    nowSeconds = nowSeconds,
                    locked = item.id in lockedIds,
                    focusRequester = attach,
                    onFocus = { onFocus(index, item) },
                    onActivate = { onActivate(index) },
                    onLongPress = { onLongPress(item) },
                )
                ContentType.MOVIE, ContentType.SERIES -> PosterCard(
                    item = item,
                    locked = item.id in lockedIds,
                    focusRequester = attach,
                    onFocus = { onFocus(index, item) },
                    onActivate = { onActivate(index) },
                    onLongPress = { onLongPress(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveCard(
    item: ContentItem,
    nowNext: NowNext?,
    nowSeconds: Long,
    locked: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onActivate: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }

    val displayTitle = item.cleanTitle?.ifBlank { null } ?: item.title
    val nowProg = nowNext?.now

    Row(
        modifier = Modifier
            .width(280.dp)
            .height(120.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusable(interactionSource = interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onActivate,
                onLongClick = onLongPress,
            )
            .focusStyle(focused = focused, radius = Radius.card)
            .padding(Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        LiveLogoBlock(url = item.logoUrl, focused = focused, locked = locked)
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            Text(
                text = displayTitle,
                color = YancoPalette.TextPrimary,
                style = YancoType.TitleS,
                maxLines = 1,
            )
            if (nowProg != null) {
                Text(
                    text = nowProg.title,
                    color = YancoPalette.TextSecondary,
                    style = YancoType.Caption,
                    maxLines = 1,
                )
                LiveProgressBar(
                    start = nowProg.startTime,
                    end = nowProg.endTime,
                    now = nowSeconds,
                    modifier = Modifier.padding(top = Space.xxs),
                )
            } else {
                item.groupName?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = YancoPalette.TextMuted,
                        style = YancoType.Caption,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveLogoBlock(url: String?, focused: Boolean, locked: Boolean) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(Radius.control))
            .background(
                if (focused) YancoPalette.BackgroundElevated else YancoPalette.BackgroundDeep,
            )
            .border(
                1.dp,
                if (focused) YancoPalette.Accent.copy(alpha = 0.45f) else YancoPalette.BorderSubtle,
                RoundedCornerShape(Radius.control),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(Space.sm),
            )
        } else {
            Text(
                text = "Y",
                color = YancoPalette.Accent.copy(alpha = 0.7f),
                style = YancoType.DisplayS,
                fontWeight = FontWeight.Black,
            )
        }
        if (locked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(YancoPalette.BackgroundDeep.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = YancoIcons.Lock,
                    contentDescription = "Locked",
                    tint = YancoPalette.Accent,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

@Composable
private fun LiveProgressBar(start: Long, end: Long, now: Long, modifier: Modifier) {
    val span = (end - start).coerceAtLeast(1)
    val pct = ((now - start).toFloat() / span).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(YancoPalette.BorderSubtle),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(pct)
                .fillMaxHeight()
                .background(YancoPalette.Accent),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(
    item: ContentItem,
    locked: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onActivate: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }

    val title = item.cleanTitle?.ifBlank { null } ?: item.title

    Column(
        modifier = Modifier
            .width(220.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusable(interactionSource = interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onActivate,
                onLongClick = onLongPress,
            )
            .focusStyle(focused = focused, radius = Radius.card),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(YancoPalette.BackgroundDeep),
        ) {
            if (!item.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    YancoPalette.BackgroundHover,
                                    YancoPalette.BackgroundElevated,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title.take(2).uppercase(),
                        color = if (focused) YancoPalette.Accent else YancoPalette.TextSecondary,
                        style = YancoType.DisplayS,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            // Darken the bottom so the title line below the card flows
            // into the art without a hard edge.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.75f to Color.Transparent,
                            1f to YancoPalette.BackgroundDeep.copy(alpha = 0.75f),
                        ),
                    ),
            )
            if (locked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Space.sm)
                        .size(22.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(YancoPalette.BackgroundDeep.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = YancoIcons.Lock,
                        contentDescription = "Locked",
                        tint = YancoPalette.Accent,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(Space.sm)
                    .clip(RoundedCornerShape(Radius.chip))
                    .background(YancoPalette.BackgroundDeep.copy(alpha = 0.6f))
                    .padding(horizontal = Space.sm, vertical = 2.dp),
            ) {
                Text(
                    text = if (item.type == ContentType.MOVIE) "MOVIE" else "SERIES",
                    color = YancoPalette.TextSecondary,
                    style = YancoType.Overline,
                )
            }
        }
        Text(
            text = title,
            color = YancoPalette.TextPrimary,
            style = YancoType.TitleS,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Space.xs, vertical = Space.sm),
        )
    }
}
