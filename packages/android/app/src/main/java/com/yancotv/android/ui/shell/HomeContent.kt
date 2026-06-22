package com.yancotv.android.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.ui.components.HexSurface
import com.yancotv.android.ui.components.ProgressStripe
import com.yancotv.android.ui.components.ResumeBadge
import com.yancotv.android.ui.components.WheelRow
import com.yancotv.android.ui.components.wheelItemTransform
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.ShellDim
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.ui.theme.YancoType
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.EpgProgramme
import com.yancotv.shared.types.HistoryEntry
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Home landing dashboard. Six rails stacked on a cinematic canvas:
 *   1. Hero — single-slide feature card rotating through the top
 *      Continue Watching pick + top "On Now" favorite-channel programs.
 *   2. Continue Watching — resume points from watch_history.
 *   3. On Now — favorite live channels with their current EPG program.
 *   4. Favorites — starred non-live titles (channels live in On Now).
 *   5. Up Next Tonight — favorite channels' next program in the next 2h.
 *   6. Recently Added — newest VOD from the catalogue (createdAt DESC).
 *
 * Every surface uses [HexSurface] + [YancoShapes] cut-corner / hex
 * shapes so the dashboard reads as part of the angular shell — not a
 * generic Netflix clone.
 *
 * Empty state is a branded welcome card shown only when everything
 * (history + favorites + catalogue) is empty.
 */
