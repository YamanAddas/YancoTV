package com.yancotv.android.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.ui.components.focusStyle
import com.yancotv.android.ui.focus.PlacedFocusAnchor
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.ShellDim
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoType
import com.yancotv.shared.content.ContentDetailService
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.history.EpisodeResumeInfo
import com.yancotv.shared.history.WatchHistoryRepository
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
    onPlayFromStart: (ContentItem, EpisodeInfo?) -> Unit,
    onResetProgress: (ContentItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    detailService: ContentDetailService = koinInject(),
    favorites: FavoritesRepository = koinInject(),
    watchHistory: WatchHistoryRepository = koinInject(),
) {
    var loaded by remember(item.id) { mutableStateOf<ContentDetailService.Loaded?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var isFav by remember(item.id) { mutableStateOf(false) }
    val playAnchor = rememberPlacedFocusAnchor()
    val scope = rememberCoroutineScope()

    LaunchedEffect(item.id) {
        loading = true
        val result =
            withContext(Dispatchers.IO) {
                runCatching { detailService.load(item) }.getOrNull()
            }
        loaded = result
        loading = false
    }
    LaunchedEffect(item.id) {
        try {
            favorites.isFavoriteFlow(item.id).collect { isFav = it }
        } catch (_: Throwable) {
            // non-blocking
        }
    }

    val rendered = loaded?.item ?: item
    val metadata = loaded?.metadata ?: ContentMetadata()
    val episodes = loaded?.episodes.orEmpty()
    val seasons = remember(episodes) { episodes.groupBy { it.seasonNumber }.toSortedMap() }
    var selectedSeason by remember(seasons) {
        mutableStateOf(seasons.keys.firstOrNull() ?: 0)
    }
    val visibleEpisodes =
        remember(seasons, selectedSeason) {
            seasons[selectedSeason].orEmpty()
        }
    var seasonPickerOpen by remember(item.id) { mutableStateOf(false) }
    // Anchor for the first visible episode of the active season. Re-keyed
    // on selectedSeason so each season change spawns a *fresh* anchor —
    // and thus a fresh FocusRequester. Reusing one across seasons hits a
    // race: the old requester briefly loses its binding while the new
    // first-episode node attaches, and a requestFocus() landing in that
    // gap silently no-ops. With a per-season anchor, the new requester
    // binds to the new node cleanly and isPlaced starts at false.
    val firstEpisodeAnchor = remember(selectedSeason) { PlacedFocusAnchor() }
    // Gate so the focus-shift LaunchedEffect doesn't fire on the initial
    // season pick (the one auto-set when episodes first load) — that
    // would steal focus from the Play button before the user has even
    // looked at it.
    var hasInitialFocus by remember(item.id) { mutableStateOf(false) }

    // Resume info — async DB read once we know the content + episode list.
    // Recomputed when episodes settle so the button label updates from
    // "Play" → "Resume S2E4" / "Play S2E5" / "Watch again S1E1" without
    // forcing the user to wait on initial render.
    var resumeInfo by remember(item.id) { mutableStateOf<EpisodeResumeInfo?>(null) }
    // Whether ANY watch_history row exists for this content (movie row
    // OR any episode row). Drives visibility of the "Reset progress"
    // secondary action — no point showing it on a never-watched title.
    var hasHistory by remember(item.id) { mutableStateOf(false) }
    // Movie-only: mid-stream resume position. Null for "no row" AND
    // "finished" (positionFor applies the 95% rule on the read side),
    // which collapses both into the same UX — primary button says
    // "Play" and starts from 0. Non-null → "Continue".
    var movieResumeSeconds by remember(item.id) { mutableStateOf<Long?>(null) }
    // Bumped by the reset action so the LaunchedEffect re-runs and the
    // resume / history state reflects the cleared row immediately.
    var historyVersion by remember(item.id) { mutableStateOf(0) }
    LaunchedEffect(item.id, episodes.size, historyVersion) {
        val snapshot =
            withContext(Dispatchers.IO) {
                val info =
                    if (rendered.type == ContentType.SERIES && episodes.isNotEmpty()) {
                        runCatching { watchHistory.mostRecentEpisode(rendered.id) }.getOrNull()
                    } else {
                        null
                    }
                val movieResume =
                    if (rendered.type == ContentType.MOVIE) {
                        runCatching { watchHistory.positionFor(rendered.id) }.getOrNull()
                    } else {
                        null
                    }
                val any = runCatching { watchHistory.hasAnyForContent(rendered.id) }.getOrDefault(false)
                Triple(info, movieResume, any)
            }
        resumeInfo = snapshot.first
        movieResumeSeconds = snapshot.second
        hasHistory = snapshot.third
    }
    val playChoice =
        remember(episodes, resumeInfo) {
            computeNextEpisode(episodes, resumeInfo)
        }
    val sortedEpisodes =
        remember(episodes) {
            episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }, { it.id }))
        }

    // Reset-progress confirmation dialog state. Keyed per item so opening
    // another title resets the dialog.
    var resetDialogOpen by remember(item.id) { mutableStateOf(false) }

    // Single LazyColumn governs the whole page so d-pad focus never has
    // to cross a scroll-container boundary.
    val listState = rememberLazyListState()
    val trapFocus = remember { FocusRequester() }
    // True once the open-time focus ladder has handed off to the Play
    // button. While false the 0-dp Spacer below is focusable so we have
    // a target for the initial trapFocus.requestFocus(). Once the
    // playAnchor is wired and focused, we disable the Spacer's
    // focusable so spatial UP from the action row doesn't dump focus
    // onto an invisible 0-dp node (the "stuck focus on top" feeling).
    // Reset per item.id so re-opening another series rearms the trap.
    var initialFocusTransferred by remember(item.id) { mutableStateOf(false) }

    // Focus trap: focusGroup boundary + an invisible 0-dp Spacer anchor.
    // The Spacer is the first focusable node inside the group, so it
    // receives focus on open before the Play button is ready. LaunchedEffect
    // below hands off to playAnchor once the button node is placed.
    // Using .focusable() (not .clickable) ensures CENTER presses on the
    // anchor are no-ops — they never intercept episode-row activations.
    Box(
        modifier =
        modifier
            .fillMaxSize()
            .background(LocalYancoPalette.current.BackgroundDeep)
            .focusGroup(),
    ) {
        Spacer(
            Modifier
                .size(0.dp)
                .focusRequester(trapFocus)
                .focusable(enabled = !initialFocusTransferred),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Space.section),
        ) {
            item(key = "hero") {
                HeroBlock(
                    item = rendered,
                    metadata = metadata,
                    episodes = episodes,
                    playChoice = playChoice,
                    movieResumeSeconds = movieResumeSeconds,
                    hasHistory = hasHistory,
                    isFavorite = isFav,
                    onPlay = {
                        // Honour the mode `computeNextEpisode` already computed:
                        //   - RESUME      → continue from saved offset on the current episode
                        //   - PLAY_NEXT   → next episode from 0 (current was finished)
                        //   - PLAY_FIRST  → S1E1 (no history yet)
                        //   - WATCH_AGAIN → S1E1 (whole series watched through)
                        // Without this branching, every Play click went through
                        // onPlayEpisode → controller.play(playable) with default
                        // fromStart=false, which seeks to the stored offset. If the
                        // chosen episode happened to be finished (PLAY_NEXT case
                        // with stale watch_history, or the original WATCH_AGAIN
                        // case where the first ep was already 100% watched), the
                        // player seeks to the end and loops.
                        when (rendered.type) {
                            ContentType.SERIES -> {
                                val choice = playChoice
                                val ep = choice?.episode
                                if (ep != null) {
                                    when (choice.mode) {
                                        PlayMode.RESUME -> onPlayEpisode(rendered, ep)
                                        PlayMode.PLAY_NEXT,
                                        PlayMode.PLAY_FIRST,
                                        PlayMode.WATCH_AGAIN,
                                        -> onPlayFromStart(rendered, ep)
                                    }
                                } else {
                                    onPlayContent(rendered)
                                }
                            }
                            else -> onPlayContent(rendered)
                        }
                    },
                    onPlayFromStart = {
                        // Series: jump to the first sorted episode (S1E1
                        // or the earliest available special). Movie: pass
                        // a null EpisodeInfo so the parent invokes
                        // controller.play(list, idx, fromStart=true) on
                        // the movie itself.
                        when (rendered.type) {
                            ContentType.SERIES -> {
                                val first = sortedEpisodes.firstOrNull()
                                if (first != null) onPlayFromStart(rendered, first)
                            }
                            else -> onPlayFromStart(rendered, null)
                        }
                    },
                    onReset = { resetDialogOpen = true },
                    onFavoriteToggle = {
                        val optimistic = !isFav
                        isFav = optimistic
                        scope.launch {
                            val newState =
                                withContext(Dispatchers.IO) {
                                    runCatching { favorites.toggle(rendered.id) }.getOrElse { optimistic }
                                }
                            if (newState != optimistic) isFav = newState
                        }
                    },
                    onBack = onDismiss,
                    playAnchor = playAnchor,
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
                    item(key = "season_selector") {
                        SeasonSelector(
                            seasonCount = seasons.size,
                            selectedSeason = selectedSeason,
                            episodeCount = seasons[selectedSeason]?.size ?: 0,
                            open = seasonPickerOpen,
                            onTriggerClick = { seasonPickerOpen = true },
                        )
                    }
                }
                if (visibleEpisodes.isEmpty() && !loading) {
                    item(key = "no_episodes") {
                        Box(
                            modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Space.page, vertical = Space.md),
                        ) {
                            Text(
                                text = "No episodes available.",
                                color = LocalYancoPalette.current.TextMuted,
                                style = YancoType.Body,
                            )
                        }
                    }
                }
                itemsIndexed(visibleEpisodes, key = { _, ep -> "ep:${ep.id}" }) { idx, ep ->
                    Box(
                        modifier = Modifier.padding(horizontal = Space.page, vertical = Space.xxs),
                    ) {
                        EpisodeRow(
                            ep = ep,
                            // Episode-tile click — smart routing per user
                            // spec: "if the episode was not in the end then
                            // pressing the episode acts like continue
                            // watching, but if the episode was in the end
                            // then pressing the episode restarts it."
                            //
                            // Look up THIS episode's watch_history row.
                            // - No row, or row is finished (≥95%) → restart
                            //   from 0 via onPlayFromStart.
                            // - Row mid-stream → resume from offset via
                            //   onPlayEpisode (PlaybackController.loadCurrent
                            //   reads the saved position from history).
                            //
                            // Lookup runs on IO; the click lambda just
                            // launches the coroutine. Cheap — single
                            // SELECT keyed by episode_id.
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val info =
                                        runCatching { watchHistory.episodeInfo(ep.id) }.getOrNull()
                                    val finished = info?.isFinished() == true
                                    withContext(Dispatchers.Main) {
                                        if (finished || info == null) {
                                            onPlayFromStart(rendered, ep)
                                        } else {
                                            onPlayEpisode(rendered, ep)
                                        }
                                    }
                                }
                            },
                            modifier = if (idx == 0) Modifier.placedFocus(firstEpisodeAnchor) else Modifier,
                        )
                    }
                }
            }
        }

        // Season picker — rendered as an overlay sibling of the LazyColumn
        // (NOT a LazyColumn item) so navigating it doesn't drive the page
        // scroll and focus stays trapped inside until the user picks a
        // season or presses BACK. Inline expansion turned out to leak
        // focus into the episode list and bounce the page scroll while
        // the user was still scanning seasons.
        if (seasonPickerOpen && rendered.type == ContentType.SERIES && seasons.size > 1) {
            SeasonPickerOverlay(
                seasons = seasons,
                selectedSeason = selectedSeason,
                onDismiss = { seasonPickerOpen = false },
                onSeasonSelect = { season ->
                    selectedSeason = season
                    seasonPickerOpen = false
                },
            )
        }

        if (resetDialogOpen) {
            ResetProgressDialog(
                isSeries = rendered.type == ContentType.SERIES,
                onConfirm = {
                    resetDialogOpen = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { watchHistory.removeForContent(rendered.id) }
                        }
                        // Bump the version so the LaunchedEffect re-runs
                        // and `hasHistory` / `resumeInfo` reflect the wipe.
                        historyVersion += 1
                        onResetProgress(rendered)
                    }
                },
                onDismiss = { resetDialogOpen = false },
            )
        }
    }

    // Auto-focus Play on open and re-assert it when `loaded` settles.
    // Series detail swaps the `rendered` ContentItem instance the moment
    // the provider round-trip returns (`item` → `loaded.item` with a
    // fresh reference), which re-keys the HeroBlock and can drop the
    // initial focus grant. Re-requesting on `loaded != null` — with a
    // short retry ladder for the frame gap between modifier attach and
    // the underlying FocusRequester node being ready — makes the Play
    // button reliably focused on open for both movies and series.
    LaunchedEffect(loaded != null) {
        runCatching { trapFocus.requestFocus() } // immediate trap while Play button renders
        playAnchor.awaitAndRequest() // waits for onPlaced, then fires once
        // Trap has done its job — disable so spatial UP from the action
        // row can't land on an invisible 0-dp Spacer.
        initialFocusTransferred = true
        hasInitialFocus = true
    }

    // After the user picks a different season from the dropdown, hand
    // focus to the first episode of the new season. We use a
    // LaunchedEffect (not a scope.launch in the click handler) so the
    // request runs after Compose has settled the recomposition and the
    // new first-episode node is attached. Gated on hasInitialFocus so
    // the initial composition's selectedSeason assignment doesn't steal
    // focus from the Play button.
    LaunchedEffect(selectedSeason) {
        if (hasInitialFocus) {
            firstEpisodeAnchor.awaitAndRequest()
        }
    }

    // Return-from-player focus restore. PlayerActivity yanks window focus
    // away; when it finishes and this overlay regains focus, Compose does
    // NOT automatically re-run the open-time focus ladder above because
    // `loaded` hasn't changed. Without this the user comes back to the
    // detail page with no visible selector — they have to press a d-pad
    // key to wake it up. Mirrors CoverflowSectionScreen's window-focus
    // handler.
    //
    // MB-115: replaced the previous 80/250/500ms delay-ladder with
    // [PlacedFocusAnchor.awaitAndRequest], which is the canonical primitive
    // for "request focus once layout has placed the target". On window
    // regain the Play button is already in composition with isPlaced=true,
    // so the snapshotFlow inside awaitAndRequest fires immediately; if for
    // any reason layout hadn't settled, it suspends correctly instead of
    // burning three arbitrary timeouts. The skill checklist explicitly
    // calls out delay-ladders as race-prone.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(Unit) {
        var seenUnfocused = false
        snapshotFlow { windowInfo.isWindowFocused }.collect { windowFocused ->
            if (!windowFocused) {
                seenUnfocused = true
            } else if (seenUnfocused) {
                seenUnfocused = false
                playAnchor.awaitAndRequest()
            }
        }
    }
}

