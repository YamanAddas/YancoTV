package com.yancotv.android.player

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
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
 * MK.38 — what the dock shows on LIVE in place of the seek ribbon.
 *
 * A channel has no timeline to scrub, but it does have a programme, and that
 * is the thing a viewer actually wants to know: what is on, how far through it
 * is, and what follows. The space the scrubber occupies on a film is exactly
 * where that belongs.
 *
 * Kept dumb, like [VodDockProgress]: the caller resolves the EPG, clamps the
 * fraction and formats the label. No clock and no locale logic in here — the
 * activity already owns both, and duplicating them is how two places come to
 * disagree about what time it is.
 */
data class DockProgramme(
    val nowTitle: String,
    val nextTitle: String? = null,
    /** 0..1, already clamped by the caller. */
    val progress: Float = 0f,
    /** Pre-formatted, e.g. "23 min left". Null when the guide gives no end time. */
    val remainingLabel: String? = null,
)

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
    /**
     * MK.38 — LIVE renders the same dock. The only differences are this flag's
     * two effects: the seek ribbon becomes [programme], and NEXT drops out of
     * [dockControlOrder] because a channel has no next episode.
     */
    isLive: Boolean = false,
    programme: DockProgramme? = null,
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
    /**
     * MK.34.8 — park initial focus on the three-dot control instead of
     * play/pause.
     *
     * Set when the dock is being RESTORED after the options sheet closed. The
     * brief requires closing the sheet to return focus to the menu hex, and
     * without this the user lands back on play/pause: one BACK press silently
     * moves the cursor eight controls away from where they were working.
     */
    focusMenuOnOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (visibility != VodDockVisibility.VISIBLE) return

    val palette = LocalYancoPalette.current
    // Remember the play-pause focus requester at dock scope so we can push
    // initial focus after layout. Don't hoist further up — the dock's own
    // visibility toggle is the right `key()` boundary for focus to reset on.
    val playPauseFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }

    // Park initial focus on play-pause. LaunchedEffect ties the request to
    // this composition — re-runs any time visibility toggles from HIDDEN →
    // VISIBLE because HIDDEN-branch returns early, so this composable is
    // re-entered fresh.
    LaunchedEffect(Unit) {
        runCatching { if (focusMenuOnOpen) menuFocus.requestFocus() else playPauseFocus.requestFocus() }
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
                .padding(start = 48.dp, end = 48.dp, bottom = 10.dp, top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Level 1 — Now Playing, pinned to the left safe area. The Box is
            // what keeps it left while the dock below centres: the Column's
            // CenterHorizontally would otherwise centre this block too.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                VodDockMetadata(data = data)
            }
            Spacer(Modifier.height(6.dp))
            // Level 2 — the timeline ribbon, or on live the programme it stands
            // in for. Live keeps the row rather than collapsing it so the dock
            // does not change height when zapping between a film and a channel.
            if (isLive) {
                LiveProgrammeRow(programme)
            } else {
                VodDockProgressRow(
                    progress = progress,
                    onSeekTo = onSeekTo,
                    onUserInteraction = onUserInteraction,
                )
            }
            Spacer(Modifier.height(6.dp))
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
                menuFocus = menuFocus,
                isLive = isLive,
            )
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
    val glass = glassTokens()
    val reduceMotion = LocalReduceMotion.current
    // Capped at 55% of the width so the block never reaches the middle of the
    // frame, where the reference shot has a face. MB-300's two-line clamp is
    // gone because the title is now one line by construction: it cannot grow
    // the Column, so it cannot starve the transport row of height, which is
    // what made the play/pause control measure to zero on long titles.
    Column(modifier = Modifier.fillMaxWidth(0.55f)) {
        Text(
            text = stringResource(R.string.vd_now_playing_label),
            color = glass.accent,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.8.sp,
        )
        Spacer(Modifier.height(3.dp))
        NowPlayingTitle(title = data.title.ifBlank { "—" }, reduceMotion = reduceMotion)
        if (data.metadataSegments.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                // Padded separator: at 14sp a bare "·" collides with digits.
                text = data.metadataSegments.joinToString("  ·  "),
                color = glass.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        data.typeLabel?.takeIf { it.isNotBlank() }?.let { label ->
            Spacer(Modifier.height(5.dp))
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
    val glass = glassTokens()
    // The brief's clamp(20px, 1.7vw, 30px) — PHYSICAL pixels at 1920, like every
    // other number in that document. Expressed as a fraction of screen width so
    // it lands on the same physical size at any density: 1.7vw of 1920px is
    // 32.6px, and the 20-30px clamp is 10-15dp on a density-2.0 TV.
    val widthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val fontSize = PlayerChromeMetrics.titleFontSp(widthDp).sp
    val mode = marqueeMode(reduceMotion)
    Text(
        text = title,
        color = glass.textPrimary,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
        maxLines = 1,
        overflow = if (mode == MarqueeMode.ELLIPSIS) TextOverflow.Ellipsis else TextOverflow.Clip,
        modifier = if (mode == MarqueeMode.ELLIPSIS) {
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
    val glass = glassTokens()
    val shape = YancoShapes.HexCapsule
    Box(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, glass.accent.copy(alpha = 0.5f), shape)
            .padding(horizontal = 11.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = glass.accent,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            maxLines = 1,
        )
    }
}

/**
 * MK.38 — what stands in for the seek ribbon on a live channel.
 *
 * Same height and same horizontal rhythm as [VodDockProgressRow], so the dock
 * does not jump when zapping between a film and a channel — the row is kept
 * even with no guide data, rendering an empty track rather than collapsing.
 *
 * Not focusable, unlike the VOD ribbon: there is nothing to scrub to. A focus
 * stop that does nothing costs a D-pad press on the way to the transport row,
 * which on a remote is a real cost rather than a cosmetic one.
 */
@Composable
private fun LiveProgrammeRow(programme: DockProgramme?) {
    val glass = glassTokens()
    val dock = dockMetrics()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dock.horizontalPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = programme?.nowTitle.orEmpty(),
                    color = glass.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (programme?.remainingLabel != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = programme.remainingLabel,
                        color = glass.textDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // The track. Painted even with no programme so the row keeps its
            // height — see the note above about zapping.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(glass.textDim.copy(alpha = 0.35f)),
            ) {
                val pct = (programme?.progress ?: 0f).coerceIn(0f, 1f)
                if (pct > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(pct)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(glass.accent),
                    )
                }
            }

            if (programme?.nextTitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.vd_dock_up_next, programme.nextTitle),
                    color = glass.textDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun VodDockProgressRow(progress: VodDockProgress, onSeekTo: (Long) -> Unit, onUserInteraction: () -> Unit) {
    val glass = glassTokens()
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

    val dock = dockMetrics()
    // MK.34.9 — the ribbon is pinned LTR, deliberately.
    //
    // MK.31.2 already decided the seek KEYS stay physical: LEFT rewinds in every
    // locale, because that is muscle memory independent of reading direction and
    // every mainstream player keeps a scrubber left-to-right. What was missing is
    // that the ribbon's VISUALS were still direction-aware, so under Arabic the
    // elapsed/remaining labels would swap sides and the played fill would grow
    // from the right — while LEFT still rewound. Half-mirrored is worse than
    // either choice: the user would press LEFT and watch the bar grow toward the
    // press. Pinning the ribbon LTR makes the visuals agree with the keys.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dock.horizontalPadding),
            modifier = Modifier
                // MK.34.5 - the brief's 70-82% of available width. A separate,
                // narrower ribbon than the metadata block above it, so the three
                // levels read as distinct objects rather than one stacked panel.
                .fillMaxWidth(0.78f)
                .glassSurface(RoundedCornerShape(percent = 50), alpha = 0.75f)
                .padding(horizontal = dock.horizontalPadding, vertical = dock.verticalPadding)
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
            // Elapsed at the left, remaining at the right, and NEITHER over the
            // track - the brief is explicit, and time text on a moving fill is
            // unreadable at three metres anyway.
            Text(
                text = labels.elapsed,
                color = glass.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            // 28dp touch surface holding the 8dp visual bar + a draggable thumb.
            // PHONE: tap jumps there; drag the thumb scrubs and commits on release.
            // TV: the Row above is focusable and the ±10 keys/buttons still seek.
            Box(
                modifier = Modifier
                    // Touch surface stays generous while the VISUAL track slims to
                    // 3dp: shrinking the hit area with the artwork would make the
                    // phone drag path finicky for no design gain.
                    .height(14.dp)
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
                        .height(3.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(glass.textDim.copy(alpha = 0.35f)),
                ) {
                    // Buffered layer - barely there, so it reads as loaded rather
                    // than competing with the played fill for attention.
                    Box(
                        modifier = Modifier
                            .height(3.dp)
                            .fillMaxWidth(bufferedPct)
                            .background(glass.textSecondary.copy(alpha = 0.3f)),
                    )
                    // Played fill - blue, per the token roles: blue is the timeline
                    // and navigation colour, champagne is reserved for selection, so
                    // a focused control never competes with the track for it.
                    Box(
                        modifier = Modifier
                            .height(3.dp)
                            .fillMaxWidth(shownPct)
                            .background(glass.accentSoft),
                    )
                }
                // Scrubber - a small champagne HEXAGON, not a circle, so the
                // signature shape carries into the timeline instead of stopping at
                // the dock. Still the drag target; only the silhouette changed.
                Box(
                    modifier = Modifier
                        .offset { IntOffset((shownPct * barWidthPx - 5.dp.toPx()).toInt(), 0) }
                        .size(10.dp)
                        .clip(MidnightHex)
                        .background(glass.accent),
                )
            }
            // MB-340 - was a bare duration, which is the one time fact you can work
            // out for yourself. Remaining is the headline; the ends-at wall clock
            // trails it in a dimmer weight.
            //
            // MK.34.5 - these were STACKED in a Column, which made this row 58px
            // tall and was the single biggest contributor to the overlay blowing its
            // height budget. Inlining them spends width, which the ribbon has, rather
            // than height, which it does not. Both stay maxLines = 1 and the group is
            // width-capped: MB-300 was an unbounded Text in exactly this row starving
            // its siblings at a large font scale.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.widthIn(max = 132.dp),
            ) {
                Text(
                    text = labels.remaining ?: labels.elapsed,
                    color = glass.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                labels.endsAt?.let { endsAt ->
                    Text(
                        text = stringResource(R.string.vd_ends_at, endsAt),
                        color = glass.textDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
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
    menuFocus: FocusRequester,
    isLive: Boolean,
) {
    val glass = glassTokens()
    val dockShape = RoundedCornerShape(18.dp)
    val dock = dockMetrics()
    // MK.34.9 — pinned LTR with the ribbon above it. -10 / +10 / next are
    // directional controls bound to a timeline that does not mirror, so
    // reversing them would put +10 on the left of a bar that still fills
    // rightward. The brief's rule — icons must not reverse unless their meaning
    // is directional — cuts this way: their meaning IS directional, and the
    // direction they refer to is the timeline's, not the text's.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        // MK.38.2 — the row measures itself. A plain Row silently measures every
        // child past the edge at zero width, and ours is ordered with the menu
        // LAST, so a narrow screen deleted the one control that could reach
        // everything the narrow screen had dropped. See [fitDockControls].
        BoxWithConstraints {
            val fit = rememberDockFit(
                hasNext = hasNext,
                isLive = isLive,
                availableDp = (maxWidth - dock.horizontalPadding * 2).value,
                gapDp = dock.gap.value,
            )
            Row(
                modifier = Modifier
                    .glassSurface(dockShape)
                    .padding(horizontal = dock.horizontalPadding, vertical = dock.verticalPadding),
                horizontalArrangement = Arrangement.spacedBy(dock.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // MK.34.10 — rendered by WALKING dockControlOrder rather than by
                // listing the controls here. The brief specifies an exact sequence,
                // and until now the only place that sequence existed was the order
                // these calls happened to appear in: nothing could assert it, and a
                // reorder during a refactor would have been silent because the dock
                // would still build, still focus and still work. Now the order is
                // data, and the test and the screen read the same object.
                fit.shown.forEach { control ->
                    when (control) {
                        DockControl.SKIP_BACK ->
                            HexControl(
                                variant = HexVariant.TRANSPORT,
                                contentDescription = stringResource(R.string.vd_rewind_10),
                                onClick = {
                                    onUserInteraction()
                                    onSkipBack()
                                },
                            ) { tint -> DockLabel("-10", tint, 9.sp) }

                        DockControl.PLAY_PAUSE ->
                            HexControl(
                                variant = HexVariant.HERO,
                                contentDescription = stringResource(if (isPlaying) R.string.vd_pause else R.string.vd_play),
                                onClick = {
                                    onUserInteraction()
                                    onTogglePlayPause()
                                },
                                focusRequester = playPauseFocus,
                            ) { tint -> DockLabel(if (isPlaying) "II" else "\u25B6", tint, 16.sp) }

                        DockControl.SKIP_FORWARD ->
                            HexControl(
                                variant = HexVariant.TRANSPORT,
                                contentDescription = stringResource(R.string.vd_forward_10),
                                onClick = {
                                    onUserInteraction()
                                    onSkipForward()
                                },
                            ) { tint -> DockLabel("+10", tint, 9.sp) }

                        DockControl.NEXT ->
                            HexControl(
                                variant = HexVariant.TRANSPORT,
                                contentDescription = stringResource(R.string.vd_next),
                                onClick = {
                                    onUserInteraction()
                                    onNext()
                                },
                            ) { tint -> DockLabel("\u203A", tint, 16.sp) }

                        DockControl.DIVIDER -> DockDivider()

                        DockControl.SUBTITLES ->
                            DockSecondary(stringResource(R.string.vd_cc)) {
                                onUserInteraction()
                                onOpenSheet(SheetMode.SUBS)
                            }

                        DockControl.AUDIO ->
                            DockSecondary(stringResource(R.string.vd_audio)) {
                                onUserInteraction()
                                onOpenSheet(SheetMode.AUDIO)
                            }

                        DockControl.SPEED ->
                            DockSecondary(stringResource(R.string.vd_speed)) {
                                onUserInteraction()
                                onOpenSheet(SheetMode.SPEED)
                            }

                        DockControl.ASPECT ->
                            DockSecondary(stringResource(R.string.vd_fit)) {
                                onUserInteraction()
                                onOpenSheet(SheetMode.ASPECT)
                            }

                        DockControl.FAVORITE ->
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

                        DockControl.MENU ->
                            HexControl(
                                variant = HexVariant.SECONDARY,
                                contentDescription = stringResource(R.string.vd_menu),
                                focusRequester = menuFocus,
                                onClick = {
                                    onUserInteraction()
                                    onOpenSheet(SheetMode.MENU)
                                },
                            ) { tint -> DockLabel("\u2022\u2022\u2022", tint, 11.sp) }
                    }
                }
            }
        }
    }
}

/**
 * MK.38.2 — how wide each control will be, resolved before any of them exists.
 *
 * Every width here is deterministic from the metrics and the label, which is
 * the whole reason this can be a calculation rather than a `SubcomposeLayout`:
 * hexagons are sized by [PlayerChromeMetrics.hexSizeDp] and word controls by
 * [PlayerChromeMetrics.dockSecondaryWidthDp], and `DockSecondary` calls that
 * same function, so the measurement and the render cannot disagree.
 *
 * The labels are read through `stringResource` on purpose — the fit depends on
 * the locale. `VELOCIDAD` is four characters longer than `SPEED`, which is the
 * difference between the row fitting on a phone and not.
 */
@Composable
private fun rememberDockFit(hasNext: Boolean, isLive: Boolean, availableDp: Float, gapDp: Float): DockFit {
    val order = dockControlOrder(hasNext, isLive)
    val transport = hexMetrics(HexVariant.TRANSPORT).size.value
    val hero = hexMetrics(HexVariant.HERO).size.value
    val secondary = hexMetrics(HexVariant.SECONDARY).size.value
    val cc = stringResource(R.string.vd_cc)
    val audio = stringResource(R.string.vd_audio)
    val speed = stringResource(R.string.vd_speed)
    val aspect = stringResource(R.string.vd_fit)

    val widths = remember(transport, hero, secondary, cc, audio, speed, aspect) {
        mapOf(
            DockControl.SKIP_BACK to transport,
            DockControl.PLAY_PAUSE to hero,
            DockControl.SKIP_FORWARD to transport,
            DockControl.NEXT to transport,
            DockControl.DIVIDER to PlayerChromeMetrics.DOCK_DIVIDER_WIDTH_DP,
            DockControl.SUBTITLES to PlayerChromeMetrics.dockSecondaryWidthDp(cc, secondary),
            DockControl.AUDIO to PlayerChromeMetrics.dockSecondaryWidthDp(audio, secondary),
            DockControl.SPEED to PlayerChromeMetrics.dockSecondaryWidthDp(speed, secondary),
            DockControl.ASPECT to PlayerChromeMetrics.dockSecondaryWidthDp(aspect, secondary),
            DockControl.FAVORITE to secondary,
            DockControl.MENU to secondary,
        )
    }

    return remember(order, widths, gapDp, availableDp) {
        fitDockControls(order, widths, gapDp, availableDp)
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
    // MK.38.2 — the formula moved to PlayerChromeMetrics so fitDockControls can
    // ask how wide this will be before it exists. Two copies would drift, and
    // the symptom is a row that measures as fitting and then does not.
    val width = PlayerChromeMetrics.dockSecondaryWidthDp(label, metrics.size.value).dp
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
    val glass = glassTokens()
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
    val glass = glassTokens()
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(hexMetrics(HexVariant.SECONDARY).size * 0.62f)
            .background(glass.rim),
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

    /**
     * MK.34.7 — the dock's three-dot control. Deliberately has no V2 panel, so
     * the activity falls through to the popup ROOT.
     *
     * It used to send AUDIO, which opened the Audio panel directly and skipped
     * the options sheet entirely — the popup hides itself whenever a panel is
     * active. A three-dot menu that lands you inside one setting is not a menu.
     */
    MENU,

    /** No matching V2 panel; activity falls through to the popup root. */
    CAST,

    /** No matching V2 panel; activity falls through to the popup root. */
    LOOK,
}
