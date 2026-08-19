package com.yancotv.android.player

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenSheet: (SheetMode) -> Unit,
    onSeekTo: (Long) -> Unit,
    onUserInteraction: () -> Unit,
    // True when the active queue has more than one item — i.e. prev/next can
    // actually move to a sibling. False for VOD movies (one-item queue). The
    // dock hides the ‹ transport button entirely when this is false so the user
    // isn't staring at a control that no-ops when pressed.
    hasSiblings: Boolean = true,
    // MB-343 (W4) — gates › separately from ‹.
    //
    // This used to ride on [hasSiblings], which made the NEXT button dead for
    // every episode: `PlaybackController.play(episode)` synthesises a ONE-item
    // queue, so `queue.size > 1` was always false during a binge and the one
    // control purpose-built for "go to the next episode" never rendered. That
    // is what the old comment here meant by "until sibling-episode loading
    // lands as a follow-up MK" — this is that follow-up.
    //
    // Split rather than widened because the two directions resolve differently:
    // NEXT has a prefetched next-episode target (PlayerActivity.upNextTarget),
    // PREVIOUS does not, so folding them together would light up a ‹ that still
    // no-ops.
    hasNext: Boolean = hasSiblings,
    // MK.28.7 (MB-273) — the "◂▸ SEEK · OK HIDE · ◀ BACK" hint strip
    // describes inputs a phone doesn't have; render it on TV only.
    isTv: Boolean = true,
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
        // Bottom scrim + content column. Gradient matches the scrim-bottom
        // from the design so the dock reads as a coherent dark band over
        // whatever frame is behind it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(start = 48.dp, end = 48.dp, bottom = 48.dp, top = 48.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to palette.BackgroundDeep.copy(alpha = 0.55f),
                        1f to palette.BackgroundDeep.copy(alpha = 0.92f),
                    ),
                ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                VodDockMetadata(data = data)
                Spacer(Modifier.height(26.dp))
                VodDockProgressRow(
                    progress = progress,
                    onSeekTo = onSeekTo,
                    onUserInteraction = onUserInteraction,
                )
                Spacer(Modifier.height(26.dp))
                VodDockTransportRow(
                    isPlaying = data.isPlaying,
                    playPauseFocus = playPauseFocus,
                    onTogglePlayPause = onTogglePlayPause,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onOpenSheet = onOpenSheet,
                    onUserInteraction = onUserInteraction,
                    hasSiblings = hasSiblings,
                    hasNext = hasNext,
                )
                if (isTv) {
                    Spacer(Modifier.height(26.dp))
                    VodDockHintRow()
                }
            }
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
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.4.sp,
        )
        Spacer(Modifier.height(8.dp))
        NowPlayingTitle(title = data.title.ifBlank { "—" }, reduceMotion = reduceMotion)
        if (data.metadataSegments.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            Text(
                // Padded separator: at 14sp a bare "·" collides with digits.
                text = data.metadataSegments.joinToString("  ·  "),
                color = MidnightGlass.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        data.typeLabel?.takeIf { it.isNotBlank() }?.let { label ->
            Spacer(Modifier.height(11.dp))
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
    // The brief's clamp(20px, 1.7vw, 30px), scaled off the real window width.
    val widthDp = LocalConfiguration.current.screenWidthDp
    val fontSize = (widthDp * 0.028f).coerceIn(20f, 30f).sp
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
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
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

// ---------------------------------------------------------------------
// Transport row — 5 primary buttons (prev / -10 / play-pause / +10 /
// next) + 6 secondary chips (CC / audio / speed / aspect / fav / menu).
// Primary buttons hold the focus spotlight; chips open downstream sheets.
// ---------------------------------------------------------------------

@Composable
private fun VodDockTransportRow(
    isPlaying: Boolean,
    playPauseFocus: FocusRequester,
    onTogglePlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenSheet: (SheetMode) -> Unit,
    onUserInteraction: () -> Unit,
    hasSiblings: Boolean,
    hasNext: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // ‹ renders only when the queue has more than one item — otherwise it is
        // a dead control, because PlaybackController.step coerces to a valid
        // index and no-ops when target == current. Single-item queues happen
        // for VOD movies and for the per-episode play path. › is gated on
        // [hasNext] instead, which MB-343 wires to the prefetched next episode.
        if (hasSiblings) {
            TransportButton(
                label = "‹",
                contentLabel = stringResource(R.string.vd_previous),
                size = 52.dp,
                onClick = {
                    onUserInteraction()
                    onPrevious()
                },
            )
            Spacer(Modifier.width(14.dp))
        }
        TransportButton(
            label = "-10",
            contentLabel = stringResource(R.string.vd_rewind_10),
            size = 58.dp,
            onClick = {
                onUserInteraction()
                onSkipBack()
            },
        )
        Spacer(Modifier.width(14.dp))
        TransportButton(
            label = if (isPlaying) "||" else "▶",
            // MB-343 — was the hardcoded literals "Pause" / "Play", the last
            // untranslated screen-reader label in this row after vd_previous and
            // vd_next moved to resources. A string constant in code is invisible
            // to lint MissingTranslation and to the MK.31 i18n sweep.
            contentLabel = stringResource(if (isPlaying) R.string.vd_pause else R.string.vd_play),
            size = 88.dp,
            primary = true,
            focusRequester = playPauseFocus,
            onClick = {
                onUserInteraction()
                onTogglePlayPause()
            },
        )
        Spacer(Modifier.width(14.dp))
        TransportButton(
            label = "+10",
            contentLabel = stringResource(R.string.vd_forward_10),
            size = 58.dp,
            onClick = {
                onUserInteraction()
                onSkipForward()
            },
        )
        if (hasNext) {
            Spacer(Modifier.width(14.dp))
            TransportButton(
                label = "›",
                // MB-343 — was the hardcoded literal "Next". A string constant
                // in code is invisible to lint MissingTranslation and to the
                // MK.31 i18n sweep, so this button's screen-reader label was
                // English in all four locales.
                contentLabel = stringResource(R.string.vd_next),
                size = 52.dp,
                onClick = {
                    onUserInteraction()
                    onNext()
                },
            )
        }
        Spacer(Modifier.width(30.dp))
        // Each secondary chip routes to the matching sheet tab. CC → SUBS
        // because the enum name is SUBS but the user-facing vocab is CC on
        // remotes. MENU opens on AUDIO as the default landing, matching
        // the MENU key behaviour elsewhere.
        SecondaryChip(label = stringResource(R.string.vd_cc), onClick = {
            onUserInteraction()
            onOpenSheet(SheetMode.SUBS)
        })
        Spacer(Modifier.width(8.dp))
        SecondaryChip(label = stringResource(R.string.vd_audio), onClick = {
            onUserInteraction()
            onOpenSheet(SheetMode.AUDIO)
        })
        Spacer(Modifier.width(8.dp))
        SecondaryChip(label = stringResource(R.string.vd_speed), onClick = {
            onUserInteraction()
            onOpenSheet(SheetMode.SPEED)
        })
        Spacer(Modifier.width(8.dp))
        SecondaryChip(label = stringResource(R.string.vd_fit), onClick = {
            onUserInteraction()
            onOpenSheet(SheetMode.ASPECT)
        })
        Spacer(Modifier.width(8.dp))
        SecondaryChip(label = stringResource(R.string.vd_fav), onClick = {
            onUserInteraction()
            onOpenSheet(SheetMode.FAV)
        })
        Spacer(Modifier.width(8.dp))
        SecondaryChip(label = stringResource(R.string.vd_menu), onClick = {
            onUserInteraction()
            onOpenSheet(SheetMode.AUDIO)
        })
    }
}

/**
 * Hex-orb transport button. Pointy-top hex silhouette with a luminous
 * accent glow on focus / primary, matching the YancoVerse lobby orb
 * language (the user's reference photo). The visual weight order is
 * primary > focused > idle:
 *
 *   - primary      → solid Accent → AccentDeep gradient, max glow,
 *                    black foreground (the play-pause hero)
 *   - focused      → dim accent wash + accent border + smaller glow
 *                    (the current cursor)
 *   - idle         → BackgroundElevated → BackgroundDeep gradient,
 *                    BorderSubtle hairline, no glow
 *
 * Glow uses the canonical CategoryRail pattern: `.shadow()` with
 * accent-tinted ambient + spot colors, applied BEFORE `.clip()` so it
 * radiates outside the hex outline.
 */
@Composable
private fun TransportButton(
    label: String,
    size: Dp,
    onClick: () -> Unit,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    contentLabel: String = label,
) {
    val palette = LocalYancoPalette.current
    val shape = YancoShapes.PointyHex
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val bgBrush =
        when {
            primary ->
                Brush.verticalGradient(listOf(palette.Accent, palette.AccentDeep))
            isFocused ->
                Brush.verticalGradient(
                    listOf(
                        palette.Accent.copy(alpha = 0.28f),
                        palette.AccentDeep.copy(alpha = 0.18f),
                    ),
                )
            else ->
                Brush.verticalGradient(
                    listOf(
                        palette.BackgroundElevated,
                        palette.BackgroundDeep.copy(alpha = 0.92f),
                    ),
                )
        }
    val borderColor =
        when {
            primary -> palette.Accent
            isFocused -> palette.Accent
            else -> palette.BorderSubtle
        }
    val fgColor =
        when {
            primary -> Color(0xFF04130C)
            isFocused -> palette.Accent
            else -> palette.TextPrimary
        }
    val glowElevation =
        when {
            primary -> 24.dp
            isFocused -> 16.dp
            else -> 0.dp
        }
    val baseModifier =
        Modifier
            .size(size)
            .shadow(
                elevation = glowElevation,
                shape = shape,
                ambientColor = palette.Accent,
                spotColor = palette.Accent,
            )
            .clip(shape)
            .background(bgBrush)
            .border(if (isFocused || primary) 2.dp else 1.dp, borderColor, shape)
            .onFocusChanged { isFocused = it.isFocused }
    val finalModifier =
        if (focusRequester != null) baseModifier.focusRequester(focusRequester) else baseModifier
    Box(
        modifier = finalModifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = contentLabel
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fgColor,
            fontSize = if (size >= 80.dp) 26.sp else 18.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

/**
 * Hex-pill secondary chip — same orb language as [TransportButton] but
 * silhouetted as a horizontal [YancoShapes.HexCapsule] so multi-letter
 * labels (CC / AUDIO / SPEED / FIT / FAV / MENU) fit the middle runway
 * cleanly. Idle = dim BackgroundElevated; focused = soft accent wash +
 * accent border + accent glow. No "primary" state — these are all peers.
 */
@Composable
private fun SecondaryChip(label: String, onClick: () -> Unit) {
    val palette = LocalYancoPalette.current
    val shape = YancoShapes.HexCapsule
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val bgBrush =
        if (isFocused) {
            Brush.verticalGradient(
                listOf(
                    palette.Accent.copy(alpha = 0.28f),
                    palette.AccentDeep.copy(alpha = 0.18f),
                ),
            )
        } else {
            SolidColor(palette.BackgroundElevated)
        }
    val borderColor = if (isFocused) palette.Accent else palette.BorderSubtle
    val fgColor = if (isFocused) palette.Accent else palette.TextPrimary
    Box(
        modifier = Modifier
            .height(42.dp)
            .shadow(
                elevation = if (isFocused) 14.dp else 0.dp,
                shape = shape,
                ambientColor = palette.Accent,
                spotColor = palette.Accent,
            )
            .clip(shape)
            .background(bgBrush)
            .border(if (isFocused) 2.dp else 1.dp, borderColor, shape)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fgColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ---------------------------------------------------------------------
// Remote hint strip at the bottom of the dock. Static copy — matches
// the design HTML's hint row so the user knows what the remote does
// while the dock is visible.
// ---------------------------------------------------------------------

@Composable
private fun VodDockHintRow() {
    val palette = LocalYancoPalette.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        HintItem(prefix = "◂▸", label = stringResource(R.string.vd_hint_seek))
        HintItem(prefix = "▾", label = stringResource(R.string.vd_menu))
        HintItem(prefix = "OK", label = stringResource(R.string.vd_hint_hide))
        HintItem(prefix = "◀", label = stringResource(R.string.vc_back))
    }
}

@Composable
private fun HintItem(prefix: String, label: String) {
    val palette = LocalYancoPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = prefix,
            color = palette.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = palette.TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.4.sp,
        )
    }
}

/**
 * 2026-04-27 — slim chip-route enum. Originally the tab key for
 * PlayerOptionsSheet.kt (a 700-line side sheet with per-tab metadata
 * fields). The sheet was retired when both LIVE and VOD migrated to
 * the new options popup + per-category panels (`PlayerOptionsMenu` /
 * `PlayerOptionsPanelHost`). This enum survives only because the
 * dock's secondary chips still emit a route hint to the activity,
 * which maps it to a `PlayerOptionCategory` for `showOptionsV2`.
 *
 * Could be replaced by `PlayerOptionCategory` directly to drop the
 * mapping; left as a separate UI-side enum so the dock stays
 * unaware of the options-package internals.
 */
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