@Composable
private fun HeroBlock(
    item: ContentItem,
    metadata: ContentMetadata,
    episodes: List<EpisodeInfo>,
    playChoice: NextEpisodeChoice?,
    movieResumeSeconds: Long?,
    hasHistory: Boolean,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onReset: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onBack: () -> Unit,
    playAnchor: PlacedFocusAnchor,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        BackdropHero(url = backdropUrlOf(item, metadata))
        Column(
            modifier =
            Modifier
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
                            color = LocalYancoPalette.current.Accent,
                            style = YancoType.Overline,
                        )
                    }
                    Text(
                        text = item.cleanTitle?.ifBlank { null } ?: item.title,
                        color = LocalYancoPalette.current.TextPrimary,
                        style = YancoType.DisplayCinematic,
                        maxLines = 2,
                    )
                    metadata.tagline?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = LocalYancoPalette.current.AccentGlow,
                            style = YancoType.BodyLong,
                        )
                    }
                    MetaLine(metadata, item.type, episodeCount = episodes.size)
                    metadata.plot?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = LocalYancoPalette.current.TextSecondary,
                            style = YancoType.BodyLong,
                            maxLines = 4,
                        )
                    }
                    ActionRow(
                        primaryLabel =
                        when (item.type) {
                            ContentType.SERIES -> playChoice?.let(::resumeButtonLabel) ?: "Play"
                            ContentType.MOVIE -> if (movieResumeSeconds != null) "Continue" else "Play"
                            else -> "Play"
                        },
                        // "Play from beginning" makes sense for movies and
                        // for series with at least one cached episode; series
                        // with zero episodes still need *some* affordance,
                        // so we hide the button in that case rather than
                        // wire it to a no-op.
                        showPlayFromStart = item.type != ContentType.SERIES || episodes.isNotEmpty(),
                        showReset = hasHistory,
                        isFavorite = isFavorite,
                        onPlay = onPlay,
                        onPlayFromStart = onPlayFromStart,
                        onReset = onReset,
                        onFavoriteToggle = onFavoriteToggle,
                        onBack = onBack,
                        playAnchor = playAnchor,
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

private fun backdropUrlOf(item: ContentItem, meta: ContentMetadata): String? = meta.backdropUrl?.takeIf { it.isNotBlank() }
    ?: meta.tmdbBackdropUrl?.takeIf { it.isNotBlank() }
    ?: item.logoUrl?.takeIf { it.isNotBlank() }

@Composable
private fun BackdropHero(url: String?) {
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(ShellDim.heroHeight)
            .background(LocalYancoPalette.current.BackgroundRaised),
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
            modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors =
                        listOf(
                            LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.85f),
                            LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.3f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors =
                        listOf(
                            Color.Transparent,
                            LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.6f),
                            LocalYancoPalette.current.BackgroundDeep,
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun Poster(url: String?) {
    Box(
        modifier =
        Modifier
            .width(ShellDim.detailPosterWidth)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(Radius.panel))
            .background(LocalYancoPalette.current.BackgroundRaised)
            .border(1.dp, LocalYancoPalette.current.PanelBorder, RoundedCornerShape(Radius.panel)),
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
                color = LocalYancoPalette.current.TextMuted,
                style = YancoType.Caption,
            )
        }
    }
}