@UnstableApi
@Composable
fun HomeContent(
    /**
     * Activated card. The third arg is a resume hint — non-null when the
     * tile carried a watch_history row with an episode_id (i.e. user was
     * mid-series). HomeScreen uses it to resume that exact episode at its
     * stored offset instead of opening the detail overlay. Channels and
     * movies always pass null.
     */
    onPlay: (List<ContentItem>, Int, String?) -> Unit,
    modifier: Modifier = Modifier,
    history: WatchHistoryRepository = koinInject(),
    favorites: FavoritesRepository = koinInject(),
    parental: ParentalRepository = koinInject(),
    epg: EpgRepository = koinInject(),
    content: ContentRepository = koinInject(),
) {
    val favoriteList by favorites.allFlow().collectAsState(initial = emptyList())
    val hiddenIds by parental.hiddenIds.collectAsState()
    val lockedIds by parental.lockedIds.collectAsState()

    // MK.25.B-prep — Continue Watching is reactive via the SQLDelight Flow.
    // The previous LaunchedEffect(Unit) was a one-shot read; the user's
    // resume offset persisted correctly to disk on player exit but Home
    // never saw the write because nothing re-ran the read. Now the rail
    // re-renders every time `watch_history` is upserted (i.e. on every
    // PlaybackController.persistResumePoint call), so exiting the player
    // returns the user to a Home that already reflects their new offset.
    val recentHistory by history.recentFlow(limit = 30).collectAsState(initial = emptyList())
    val resumeByContent by remember {
        derivedStateOf {
            // recentHistory is ordered DESC by watched_at — newest first.
            // Plain `associateBy` keeps the LAST entry per key (oldest in
            // this list), so when a series has multiple episodes in the
            // recent-30 the hero displayed the OLDEST one while the
            // playback path (mostRecentEpisode → DESC + first) launched
            // the NEWEST one — the user-visible "wrong episode played"
            // bug. Use distinctBy first to keep only the newest per
            // contentId, then associateBy on that.
            recentHistory.distinctBy { it.contentId }.associateBy { it.contentId }
        }
    }
    val continueWatching by remember {
        derivedStateOf {
            recentHistory
                .map { it.content }
                .filter { it.id !in hiddenIds }
                .distinctBy { it.id }
                .take(12)
        }
    }
    val onNowItems = remember { mutableStateListOf<NowPairing>() }
    val upNextItems = remember { mutableStateListOf<NowPairing>() }
    val recentlyAdded = remember { mutableStateListOf<ContentItem>() }

    // MK.22.A.5 (MB-222): one shared "now" tick. OnNowTile previously
    // captured `nowSec = remember { System.currentTimeMillis() / 1000 }`
    // per tile, which is read once at first composition and never
    // updates — programme progress bars on Home never advanced. Mirrors
    // GuideScreen.kt:330-335's pattern: single LaunchedEffect ticks every
    // 30 s; every consumer reads the same value via parameter.
    val nowSec = remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec.value = System.currentTimeMillis() / 1000
            kotlinx.coroutines.delay(30_000L)
        }
    }

    // (Continue Watching + resume map now derived from `history.recentFlow`
    // above — the previous one-shot LaunchedEffect was the bug.)

    // Favorites-derived: non-live favorites for the Favorites rail,
    // and live favorites' tvgIds for the On Now + Up Next EPG batch.
    val nonLiveFavorites =
        remember(favoriteList.size, hiddenIds) {
            favoriteList
                .map { it.content }
                .filter { it.type != ContentType.LIVE && it.id !in hiddenIds }
                .take(20)
        }
    val liveFavorites =
        remember(favoriteList.size, hiddenIds) {
            favoriteList
                .map { it.content }
                .filter { it.type == ContentType.LIVE && it.id !in hiddenIds }
        }

    // Batch EPG lookup for all favorite live channels. The map is keyed
    // by tvgId, so we filter the liveFavorites list back into the two
    // rails (on-now = programmes where start<=now<end; up-next = next
    // programme starting within 2h).
    LaunchedEffect(liveFavorites) {
        if (liveFavorites.isEmpty()) {
            Snapshot.withMutableSnapshot {
                onNowItems.clear()
                upNextItems.clear()
            }
            return@LaunchedEffect
        }
        val ids =
            liveFavorites
                .mapNotNull { it.tvgId?.takeIf { tv -> tv.isNotBlank() } }
                .distinct()
                .take(60)
        if (ids.isEmpty()) return@LaunchedEffect
        val batch =
            withContext(Dispatchers.IO) {
                runCatching { epg.getNowNextBatch(ids) }.getOrElse { emptyMap() }
            }
        val nowSec = System.currentTimeMillis() / 1000
        val upNextCutoff = nowSec + 2 * 3600 // 2 hours out
        val nowList = mutableListOf<NowPairing>()
        val upNextList = mutableListOf<NowPairing>()
        for (channel in liveFavorites) {
            val key = channel.tvgId?.takeIf { it.isNotBlank() } ?: continue
            val nn = batch[key] ?: continue
            nn.now?.let { nowList.add(NowPairing(channel, it)) }
            val next = nn.next
            if (next != null && next.startTime in (nowSec + 1)..upNextCutoff) {
                upNextList.add(NowPairing(channel, next))
            }
        }
        Snapshot.withMutableSnapshot {
            onNowItems.clear()
            onNowItems.addAll(nowList.take(12))
        }
        Snapshot.withMutableSnapshot {
            upNextItems.clear()
            upNextItems.addAll(upNextList.sortedBy { it.programme.startTime }.take(12))
        }
    }

    // Recently added = VOD rows (movies + series) ordered by created_at DESC
    // via a dedicated SQLDelight query. The older client-side merge paged 400
    // rows and re-sorted in Kotlin, which was slow on first catalog load and
    // also wrong past the 200-per-type cap.
    //
    // Language bias: this user's catalog is English + Arabic heavy, so we
    // prefer titles that match one of those scripts or whose group name
    // contains a matching language token. If the filtered result is too
    // thin (< 8), fall back to the unfiltered list so the rail never
    // collapses on a small catalog.
    LaunchedEffect(Unit) {
        val combined =
            withContext(Dispatchers.IO) {
                runCatching {
                    content.recentlyAddedVod(limit = 60)
                }.getOrElse { emptyList() }
                    .filter { it.id !in hiddenIds }
            }
        val biased = combined.filter { matchesPreferredLanguage(it) }
        val final = if (biased.size >= 8) biased.take(20) else combined.take(20)
        Snapshot.withMutableSnapshot {
            recentlyAdded.clear()
            recentlyAdded.addAll(final)
        }
    }

    // MB-74: coarse-key so buildHeroSlides only reruns when the lead CW item
    // or top-2 On Now programme titles change — not on every EPG tick.
    val heroSlidesKey by remember {
        derivedStateOf {
            Triple(
                continueWatching.firstOrNull()?.id,
                onNowItems.take(2).map { it.channel.id to it.programme.title },
                resumeByContent.size,
            )
        }
    }
    val heroSlides =
        remember(heroSlidesKey) {
            buildHeroSlides(
                continueWatching = continueWatching.toList(),
                resumeByContent = resumeByContent,
                onNow = onNowItems.toList(),
            )
        }

    val isTotallyEmpty =
        continueWatching.isEmpty() &&
            nonLiveFavorites.isEmpty() &&
            onNowItems.isEmpty() &&
            upNextItems.isEmpty() &&
            recentlyAdded.isEmpty()

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .background(LocalYancoPalette.current.BackgroundDeep)
            .verticalScroll(rememberScrollState())
            .padding(top = Space.xl, bottom = Space.section),
        verticalArrangement = Arrangement.spacedBy(Space.xxxl),
    ) {
        if (isTotallyEmpty) {
            EmptyHome(modifier = Modifier.padding(horizontal = Space.section))
            return@Column
        }

        if (heroSlides.isNotEmpty()) {
            HomeHero(
                slides = heroSlides,
                lockedIds = lockedIds,
                onPlay = { slide ->
                    onPlay(listOf(slide.item), 0, resumeByContent[slide.item.id]?.episodeId)
                },
                modifier = Modifier.padding(horizontal = Space.section),
            )
        }

        if (continueWatching.isNotEmpty()) {
            PosterRail(
                eyebrow = "FOR YOU",
                title = "Continue watching",
                caption = "Jump back where you left off",
                items = continueWatching,
                lockedIds = lockedIds,
                resumeByContent = resumeByContent,
                onPlay = { item ->
                    val snapshot = continueWatching.toList()
                    val idx = snapshot.indexOfFirst { it.id == item.id }
                    if (idx >= 0) onPlay(snapshot, idx, resumeByContent[item.id]?.episodeId)
                },
            )
        }
        if (onNowItems.isNotEmpty()) {
            OnNowRail(
                items = onNowItems,
                lockedIds = lockedIds,
                nowSec = nowSec.value,
                onPlay = { item ->
                    val snapshot = onNowItems.toList()
                    val list = snapshot.map { it.channel }
                    val idx = list.indexOfFirst { it.id == item.id }
                    if (idx >= 0) onPlay(list, idx, null)
                },
            )
        }
        if (nonLiveFavorites.isNotEmpty()) {
            PosterRail(
                eyebrow = "YOUR LIBRARY",
                title = "Favorites",
                caption = "Movies and series you starred",
                items = nonLiveFavorites,
                lockedIds = lockedIds,
                resumeByContent = resumeByContent,
                onPlay = { item ->
                    val idx = nonLiveFavorites.indexOfFirst { it.id == item.id }
                    if (idx >= 0) onPlay(nonLiveFavorites, idx, resumeByContent[item.id]?.episodeId)
                },
            )
        }
        if (upNextItems.isNotEmpty()) {
            UpNextRail(
                items = upNextItems,
                lockedIds = lockedIds,
                onPlay = { item ->
                    val snapshot = upNextItems.toList()
                    val list = snapshot.map { it.channel }
                    val idx = list.indexOfFirst { it.id == item.id }
                    if (idx >= 0) onPlay(list, idx, null)
                },
            )
        }
        if (recentlyAdded.isNotEmpty()) {
            PosterRail(
                eyebrow = "FRESH",
                title = "Recently added",
                caption = "New movies and series in your library",
                items = recentlyAdded,
                lockedIds = lockedIds,
                resumeByContent = resumeByContent,
                onPlay = { item ->
                    val snapshot = recentlyAdded.toList()
                    val idx = snapshot.indexOfFirst { it.id == item.id }
                    if (idx >= 0) onPlay(snapshot, idx, resumeByContent[item.id]?.episodeId)
                },
            )
        }
    }
}

