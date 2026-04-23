package com.yancotv.android.ui.shell

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.yancotv.android.ui.focus.PlacedFocusAnchor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yancotv.android.ui.components.HexSurface
import com.yancotv.android.ui.components.WheelRow
import com.yancotv.android.ui.components.wheelItemTransform
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.ui.theme.YancoType
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.NowNext

/**
 * Horizontal rail of content cards docked beneath the hero. Live channels
 * render as hex-capsule "broadcast" cards (logo + name + now-playing); movies
 * and series render as cut-corner poster cards. Rail handles its own focus
 * group + auto-scroll so the focused card always stays near the centre.
 *
 * Cards consume [HexSurface] so the angular frame language stays consistent
 * with filter chips and buttons in the same shell.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContentRail(
    type: ContentType,
    items: List<ContentItem>,
    nowNextMap: Map<String, NowNext>,
    nowSeconds: Long,
    lockedIds: Set<String>,
    focusedIndex: Int,
    firstItemAnchor: PlacedFocusAnchor,
    onFocus: (Int, ContentItem) -> Unit,
    onActivate: (Int) -> Unit,
    onLongPress: (ContentItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Scroll is driven solely by focus → WheelRow's CenterBringIntoViewSpec
    // centre-snaps the focused card. NEVER add an animateScrollToItem here
    // keyed on focusedIndex — it would race with bringIntoView and stack
    // two animations, which is exactly the lag we removed.

    val itemWidth = when (type) {
        ContentType.LIVE -> 300.dp
        ContentType.MOVIE, ContentType.SERIES -> 240.dp
    }

    WheelRow(
        itemWidth = itemWidth,
        listState = listState,
        horizontalArrangement = Arrangement.spacedBy(Space.lg),
        verticalPadding = Space.xl,
        minSidePadding = Space.page,
        modifier = modifier
            .fillMaxWidth()
            .focusGroup(),
    ) {
        val safeFocusedIndex = focusedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
            val attach = if (index == safeFocusedIndex) firstItemAnchor.requester else null
            // MB-66: memoize per stable key — graphicsLayer lambda creation is
            // cheap but allocates a new Modifier chain every recompose otherwise.
            val wheel = remember(index) { Modifier.wheelItemTransform(listState = listState, index = index) }
            // MB-67: mark the anchor placed when the focused card's node lands in
            // layout. PlacedFocusAnchor.awaitAndRequest() in BrowseShell waits for
            // this signal instead of a delay-ladder, so focus restore is deterministic.
            val anchorMod = if (index == safeFocusedIndex) {
                Modifier.onPlaced { firstItemAnchor.markPlaced() }
            } else {
                Modifier
            }
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
                    modifier = wheel.then(anchorMod),
                )
                ContentType.MOVIE, ContentType.SERIES -> PosterCard(
                    item = item,
                    locked = item.id in lockedIds,
                    focusRequester = attach,
                    onFocus = { onFocus(index, item) },
                    onActivate = { onActivate(index) },
                    onLongPress = { onLongPress(item) },
                    modifier = wheel.then(anchorMod),
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
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }

    val selfRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val displayTitle = item.cleanTitle?.ifBlank { null } ?: item.title
    val nowProg = nowNext?.now
    val nextProg = nowNext?.next

    // Progress 0..1 through the NOW programme. Null when we have no EPG for
    // this channel — the ring falls back to an outline-only state so the card
    // doesn't look broken on ungauge-able channels (news feeds, 24/7 etc.).
    val progress: Float? = nowProg?.let {
        val span = (it.endTime - it.startTime).coerceAtLeast(1L)
        ((nowSeconds - it.startTime).toFloat() / span).coerceIn(0f, 1f)
    }
    // Minutes remaining in NOW programme, for the top-right "Xm left" chip.
    val minutesLeft: Int? = nowProg?.let {
        val remaining = (it.endTime - nowSeconds).coerceAtLeast(0L)
        (remaining / 60L).toInt()
    }
    val timeLeftLabel: String? = minutesLeft?.let { m ->
        when {
            m <= 0 -> null
            m < 60 -> "${m}m left"
            else -> "${m / 60}h ${m % 60}m"
        }
    }

    // Console-strip layout: logo disc + radial progress ring on the left,
    // text column on the right, time-left chip pinned to the top-right. The
    // disc is a rounded square rather than a second hex — a nested hex inside
    // a hex capsule fights the frame's silhouette, which is what the old
    // LiveLogoBlock looked like. The ring carries the progress signal so the
    // text column gets its full height back for NOW + NEXT.
    HexSurface(
        shape = YancoShapes.HexCapsule,
        focused = focused,
        bevelInset = 2.dp,
        // Wheel transform applied by the caller via [modifier] — keep it on
        // the outer surface so the tilt/scale wraps the entire hex frame,
        // not just the content Row inside.
        modifier = modifier
            .width(300.dp)
            .height(120.dp)
            .focusRequester(selfRequester)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusable(interactionSource = interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    onActivate()
                    scope.launch {
                        delay(80)
                        runCatching { selfRequester.requestFocus() }
                    }
                },
                onLongClick = onLongPress,
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, end = 16.dp, top = Space.sm, bottom = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                LogoDisc(
                    url = item.logoUrl,
                    focused = focused,
                    locked = locked,
                    progress = progress,
                )
                Column(
                    modifier = Modifier.fillMaxHeight().weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = displayTitle,
                        color = YancoPalette.TextPrimary,
                        style = YancoType.TitleS,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    if (nowProg != null) {
                        Text(
                            text = nowProg.title,
                            color = YancoPalette.TextSecondary,
                            style = YancoType.Caption,
                            maxLines = 1,
                            modifier = Modifier.padding(top = Space.xxs),
                        )
                        nextProg?.let { next ->
                            Text(
                                text = "Next · ${next.title}",
                                color = YancoPalette.TextMuted,
                                style = YancoType.Overline,
                                maxLines = 1,
                                modifier = Modifier.padding(top = Space.xxs),
                            )
                        }
                    } else {
                        item.groupName?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                color = YancoPalette.TextMuted,
                                style = YancoType.Caption,
                                maxLines = 1,
                                modifier = Modifier.padding(top = Space.xxs),
                            )
                        }
                    }
                }
            }

            // Time-left chip — absolute TopEnd so the Column above can size
            // to its content without reserving space for the chip.
            if (timeLeftLabel != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 14.dp)
                        .clip(YancoShapes.ChipBevel)
                        .background(YancoPalette.BackgroundDeep.copy(alpha = 0.72f))
                        .border(
                            1.dp,
                            YancoPalette.Accent.copy(alpha = 0.35f),
                            YancoShapes.ChipBevel,
                        )
                        .padding(horizontal = Space.sm, vertical = 2.dp),
                ) {
                    Text(
                        text = timeLeftLabel,
                        color = YancoPalette.Accent,
                        style = YancoType.Overline,
                    )
                }
            }
        }
    }
}

/**
 * Channel logo presented as a rounded-square disc wrapped by a thin radial
 * progress ring. The ring sweeps clockwise from 12 o'clock and tracks the
 * fraction of the NOW programme that has elapsed; a small LIVE pip sits at
 * the 12 o'clock position of the ring as an affordance.
 *
 * The disc deliberately reads as a *different* shape language from the
 * outer hex capsule — a rounded square instead of a hex tile. Nesting a
 * second hex inside a hex capsule fights the outer silhouette and was the
 * "hex inside a hex is horrible" feedback this redesign responds to.
 */