@Composable
private fun MetaLine(meta: ContentMetadata, type: ContentType, episodeCount: Int) {
    val bits =
        buildList {
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
                    modifier =
                    Modifier
                        .size(3.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(LocalYancoPalette.current.TextFaint),
                )
            }
            Text(
                text = text,
                color = LocalYancoPalette.current.TextSecondary,
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
            color = LocalYancoPalette.current.TextMuted,
            style = YancoType.Overline,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            color = LocalYancoPalette.current.TextPrimary,
            style = YancoType.Body,
        )
    }
}

@Composable
private fun ActionRow(
    primaryLabel: String,
    showPlayFromStart: Boolean,
    showReset: Boolean,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onReset: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onBack: () -> Unit,
    playAnchor: PlacedFocusAnchor,
) {
    Row(
        modifier = Modifier.padding(top = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        PrimaryButton(
            label = primaryLabel,
            onClick = onPlay,
            playAnchor = playAnchor,
        )
        if (showPlayFromStart) {
            SecondaryButton(
                label = "Play from beginning",
                icon = null,
                onClick = onPlayFromStart,
                accent = false,
            )
        }
        if (showReset) {
            SecondaryButton(
                label = "Reset progress",
                icon = null,
                onClick = onReset,
                accent = false,
            )
        }
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
private fun PrimaryButton(label: String, onClick: () -> Unit, playAnchor: PlacedFocusAnchor) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) LocalYancoPalette.current.AccentGlow else LocalYancoPalette.current.Accent
    Row(
        modifier =
        Modifier
            .clip(RoundedCornerShape(Radius.control))
            .background(bg)
            .placedFocus(playAnchor)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.xxl, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(
            imageVector = YancoIcons.Play,
            contentDescription = null,
            tint = LocalYancoPalette.current.BackgroundDeep,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            color = LocalYancoPalette.current.BackgroundDeep,
            style = YancoType.LabelStrong,
        )
    }
}

@Composable
private fun SecondaryButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit, accent: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg =
        when {
            focused -> LocalYancoPalette.current.BackgroundHover
            accent -> LocalYancoPalette.current.Accent.copy(alpha = 0.18f)
            else -> Color.Transparent
        }
    val border =
        when {
            focused -> LocalYancoPalette.current.FocusRing
            accent -> LocalYancoPalette.current.Accent.copy(alpha = 0.5f)
            else -> LocalYancoPalette.current.PanelBorder
        }
    val textColor =
        when {
            accent -> LocalYancoPalette.current.Accent
            focused -> LocalYancoPalette.current.TextPrimary
            else -> LocalYancoPalette.current.TextSecondary
        }
    Row(
        modifier =
        Modifier
            .clip(RoundedCornerShape(Radius.control))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(Radius.control))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
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
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.page, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = "EPISODES",
            color = LocalYancoPalette.current.Accent,
            style = YancoType.Overline,
        )
        if (loading && episodeCount == 0) {
            CircularProgressIndicator(
                color = LocalYancoPalette.current.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Text(
                text = "$episodeCount total",
                color = LocalYancoPalette.current.TextMuted,
                style = YancoType.Caption,
            )
        }
    }
}