// ---------- Hero ----------

private data class HeroSlide(val item: ContentItem, val eyebrow: String, val accentIcon: ImageVector, val headline: String, val subhead: String)

private fun buildHeroSlides(continueWatching: List<ContentItem>, resumeByContent: Map<String, HistoryEntry>, onNow: List<NowPairing>): List<HeroSlide> {
    val slides = mutableListOf<HeroSlide>()
    continueWatching.firstOrNull()?.let { item ->
        val resume = resumeByContent[item.id]
        val sub =
            resume?.let { r ->
                val dur = r.durationSeconds
                if (dur != null && dur > 0) {
                    val remainingSec = (dur - r.positionSeconds).coerceAtLeast(0.0).roundToInt()
                    val minutes = (remainingSec / 60).coerceAtLeast(1)
                    "${minutes}m left • pick up where you stopped"
                } else {
                    "Resume playback"
                }
            } ?: "Resume playback"
        slides.add(
            HeroSlide(
                item = item,
                eyebrow = "CONTINUE WATCHING",
                accentIcon = YancoIcons.Play,
                headline = item.cleanTitle?.ifBlank { null } ?: item.title,
                subhead = sub,
            ),
        )
    }
    onNow.take(2).forEach { pair ->
        slides.add(
            HeroSlide(
                item = pair.channel,
                eyebrow = "ON AIR NOW",
                accentIcon = YancoIcons.Live,
                headline = pair.programme.title,
                subhead =
                (pair.channel.cleanTitle?.ifBlank { null } ?: pair.channel.title) +
                    "  •  " + formatTimeWindow(pair.programme),
            ),
        )
    }
    return slides
}

