package com.yancotv.android.ui.shell

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.EpgPrefs
import com.yancotv.android.recording.schedule.RecordingScheduleScheduler
import com.yancotv.android.reminders.ReminderScheduler
import com.yancotv.android.ui.components.ButtonSize
import com.yancotv.android.ui.components.YancoPrimaryButton
import com.yancotv.android.ui.parental.ChannelActionsMenu
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.catchup.CatchupService
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.recording.RecordingScheduleRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.EpgGuideChannel
import com.yancotv.shared.types.EpgGuideData
import com.yancotv.shared.types.EpgProgramme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

// MK.15.2 — timeline density is derived from the user's selected
// "timeline minutes visible" pref. Assumes ~1080 dp of horizontal space
// after the channel column on a 1080p TV; phones squeeze tighter. The
// resulting dp-per-minute is clamped so we never lose readability or
// overload Compose with absurdly wide canvases.
private val ROW_HEIGHT = 56.dp
private val HEADER_HEIGHT = 28.dp
private val CHANNEL_COL_WIDTH = 160.dp

// 2026-04-27 — distance between the channel column and the now-line
// when the timeline auto-snaps or the user taps "Now". Down from 80 dp;
// user reported the indicator sat too far right and was hard to find
// after browsing.
private val NOW_LEAD_IN = 16.dp
private val MIN_PROG_WIDTH = 48.dp
private const val ASSUMED_TIMELINE_DP = 1080

private fun pxPerMinFor(timelineMinutes: Int): Int = (ASSUMED_TIMELINE_DP / timelineMinutes.coerceAtLeast(1)).coerceIn(2, 24)

// Recompute the red "now" line every minute. Programme blocks only redraw
// when the window slides — which happens on a coarser 30-min grain.
private const val NOW_TICK_MS = 60_000L

// Paged guide load: 100 channels per page, extend when the user scrolls
// within PREFETCH_THRESHOLD rows of the end. Tuned for Fire TV: a bigger
// page buys fewer DB round-trips but each one locks main for longer when
// the result is mapped to domain objects.
private const val GUIDE_PAGE_SIZE = 100L
private const val PREFETCH_THRESHOLD = 20

// MB-286 — retention ceiling for the paged guide (contributes to MB-230).
// The scroll-pagination effect below used to append forever: on a
// 20k-live-channel provider, scrolling to the bottom pinned ~20k
// EpgGuideChannel objects — each carrying its whole `programmes` list for
// the entire user-configured window — in one Compose snapshot state var.
// Fire TV's per-process heap budget is 384 MB; that alone can consume all
// of it and tip the process into runaway GC.
//
// Two bounds, because a guide channel is NOT the same weight as a
// coverflow ContentItem:
//  - CHANNELS mirrors CoverflowSectionScreen's `items.size >= 1000` guard
//    and is the binding cap in the common case (default window is
//    daysBack=0 / daysForward=1, i.e. ~26 programmes per channel).
//  - PROGRAMMES is the safety valve for wide windows. EpgPrefs allows
//    daysBack 0-14 plus daysForward 1-14 — up to 28 days, ~670 programmes
//    per channel — where 1000 channels would retain roughly 400 MB on
//    their own. At ~400-600 bytes retained per EpgProgramme (the id /
//    title / description strings dominate), 50k programmes is ~20-30 MB,
//    which is this screen's fair share of the budget.
//
// Neither bound drops already-loaded rows — we only stop fetching more —
// so LazyColumn scroll position and D-pad focus are untouched.
private const val GUIDE_MAX_RETAINED_CHANNELS = 1000
private const val GUIDE_MAX_RETAINED_PROGRAMMES = 50_000

// MK.EPG.A — empty time slots between programmes (and the trailing span
// after the last visible programme up to `windowEnd`) used to render as
// bare `Box` filler that D-pad couldn't focus. The user saw "grey areas
// I can't reach". Each is now a focusable [NoEpgCell] (one cell per gap
// — D-pad RIGHT/LEFT hops the gap in a single press; CENTER plays the
// channel live; long-press opens the channel actions menu). The leading
// gap (before the first visible programme) intentionally stays a bare
// non-focusable Box — see the comment in `ChannelRow` for why
// (MK.EPG.A.fix1, 2026-05-05).

/** MK.14.6 — series-binding lookahead window. 7 days covers the full
 *  EPG horizon most providers populate; longer windows are wasted because
 *  EPG refresh will replace the rows before the alarm fires. */
private const val SERIES_LOOKAHEAD_MS: Long = 7L * 24L * 60L * 60_000L

/**
 * 2D EPG guide: channels on the Y axis, time on the X axis. Horizontal
 * scroll is shared across the header and every channel row so the time
 * labels stay aligned.
 *
 * Limitations for MK.7.4:
 *  - Window doesn't slide automatically as hours pass; the user reopens
 *    Guide for a fresh 6h slice. Continuous sliding lands in MK.7.5 once
 *    the reminders workflow needs the same timeline.
 *  - D-pad focus navigation between programme blocks uses Compose's
 *    default focus finder, which is "good enough" but doesn't auto-scroll
 *    the window horizontally when you focus an off-screen block. Polishing
 *    that needs bringIntoViewRequester wiring — deferred.
 */