@Composable
private fun SeasonSelector(
    seasonCount: Int,
    selectedSeason: Int,
    episodeCount: Int,
    open: Boolean,
    onTriggerClick: () -> Unit,
) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.page, vertical = Space.xs),
    ) {
        SeasonTrigger(
            label = if (selectedSeason == 0) "Specials" else "Season $selectedSeason",
            count = episodeCount,
            open = open,
            onClick = onTriggerClick,
        )
    }
}

@Composable
private fun SeasonTrigger(label: String, count: Int, open: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) LocalYancoPalette.current.BackgroundHover else LocalYancoPalette.current.BackgroundRaised
    val border = if (focused) LocalYancoPalette.current.FocusRing else LocalYancoPalette.current.PanelBorder
    Row(
        modifier =
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, border, RoundedCornerShape(Radius.pill))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.DropdownList, onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (focused) LocalYancoPalette.current.Accent else LocalYancoPalette.current.TextPrimary,
            style = YancoType.LabelStrong,
        )
        Box(
            modifier =
            Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.5f))
                .padding(horizontal = Space.sm, vertical = 2.dp),
        ) {
            Text(
                text = "$count episodes",
                color = LocalYancoPalette.current.TextSecondary,
                style = YancoType.Caption,
            )
        }
        // Chevron — flips orientation when expanded.
        Text(
            text = if (open) "▲" else "▼",
            color = if (focused) LocalYancoPalette.current.Accent else LocalYancoPalette.current.TextSecondary,
            style = YancoType.Caption,
        )
    }
}