@Composable
private fun HomeHero(slides: List<HeroSlide>, lockedIds: Set<String>, onPlay: (HeroSlide) -> Unit, modifier: Modifier = Modifier) {
    var index by remember(slides.size) { mutableStateOf(0) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // MB-64: rememberUpdatedState so the effect doesn't restart on every
    // focus change — only on slides.size. The loop checks focus each cycle.
    val focusedState = rememberUpdatedState(focused)
    LaunchedEffect(slides.size) {
        if (slides.size <= 1) return@LaunchedEffect
        while (true) {
            delay(7000L)
            if (!focusedState.value) index = (index + 1) % slides.size
        }
    }

    val safeIndex = index.coerceIn(0, slides.lastIndex)
    val slide = slides[safeIndex]
    val locked = slide.item.id in lockedIds

    HexSurface(
        shape = YancoShapes.CutCornerCard,
        focused = focused,
        bevelInset = 4.dp,
        modifier =
        modifier
            .fillMaxWidth()
            .height(320.dp)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = { onPlay(slide) }),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = slide,
                transitionSpec = {
                    // MK.22.B.4: was 420 ms fadeIn + 280 ms fadeOut. Both
                    // layers paint full-bleed AsyncImage + 3 gradients +
                    // text simultaneously during the cross-fade, which on
                    // Fire TV reads as a heavy swap. Cut to 240/200 so the
                    // hero feels snappier without losing the cross-fade
                    // character.
                    (fadeIn(tween(durationMillis = 240))) togetherWith
                        fadeOut(tween(durationMillis = 200))
                },
                label = "hero-slide",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                // MB-64: pass interaction so HeroCta reads focused locally —
                // only the CTA sub-tree recomposes on focus change, not the
                // entire HeroFrame with its gradient siblings.
                HeroFrame(
                    slide = current,
                    interaction = interaction,
                    locked = locked,
                )
            }
            // Slide-position pips — only show when there's more than one
            // slide, tucked into the top-right cut-corner.
            if (slides.size > 1) {
                Row(
                    modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Space.lg),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    slides.indices.forEach { i ->
                        Box(
                            modifier =
                            Modifier
                                .size(width = if (i == safeIndex) 18.dp else 6.dp, height = 6.dp)
                                .clip(RoundedCornerShape(Radius.pill))
                                .background(
                                    if (i == safeIndex) {
                                        LocalYancoPalette.current.Accent
                                    } else {
                                        LocalYancoPalette.current.TextFaint
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroFrame(slide: HeroSlide, interaction: MutableInteractionSource, locked: Boolean) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop — full-bleed artwork, falls back to a two-tone
        // gradient when the item has no logo so the hero still reads
        // premium on catalogues without artwork.
        if (!slide.item.logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = slide.item.logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors =
                            listOf(
                                LocalYancoPalette.current.BackgroundElevated,
                                LocalYancoPalette.current.BackgroundHover,
                            ),
                        ),
                    ),
            )
        }
        // Cinematic gradient — darken left for text legibility + fade
        // bottom so the eyebrow/title/subhead float on a soft base.
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors =
                        listOf(
                            LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.92f),
                            LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.40f),
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
                            LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.45f),
                            LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.85f),
                        ),
                    ),
                ),
        )

        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = Space.xxxl, vertical = Space.xxl),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Icon(
                    imageVector = slide.accentIcon,
                    contentDescription = null,
                    tint = LocalYancoPalette.current.Accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = slide.eyebrow,
                    color = LocalYancoPalette.current.Accent,
                    style = YancoType.Overline,
                )
            }
            Spacer(Modifier.height(Space.sm))
            Text(
                text = slide.headline,
                color = LocalYancoPalette.current.TextPrimary,
                style = YancoType.DisplayM,
                maxLines = 2,
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                text = slide.subhead,
                color = LocalYancoPalette.current.TextSecondary,
                style = YancoType.Body,
                maxLines = 1,
            )
            Spacer(Modifier.height(Space.lg))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                HeroCta(interaction = interaction, locked = locked)
                if (!slide.item.groupName.isNullOrBlank()) {
                    Text(
                        text = slide.item.groupName!!,
                        color = LocalYancoPalette.current.TextMuted,
                        style = YancoType.Caption,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCta(interaction: MutableInteractionSource, locked: Boolean) {
    val focused by interaction.collectIsFocusedAsState()
    val label = if (locked) "Enter PIN" else "Watch now"
    val icon = if (locked) YancoIcons.Lock else YancoIcons.Play
    Row(
        modifier =
        Modifier
            .clip(YancoShapes.ButtonBevel)
            .background(
                if (focused) {
                    LocalYancoPalette.current.Accent
                } else {
                    LocalYancoPalette.current.Accent.copy(alpha = 0.22f)
                },
            ).padding(horizontal = Space.lg, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (focused) LocalYancoPalette.current.BackgroundDeep else LocalYancoPalette.current.Accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = if (focused) LocalYancoPalette.current.BackgroundDeep else LocalYancoPalette.current.Accent,
            style = YancoType.LabelStrong,
        )
    }
}

// ---------- Rails (shared header + wheel row) ----------

@Composable
private fun RailHeader(eyebrow: String, title: String, caption: String) {
    Column(modifier = Modifier.padding(horizontal = Space.section)) {
        Text(
            text = eyebrow,
            color = LocalYancoPalette.current.Accent,
            style = YancoType.Overline,
        )
        Spacer(Modifier.height(Space.xxs))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = title,
                color = LocalYancoPalette.current.TextPrimary,
                style = YancoType.TitleL,
            )
            Spacer(Modifier.width(Space.md))
            Text(
                text = caption,
                color = LocalYancoPalette.current.TextMuted,
                style = YancoType.Caption,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

@Composable
private fun PosterRail(
    eyebrow: String,
    title: String,
    caption: String,
    items: List<ContentItem>,
    lockedIds: Set<String>,
    resumeByContent: Map<String, HistoryEntry>,
    onPlay: (ContentItem) -> Unit,
) {
    val listState = rememberLazyListState()
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        RailHeader(eyebrow = eyebrow, title = title, caption = caption)
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
                    // MK.22.B.6: remember(index) wrapper closed over listState
                    // (an unstable input) and added nothing — wheelItemTransform
                    // already returns a stable lambda-driven graphicsLayer.
                    modifier = Modifier.wheelItemTransform(listState = listState, index = index),
                )
            }
        }
    }
}

