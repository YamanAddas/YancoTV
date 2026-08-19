package com.yancotv.android.player

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.R
import com.yancotv.android.ui.theme.LocalReduceMotion
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoShapes

/**
 * Concept A port of the VOD "controller visible" state. Replaces Media3's
 * built-in `PlayerControlView` for non-live items. Stays mounted behind
 * the `vod_dock_stub` ViewStub and toggles visibility via [VodDockVisibility].
 *
 * Scope for MK.16.player.vod.dock:
 *  - Metadata block (kicker + title + chips)
 *  - Progress bar (played / buffered / scrub-focus) + in/out times
 *  - Transport row: PREV / -10 / PLAY-PAUSE / +10 / NEXT
 *  - Secondary chip row (CC / AUDIO / SPEED / ASPECT / FAV / MENU)
 *  - Remote hint strip
 *
 * Scrub-preview thumbnail grid, chapter ticks, center PLAYING pill, and
 * dynamic secondary chip labels are deferred to follow-up slices.
 *
 * Theme note: like `VodPlayerChrome`, this overlay does not wrap in
 * `YancoTheme` — the `LocalYancoPalette` default (FrostedEmerald) picks
 * up per the MK.16.1 precedent. Runtime theme switching for overlays
 * lands with MK.16.2.
 */
enum class VodDockVisibility {
    HIDDEN,
    VISIBLE,
}

/**
 * Metadata payload for the bottom-left block. All strings pre-formatted;
 * [chips] renders in the order supplied. [isPlaying] drives the
 * play-pause button's icon swap inside the transport row.
 */
data class VodDockData(
    /**
     * The programme title, ALREADY normalized by [nowPlayingFrom]. One line.
     *
     * MK.34.3 — this used to be the provider's raw string, which for a Turkish
     * episode names the show twice and the episode twice; the dock rendered all
     * of it at 34sp across two lines. Normalization happens in the activity so
     * this composable stays a renderer.
     */
    val title: String = "",
    /**
     * Ordered metadata segments — year, season/episode, episode name. Rendered
     * joined with " · " on one line under the title. Replaces the old `kicker`,
     * which was a second copy of the raw string.
     */
    val metadataSegments: List<String> = emptyList(),
    /** Content-type badge text ("EPISODE" / "MOVIE"), or null to omit it. */
    val typeLabel: String? = null,
    val isPlaying: Boolean = true,
)

/**
 * Playback progress in milliseconds. Recomposed by the activity on a
 * lightweight timer while the dock is visible. Caller coerces to the
 * actual media window — this composable just renders what it's given.
 */
data class VodDockProgress(val playedMs: Long = 0L, val bufferedMs: Long = 0L, val durationMs: Long = 0L)

/**
 * Top-level dispatcher. Stage-1 skeleton: renders nothing when visibility
 * is HIDDEN; VISIBLE renders an empty full-screen Box that later stages
 * fill in with the metadata / progress / transport / secondary rows.
 */
@Composable
fun VodPlayerDock(
    visibility: VodDockVisibility,
    data: VodDockData,
    progress: VodDockProgress,
    onTogglePlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onNext: () -> Unit,
    onOpenSheet: (SheetMode) -> Unit,
    onSeekTo: (Long) -> Unit,
    onUserInteraction: () -> Unit,
    // MB-343 (W4) — gates the › NEXT control.
    //
    // This used to ride on a `hasSiblings` flag, which made NEXT dead for every
    // episode: `PlaybackController.play(episode)` synthesises a ONE-item queue,
    // so `queue.size > 1` was always false during a binge and the one control
    // purpose-built for "go to the next episode" never rendered.
    //
    // MK.34.4 removed the ‹ PREVIOUS control and `hasSiblings` with it (user
    // decision): the reference dock has neither, and ‹ lost nothing because the
    // one-item queue made it dead for episodes anyway. › survives on this flag,
    // which is backed by PlayerActivity's prefetched next-episode target.
    hasNext: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (visibility != VodDockVisibility.VISIBLE) return

    val palette = LocalYancoPalette.current
    // Remember the play-pause focus requester at dock scope so we can push
    // initial focus after layout. Don't hoist further up — the dock's own
    // visibility toggle is the right `key()` boundary for focus to reset on.
    val playPauseFocus = remember { FocusRequester() }

    // Park initial focus on play-pause. LaunchedEffect ties the request to
    // this composition — re-runs any time visibility toggles from HIDDEN →
    // VISIBLE because HIDDEN-branch returns early, so this composable is
    // re-entered fresh.
    LaunchedEffect(Unit) {
        runCatching { playPauseFocus.requestFocus() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // MK.34.4 — three stacked levels, bottom-anchored. The scrim stays but
        // is lighter and shorter than the old one: each level now carries its
        // own glass, so the gradient only has to keep text legible over a bright
        // frame rather than pretend to be the dock's background.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to palette.BackgroundDeep.copy(alpha = 0.42f),
                        1f to palette.BackgroundDeep.copy(alpha = 0.86f),
                    ),
                )
                .padding(start = 48.dp, end = 48.dp, bottom = 20.dp, top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Level 1 — Now Playing, pinned to the left safe area. The Box is
            // what keeps it left while the dock below centres: the Column's
            // CenterHorizontally would otherwise centre this block too.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                VodDockMetadata(data = data)
            }
            Spacer(Modifier.height(10.dp))
            // Level 2 — timeline ribbon.
            VodDockProgressRow(
                progress = progress,
                onSeekTo = onSeekTo,
                onUserInteraction = onUserInteraction,
            )
            Spacer(Modifier.height(9.dp))
            // Level 3 — the floating dock, centred under the timeline.
            VodDockTransportRow(
                isPlaying = data.isPlaying,
                playPauseFocus = playPauseFocus,
                onTogglePlayPause = onTogglePlayPause,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onNext = onNext,
                onOpenSheet = onOpenSheet,
                onUserInteraction = onUserInteraction,
                hasNext = hasNext,
            )
        }
    }
}