@Composable
private fun SeasonOption(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg =
        when {
            focused -> LocalYancoPalette.current.BackgroundHover
            selected -> LocalYancoPalette.current.Accent.copy(alpha = 0.12f)
            else -> Color.Transparent
        }
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .background(bg)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color =
            when {
                focused -> LocalYancoPalette.current.Accent
                selected -> LocalYancoPalette.current.Accent
                else -> LocalYancoPalette.current.TextPrimary
            },
            style = YancoType.Label,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count episodes",
            color = LocalYancoPalette.current.TextMuted,
            style = YancoType.Caption,
        )
        if (selected) {
            Text(
                text = "✓",
                color = LocalYancoPalette.current.Accent,
                style = YancoType.Label,
            )
        }
    }
}

@Composable
private fun EpisodeRow(ep: EpisodeInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .focusStyle(focused = focused, radius = Radius.card, liftScale = 1.015f)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        Box(
            modifier =
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(Radius.chip))
                .background(
                    if (focused) {
                        LocalYancoPalette.current.Accent.copy(alpha = 0.22f)
                    } else {
                        LocalYancoPalette.current.BackgroundElevated
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "E%02d".format(ep.episodeNumber),
                color = LocalYancoPalette.current.Accent,
                style = YancoType.LabelStrong,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            Text(
                text = ep.title.takeIf { it.isNotBlank() } ?: "Episode ${ep.episodeNumber}",
                color = LocalYancoPalette.current.TextPrimary,
                style = YancoType.TitleS,
                maxLines = 1,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Season ${ep.seasonNumber}",
                    color = LocalYancoPalette.current.TextMuted,
                    style = YancoType.Caption,
                )
                ep.duration?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "\u00b7",
                        color = LocalYancoPalette.current.TextFaint,
                        style = YancoType.Caption,
                    )
                    Text(
                        text = it,
                        color = LocalYancoPalette.current.TextMuted,
                        style = YancoType.Caption,
                    )
                }
            }
        }
        Icon(
            imageVector = YancoIcons.Play,
            contentDescription = null,
            tint = if (focused) LocalYancoPalette.current.Accent else LocalYancoPalette.current.TextFaint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ResetProgressDialog(isSeries: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalYancoPalette.current
    val body =
        if (isSeries) {
            "This clears your resume points and watched marks for every " +
                "episode in this series. You'll start fresh next time."
        } else {
            "This clears your resume point for this title. Next play will " +
                "start from the beginning."
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.BackgroundRaised,
        title = { Text("Reset progress?", color = palette.TextPrimary) },
        text = { Text(body, color = palette.TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Reset", color = palette.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = palette.TextMuted)
            }
        },
    )
}

private enum class PlayMode {
    /** Mid-episode — pick up at the stored offset. Button: "Resume SxEy". */
    RESUME,

    /** Last watched episode is finished; advance to the next in season/episode order. Button: "Play SxEy". */
    PLAY_NEXT,

    /** No watch history at all — start at S1E1 (or the first available row). Button: "Play SxEy". */
    PLAY_FIRST,

    /** Every episode finished; loop back to the first one. Button: "Watch again SxEy". */
    WATCH_AGAIN,
}

private data class NextEpisodeChoice(val episode: EpisodeInfo, val mode: PlayMode)

/**
 * Resolve which episode the Play button should target given the loaded
 * episode list and the most-recent watch-history row. Returns null only
 * for an empty episode list — the caller falls back to onPlayContent.
 *
 * Sort order is (seasonNumber, episodeNumber, id). The id tiebreak keeps
 * runs of unnumbered specials stable rather than letting them swap on
 * each recomposition.
 */
private fun computeNextEpisode(
    episodes: List<EpisodeInfo>,
    resumeInfo: EpisodeResumeInfo?,
): NextEpisodeChoice? {
    if (episodes.isEmpty()) return null
    val sorted = episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }, { it.id }))
    if (resumeInfo == null) {
        return NextEpisodeChoice(sorted.first(), PlayMode.PLAY_FIRST)
    }
    val watchedIdx = sorted.indexOfFirst { it.id == resumeInfo.episodeId }
    if (watchedIdx < 0) {
        // History row references an episode that's no longer in the list
        // (re-sync dropped it, source rotated catalogs). Fall back to first.
        return NextEpisodeChoice(sorted.first(), PlayMode.PLAY_FIRST)
    }
    if (!resumeInfo.isFinished()) {
        return NextEpisodeChoice(sorted[watchedIdx], PlayMode.RESUME)
    }
    val next = sorted.getOrNull(watchedIdx + 1)
    return if (next != null) {
        NextEpisodeChoice(next, PlayMode.PLAY_NEXT)
    } else {
        NextEpisodeChoice(sorted.first(), PlayMode.WATCH_AGAIN)
    }
}