@UnstableApi
@Composable
fun GuideScreen(
    onPlay: (EpgGuideChannel, EpgProgramme?) -> Unit,
    onPlayCatchup: (ContentItem) -> Unit,
    modifier: Modifier = Modifier,
    // MK.guide.groups — Sidebar ↔ Categories ↔ Content cascade, driven
    // from HomeScreen the same way BrowseSection (Live/Movies/Series)
    // is. RIGHT/CENTER walks forward; LEFT/BACK walks back. `panelFocus`
    // is the single source of truth; the LaunchedEffect below moves
    // focus on every transition. Defaults preserve the old standalone
    // behaviour for any future caller that doesn't host the cascade.
    panelFocus: PanelFocus = PanelFocus.Categories,
    onPanelFocusChanged: (PanelFocus) -> Unit = {},
    onExitToSidebar: () -> Unit = {},
    epg: EpgRepository = koinInject(),
    scheduler: ReminderScheduler = koinInject(),
    catchup: CatchupService = koinInject(),
    contentRepo: ContentRepository = koinInject(),
    parental: ParentalRepository = koinInject(),
    recordScheduler: RecordingScheduleScheduler = koinInject(),
    recordSchedules: RecordingScheduleRepository = koinInject(),
    appPrefs: AppPreferences = koinInject(),
) {
    // MK.15.1 — EPG window is now driven by user prefs (daysBack /
    // daysForward). Reactive: changing the slider in Settings rebuilds
    // the visible range without restart.
    val epgPrefs by appPrefs.epgFlow.collectAsState()
    // Channel list is grown via pagination — initial 100, then more as the
    // user scrolls. Holds bounded memory even for 250k-channel catalogs.
    var channels by remember { mutableStateOf<List<EpgGuideChannel>>(emptyList()) }
    var totalChannels by remember { mutableStateOf(0L) }
    // MK.guide.groups — group filter (null = All). The chip strip above
    // the grid lets the user narrow to a single live group; same data
    // model as the Live TV CategoryRail but rendered horizontally so the
    // guide keeps its full-width time grid.
    var groups by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedGroup by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var windowStartState by remember { mutableStateOf(0L) }
    var windowEndState by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var allLoaded by remember { mutableStateOf(false) }
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    var actionTarget by remember { mutableStateOf<ProgrammeAction?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    // Stage 5.6 — first-time recording disclaimer. Wraps both schedule
    // entry points (single programme + series binding) so the user
    // sees the legal acknowledgement once before their first scheduled
    // recording. Persisted via SharedPreferences in [RecordingDisclaimerPrefs];
    // future Schedule clicks pass through immediately.
    val recordingDisclaimerGate = com.yancotv.android.recording.rememberRecordingDisclaimerGate()

    // MK.guide.groups — focus anchors for the cascade (mirrors
    // BrowseSection's pattern). Pill anchor uses the placed-focus
    // primitive so requestFocus() always lands on a placed node;
    // gridFocus is a plain FocusRequester targeting the right-pane
    // wrapper.
    val pillAnchor = com.yancotv.android.ui.focus.rememberPlacedFocusAnchor()
    val gridFocus = remember { FocusRequester() }
    LaunchedEffect(panelFocus) {
        when (panelFocus) {
            PanelFocus.Categories -> pillAnchor.awaitAndRequest()
            PanelFocus.Content -> runCatching { gridFocus.requestFocus() }
            PanelFocus.Sidebar -> { /* shell sidebar owns focus */ }
        }
    }
    // MK.28.5 (MB-265) — window-regain focus restore, ported from
    // CoverflowSectionScreen. The Guide launches PlayerActivity on every
    // programme activation, but panelFocus doesn't change across the
    // player round-trip, so the effect above never re-fires and BACK from
    // fullscreen left no visible selector until a D-pad nudge (the MB-84
    // symptom class on a screen that never got the handler).
    val guideWindowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    // rememberUpdatedState: LaunchedEffect(Unit) never restarts, so a plain
    // capture would freeze panelFocus at its first-composition value.
    val currentPanelFocus by rememberUpdatedState(panelFocus)
    LaunchedEffect(Unit) {
        var seenUnfocused = false
        snapshotFlow { guideWindowInfo.isWindowFocused }.collect { windowFocused ->
            if (!windowFocused) {
                seenUnfocused = true
            } else if (seenUnfocused) {
                seenUnfocused = false
                when (currentPanelFocus) {
                    PanelFocus.Categories -> pillAnchor.awaitAndRequest()
                    PanelFocus.Content -> runCatching { gridFocus.requestFocus() }
                    PanelFocus.Sidebar -> { /* shell sidebar owns focus */ }
                }
            }
        }
    }
    // Hole-cover: RIGHT-arrow from rail moves focus into the grid via
    // Compose's natural sibling traversal (no callback fires). Without
    // syncing panelFocus → Content here, BACK from grid would call
    // onPanelFocusChanged(Categories) on a state already-equal-to-
    // Categories — LaunchedEffect wouldn't re-fire, pill wouldn't
    // refocus. Tracked separately from a Content key so we can also
    // observe when grid loses focus to either rail (BACK) or to nothing
    // (overlay opens) without churning panelFocus on every transient.

    // Reload when the user changes the EPG window prefs OR picks a
    // different group filter. We deliberately do NOT blank `channels`
    // here: blanking flips `guideEmpty` to true mid-press, which would
    // unmount the Row containing the just-pressed CategoryRail pill —
    // a focused-composable-disposed-mid-event race that crashed the
    // previous slice (2026-04-27 hands-on). Letting the old list stay
    // visible until the new load returns also reads as a snappier
    // fetch (no "Loading…" flash). LaunchedEffect(reloadTick) cancels
    // its prior coroutine when tick increments, so spam-pressing
    // Sports → News → Movies doesn't stack in-flight loads.
    LaunchedEffect(epgPrefs.daysBack, epgPrefs.daysForward, selectedGroup) {
        if (channels.isNotEmpty()) {
            allLoaded = false
            reloadTick++
        }
    }

    // Initial / forced reload — resets pagination and fetches page 0.
    LaunchedEffect(reloadTick) {
        val initial = channels.isEmpty()
        if (initial) loading = true
        val now = System.currentTimeMillis() / 1000L
        val nowAligned = now - (now % (30L * 60L))
        // 2026-04-27 — always include a 2-hour baseline of catch-up so
        // even with daysBack=0 (the new default) the user can scroll
        // back to the show that just ended. Cuts the "buried under 24
        // hours of past programmes" UX the user flagged.
        val baselineSec = EpgPrefs.CATCHUP_BASELINE_HOURS * 60L * 60L
        val windowStart = nowAligned - epgPrefs.daysBack * 24L * 60L * 60L - baselineSec
        val windowEnd = nowAligned + epgPrefs.daysForward * 24L * 60L * 60L
        windowStartState = windowStart
        windowEndState = windowEnd

        val loaded =
            withContext(Dispatchers.IO) {
                val total =
                    runCatching {
                        epg.countGuideChannels(
                            startTime = windowStart,
                            endTime = windowEnd,
                            groupName = selectedGroup,
                        )
                    }.onFailure { Log.w("Yanco", "GuideScreen.countGuideChannels failed: ${it.message}", it) }
                        .getOrElse { 0L }
                val page =
                    runCatching {
                        epg.getGuideData(
                            startTime = windowStart,
                            endTime = windowEnd,
                            sourceId = null,
                            groupName = selectedGroup,
                            limit = GUIDE_PAGE_SIZE,
                            offset = 0L,
                        )
                    }.onFailure { Log.w("Yanco", "GuideScreen.getGuideData(initial) failed: ${it.message}", it) }
                        .getOrElse { EpgGuideData(channels = emptyList(), startTime = windowStart, endTime = windowEnd) }
                // Refresh the group list off the same window — keeps the
                // chip strip in sync with what's filterable. Cheap; runs
                // once per reloadTick.
                val grps =
                    runCatching { epg.getGuideGroups(windowStart, windowEnd) }
                        .onFailure { Log.w("Yanco", "GuideScreen.getGuideGroups failed: ${it.message}", it) }
                        .getOrElse { emptyList() }
                Triple(total, page, grps)
            }
        totalChannels = loaded.first
        groups = loaded.third
        // Defensive dedup: LazyColumn crashes hard on duplicate keys, and the
        // SQL is supposed to return one row per tvg_id — but this belt-and-
        // suspenders keeps the UI alive if the query ever regresses.
        channels = loaded.second.channels.distinctBy { it.tvgId }
        allLoaded = channels.size >= totalChannels.toInt() || loaded.second.channels.isEmpty()
        // If the selected group disappeared from the catalog (provider
        // refreshed and dropped it), fall back to All so the user isn't
        // stranded on a chip with no chips selected.
        if (selectedGroup != null && groups.none { it == selectedGroup }) {
            selectedGroup = null
        }
        if (initial) loading = false
    }

    // Scroll-triggered pagination. Fires when the user's close to the end of
    // the currently-loaded window; loads the next page in the background.
    val lastVisibleIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo
            if (info.isEmpty()) -1 else info.last().index
        }
    }
    LaunchedEffect(lastVisibleIndex, allLoaded) {
        if (allLoaded || loadingMore || channels.isEmpty()) return@LaunchedEffect
        if (lastVisibleIndex < channels.size - PREFETCH_THRESHOLD) return@LaunchedEffect
        // MB-286 — stop paginating once the retained set hits the heap
        // ceiling. Flipping `allLoaded` instead of a bare `return` parks
        // the effect (its own key changes, so it re-runs once and exits on
        // the guard above) and keeps this log line from repeating on every
        // scroll tick. Nothing already rendered is discarded. The
        // "Showing N of T channels" header stays truthful, and a group or
        // EPG-window change resets `allLoaded` through `reloadTick`, so the
        // rest of the catalog is still reachable via the category rail.
        val retainedProgrammes = channels.sumOf { it.programmes.size }
        if (channels.size >= GUIDE_MAX_RETAINED_CHANNELS || retainedProgrammes >= GUIDE_MAX_RETAINED_PROGRAMMES) {
            Log.i(
                "Yanco",
                "GuideScreen: pagination capped at ${channels.size}/$GUIDE_MAX_RETAINED_CHANNELS channels, " +
                    "$retainedProgrammes/$GUIDE_MAX_RETAINED_PROGRAMMES programmes (of $totalChannels total channels); " +
                    "narrow with a category filter to reach the rest",
            )
            allLoaded = true
            return@LaunchedEffect
        }
        loadingMore = true
        // MB-288 — `loadingMore` MUST be cleared on cancellation, not just on
        // the happy path. `lastVisibleIndex` is a key of this effect and
        // changes on every scrolled row, so scrolling while a page is in
        // flight cancels this coroutine at the withContext below. Without the
        // finally, the trailing `loadingMore = false` never ran, the first
        // guard above then rejected every subsequent attempt, and guide
        // pagination was dead for the rest of the screen's life — while the
        // header sat on "loading more..." forever. Nothing else resets it;
        // the reloadTick path doesn't touch it. Pagination fires precisely
        // WHILE the user scrolls, so this was easy to hit.
        try {
            val nextPage =
                withContext(Dispatchers.IO) {
                    runCatching {
                        epg.getGuideData(
                            startTime = windowStartState,
                            endTime = windowEndState,
                            sourceId = null,
                            groupName = selectedGroup,
                            limit = GUIDE_PAGE_SIZE,
                            offset = channels.size.toLong(),
                        )
                    }.onFailure { Log.w("Yanco", "GuideScreen.getGuideData(more) failed: ${it.message}", it) }
                        .getOrElse { EpgGuideData(channels = emptyList(), startTime = windowStartState, endTime = windowEndState) }
                }
            // Same defensive dedup: if a page overlaps with what's already loaded
            // (e.g. two calls racing, or a tvg_id appearing on a page boundary)
            // we drop the duplicates so LazyColumn keys stay unique.
            val existing = channels.mapTo(HashSet(channels.size)) { it.tvgId }
            val newOnly = nextPage.channels.distinctBy { it.tvgId }.filter { it.tvgId !in existing }
            val appended = channels + newOnly
            channels = appended
            allLoaded = nextPage.channels.isEmpty() || appended.size >= totalChannels.toInt()
        } finally {
            loadingMore = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(NOW_TICK_MS)
            nowSeconds = System.currentTimeMillis() / 1000L
        }
    }

    val guide =
        if (channels.isEmpty()) {
            null
        } else {
            EpgGuideData(
                channels = channels,
                startTime = windowStartState,
                endTime = windowEndState,
            )
        }
    val guideEmpty = guide == null

    // MK.8.7.c — channel long-press action menu. Resolve the Guide's
    // EpgGuideChannel (which only carries tvg_id) into a real ContentItem
    // via ContentRepository.findLiveByTvgId so the menu can key its
    // lock/hide actions off the content row's id. Declared here (before
    // the Column so the GuideGrid callsite can reference it) instead of
    // after — Kotlin's lexical scoping requires the lambda to be in
    // scope when passed down.
    var actionsFor by remember { mutableStateOf<ContentItem?>(null) }
    val onChannelLongPress: (EpgGuideChannel) -> Unit = { channel ->
        val tvg = channel.tvgId
        if (tvg.isNotBlank()) {
            // Set a placeholder immediately; the LaunchedEffect below
            // upgrades it to the real DB-resolved ContentItem.
            actionsFor =
                ContentItem(
                    id = "guide:$tvg",
                    sourceId = "",
                    type = com.yancotv.shared.types.ContentType.LIVE,
                    title = channel.name,
                    cleanTitle = channel.name,
                    groupName = null,
                    streamUrl = channel.streamUrl ?: "",
                    logoUrl = channel.logoUrl,
                    tvgId = tvg,
                    metadataJson = null,
                    sortOrder = 0,
                    createdAt = 0L,
                )
        }
    }

    // Upgrade the placeholder to the real ContentItem (with a valid db id)
    // so lock/hide writes reach the right row.
    LaunchedEffect(actionsFor?.tvgId) {
        val snapshot = actionsFor ?: return@LaunchedEffect
        if (snapshot.id.startsWith("guide:")) {
            val real =
                withContext(Dispatchers.IO) {
                    runCatching { snapshot.tvgId?.let { contentRepo.findLiveByTvgId(it) } }
                        .onFailure { Log.w("Yanco", "GuideScreen.findLiveByTvgId(${snapshot.tvgId}) failed: ${it.message}", it) }
                        .getOrNull()
                }
            if (real != null) actionsFor = real
        }
    }

    actionsFor?.let { item ->
        if (!item.id.startsWith("guide:")) {
            ChannelActionsMenu(
                item = item,
                repo = parental,
                onDismiss = { actionsFor = null },
            )
        }
    }

    // MK.guide.groups — A+B fix (2026-04-27). Rail is hoisted ABOVE the
    // guideEmpty branch so it never unmounts during a category swap;
    // only the right pane swaps between Loading / SyncPanel / Grid.
    // Combined with B (channels not blanked on switch), the focused
    // pill survives the press → no unmount-while-focused crash.
    val railSelected = selectedGroup ?: ALL_GROUPS
    // BackHandler is gated on grid focus so BACK from a programme cell
    // returns to the rail rather than exiting the screen. Hardware BACK
    // when the rail itself is focused bubbles up to the shell.
    var gridHasFocus by remember { mutableStateOf(false) }
    BackHandler(enabled = gridHasFocus) {
        onPanelFocusChanged(PanelFocus.Categories)
    }
    // MK.20.3 — guide rail honours the same smart-grouping toggle as the
    // Live/Movies/Series rails. `groups` here is already provider-ordered
    // (MK.20.1 distinctGuideGroups). When the toggle is on, hidden filter
    // first, then bucket via CategoryTreeBuilder, then flatten per the
    // expand state.
    val guideHiddenGroups by appPrefs.hiddenGroupsFlow.collectAsState()
    val guideGeneral by appPrefs.generalFlow.collectAsState()
    val guideSmartEnabled = guideGeneral.smartGrouping
    val guidePinnedParentsByType by appPrefs.pinnedParentsFlow.collectAsState()
    // Guide is a live-only surface — pin list is keyed off ContentType.LIVE.
    val guidePinnedParents = guidePinnedParentsByType[com.yancotv.shared.types.ContentType.LIVE] ?: emptyList()
    // MK.28.8 (MB-284) — saveable like BrowseSection's expand state.
    var guideExpandedParents by androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver(
            save = { state -> state.value.toList() },
            restore = { saved -> mutableStateOf(saved.toSet()) },
        ),
    ) { mutableStateOf(emptySet<String>()) }
    val guideRailRows =
        remember(groups, guideHiddenGroups, guideSmartEnabled, guideExpandedParents, guidePinnedParents) {
            if (!guideSmartEnabled) {
                null
            } else {
                val filtered = applySmartGroupingHidden(groups, guideHiddenGroups)
                val tree = com.yancotv.shared.content.CategoryTreeBuilder.build(filtered, guidePinnedParents)
                flattenCategoryTree(tree, guideExpandedParents)
            }
        }
    // **MK.EPG.A.fix3 (2026-05-05) — match BrowseSection's mount gate.**
    //
    // Pre-fix the rail stayed mounted whenever `groups.isNotEmpty()`,
    // independent of `panelFocus`. After the user entered the timeline
    // (`panelFocus == Content`) the rail's pills were still in the
    // focus tree. RIGHT-from-ChannelCell triggered a focus search that
    // — if it couldn't find a clean target inside the grid focusGroup
    // (e.g. the timeline cells were partially off-screen and Compose
    // bailed mid-bringIntoView) — expanded UP to the parent focusGroup,
    // found a pill, focused it. CategoryRail's `onFocusChanged` then
    // fired `onPanelFocusChanged(Categories)`, and the user reported
    // "RIGHT from channels jumps back to categories".
    //
    // BrowseSection (Live TV / Movies / Series) avoids this by gating
    // the rail mount on `panelFocus != Content`. Mirroring that here
    // removes the pills from the focus tree entirely whenever the user
    // is browsing the timeline. They re-mount on LEFT/BACK from the
    // grid, which already flips `panelFocus` back to Categories.
    val categoriesVisible = panelFocus != PanelFocus.Content
    Row(modifier = modifier.fillMaxSize()) {
        if (categoriesVisible && groups.isNotEmpty()) {
            CategoryRail(
                groups = if (guideSmartEnabled) emptyList() else groups,
                selected = railSelected,
                onSelect = { picked ->
                    selectedGroup = if (picked == ALL_GROUPS) null else picked
                },
                onEnterContent = {
                    // CENTER on a pill commits selection AND walks
                    // forward into the grid. RIGHT-arrow does the same
                    // via Compose's natural focus traversal between
                    // sibling Row children.
                    onPanelFocusChanged(PanelFocus.Content)
                },
                onExitToSidebar = {
                    onPanelFocusChanged(PanelFocus.Sidebar)
                    onExitToSidebar()
                },
                onPanelFocusChanged = { hasFocus ->
                    if (hasFocus) onPanelFocusChanged(PanelFocus.Categories)
                },
                selectedAnchor = pillAnchor,
                showFavorites = false,
                rows = guideRailRows,
                onToggleExpand = { label ->
                    guideExpandedParents =
                        if (label in guideExpandedParents) guideExpandedParents - label else guideExpandedParents + label
                },
            )
        }
        Column(
            modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(gridFocus)
                .focusGroup()
                .onFocusChanged { state ->
                    gridHasFocus = state.hasFocus
                    // Hole-cover: RIGHT-arrow from rail uses Compose
                    // natural traversal, no callback fires.
                    // Syncing panelFocus → Content here keeps the
                    // state machine in step so BACK from grid
                    // produces a Content → Categories transition
                    // (LaunchedEffect re-fires, pill refocuses).
                    if (state.hasFocus && panelFocus != PanelFocus.Content) {
                        onPanelFocusChanged(PanelFocus.Content)
                    }
                },
        ) {
            if (guideEmpty) {
                if (loading) {
                    GuideEmptyState(text = "Loading guide…", modifier = Modifier.fillMaxSize())
                } else {
                    // Diagnostics panel for stuck/empty guides. Carries the
                    // refresh + re-sync actions so users don't have to dig
                    // through Settings.
                    GuideSyncPanel(
                        compact = false,
                        onRefreshed = { reloadTick++ },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                GuideSyncPanel(
                    compact = true,
                    onRefreshed = { reloadTick++ },
                )
                GuideGrid(
                    guide = guide!!,
                    nowSeconds = nowSeconds,
                    listState = listState,
                    totalCount = totalChannels,
                    loadingMore = loadingMore,
                    onPlay = onPlay,
                    onProgrammeAction = { channel, programme ->
                        actionTarget = ProgrammeAction(channel, programme)
                    },
                    onChannelLongPress = onChannelLongPress,
                    onExitLeftFromChannel = { onPanelFocusChanged(PanelFocus.Categories) },
                    pxPerMin = pxPerMinFor(epgPrefs.timelineMinutes),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    val target = actionTarget
    if (target != null) {
        // Cheap availability probe for past programmes: DB-only, no network.
        // Running it in a LaunchedEffect keyed on the target keeps the probe
        // off the main thread while the dialog renders immediately.
        var catchupItem by remember(target.programme.id) { mutableStateOf<ContentItem?>(null) }
        LaunchedEffect(target.programme.id) {
            val programme = target.programme
            if (programme.endTime > nowSeconds) {
                catchupItem = null
                return@LaunchedEffect
            }
            val resolved =
                withContext(Dispatchers.IO) {
                    runCatching { catchup.resolve(programme) }
                        .onFailure { Log.w("Yanco", "GuideScreen.catchup.resolve(${programme.id}) failed: ${it.message}", it) }
                        .getOrNull()
                }
            catchupItem = (resolved as? CatchupService.Resolution.Playable)?.item
        }

        // MK.14.4 — surface "Record this programme" / "Cancel scheduled
        // recording" alongside the reminder action when the programme is
        // in the future. Currently-airing programmes use the player's
        // MENU → Record path (MK.14.2); past programmes use catch-up.
        // Keyed on programme id to refresh when the user re-opens the
        // dialog after creating/cancelling a schedule.
        var existingScheduleId by remember(target.programme.id) {
            mutableStateOf<String?>(null)
        }
        // MK.14.6 — series-binding state for the long-pressed programme.
        // Active iff at least one non-terminal schedule is tagged with the
        // (channel, title) series_key. Used to swap "Record series" for
        // "Cancel series" in the dialog.
        var isSeriesBound by remember(target.programme.id) { mutableStateOf(false) }
        // MK.28.3 (MB-250) — reminder state loads with the schedule lookup
        // below, NOT via a blocking scheduler.isSet() call in the composable
        // body. That call re-ran on every recomposition (the 60s nowSeconds
        // ticker) and, during an EPG import — whose bulk writer holds one
        // transaction across the whole parse — queued on the writer
        // connection from the main thread: a guaranteed multi-second freeze
        // from the very screen hosting the REFRESH EPG button.
        var reminderSet by remember(target.programme.id) { mutableStateOf(false) }
        LaunchedEffect(target.programme.id) {
            withContext(Dispatchers.IO) {
                runCatching {
                    reminderSet = scheduler.isSet(target.programme.id)
                    val all = recordSchedules.getAll()
                    existingScheduleId =
                        all.firstOrNull { entry ->
                            entry.programmeId == target.programme.id &&
                                !entry.state.isTerminal()
                        }?.id
                    val key =
                        RecordingScheduleScheduler.seriesKeyFor(
                            target.channel.tvgId,
                            target.programme.title,
                        )
                    isSeriesBound =
                        all.any { entry ->
                            entry.seriesKey == key && !entry.state.isTerminal()
                        }
                }
            }
        }

        ProgrammeActionDialog(
            channel = target.channel,
            programme = target.programme,
            nowSeconds = nowSeconds,
            isReminderSet = reminderSet,
            isRecordScheduled = existingScheduleId != null,
            isSeriesBound = isSeriesBound,
            catchupItem = catchupItem,
            onWatch = {
                actionTarget = null
                onPlay(target.channel, target.programme)
            },
            onPlayCatchup = { item ->
                actionTarget = null
                onPlayCatchup(item)
            },
            onSetReminder = {
                // MB-250 — upsert + AlarmManager.setExact off-main; can block
                // behind the whole-import EPG transaction (freeze → ANR).
                coroutineScope.launch(Dispatchers.IO) {
                    runCatching { scheduler.set(target.channel.tvgId, target.programme) }
                }
                reminderSet = true
                actionTarget = null
            },
            onCancelReminder = {
                coroutineScope.launch(Dispatchers.IO) {
                    runCatching { scheduler.cancel(target.programme.id) }
                }
                reminderSet = false
                actionTarget = null
            },
            onScheduleRecord = {
                val channel = target.channel
                val programme = target.programme
                val streamUrl = channel.streamUrl
                if (streamUrl.isNullOrBlank()) {
                    Log.w(
                        "Yanco",
                        "GuideScreen.onScheduleRecord: no streamUrl for tvgId=${channel.tvgId}; skipping",
                    )
                } else {
                    // **MK.REC.RESILIENCE 2026-05-15.** Both the contentRepo
                    // lookup and recordScheduler.schedule (which insert +
                    // arm alarms + transitionTo, all blocking SQLDelight
                    // and AlarmManager writes) MUST run off the main
                    // thread. On a cold DB the chain easily exceeds the
                    // 5s ANR budget. The series-binding path below
                    // already does this; the single-record path was
                    // missed. Native-android-mk skill explicitly bans
                    // calling shared/ repos from a Compose lambda.
                    //
                    // recordingDisclaimerGate stays on Main — it's a
                    // composable-side state setter that opens the disclaimer
                    // dialog if the user hasn't accepted yet. Only the
                    // post-gate work moves to IO.
                    recordingDisclaimerGate {
                        coroutineScope.launch(Dispatchers.IO) {
                            runCatching {
                                val contentItem =
                                    contentRepo.findLiveByTvgId(channel.tvgId)
                                recordScheduler.schedule(
                                    contentId = contentItem?.id,
                                    programmeId = programme.id,
                                    title = programme.title,
                                    streamUrl = streamUrl,
                                    scheduledStart = programme.startTime * 1000L,
                                    scheduledEnd = programme.endTime * 1000L,
                                )
                            }.onFailure {
                                Log.e("Yanco", "schedule failed for ${programme.id}", it)
                            }
                        }
                    }
                }
                actionTarget = null
            },
            onCancelRecord = {
                // Cancel also blocks on AlarmManager + SQLDelight; move
                // to IO for the same ANR reason as onScheduleRecord above.
                existingScheduleId?.let { id ->
                    coroutineScope.launch(Dispatchers.IO) {
                        runCatching { recordScheduler.cancel(id) }
                            .onFailure { Log.w("Yanco", "cancel-record failed for $id", it) }
                    }
                }
                actionTarget = null
            },
            onScheduleSeries = {
                // MK.14.6 — bind every future programme on this channel
                // matching the long-pressed title within the 7-day EPG
                // lookahead. Runs on IO; dialog dismisses immediately so
                // the user isn't blocked by the EPG query + N inserts.
                val channel = target.channel
                val programme = target.programme
                val streamUrl = channel.streamUrl
                if (!streamUrl.isNullOrBlank()) {
                    recordingDisclaimerGate {
                        coroutineScope.launch(Dispatchers.IO) {
                            runCatching {
                                val contentItem =
                                    contentRepo.findLiveByTvgId(channel.tvgId)
                                // epg_programmes.start_time/end_time are
                                // XMLTV epoch SECONDS (parser at
                                // XmltvParser.kt:186 returns epochSeconds).
                                // The query lower bound is `start_time >= ?`
                                // which compared a ms `now` (~1.78e12)
                                // against a seconds column (~1.78e9), so
                                // every row appeared "in the past" and the
                                // list was always empty — the user tapping
                                // Schedule-series silently bound 0
                                // programmes. Convert to seconds at the
                                // call site to match column units.
                                val nowSec = System.currentTimeMillis() / 1000L
                                val matches =
                                    epg.findFutureByChannelAndTitle(
                                        tvgId = channel.tvgId,
                                        title = programme.title,
                                        nowSec = nowSec,
                                        windowSec = SERIES_LOOKAHEAD_MS / 1000L,
                                    )
                                recordScheduler.scheduleSeries(
                                    contentId = contentItem?.id,
                                    channelTvgId = channel.tvgId,
                                    title = programme.title,
                                    streamUrl = streamUrl,
                                    programmes =
                                    matches.map { p ->
                                        Triple(p.id, p.startTime * 1000L, p.endTime * 1000L)
                                    },
                                )
                            }.onFailure {
                                Log.e("Yanco", "scheduleSeries failed for ${programme.title}", it)
                            }
                        }
                    }
                }
                actionTarget = null
            },
            onCancelSeries = {
                val channel = target.channel
                val programme = target.programme
                val key =
                    RecordingScheduleScheduler.seriesKeyFor(channel.tvgId, programme.title)
                coroutineScope.launch(Dispatchers.IO) {
                    runCatching { recordScheduler.cancelSeries(key) }
                        .onFailure { Log.w("Yanco", "cancelSeries failed for $key", it) }
                }
                actionTarget = null
            },
            onDismiss = { actionTarget = null },
        )
    }
}

private data class ProgrammeAction(val channel: EpgGuideChannel, val programme: EpgProgramme)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideGrid(
    guide: EpgGuideData,
    nowSeconds: Long,
    listState: LazyListState,
    totalCount: Long,
    loadingMore: Boolean,
    onPlay: (EpgGuideChannel, EpgProgramme?) -> Unit,
    onProgrammeAction: (EpgGuideChannel, EpgProgramme) -> Unit,
    onChannelLongPress: (EpgGuideChannel) -> Unit,
    onExitLeftFromChannel: () -> Unit,
    pxPerMin: Int,
    modifier: Modifier,
) {
    val hScroll = rememberScrollState()
    val totalMinutes = ((guide.endTime - guide.startTime) / 60L).toInt()
    val timelineWidth = (totalMinutes * pxPerMin).dp
    val gridScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // MK.15.4 — auto-snap to "now" on first paint so the grid opens with
    // the current programme visible even when the window has hours of
    // catch-up to its left. Re-runs only when the window's start changes
    // (window refresh / pref bump).
    LaunchedEffect(guide.startTime, pxPerMin) {
        val nowOffsetMin = ((nowSeconds - guide.startTime) / 60L).toInt().coerceAtLeast(0)
        val targetPx = with(density) { (nowOffsetMin * pxPerMin).dp.toPx() }.toInt()
        // 2026-04-27 — land "now" 16 dp from the left edge of the
        // timeline (down from 80 dp). User feedback: the indicator
        // sat too far right; reducing the lead-in puts the now-line
        // closer to the channel column so the eye doesn't have to
        // scan as far to find the live edge.
        val padPx = with(density) { NOW_LEAD_IN.toPx() }.toInt()
        hScroll.scrollTo((targetPx - padPx).coerceAtLeast(0))
    }

    // "Jump to now" snap helper. Hoisted to a closure so the button in
    // the time header uses the same target.
    val nowOffsetMinTop = ((nowSeconds - guide.startTime) / 60L).toInt().coerceAtLeast(0)
    val jumpTarget =
        with(density) {
            (nowOffsetMinTop * pxPerMin).dp.toPx().toInt() - NOW_LEAD_IN.toPx().toInt()
        }.coerceAtLeast(0)
    val onJumpToNow: () -> Unit = {
        gridScope.launch { hScroll.animateScrollTo(jumpTarget) }
    }

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .background(LocalYancoPalette.current.BackgroundDeep),
    ) {
        TimeHeader(
            startTime = guide.startTime,
            totalMinutes = totalMinutes,
            timelineWidth = timelineWidth,
            pxPerMin = pxPerMin,
            hScroll = hScroll,
            onJumpToNow = onJumpToNow,
        )

        if (totalCount > guide.channels.size) {
            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .background(LocalYancoPalette.current.BackgroundRaised)
                    .padding(horizontal = 24.dp, vertical = 4.dp),
            ) {
                androidx.compose.material3.Text(
                    text =
                    if (loadingMore) {
                        "Showing ${guide.channels.size} of $totalCount channels · loading more…"
                    } else {
                        "Showing ${guide.channels.size} of $totalCount channels"
                    },
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // Stage 5.4 perf slice 2 — `BoxWithConstraints` reads the
            // panel's max width once so each `ChannelRow` knows the
            // pixel viewport for its `hScroll` and can virtualise the
            // programme list to the visible window. Without it every
            // row composed all 24 h × 50–150 programmes per channel —
            // ~750–2250 `ProgrammeBlock` instances always in the
            // layout tree, walked on every scroll.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val viewportWidthPx =
                    with(density) { (maxWidth - CHANNEL_COL_WIDTH).toPx() }
                        .toInt()
                        .coerceAtLeast(0)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(guide.channels, key = { it.tvgId }) { channel ->
                        ChannelRow(
                            channel = channel,
                            windowStart = guide.startTime,
                            windowEnd = guide.endTime,
                            timelineWidth = timelineWidth,
                            pxPerMin = pxPerMin,
                            viewportWidthPx = viewportWidthPx,
                            hScroll = hScroll,
                            onPlayChannel = { onPlay(channel, null) },
                            onLongPressChannel = { onChannelLongPress(channel) },
                            onProgrammeAction = { prog -> onProgrammeAction(channel, prog) },
                            onExitLeftFromChannel = onExitLeftFromChannel,
                        )
                    }
                }
            }

            // Vertical "now" indicator line. Rendered outside the lazy rows
            // so it sits above the programme blocks, offset in sync with
            // the shared horizontal scroll state.
            //
            // **Stage 5.4 fix (2026-04-28).** The `leftPx` read of
            // `hScroll.value` was computed OUTSIDE the `Modifier.offset { }`
            // lambda — i.e. at composition time. Every horizontal scroll
            // tick mutates `hScroll.value` (snapshot state); reading it in
            // composition makes the enclosing `GuideGrid` re-compose every
            // frame, which then re-evaluates `TimeHeader` + the LazyColumn's
            // visible `ChannelRow`s + every `ProgrammeBlock` inside them.
            // Pre-fix EPG horizontal scroll dropped to ~3 fps with 97-98 %
            // janky frames on Fire TV (release build). The earlier comment
            // claimed the offset-lambda made recomposition cheap, but the
            // read sat outside the lambda — comment didn't match the code.
            //
            // Move the computation INSIDE the offset lambda so the
            // `hScroll.value` read happens at LAYOUT time. Layout reruns
            // are cheap (just the Box's position changes); recomposition
            // of the grid no longer triggers on every scroll frame.
            val nowOffsetMin = ((nowSeconds - guide.startTime) / 60L).toInt()
            if (nowOffsetMin in 0..totalMinutes) {
                Box(
                    modifier =
                    Modifier
                        .offset {
                            val leftPx =
                                CHANNEL_COL_WIDTH.toPx() +
                                    (nowOffsetMin * pxPerMin).dp.toPx() -
                                    hScroll.value
                            IntOffset(leftPx.toInt(), 0)
                        }
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color(0xFFE25555)),
                )
            }

            // 2026-04-27 — the floating BottomEnd "Jump to now" button
            // was removed. The same action is now in the time header's
            // channel-column slot (TimeHeader → JumpToNowButton),
            // always visible and D-pad-reachable via UP from any
            // channel row.
        }
    }
}

@Composable
private fun TimeHeader(
    startTime: Long,
    totalMinutes: Int,
    timelineWidth: androidx.compose.ui.unit.Dp,
    pxPerMin: Int,
    hScroll: androidx.compose.foundation.ScrollState,
    onJumpToNow: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .background(LocalYancoPalette.current.BackgroundRaised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 2026-04-27 — "Now" jump button lives in the channel-column
        // slot so it's always reachable: D-pad UP from any channel row
        // lands on it via natural focus traversal. Sticky regardless
        // of timeline scroll. Tapping snaps the timeline so the now-
        // line sits ~80 dp from the lane edge.
        Box(
            modifier = Modifier.width(CHANNEL_COL_WIDTH).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            YancoPrimaryButton(
                onClick = onJumpToNow,
                size = ButtonSize.Compact,
                translucent = true,
            ) {
                Text(text = "Now")
            }
        }
        Row(
            modifier =
            Modifier
                .horizontalScroll(hScroll)
                .width(timelineWidth)
                .fillMaxHeight(),
        ) {
            // Ticks every 30 min. Each tick owns its 30-min slice width so
            // the label stays left-aligned with the tick line.
            var minute = 0
            while (minute < totalMinutes) {
                val slice = minOf(30, totalMinutes - minute)
                val label = formatHourMinute(startTime + minute * 60L)
                Box(
                    modifier =
                    Modifier
                        .width((slice * pxPerMin).dp)
                        .fillMaxHeight()
                        .border(0.5.dp, LocalYancoPalette.current.BorderSubtle),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = label,
                        color = LocalYancoPalette.current.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                minute += slice
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: EpgGuideChannel,
    windowStart: Long,
    windowEnd: Long,
    timelineWidth: androidx.compose.ui.unit.Dp,
    pxPerMin: Int,
    viewportWidthPx: Int,
    hScroll: androidx.compose.foundation.ScrollState,
    onPlayChannel: () -> Unit,
    onLongPressChannel: () -> Unit,
    onProgrammeAction: (EpgProgramme) -> Unit,
    onExitLeftFromChannel: () -> Unit,
) {
    // Stage 5.4 perf slice 2 — virtualise the programme list.
    //
    // The pre-MK.EPG.A architecture: emit only programmes whose
    // `[startTime, endTime]` intersects the viewport ± 60-min buffer.
    // Each programme renders as a [ProgrammeBlock] inside a flow Row,
    // separated by bare `Box` gaps. Compose's sibling focus search
    // walks D-pad RIGHT/LEFT through them naturally.
    //
    // MK.EPG.A.fix2 (2026-05-05) reverts the cmd-list pipeline that
    // was added on 2026-05-04 — it was per-frame allocating a fresh
    // `List<TimelineCmd>` per visible channel on every scroll tick,
    // which on the user's 4022-channel install pushed `~10 MB/s` GC
    // churn and a `FLUIDITY_ALERT: 50/199 frames dropped` per minute
    // from logcat. The user reported this as "can't navigate the
    // timeline" — focus changes felt locked because every key press
    // landed inside a janky frame.
    //
    // The empty-time-slot fix from MK.EPG.A is preserved by a smaller
    // change in the render loop below: inter-programme gaps and the
    // trailing gap now render as focusable [NoEpgCell]s instead of
    // bare `Box`. Leading gap stays a bare `Box` so RIGHT-from-
    // ChannelCell finds the first programme block, never an off-
    // viewport empty cell (the focus regression we hit yesterday).
    // MK.EPG.I — quantize the scroll-driven re-filter to 30-min buckets.
    //
    // Pre-fix the `visibleProgrammes` derivedStateOf read `hScroll.value`
    // directly. Every horizontal scroll tick (~60fps during user scroll)
    // mutated hScroll → invalidated this derived state → re-ran the
    // `channel.programmes.filter { ... }` lambda → allocated a new
    // `List<EpgProgramme>` per visible channel. On the user's 4022-channel
    // install with ~15 visible rows that's ~900 list allocations / sec
    // during scroll, on top of every Compose layout pass. Logcat showed
    // measurable GC pressure during sustained horizontal scrolling even
    // after MK.EPG.A.fix2 took the cmd-list pipeline out of the picture.
    //
    // Two-tier derivedStateOf: inner bucket runs every frame but does
    // cheap math (one division) and emits the same value within a bucket;
    // outer filter only re-runs when the bucket value actually changes,
    // i.e. once per 30 min of scroll motion. The 60-min `bufferMin`
    // safety margin (kept on both sides of the snapped viewport) covers
    // any programmes the user can scroll past within a single bucket
    // before the next re-filter triggers — bucket=30min < buffer=60min,
    // so the active result set always covers the actual viewport.
    val scrollBucketMin by remember(pxPerMin) {
        derivedStateOf {
            val bucketWidthMin = 30L
            val pxPerBucket = (bucketWidthMin * pxPerMin).toLong().coerceAtLeast(1L)
            (hScroll.value.toLong() / pxPerBucket) * bucketWidthMin
        }
    }
    val visibleProgrammes by remember(
        channel.programmes,
        windowStart,
        windowEnd,
        pxPerMin,
        viewportWidthPx,
    ) {
        derivedStateOf {
            val viewportStartMin = scrollBucketMin
            val viewportMin = (viewportWidthPx.toLong() / pxPerMin).coerceAtLeast(0L)
            // bucketWidthMin (30) added to the forward extent so a user
            // who scrolls all the way to the bucket's far edge still has
            // their viewport covered before the next bucket triggers
            // a re-filter.
            val bucketWidthMin = 30L
            val bufferMin = 60L
            val rangeStartTime =
                windowStart + ((viewportStartMin - bufferMin).coerceAtLeast(0L)) * 60L
            val rangeEndTime =
                windowStart + (viewportStartMin + bucketWidthMin + viewportMin + bufferMin) * 60L
            channel.programmes.filter { p ->
                val pe = p.endTime.coerceAtMost(windowEnd)
                val ps = p.startTime.coerceAtLeast(windowStart)
                pe > rangeStartTime && ps < rangeEndTime && pe > ps
            }
        }
    }

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(LocalYancoPalette.current.BackgroundRaised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sticky channel cell — stays visible even as the timeline scrolls.
        ChannelCell(
            channel = channel,
            onClick = onPlayChannel,
            onLongPress = onLongPressChannel,
            onExitLeft = onExitLeftFromChannel,
        )

        // Programme lane. Same flow-Row layout the OLD code used —
        // bare leading gap + alternating ProgrammeBlocks / focusable
        // NoEpgCells (gap fillers) + focusable trailing NoEpgCell.
        Row(
            modifier =
            Modifier
                .horizontalScroll(hScroll)
                .width(timelineWidth)
                .fillMaxHeight(),
        ) {
            var cursor = windowStart
            var firstEmitted = true
            for (prog in visibleProgrammes) {
                val clampedStart = prog.startTime.coerceAtLeast(windowStart)
                val clampedEnd = prog.endTime.coerceAtMost(windowEnd)
                if (clampedEnd <= clampedStart) continue
                val gapStartFrom = if (firstEmitted) windowStart else cursor
                val gapMin = ((clampedStart - gapStartFrom) / 60L).toInt().coerceAtLeast(0)
                if (gapMin > 0) {
                    if (firstEmitted) {
                        // **Leading gap stays a bare, NON-focusable Box.**
                        // Otherwise RIGHT-from-ChannelCell would find this
                        // empty cell as the leftmost focusable, trigger
                        // bringIntoView → scroll left → focus regression
                        // we hit on 2026-05-05. The user can still reach
                        // this empty span by scrolling left until it
                        // becomes an inter-programme gap (covered below).
                        Box(modifier = Modifier.width((gapMin * pxPerMin).dp).fillMaxHeight())
                    } else {
                        key("N-$gapStartFrom") {
                            NoEpgCell(
                                startTime = gapStartFrom,
                                endTime = clampedStart,
                                widthDp = (gapMin * pxPerMin).dp,
                                onPlayLive = onPlayChannel,
                                onLongPress = onLongPressChannel,
                            )
                        }
                    }
                }
                val durMin = ((clampedEnd - clampedStart) / 60L).toInt().coerceAtLeast(1)
                key("P-${prog.id}") {
                    ProgrammeBlock(
                        programme = prog,
                        widthDp = (durMin * pxPerMin).dp.coerceAtLeast(MIN_PROG_WIDTH),
                        onActivate = { onProgrammeAction(prog) },
                    )
                }
                cursor = clampedEnd
                firstEmitted = false
            }
            // **Trailing focusable cell** so the right edge of every
            // row is reachable — pre-MK.EPG.A this area was unallocated
            // empty space inside the fixed-width Row. Capped at the
            // window's end so we don't fabricate a focusable cell over
            // off-viewport future programmes that the filter excluded.
            if (!firstEmitted && cursor < windowEnd) {
                val trailingMin = ((windowEnd - cursor) / 60L).toInt()
                if (trailingMin > 0) {
                    key("N-$cursor") {
                        NoEpgCell(
                            startTime = cursor,
                            endTime = windowEnd,
                            widthDp = (trailingMin * pxPerMin).dp,
                            onPlayLive = onPlayChannel,
                            onLongPress = onLongPressChannel,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCell(channel: EpgGuideChannel, onClick: () -> Unit, onLongPress: () -> Unit, onExitLeft: () -> Unit = {}) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) LocalYancoPalette.current.BackgroundHover else LocalYancoPalette.current.BackgroundRaised
    val border = if (focused) LocalYancoPalette.current.FocusRing else LocalYancoPalette.current.BorderSubtle

    // MK.16.5 — channel-number prefix when toggled on. Uses the
    // user-picked padding format. Absent when source has no number.
    val prefs: AppPreferences = koinInject()
    val general by prefs.generalFlow.collectAsState()
    val numberPrefix =
        if (general.showChannelNumbers) {
            general.channelNumberFormat.format(channel.channelNumber).takeIf { it.isNotBlank() }
        } else {
            null
        }

    Row(
        modifier =
        Modifier
            .width(CHANNEL_COL_WIDTH)
            .fillMaxHeight()
            .background(bg)
            .border(0.5.dp, border)
            // Hole-cover: LEFT from the leftmost cell exits to the
            // category rail. Programmes inside the timeline use
            // their own LEFT/RIGHT for navigation between blocks;
            // only the channel column is the "leftmost edge"
            // where LEFT must escape the panel.
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                    onExitLeft()
                    true
                } else {
                    false
                }
            }
            .focusable(interactionSource = interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongPress,
            ).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(LocalYancoPalette.current.BackgroundDeep),
            contentAlignment = Alignment.Center,
        ) {
            if (!channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                )
            }
        }
        if (numberPrefix != null) {
            Text(
                text = numberPrefix,
                color = LocalYancoPalette.current.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = channel.name,
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProgrammeBlock(programme: EpgProgramme, widthDp: androidx.compose.ui.unit.Dp, onActivate: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) LocalYancoPalette.current.BackgroundHover else LocalYancoPalette.current.BackgroundDeep
    val border = if (focused) LocalYancoPalette.current.FocusRing else LocalYancoPalette.current.BorderSubtle

    Column(
        modifier =
        Modifier
            .width(widthDp)
            .fillMaxHeight()
            .padding(1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(0.5.dp, border, RoundedCornerShape(4.dp))
            .focusable(interactionSource = interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onActivate,
            )
            // Audit catch — merge programme title + start clock into
            // one TalkBack announcement. With hundreds of programme
            // cells per visible EPG window this is the highest-volume
            // silent-button site in the app.
            .semantics(mergeDescendants = true) {
                contentDescription = "${programme.title}, starts ${formatHourMinute(programme.startTime)}"
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = programme.title,
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatHourMinute(programme.startTime),
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * MK.EPG.A — focusable empty-time cell rendered in EPG gaps.
 *
 * Why it exists: pre-MK.EPG.A the inter-programme gaps and the trailing
 * space after the last programme rendered as bare `Box` filler. D-pad
 * navigation cannot focus a node without `.focusable`, so the user saw
 * "grey areas they couldn't reach" — the only D-pad escape from a gap
 * was to leave the row entirely. Reported 2026-05-04.
 *
 * Behaviour:
 *  - CENTER plays the channel live (via the row's [onPlayLive], same
 *    callback the channel cell uses). Sensible default — the user has
 *    no programme to act on, but the channel itself still streams.
 *  - Long-press opens [com.yancotv.android.ui.parental.ChannelActionsMenu]
 *    via the row's [onLongPress] (lock / hide / favourite). Mirrors the
 *    channel cell's long-press path so the menu is reachable from any
 *    cell on the row, not only the ID column.
 *  - Unfocused: blends into the row background so the lane reads as
 *    intentionally empty without visual noise. Focused: standard
 *    palette focus ring + a "No info" label + the time range so the
 *    user knows what slot they're on.
 *  - Time range is exposed via `semantics.contentDescription` for
 *    TalkBack / TV reader.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoEpgCell(startTime: Long, endTime: Long, widthDp: androidx.compose.ui.unit.Dp, onPlayLive: () -> Unit, onLongPress: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalYancoPalette.current
    val bg = if (focused) palette.BackgroundHover else palette.BackgroundRaised
    val border = if (focused) palette.FocusRing else palette.BorderSubtle

    val rangeLabel = "${formatHourMinute(startTime)} – ${formatHourMinute(endTime)}"
    val description = "No programme info, $rangeLabel. Press to watch this channel live."

    Column(
        modifier =
        Modifier
            .width(widthDp)
            .fillMaxHeight()
            .padding(1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(0.5.dp, border, RoundedCornerShape(4.dp))
            .semantics { contentDescription = description }
            .focusable(interactionSource = interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onPlayLive,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        // Only render the label when the cell is wide enough to read it.
        // Below ~60 dp the text wraps awkwardly inside an hour bucket at
        // very low timeline density.
        if (widthDp >= 60.dp) {
            Text(
                text = if (focused) "No info" else "—",
                color = if (focused) palette.TextPrimary else palette.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (focused) {
                Text(
                    text = rangeLabel,
                    color = palette.TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GuideEmptyState(text: String, modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(LocalYancoPalette.current.BackgroundDeep),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = LocalYancoPalette.current.TextMuted)
    }
}

@Composable
private fun ProgrammeActionDialog(
    channel: EpgGuideChannel,
    programme: EpgProgramme,
    nowSeconds: Long,
    isReminderSet: Boolean,
    isRecordScheduled: Boolean,
    isSeriesBound: Boolean,
    catchupItem: ContentItem?,
    onWatch: () -> Unit,
    onPlayCatchup: (ContentItem) -> Unit,
    onSetReminder: () -> Unit,
    onCancelReminder: () -> Unit,
    onScheduleRecord: () -> Unit,
    onCancelRecord: () -> Unit,
    onScheduleSeries: () -> Unit,
    onCancelSeries: () -> Unit,
    onDismiss: () -> Unit,
) {
    // "Future" means the programme hasn't started yet. Setting a reminder on
    // something already live is pointless — the user should just press Watch.
    val isFuture = programme.startTime > nowSeconds
    val isPast = programme.endTime <= nowSeconds
    val canRecord = isFuture && !channel.streamUrl.isNullOrBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalYancoPalette.current.BackgroundRaised,
        title = {
            Text(text = programme.title, color = LocalYancoPalette.current.TextPrimary)
        },
        text = {
            Column {
                Text(
                    text = channel.name,
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 13.sp,
                )
                Text(
                    text = "${formatHourMinute(programme.startTime)} – ${formatHourMinute(programme.endTime)}",
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 12.sp,
                )
                // MK.15.5 — surface synopsis + category when XMLTV provides
                // them. Most providers ship at least description; category
                // is rarer but cheap to render when present.
                programme.category?.takeIf { it.isNotBlank() }?.let { cat ->
                    Text(
                        text = cat.uppercase(),
                        color = LocalYancoPalette.current.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                programme.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(
                        text = desc,
                        color = LocalYancoPalette.current.TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            // State-gated primary action:
            //   - currently airing → "Watch channel"
            //   - past + has catch-up → "Play catch-up"
            //   - past without catch-up OR future → no primary; the
            //     user closes the dialog (future has reminder / record
            //     in the dismiss row, past-without-catchup has nothing
            //     useful here so we don't fake one)
            when {
                !isPast && !isFuture -> {
                    TextButton(onClick = onWatch) {
                        Text(text = "Watch channel", color = LocalYancoPalette.current.Accent)
                    }
                }
                isPast && catchupItem != null -> {
                    TextButton(onClick = { onPlayCatchup(catchupItem) }) {
                        Text(text = "Play catch-up", color = LocalYancoPalette.current.Accent)
                    }
                }
                else -> Unit
            }
        },
        dismissButton = {
            // Pack the future-programme actions into a horizontal row so
            // both Reminder and Record sit side-by-side without overflowing
            // the AlertDialog's button slot. On phone this still fits;
            // on TV the D-pad navigates between them naturally.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    isFuture -> {
                        if (isReminderSet) {
                            TextButton(onClick = onCancelReminder) {
                                Text(text = "Cancel reminder", color = LocalYancoPalette.current.TextPrimary)
                            }
                        } else {
                            TextButton(onClick = onSetReminder) {
                                Text(text = "Set reminder", color = LocalYancoPalette.current.TextPrimary)
                            }
                        }
                        if (canRecord) {
                            if (isRecordScheduled) {
                                TextButton(onClick = onCancelRecord) {
                                    Text(text = "Cancel recording", color = LocalYancoPalette.current.TextPrimary)
                                }
                            } else {
                                TextButton(onClick = onScheduleRecord) {
                                    Text(text = "Record", color = LocalYancoPalette.current.TextPrimary)
                                }
                            }
                            // MK.14.6 — series binding. "Record series" arms
                            // every future programme on this channel matching
                            // the current title within the 7-day EPG window.
                            if (isSeriesBound) {
                                TextButton(onClick = onCancelSeries) {
                                    Text(text = "Cancel series", color = LocalYancoPalette.current.TextPrimary)
                                }
                            } else {
                                TextButton(onClick = onScheduleSeries) {
                                    Text(text = "Record series", color = LocalYancoPalette.current.TextPrimary)
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        },
    )
}

/** Formats a unix-second timestamp in the device's local HH:mm form. */
private fun formatHourMinute(unixSeconds: Long): String {
    val calendar =
        java.util.Calendar.getInstance().apply {
            timeInMillis = unixSeconds * 1000L
        }
    val h = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val m = calendar.get(java.util.Calendar.MINUTE)
    return buildString {
        if (h < 10) append('0')
        append(h)
        append(':')
        if (m < 10) append('0')
        append(m)
    }
}