// ---------------------------------------------------------------------
// Shape helper — duplicated local copy of the `hexRowShape` used in
// `PlayerOptionsSheet` and `VodPlayerChrome`. Tiny enough to keep local
// per-file; no util module needed yet.
// ---------------------------------------------------------------------

@Composable
private fun hexRowShape(corner: Dp): Shape {
    val density = LocalDensity.current
    return remember(corner, density) {
        val c = with(density) { corner.toPx() }
        GenericShape { size, _ ->
            moveTo(c, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height - c)
            lineTo(size.width - c, size.height)
            lineTo(0f, size.height)
            lineTo(0f, c)
            close()
        }
    }
}

// Pointy-top hex orb silhouette is now [YancoShapes.PointyHex] — shared
// with the player options sheet so both surfaces drift together when the
// orb language is tuned.

// ---------------------------------------------------------------------
// Metadata block — kicker + gradient title + chip row. Sits above the
// progress bar and is the visual anchor of the dock's "what am I
// watching" answer.
// ---------------------------------------------------------------------

@Composable
private fun VodDockMetadata(data: VodDockData) {
    val reduceMotion = LocalReduceMotion.current
    // Capped at 55% of the width so the block never reaches the middle of the
    // frame, where the reference shot has a face. MB-300's two-line clamp is
    // gone because the title is now one line by construction: it cannot grow
    // the Column, so it cannot starve the transport row of height, which is
    // what made the play/pause control measure to zero on long titles.
    Column(modifier = Modifier.fillMaxWidth(0.55f)) {
        Text(
            text = stringResource(R.string.vd_now_playing_label),
            color = MidnightGlass.Champagne,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.8.sp,
        )
        Spacer(Modifier.height(4.dp))
        NowPlayingTitle(title = data.title.ifBlank { "—" }, reduceMotion = reduceMotion)
        if (data.metadataSegments.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                // Padded separator: at 14sp a bare "·" collides with digits.
                text = data.metadataSegments.joinToString("  ·  "),
                color = MidnightGlass.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        data.typeLabel?.takeIf { it.isNotBlank() }?.let { label ->
            Spacer(Modifier.height(7.dp))
            TypeBadge(label)
        }
    }
}

/**
 * MK.34.3 — the programme title. One line, and the ONLY animated element.
 *
 * `basicMarquee` already encodes the brief's rule that a title which fits must
 * stay completely still — it measures the content against the container and
 * animates only on overflow, so there is no manual scrollWidth/clientWidth
 * comparison to get wrong. Its defaults also happen to match the brief: 30.dp/s
 * velocity, and a delay before each pass. Both are stated explicitly here
 * anyway, because relying on a default that matches a spec by coincidence is
 * how a library upgrade silently changes a designed behaviour.
 *
 * Reduced motion switches to a plain ellipsis rather than a slower scroll: the
 * accessibility preference asks for no movement, not less of it.
 *
 * Direction is not forced. Compose resolves bidi from the text itself, so an
 * Arabic title lays out RTL and scrolls the way it reads, while a Turkish one
 * stays LTR — the `dir="auto"` the brief asks for, obtained by not overriding
 * what the platform already computes.
 */
@Composable
private fun NowPlayingTitle(title: String, reduceMotion: Boolean) {
    // The brief's clamp(20px, 1.7vw, 30px) — PHYSICAL pixels at 1920, like every
    // other number in that document. Expressed as a fraction of screen width so
    // it lands on the same physical size at any density: 1.7vw of 1920px is
    // 32.6px, and the 20-30px clamp is 10-15dp on a density-2.0 TV.
    val widthDp = LocalConfiguration.current.screenWidthDp
    val fontSize = (widthDp * 0.017f).coerceIn(10f, 15f).sp
    Text(
        text = title,
        color = MidnightGlass.TextPrimary,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
        maxLines = 1,
        overflow = if (reduceMotion) TextOverflow.Ellipsis else TextOverflow.Clip,
        modifier = if (reduceMotion) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .edgeFade()
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = MARQUEE_DELAY_MS,
                    repeatDelayMillis = MARQUEE_DELAY_MS,
                    velocity = MARQUEE_VELOCITY,
                )
        },
    )
}