@Composable
private fun OnNowRail(items: List<NowPairing>, lockedIds: Set<String>, nowSec: Long, onPlay: (ContentItem) -> Unit) {
    val listState = rememberLazyListState()
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        RailHeader(
            eyebrow = "ON AIR",
            title = "On now",
            caption = "Live right this second on your favorite channels",
        )
        WheelRow(
            itemWidth = ShellDim.posterTile,
            listState = listState,
            horizontalArrangement = Arrangement.spacedBy(Space.lg),
            verticalPadding = Space.lg,
            minSidePadding = Space.section,
        ) {
            itemsIndexed(items, key = { _, it -> it.channel.id }) { index, pair ->
                OnNowTile(
                    pair = pair,
                    locked = pair.channel.id in lockedIds,
                    nowSec = nowSec,
                    onClick = { onPlay(pair.channel) },
                    // MK.22.B.6: remember(index) wrapper closed over listState
                    // (an unstable input) and added nothing — wheelItemTransform
                    // already returns a stable lambda-driven graphicsLayer.
                    modifier = Modifier.wheelItemTransform(listState = listState, index = index),
                )
            }
        }
    }
}

@Composable
private fun UpNextRail(items: List<NowPairing>, lockedIds: Set<String>, onPlay: (ContentItem) -> Unit) {
    val listState = rememberLazyListState()
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        RailHeader(
            eyebrow = "TONIGHT",
            title = "Up next",
            caption = "Starting soon on your favorite channels",
        )
        WheelRow(
            itemWidth = ShellDim.posterTile,
            listState = listState,
            horizontalArrangement = Arrangement.spacedBy(Space.lg),
            verticalPadding = Space.lg,
            minSidePadding = Space.section,
        ) {
            itemsIndexed(items, key = { _, it -> it.channel.id + ":" + it.programme.id }) { index, pair ->
                UpNextTile(
                    pair = pair,
                    locked = pair.channel.id in lockedIds,
                    onClick = { onPlay(pair.channel) },
                    // MK.22.B.6: remember(index) wrapper closed over listState
                    // (an unstable input) and added nothing — wheelItemTransform
                    // already returns a stable lambda-driven graphicsLayer.
                    modifier = Modifier.wheelItemTransform(listState = listState, index = index),
                )
            }
        }
    }
}

