package com.yancotv.android.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.ui.theme.YancoType
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.NowNext
import kotlinx.coroutines.delay

/**
 * Dominant hero at the top of the browse shell. Swaps its backdrop + copy
 * as the user moves through the rail beneath — moving focus is what creates
 * the cinematic payoff, not a separate click.
 *
 * Three compositional states:
 *   - empty:   no item focused (initial paint, empty catalogue) → branded idle card
 *   - idle:    item focused, nothing playing → backdrop + metadata + Play CTA
 *   - playing: something is on → MiniPlayer as the backdrop + "Now playing" overlay
 *
 * The hero is edge-to-edge within its lane (no panel border) so the art
 * bleeds against the shell. Left-darken gradient + vertical bottom-fade
 * keep the copy readable regardless of the artwork's luminance.
 */
@UnstableApi
@Composable
fun FeatureHero(
    focused: ContentItem?,
    playing: ContentItem?,
    nowNext: NowNext?,
    nowSeconds: Long,
    sourceName: String?,
    isFavorite: Boolean,
    isLocked: Boolean,
    controller: PlaybackController,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Backdrop layer — either the live mini player, or the focused
        // item's artwork, or a branded gradient when nothing is picked.
        HeroBackdrop(
            focused = focused,
            playing = playing,
            controller = controller,
        )

        // Dual gradient: horizontal left-darken so the copy column is
        // readable, plus a subtle vertical bottom-fade so any card rail
        // behind reads as continuation of the canvas, not a hard edge.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.92f),
                            0.45f to LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.45f),
                            1f to Color.Transparent,
                        ),
                    ).background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.75f to Color.Transparent,
                            1f to LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.55f),
                        ),
                    ),
        )

        if (focused == null) {
            HeroIdlePlaceholder(modifier = Modifier.align(Alignment.CenterStart))
            return@Box
        }

        HeroCopy(
            focused = focused,
            playing = playing,
            nowNext = nowNext,
            nowSeconds = nowSeconds,
            sourceName = sourceName,
            isFavorite = isFavorite,
            isLocked = isLocked,
            onPlay = onPlay,
            onToggleFavorite = onToggleFavorite,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = Space.page, end = Space.xxxl)
                    .widthIn(max = 620.dp),
        )
    }
}

@UnstableApi
@Composable
private fun HeroBackdrop(
    focused: ContentItem?,
    playing: ContentItem?,
    controller: PlaybackController,
) {
    // Debounce the URL so rapid D-pad traversal doesn't fire a new Coil
    // request on every frame — wait until the user rests on a channel.
    var debouncedUrl by remember { mutableStateOf(focused?.logoUrl) }
    LaunchedEffect(focused?.logoUrl) {
        delay(300L)
        debouncedUrl = focused?.logoUrl
    }
    // Only LIVE channels surface the MiniPlayer in the hero — for VOD
    // (Movies / Series) the playing video bleeding into the focused
    // movie's hero (or worse, into a sibling section's hero after a
    // section swap) reads as a glitch. Keep the poster instead. Live
    // channels still get the cinematic mini-stream because their whole
    // value prop is "what's on right now".
    when {
        playing != null && playing.type == ContentType.LIVE -> {
            Box(Modifier.fillMaxSize()) {
                // Hero backdrop wants the full 520dp lane behind metadata —
                // fill the available space rather than the legacy 180dp strip.
                MiniPlayer(
                    modifier = Modifier.fillMaxSize(),
                    controller = controller,
                )
            }
        }
        debouncedUrl?.isNotBlank() == true -> {
            AnimatedContent(
                targetState = debouncedUrl!!,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(200)) },
                label = "hero-backdrop",
            ) { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        else -> {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors =
                                    listOf(
                                        LocalYancoPalette.current.BackgroundHover,
                                        LocalYancoPalette.current.BackgroundDeep,
                                    ),
                            ),
                        ),
            )
        }
    }
}