private const val MARQUEE_DELAY_MS = 1500
private val MARQUEE_VELOCITY = 30.dp

/**
 * Soft edges on the title viewport so a scrolling line dissolves instead of
 * being guillotined by the container edge.
 *
 * `BlendMode.DstIn` multiplies the existing alpha, which needs the content in
 * its own layer to blend against — hence the offscreen compositing strategy.
 * Without it the blend would apply against the window and punch a transparent
 * hole through the dock behind the text.
 */
private fun Modifier.edgeFade(width: Dp = 22.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = (width.toPx() / size.width).coerceIn(0f, 0.5f)
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                fade to Color.Black,
                1f - fade to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Content-type badge. Small, outlined, hex-derived, and deliberately not
 * dominant — the brief calls this out, and the champagne is held at half alpha
 * so it reads as a label rather than competing with the hero control.
 */
@Composable
private fun TypeBadge(label: String) {
    val shape = YancoShapes.HexCapsule
    Box(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, MidnightGlass.Champagne.copy(alpha = 0.5f), shape)
            .padding(horizontal = 11.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = MidnightGlass.Champagne,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun VodDockProgressRow(progress: VodDockProgress, onSeekTo: (Long) -> Unit, onUserInteraction: () -> Unit) {
    val palette = LocalYancoPalette.current
    val duration = progress.durationMs.coerceAtLeast(1L)
    // MB-340 — with an unknown duration the old `coerceAtLeast(1L)` divisor made
    // both fills saturate to 100% the moment position passed 1 ms, so an
    // unprepared or duration-less stream showed a completed progress bar. Render
    // an empty track instead: no duration means no known progress.
    val durationKnown = progress.durationMs > 0L
    val playedPct = if (durationKnown) (progress.playedMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufferedPct = if (durationKnown) (progress.bufferedMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

    // Touch-scrub state. While the user drags the bar, render at [scrubPct] and
    // show the dragged time; commit the seek on release. null = follow playback.
    var barWidthPx by remember { mutableStateOf(1f) }
    var scrubPct by remember { mutableStateOf<Float?>(null) }
    val shownPct = (scrubPct ?: playedPct).coerceIn(0f, 1f)
    val shownMs = scrubPct?.let { (it * duration).toLong() } ?: progress.playedMs

    // MB-340 — derived from shownMs (not progress.playedMs) so the labels track
    // the thumb during a touch drag instead of the underlying playback position.
    // `nowMs` is read per recomposition rather than remembered: the dock ticks at
    // 2 Hz while visible, so the ends-at clock stays honest without its own timer.
    val labels = DockTimeFormatter.labels(
        playedMs = shownMs,
        durationMs = progress.durationMs,
        isLive = false,
        nowMs = System.currentTimeMillis(),
        zone = java.util.TimeZone.getDefault(),
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .focusable()
            // MK.31.2 — DELIBERATELY PHYSICAL, do not convert these to
            // startward/endward like the rest of the app's LEFT/RIGHT handlers.
            // A media timeline does not mirror under RTL: platform playback UI
            // (and every mainstream video app in Arabic) keeps the scrubber
            // left-to-right, and LEFT = rewind is muscle memory independent of
            // reading direction. Mirroring this would make Arabic users seek
            // backwards when they meant forwards.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onUserInteraction()
                        onSeekTo((progress.playedMs - 10_000L).coerceAtLeast(0L))
                        true
                    }
                    Key.DirectionRight -> {
                        onUserInteraction()
                        onSeekTo((progress.playedMs + 10_000L).coerceAtMost(progress.durationMs))
                        true
                    }
                    else -> false
                }
            },
    ) {
        Text(
            text = labels.elapsed,
            color = palette.Accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(80.dp),
        )
        // 28dp touch surface holding the 8dp visual bar + a draggable thumb.
        // PHONE: tap jumps there; drag the thumb scrubs and commits on release.
        // TV: the Row above is focusable and the ±10 keys/buttons still seek.
        Box(
            modifier = Modifier
                .height(28.dp)
                .weight(1f)
                .onSizeChanged { barWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        onUserInteraction()
                        onSeekTo(((offset.x / barWidthPx).coerceIn(0f, 1f) * duration).toLong())
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            onUserInteraction()
                            scrubPct = (offset.x / barWidthPx).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            scrubPct?.let { onSeekTo((it * duration).toLong()) }
                            scrubPct = null
                        },
                        onDragCancel = { scrubPct = null },
                    ) { change, _ ->
                        scrubPct = (change.position.x / barWidthPx).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .fillMaxWidth()
                    .clip(hexRowShape(3.dp))
                    .background(palette.BackgroundRaised),
            ) {
                // Buffered layer — pale fill up to the buffered percent.
                Box(
                    modifier = Modifier
                        .height(8.dp)
                        .fillMaxWidth(bufferedPct)
                        .background(palette.BorderSubtle.copy(alpha = 0.6f)),
                )
                // Played / scrub layer.
                Box(
                    modifier = Modifier
                        .height(8.dp)
                        .fillMaxWidth(shownPct)
                        .background(palette.Accent),
                )
            }
            // Draggable thumb (the "marker"), centered on the played/scrub point.
            Box(
                modifier = Modifier
                    .offset { IntOffset((shownPct * barWidthPx - 9.dp.toPx()).toInt(), 0) }
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(palette.Accent),
            )
        }
        // MB-340 — was a bare duration, which is the one time fact you can work
        // out for yourself. Remaining is the headline; the ends-at wall clock
        // sits under it in a lighter weight.
        //
        // A Column, not a wider single label: the map measured both slots pinned
        // at width(80.dp) sized for "00:00:00", and appending "· ENDS 21:47"
        // there would wrap (the Text has no maxLines) and grow the row height —
        // the same column that produced MB-300 at 125% font scale. Stacking keeps
        // the row height governed by the bar, and 96dp fits "-1:59:59" with the
        // sign at every shipped preset.
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.width(96.dp),
        ) {
            Text(
                text = labels.remaining ?: labels.elapsed,
                color = palette.TextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            labels.endsAt?.let { endsAt ->
                Text(
                    text = stringResource(R.string.vd_ends_at, endsAt),
                    color = palette.TextFaint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * MK.34.4 — the floating control dock.
 *
 * One centred glass slab rather than a full-width row of free-floating orbs, so
 * the controls read as a single object sitting on the frame. Reference
 * proportions are quoted at 1920x1080; the hexagons size themselves off the real
 * window width via [hexMetrics], so this is not pinned to one resolution.
 *
 * **Emphasis is deliberately unequal**, which the brief is explicit about:
 * HERO play/pause > TRANSPORT (-10 / +10 / next) > SECONDARY (CC … menu). The
 * control this replaces gave the hero an 88dp orb with a 24dp accent-tinted
 * shadow and a near-black foreground — a solid green-black button with a glow,
 * which is exactly the "excessive glow / 3D-game styling" the brief warns off.
 *
 * **PREVIOUS is gone, NEXT stays** (user decision, 2026-08-19). The reference
 * dock has neither, and "remove any stray comma/apostrophe button" describes the
 * old ‹ and › glyphs precisely — at 52dp they render as loose punctuation. But ›
 * is MB-343's next-episode control, shipped and device-verified the same day,
 * and the brief also forbids breaking existing playback actions. ‹ loses nothing:
 * `play(episode)` synthesises a one-item queue, so it was already dead for every
 * episode. › is kept and restyled as a proper hexagon in the transport cluster
 * rather than as a stray mark, which addresses the actual complaint.
 */
@Composable
private fun VodDockTransportRow(
    isPlaying: Boolean,
    playPauseFocus: FocusRequester,
    onTogglePlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onNext: () -> Unit,
    onOpenSheet: (SheetMode) -> Unit,
    onUserInteraction: () -> Unit,
    hasNext: Boolean,
) {
    val dockShape = RoundedCornerShape(18.dp)
    val dock = dockMetrics()
    Row(
        modifier = Modifier
            .glassSurface(dockShape)
            .padding(horizontal = dock.horizontalPadding, vertical = dock.verticalPadding),
        horizontalArrangement = Arrangement.spacedBy(dock.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HexControl(
            variant = HexVariant.TRANSPORT,
            contentDescription = stringResource(R.string.vd_rewind_10),
            onClick = {
                onUserInteraction()
                onSkipBack()
            },
        ) { tint -> DockLabel("-10", tint, 9.sp) }

        HexControl(
            variant = HexVariant.HERO,
            contentDescription = stringResource(if (isPlaying) R.string.vd_pause else R.string.vd_play),
            onClick = {
                onUserInteraction()
                onTogglePlayPause()
            },
            focusRequester = playPauseFocus,
        ) { tint -> DockLabel(if (isPlaying) "II" else "▶", tint, 16.sp) }

        HexControl(
            variant = HexVariant.TRANSPORT,
            contentDescription = stringResource(R.string.vd_forward_10),
            onClick = {
                onUserInteraction()
                onSkipForward()
            },
        ) { tint -> DockLabel("+10", tint, 9.sp) }

        if (hasNext) {
            HexControl(
                variant = HexVariant.TRANSPORT,
                contentDescription = stringResource(R.string.vd_next),
                onClick = {
                    onUserInteraction()
                    onNext()
                },
            ) { tint -> DockLabel("›", tint, 16.sp) }
        }

        DockDivider()

        DockSecondary(stringResource(R.string.vd_cc)) {
            onUserInteraction()
            onOpenSheet(SheetMode.SUBS)
        }
        DockSecondary(stringResource(R.string.vd_audio)) {
            onUserInteraction()
            onOpenSheet(SheetMode.AUDIO)
        }
        DockSecondary(stringResource(R.string.vd_speed)) {
            onUserInteraction()
            onOpenSheet(SheetMode.SPEED)
        }
        DockSecondary(stringResource(R.string.vd_fit)) {
            onUserInteraction()
            onOpenSheet(SheetMode.ASPECT)
        }

        HexControl(
            variant = HexVariant.SECONDARY,
            contentDescription = stringResource(R.string.vd_fav),
            onClick = {
                onUserInteraction()
                onOpenSheet(SheetMode.FAV)
            },
        ) { tint ->
            Icon(
                imageVector = YancoIcons.Favorites,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(hexMetrics(HexVariant.SECONDARY).size * 0.42f),
            )
        }
        HexControl(
            variant = HexVariant.SECONDARY,
            contentDescription = stringResource(R.string.vd_menu),
            onClick = {
                onUserInteraction()
                onOpenSheet(SheetMode.AUDIO)
            },
        ) { tint -> DockLabel("•••", tint, 11.sp) }
    }
}

/**
 * Secondary control carrying a word rather than a glyph.
 *
 * Width is derived from the label instead of fixed, because "CC" and "SPEED"
 * cannot share one box: a regular hexagon's flat top is only half its width, so
 * a single width either clips the long labels or leaves the short ones swimming.
 */
@Composable
private fun DockSecondary(label: String, onClick: () -> Unit) {
    val metrics = hexMetrics(HexVariant.SECONDARY)
    // Text sits at the hexagon's VERTICAL MIDDLE, where the silhouette is at its
    // full width — not at the narrower flat top — so the label has more room than
    // the outline suggests. ~5dp per character plus 60% of the height for the two
    // slanted ends, floored at the square size so "CC" never renders narrower
    // than a regular hexagon.
    val width = ((label.length * 5).dp + metrics.size * 0.6f).coerceAtLeast(metrics.size)
    HexControl(
        variant = HexVariant.SECONDARY,
        contentDescription = label,
        onClick = onClick,
        width = width,
    ) { tint -> DockLabel(label, tint, 9.sp) }
}

/** Shared label treatment so every control in the dock has one type voice. */
@Composable
private fun DockLabel(text: String, tint: Color, size: androidx.compose.ui.unit.TextUnit) {
    Text(
        text = text,
        color = tint,
        fontSize = size,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        maxLines = 1,
    )
}

/**
 * The brief's "subtle vertical divider" between transport and secondary groups.
 * Faint on purpose: it separates two clusters, it is not a control.
 */
@Composable
private fun DockDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(hexMetrics(HexVariant.SECONDARY).size * 0.62f)
            .background(MidnightGlass.RimLight),
    )
}

enum class SheetMode {
    AUDIO,
    SUBS,
    SPEED,
    ASPECT,
    SLEEP,
    RECORD,
    FAV,
    EXT,

    /** No matching V2 panel; activity falls through to the popup root. */
    CAST,

    /** No matching V2 panel; activity falls through to the popup root. */
    LOOK,
}