private fun resumeButtonLabel(choice: NextEpisodeChoice): String {
    val ep = choice.episode
    val sxe = "S${ep.seasonNumber}E${ep.episodeNumber}"
    return when (choice.mode) {
        PlayMode.RESUME -> "Resume $sxe"
        PlayMode.PLAY_NEXT -> "Play $sxe"
        PlayMode.PLAY_FIRST -> "Play $sxe"
        PlayMode.WATCH_AGAIN -> "Watch again $sxe"
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeasonPickerOverlay(
    seasons: java.util.SortedMap<Int, List<EpisodeInfo>>,
    selectedSeason: Int,
    onDismiss: () -> Unit,
    onSeasonSelect: (Int) -> Unit,
) {
    BackHandler { onDismiss() }
    val selectedAnchor = rememberPlacedFocusAnchor()
    LaunchedEffect(Unit) { selectedAnchor.awaitAndRequest() }

    // Outer scrim — fills the screen, dims everything behind, eats taps
    // outside the panel for tap-to-dismiss. focusGroup() + focusProperties
    // exit=Cancel traps focus inside this overlay so D-pad can't escape
    // into the page LazyColumn underneath. focusGroup() alone only groups
    // children — it doesn't prevent focus from leaving when the user
    // presses UP at the top or DOWN at the bottom of the season list.
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
            Modifier
                .width(480.dp)
                .heightIn(max = 480.dp)
                .clip(RoundedCornerShape(Radius.panel))
                .background(LocalYancoPalette.current.BackgroundRaised)
                .border(1.dp, LocalYancoPalette.current.PanelBorder, RoundedCornerShape(Radius.panel))
                // Eat taps inside the panel so the outer scrim's
                // dismiss-on-tap doesn't fire when the user taps a
                // SeasonOption.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Text(
                text = "Select season",
                color = LocalYancoPalette.current.TextPrimary,
                style = YancoType.LabelStrong,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            )
            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LocalYancoPalette.current.PanelBorder),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(seasons.keys.toList(), key = { _, s -> "ovrly:$s" }) { idx, season ->
                    Column {
                        if (idx > 0) {
                            Box(
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(LocalYancoPalette.current.PanelBorder),
                            )
                        }
                        SeasonOption(
                            label = if (season == 0) "Specials" else "Season $season",
                            count = seasons[season]?.size ?: 0,
                            selected = season == selectedSeason,
                            onClick = { onSeasonSelect(season) },
                            modifier = if (season == selectedSeason) Modifier.placedFocus(selectedAnchor) else Modifier,
                        )
                    }
                }
            }
        }
    }
}