// ---------- Tile variants ----------

@Composable
private fun PosterTile(item: ContentItem, locked: Boolean, resume: HistoryEntry?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val progressPct =
        resume?.let { entry ->
            val dur = entry.durationSeconds ?: return@let 0f
            if (dur <= 0) 0f else (entry.positionSeconds / dur).toFloat().coerceIn(0f, 1f)
        } ?: 0f

    HexSurface(
        shape = YancoShapes.CutCornerCardSmall,
        focused = focused,
        bevelInset = 3.dp,
        modifier =
        modifier
            .width(ShellDim.posterTile)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(ShellDim.posterTileAspect),
            ) {
                TileArt(item = item, focused = focused)
                Box(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors =
                                listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.9f),
                                ),
                            ),
                        ),
                )
                if (locked) {
                    LockBadge(modifier = Modifier.align(Alignment.TopStart).padding(Space.sm))
                }
                if (resume != null) {
                    ResumeBadge(
                        label = resumeLabelFor(resume),
                        modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(Space.sm),
                    )
                }
                TypeChip(
                    item = item,
                    modifier =
                    Modifier
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
                modifier =
                Modifier
                    .fillMaxWidth()
                    .background(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.55f))
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
            ) {
                Text(
                    text = item.cleanTitle?.ifBlank { null } ?: item.title,
                    color = LocalYancoPalette.current.TextPrimary,
                    style = YancoType.TitleS,
                    maxLines = 1,
                )
                Text(
                    text = secondaryLine(item, resume),
                    color = LocalYancoPalette.current.TextMuted,
                    style = YancoType.Caption,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun OnNowTile(pair: NowPairing, locked: Boolean, nowSec: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // MK.22.A.5 (MB-222): nowSec is now passed in from a single ticking
    // LaunchedEffect at the HomeContent level. Previously this was
    // `remember { System.currentTimeMillis() / 1000 }` — captured once
    // at first composition and never refreshed, so the progress bar was
    // frozen at the moment the tile composed. Same value reaches every
    // OnNowTile so they tick in sync.
    val dur = (pair.programme.endTime - pair.programme.startTime).coerceAtLeast(1)
    val elapsed = (nowSec - pair.programme.startTime).coerceIn(0, dur)
    val progressPct = (elapsed.toFloat() / dur.toFloat()).coerceIn(0f, 1f)

    HexSurface(
        shape = YancoShapes.CutCornerCardSmall,
        focused = focused,
        bevelInset = 3.dp,
        modifier =
        modifier
            .width(ShellDim.posterTile)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(ShellDim.posterTileAspect),
            ) {
                TileArt(item = pair.channel, focused = focused)
                Box(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors =
                                listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.92f),
                                ),
                            ),
                        ),
                )
                if (locked) {
                    LockBadge(modifier = Modifier.align(Alignment.TopStart).padding(Space.sm))
                }
                LiveBadge(modifier = Modifier.align(Alignment.TopEnd).padding(Space.sm))
                TypeChip(
                    item = pair.channel,
                    modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(Space.sm),
                )
                ProgressStripe(
                    progress = progressPct,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            Column(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .background(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.55f))
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
            ) {
                Text(
                    text = pair.programme.title,
                    color = LocalYancoPalette.current.TextPrimary,
                    style = YancoType.TitleS,
                    maxLines = 1,
                )
                Text(
                    text = pair.channel.cleanTitle?.ifBlank { null } ?: pair.channel.title,
                    color = LocalYancoPalette.current.TextMuted,
                    style = YancoType.Caption,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun UpNextTile(pair: NowPairing, locked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    HexSurface(
        shape = YancoShapes.CutCornerCardSmall,
        focused = focused,
        bevelInset = 3.dp,
        modifier =
        modifier
            .width(ShellDim.posterTile)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(ShellDim.posterTileAspect),
            ) {
                TileArt(item = pair.channel, focused = focused)
                Box(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors =
                                listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.92f),
                                ),
                            ),
                        ),
                )
                if (locked) {
                    LockBadge(modifier = Modifier.align(Alignment.TopStart).padding(Space.sm))
                }
                StartTimeBadge(
                    programme = pair.programme,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Space.sm),
                )
                TypeChip(
                    item = pair.channel,
                    modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(Space.sm),
                )
            }
            Column(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .background(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.55f))
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
            ) {
                Text(
                    text = pair.programme.title,
                    color = LocalYancoPalette.current.TextPrimary,
                    style = YancoType.TitleS,
                    maxLines = 1,
                )
                Text(
                    text = pair.channel.cleanTitle?.ifBlank { null } ?: pair.channel.title,
                    color = LocalYancoPalette.current.TextMuted,
                    style = YancoType.Caption,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------- Shared tile chrome ----------

@Composable
private fun TileArt(item: ContentItem, focused: Boolean) {
    if (!item.logoUrl.isNullOrBlank()) {
        AsyncImage(
            model = item.logoUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors =
                        listOf(
                            LocalYancoPalette.current.BackgroundHover,
                            LocalYancoPalette.current.BackgroundElevated,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (item.cleanTitle?.ifBlank { null } ?: item.title).take(2).uppercase(),
                color = if (focused) LocalYancoPalette.current.Accent else LocalYancoPalette.current.TextSecondary,
                style = YancoType.DisplayS,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun LockBadge(modifier: Modifier = Modifier) {
    Box(
        modifier =
        modifier
            .size(24.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = YancoIcons.Lock,
            contentDescription = "Locked",
            tint = LocalYancoPalette.current.Live,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * MK.28.2 — Label derivation for the [ResumeBadge] on Home tiles. The
 * shared [com.yancotv.android.ui.components.ResumeBadge] primitive is
 * pre-formatted text; this helper keeps the HistoryEntry-→-string mapping
 * local to HomeContent so other surfaces (browse, favorites, search,
 * episode list) that already source [com.yancotv.shared.history.WatchProgress]
 * can use the shared [com.yancotv.android.ui.components.formatResumeLabel]
 * instead.
 */
private fun resumeLabelFor(resume: HistoryEntry): String {
    val dur = resume.durationSeconds
    return if (dur != null && dur > 0) {
        val remainingSec = (dur - resume.positionSeconds).toDouble().coerceAtLeast(0.0).roundToInt()
        val minutes = (remainingSec / 60).coerceAtLeast(1)
        "${minutes}m left"
    } else {
        "Resume"
    }
}

@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier =
        modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(LocalYancoPalette.current.Live.copy(alpha = 0.88f))
            .padding(horizontal = Space.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(
            modifier =
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(LocalYancoPalette.current.TextPrimary),
        )
        Text(
            text = "LIVE",
            color = LocalYancoPalette.current.TextPrimary,
            style = YancoType.Overline,
        )
    }
}

@Composable
private fun StartTimeBadge(programme: EpgProgramme, modifier: Modifier = Modifier) {
    Row(
        modifier =
        modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.78f))
            .padding(horizontal = Space.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            text = formatClock(programme.startTime),
            color = LocalYancoPalette.current.Accent,
            style = YancoType.CaptionStrong,
        )
    }
}

@Composable
private fun TypeChip(item: ContentItem, modifier: Modifier = Modifier) {
    val raw =
        item.groupName?.takeIf { it.isNotBlank() }
            ?: item.type.name
                .lowercase()
                .replaceFirstChar(Char::uppercase)
    val label = raw.take(28)
    Box(
        modifier =
        modifier
            .clip(YancoShapes.ChipBevel)
            .background(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.72f))
            .padding(horizontal = Space.md, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = LocalYancoPalette.current.TextSecondary,
            style = YancoType.Caption,
            maxLines = 1,
        )
    }
}

// ProgressStripe lives in com.yancotv.android.ui.components.WatchIndicator
// since MK.28.2 — same gradient bar, shared across home / browse /
// favorites / search / episode list. Imported above.

// ---------- Utilities ----------

private data class NowPairing(val channel: ContentItem, val programme: EpgProgramme)

/**
 * Preferred-language filter for the Recently Added rail. This user's catalog is
 * English + Arabic heavy; providers dump every language into one playlist, so
 * the raw "newest first" view gets flooded with TR/RU/ES rows the user won't
 * watch.
 *
 * Matching is cheap and forgiving — we accept a row if any of these hold:
 *   1. The title contains Arabic script (U+0600..U+06FF).
 *   2. The title is ASCII-dominant (≥70% printable ASCII), which catches
 *      Latin-script non-English titles too but is close enough at this scale.
 *   3. The group name contains a language token we recognise (EN/AR/UK/US/USA/
 *      ENGLISH/ARABIC). Providers frequently prefix groups like "EN | MOVIES".
 *
 * Callers fall back to the unfiltered list when this returns too few rows —
 * don't tighten it into a hard gate.
 */
private fun matchesPreferredLanguage(item: ContentItem): Boolean {
    val title = item.title
    if (title.any { it.code in 0x0600..0x06FF }) return true
    val group = item.groupName?.uppercase() ?: ""
    val tokens =
        listOf(
            "EN",
            "ENGLISH",
            "AR",
            "ARABIC",
            "UK",
            "US",
            "USA",
        )
    if (tokens.any { tok ->
            // Word-boundary-ish match so "UKR" (Ukrainian) doesn't satisfy "UK".
            val idx = group.indexOf(tok)
            idx >= 0 &&
                (idx == 0 || !group[idx - 1].isLetterOrDigit()) &&
                (idx + tok.length == group.length || !group[idx + tok.length].isLetterOrDigit())
        }
    ) {
        return true
    }
    val asciiCount = title.count { it.code in 0x20..0x7E }
    return title.isNotEmpty() && asciiCount.toFloat() / title.length >= 0.7f
}

private fun secondaryLine(item: ContentItem, resume: HistoryEntry?): String = when {
    resume != null && resume.durationSeconds != null -> {
        val watched = formatMmSs(resume.positionSeconds.roundToInt())
        val total = formatMmSs(resume.durationSeconds!!.roundToInt())
        "$watched / $total"
    }
    !item.groupName.isNullOrBlank() -> item.groupName!!
    else ->
        item.type.name
            .lowercase()
            .replaceFirstChar(Char::uppercase)
}

private fun formatMmSs(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val m = s / 60
    val r = s % 60
    return if (m >= 60) {
        val h = m / 60
        val mm = m % 60
        String.format(Locale.ROOT, "%d:%02d:%02d", h, mm, r)
    } else {
        String.format(Locale.ROOT, "%d:%02d", m, r)
    }
}

private fun formatClock(unixSeconds: Long): String {
    val millis = unixSeconds * 1000
    val cal =
        java.util.Calendar
            .getInstance()
            .apply { timeInMillis = millis }
    val hour24 = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = cal.get(java.util.Calendar.MINUTE)
    val hour12 = ((hour24 + 11) % 12) + 1
    val suffix = if (hour24 < 12) "AM" else "PM"
    return String.format(Locale.ROOT, "%d:%02d %s", hour12, minute, suffix)
}

private fun formatTimeWindow(programme: EpgProgramme): String = "${formatClock(programme.startTime)} – ${formatClock(programme.endTime)}"

@Composable
private fun EmptyHome(modifier: Modifier) {
    // Cut-corner hero-sized welcome card. Same shape family as the
    // real hero so an empty catalogue still reads as "the dashboard
    // is here, just waiting on content".
    HexSurface(
        shape = YancoShapes.CutCornerCard,
        focused = false,
        bevelInset = 4.dp,
        modifier =
        modifier
            .fillMaxWidth()
            .height(260.dp),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors =
                        listOf(
                            LocalYancoPalette.current.BackgroundRaised,
                            LocalYancoPalette.current.BackgroundElevated,
                        ),
                    ),
                ).padding(horizontal = Space.xxxl, vertical = Space.xxxl),
        ) {
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Text(
                    text = "YANCOTV+",
                    color = LocalYancoPalette.current.Accent,
                    style = YancoType.Overline,
                )
                Text(
                    text = "Your cinematic IPTV suite",
                    color = LocalYancoPalette.current.TextPrimary,
                    style = YancoType.DisplayS,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = "Add a source in Settings → Sources, star a few channels, and this dashboard lights up with what to watch right now.",
                    color = LocalYancoPalette.current.TextSecondary,
                    style = YancoType.BodyLong,
                )
            }
        }
    }
}