@Composable
private fun HeroCopy(
    focused: ContentItem,
    playing: ContentItem?,
    nowNext: NowNext?,
    nowSeconds: Long,
    sourceName: String?,
    isFavorite: Boolean,
    isLocked: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier,
) {
    val eyebrow =
        when (focused.type) {
            ContentType.LIVE -> "LIVE CHANNEL"
            ContentType.MOVIE -> "FEATURE"
            ContentType.SERIES -> "SERIES"
        }
    val title = focused.cleanTitle?.ifBlank { null } ?: focused.title
    val nowProg = nowNext?.now
    val nextProg = nowNext?.next
    val isCurrentlyPlaying = playing?.id == focused.id

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        // Eyebrow row: type label + live pill when something is on.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text(
                text = eyebrow,
                color = LocalYancoPalette.current.Accent,
                style = YancoType.Overline,
            )
            if (isCurrentlyPlaying) HeroLivePill()
            if (isLocked) HeroLockChip()
        }

        // Title in cinematic treatment. Clamp to 2 lines — the hero is
        // horizontal, not a detail page.
        Text(
            text = title,
            color = LocalYancoPalette.current.TextPrimary,
            style = YancoType.DisplayCinematic,
            maxLines = 2,
        )

        // For live channels, the now-playing program is the headline
        // metadata. For movies/series we surface plot — stored in
        // metadataJson — but that parse is lossy enough to skip here and
        // let Detail do its job, so we show category/source chips instead.
        when (focused.type) {
            ContentType.LIVE ->
                LiveHeroMeta(
                    nowProg = nowProg,
                    nextProg = nextProg,
                    nowSeconds = nowSeconds,
                    group = focused.groupName,
                )
            ContentType.MOVIE, ContentType.SERIES ->
                VodHeroMeta(
                    group = focused.groupName,
                    sourceName = sourceName,
                    type = focused.type,
                )
        }

        Spacer(Modifier.height(Space.xs))
        // Two-button action row. Play is the primary CTA — gets the accent
        // fill. Favorite is secondary, tonal only.
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            PrimaryCta(
                // LIVE channels auto-preview on focus — the button never
                // needs to say "Play now" because the stream is already
                // running (or about to, inside the 400ms debounce window).
                // For movies/series no auto-preview fires; the OK press
                // opens the detail overlay, which owns the real Play CTA.
                label =
                    when {
                        focused.type == ContentType.LIVE -> "Open fullscreen"
                        isCurrentlyPlaying -> "Open fullscreen"
                        else -> "Play now"
                    },
                icon = YancoIcons.Play,
                onClick = onPlay,
            )
            TonalCta(
                label = if (isFavorite) "In favorites" else "Favorite",
                icon = if (isFavorite) YancoIcons.StarFilled else YancoIcons.StarOutline,
                highlighted = isFavorite,
                onClick = onToggleFavorite,
            )
        }
    }
}

@Composable
private fun LiveHeroMeta(
    nowProg: com.yancotv.shared.types.EpgProgramme?,
    nextProg: com.yancotv.shared.types.EpgProgramme?,
    nowSeconds: Long,
    group: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (nowProg != null) {
            Text(
                text = "Now: ${nowProg.title}",
                color = LocalYancoPalette.current.TextPrimary,
                style = YancoType.TitleM,
                maxLines = 2,
            )
            // Progress bar with clock endpoints. Time left is the concise
            // fact worth surfacing, not start/end as full timestamps.
            HeroProgress(
                start = nowProg.startTime,
                end = nowProg.endTime,
                now = nowSeconds,
            )
        }
        if (nextProg != null) {
            Text(
                text = "Up next: ${nextProg.title}",
                color = LocalYancoPalette.current.TextMuted,
                style = YancoType.Caption,
                maxLines = 1,
            )
        }
        MetaChipRow(
            chips =
                remember(group, nowProg?.category) {
                    buildList {
                        group?.takeIf { it.isNotBlank() }?.let { add(it) }
                        nowProg?.category?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                },
        )
    }
}

@Composable
private fun VodHeroMeta(
    group: String?,
    sourceName: String?,
    type: ContentType,
) {
    MetaChipRow(
        chips =
            remember(type, group, sourceName) {
                buildList {
                    add(if (type == ContentType.MOVIE) "Movie" else "Series")
                    group?.takeIf { it.isNotBlank() }?.let { add(it) }
                    sourceName?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            },
    )
}

@Composable
private fun MetaChipRow(chips: List<String>) {
    if (chips.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        chips.take(4).forEach { chip -> HeroMetaChip(chip) }
    }
}

@Composable
private fun HeroMetaChip(label: String) {
    Box(
        modifier =
            Modifier
                .clip(YancoShapes.ChipBevel)
                .background(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.65f))
                .border(1.dp, LocalYancoPalette.current.PanelBorder, YancoShapes.ChipBevel)
                .padding(horizontal = Space.md, vertical = Space.xs),
    ) {
        Text(
            text = label,
            color = LocalYancoPalette.current.TextSecondary,
            style = YancoType.Caption,
            maxLines = 1,
        )
    }
}