@Composable
private fun LogoDisc(
    url: String?,
    focused: Boolean,
    locked: Boolean,
    progress: Float?,
) {
    val totalSize = 84.dp
    val discSize = 72.dp
    val ringStroke = 2.5.dp
    val discShape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier.size(totalSize),
        contentAlignment = Alignment.Center,
    ) {
        // Radial progress ring. Rendered via drawArc on a Canvas sized to
        // the outer 84dp so the ring sits in the 6dp gutter around the 72dp
        // disc. Track is always drawn (full 360° dim circle); the accent
        // sweep only fills when we have EPG data.
        Canvas(modifier = Modifier.size(totalSize)) {
            val strokePx = ringStroke.toPx()
            val arcTopLeft = Offset(strokePx / 2f, strokePx / 2f)
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            drawArc(
                color = YancoPalette.BorderSubtle,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            if (progress != null && progress > 0f) {
                drawArc(
                    color = YancoPalette.Accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }

        // Logo disc — the rounded square that replaces the old inner hex.
        Box(
            modifier = Modifier
                .size(discSize)
                .clip(discShape)
                .background(
                    if (focused) YancoPalette.BackgroundElevated else YancoPalette.BackgroundDeep,
                )
                .border(
                    1.dp,
                    if (focused) YancoPalette.Accent.copy(alpha = 0.55f) else YancoPalette.PanelBorder,
                    discShape,
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
        }

        // LIVE pip at 12 o'clock. Sits on top of the ring track so the user
        // sees "this is a live broadcast" independently of whether we have
        // EPG data for the progress sweep.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-1).dp)
                .size(12.dp)
                .clip(RoundedCornerShape(50))
                .background(YancoPalette.BackgroundDeep)
                .border(1.dp, YancoPalette.Accent, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFFF4D4D)),
            )
        }

        if (locked) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(YancoPalette.BackgroundDeep.copy(alpha = 0.92f))
                    .border(
                        1.dp,
                        YancoPalette.Accent.copy(alpha = 0.55f),
                        RoundedCornerShape(Radius.pill),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = YancoIcons.Lock,
                    contentDescription = "Locked",
                    tint = YancoPalette.Accent,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
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
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }

    val selfRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val title = item.cleanTitle?.ifBlank { null } ?: item.title

    // Cut-corner card — top-left + bottom-right bevelled, others rounded so
    // the poster reads as an angular editorial frame instead of a plain
    // rounded rectangle.
    HexSurface(
        shape = YancoShapes.CutCornerCard,
        focused = focused,
        bevelInset = 3.dp,
        modifier = modifier
            .width(240.dp)
            .focusRequester(selfRequester)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusable(interactionSource = interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    onActivate()
                    scope.launch {
                        delay(80)
                        runCatching { selfRequester.requestFocus() }
                    }
                },
                onLongClick = onLongPress,
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
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
                // Darken the bottom so the title line integrates with the art.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to Color.Transparent,
                                1f to YancoPalette.BackgroundDeep.copy(alpha = 0.92f),
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
                        .align(Alignment.TopEnd)
                        .padding(Space.sm)
                        .clip(YancoShapes.ChipBevel)
                        .background(YancoPalette.BackgroundDeep.copy(alpha = 0.72f))
                        .border(1.dp, YancoPalette.Accent.copy(alpha = 0.35f), YancoShapes.ChipBevel)
                        .padding(horizontal = Space.sm, vertical = 2.dp),
                ) {
                    Text(
                        text = if (item.type == ContentType.MOVIE) "MOVIE" else "SERIES",
                        color = YancoPalette.Accent,
                        style = YancoType.Overline,
                    )
                }
            }
            // Title strip integrated into the shell — reads as the card's
            // lower band rather than detached text.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(YancoPalette.BackgroundDeep.copy(alpha = 0.55f))
                    .padding(horizontal = Space.md, vertical = Space.sm),
            ) {
                Text(
                    text = title,
                    color = YancoPalette.TextPrimary,
                    style = YancoType.TitleS,
                    maxLines = 1,
                )
            }
        }
    }
}