@Composable
private fun HeroProgress(
    start: Long,
    end: Long,
    now: Long,
) {
    // MB-81: span depends only on start/end, not now — memoize so EPG ticks
    // that update `now` every second don't recompute this on every frame.
    val span = remember(start, end) { (end - start).coerceAtLeast(1) }
    val pct = ((now - start).toFloat() / span).coerceIn(0f, 1f)
    val remainingMin = ((end - now).coerceAtLeast(0) / 60).toInt()
    // MB-81 holds post-MK.16.1: palette changes rarely (only on theme swap),
    // so `remember(pal)` rebuilds the brush only then — not on every EPG tick.
    val pal = LocalYancoPalette.current
    val heroProgressBrush =
        remember(pal) {
            Brush.horizontalGradient(
                listOf(pal.AccentDeep, pal.Accent, pal.AccentGlow),
            )
        }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(pal.BorderSubtle),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(pct)
                        .fillMaxHeight()
                        .background(heroProgressBrush),
            )
        }
        Text(
            text = if (remainingMin > 0) "$remainingMin min left" else "Ending now",
            color = LocalYancoPalette.current.TextMuted,
            style = YancoType.Caption,
        )
    }
}

@Composable
private fun PrimaryCta(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = YancoShapes.ButtonBevel
    val bg by animateColorAsState(
        targetValue = if (focused) LocalYancoPalette.current.AccentGlow else LocalYancoPalette.current.Accent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "primaryCtaBg",
    )
    val fg = LocalYancoPalette.current.BackgroundDeep
    // Bevelled hex button — accent-filled primary CTA. A hairline focus rim
    // rides the bevel so the shape reads as an angular playbill.
    Row(
        modifier =
            Modifier
                .clip(shape)
                .background(bg)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) LocalYancoPalette.current.FocusRing else LocalYancoPalette.current.AccentDeep,
                    shape = shape,
                ).focusable(interactionSource = interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = label }
                .padding(horizontal = Space.xxxl, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(
            text = label,
            color = fg,
            style = YancoType.LabelStrong,
        )
    }
}

@Composable
private fun TonalCta(
    label: String,
    icon: ImageVector,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = YancoShapes.ButtonBevel
    val bg by animateColorAsState(
        targetValue =
            when {
                focused -> LocalYancoPalette.current.Accent.copy(alpha = 0.22f)
                highlighted -> LocalYancoPalette.current.Accent.copy(alpha = 0.14f)
                else -> LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.6f)
            },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tonalCtaBg",
    )
    val border =
        when {
            focused -> LocalYancoPalette.current.FocusRing
            highlighted -> LocalYancoPalette.current.Accent.copy(alpha = 0.55f)
            else -> LocalYancoPalette.current.PanelBorder
        }
    val fg =
        when {
            highlighted -> LocalYancoPalette.current.Accent
            else -> LocalYancoPalette.current.TextPrimary
        }
    Row(
        modifier =
            Modifier
                .clip(shape)
                .background(bg)
                .border(if (focused) 2.dp else 1.dp, border, shape)
                .focusable(interactionSource = interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = label }
                .padding(horizontal = Space.xxxl, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(
            text = label,
            color = fg,
            style = YancoType.LabelStrong,
        )
    }
}

@Composable
private fun HeroLivePill() {
    Row(
        modifier =
            Modifier
                .clip(YancoShapes.ChipBevel)
                .background(LocalYancoPalette.current.Live)
                .padding(horizontal = Space.md, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(
            modifier =
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(Color.White),
        )
        Text(text = "LIVE", color = Color.White, style = YancoType.Overline)
    }
}

@Composable
private fun HeroLockChip() {
    Row(
        modifier =
            Modifier
                .clip(YancoShapes.ChipBevel)
                .background(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.85f))
                .border(1.dp, LocalYancoPalette.current.Accent.copy(alpha = 0.45f), YancoShapes.ChipBevel)
                .padding(horizontal = Space.md, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(
            imageVector = YancoIcons.Lock,
            contentDescription = null,
            tint = LocalYancoPalette.current.Accent,
            modifier = Modifier.size(10.dp),
        )
        Text(text = "LOCKED", color = LocalYancoPalette.current.Accent, style = YancoType.Overline)
    }
}

@Composable
private fun HeroIdlePlaceholder(modifier: Modifier) {
    Column(
        modifier =
            modifier
                .padding(start = Space.page, end = Space.xxxl)
                .widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = "YANCOTV+",
            color = LocalYancoPalette.current.Accent,
            style = YancoType.Overline,
        )
        Text(
            text = "Move through the rail to preview",
            color = LocalYancoPalette.current.TextPrimary,
            style = YancoType.DisplayS,
        )
        Text(
            text = "The focused title becomes the hero — browse, then press OK to watch.",
            color = LocalYancoPalette.current.TextSecondary,
            style = YancoType.BodyLong,
        )
    }
}
