package com.yancotv.android.ui.shell

import android.util.Log
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.R
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.content.SourceCategoryTreeBuilder
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Which of the three vertical panels currently owns focus. Hoisted to
 * [HomeScreen] so the sidebar can collapse to icon-only and the categories
 * rail can mount/unmount in lockstep with focus movement.
 *
 * Cascade behaviour driven by this state:
 *   - Sidebar    → sidebar 260dp, categories hidden,    content visible
 *   - Categories → sidebar 92dp,  categories 240dp,     content visible
 *   - Content    → sidebar 92dp,  categories hidden,    content fills
 *
 * Persisted via [rememberSaveable] so process death + rotation don't
 * reset the user back to the sidebar mid-watch.
 */
enum class PanelFocus { Sidebar, Categories, Content }

/**
 * Browse-section wrapper used by Live TV / Movies / Series. Composes:
 *
 *   ┌─────────────────────────┐  ┌────────────────────────────────────┐
 *   │   CategoryRail (240dp)  │  │   CoverflowSectionScreen           │
 *   │   • Favorites           │  │   • split preview pane              │
 *   │   • All                 │  │   • 3D coverflow wheel              │
 *   │   • Group A             │  │                                     │
 *   │   • Group B             │  │                                     │
 *   │   • …                   │  │                                     │
 *   └─────────────────────────┘  └────────────────────────────────────┘
 *
 * Lifts the categories chip state out of [CoverflowSectionScreen] so the
 * vertical rail and the content panel share a single source of truth.
 *
 * Cascade focus contract:
 *   - When [panelFocus] == [PanelFocus.Content] the rail unmounts and the
 *     coverflow panel takes the rail's space (Compose layout handles this
 *     automatically because the rail just stops rendering).
 *   - When focus enters either child, that child's onPanelFocusChanged
 *     callback flips [panelFocus] up to HomeScreen via [onPanelFocusChanged].
 *   - BACK from coverflow lands focus on the active rail pill via
 *     [categoriesFocus]. BACK from rail bubbles up via [onExitToSidebar].
 */
@OptIn(ExperimentalComposeUiApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun BrowseSection(
    type: ContentType,
    panelFocus: PanelFocus,
    onPanelFocusChanged: (PanelFocus) -> Unit,
    onActivate: (List<ContentItem>, Int) -> Unit,
    /** MK.29.3 — preview-pane Watch on a movie: play, don't open detail. */
    onPlayNow: (List<ContentItem>, Int) -> Unit,
    onExitToSidebar: () -> Unit,
    restoreFocusOnWindowRegain: Boolean,
    /** Fired by the coverflow empty-state's "Add a source" CTA. */
    onAddSource: (() -> Unit)? = null,
    repo: ContentRepository = koinInject(),
    prefs: AppPreferences = koinInject(),
    /**
     * MK.33.1 — needed only for the playlist ORDER and display names when
     * bucketing categories per playlist. `content.source_id` gives the grouping;
     * this gives the row order the user sees on the Sources screen, and the
     * names (a source_id is a UUID and is never rendered).
     */
    sourceRepo: SourceRepository = koinInject(),
    modifier: Modifier = Modifier,
) {
    // coverflowFocus is owned here (not at HomeScreen) so it lives under the
    // `key(contentType)` boundary — every type swap creates a fresh
    // FocusRequester bound to the new section's leftmost orb. Hoisting it
    // up to HomeScreen meant a single requester was reused across types,
    // and after Live → Movies the requester was still bound to Live's
    // (now-unmounted) coverflow node. requestFocus() then targeted nothing
    // and the cascade transition silently swallowed the press.
    val coverflowFocus = remember { FocusRequester() }
    // Single source of truth for "focus a category pill". PlacedFocusAnchor
    // (per native-android-mk skill) waits for the pill's onPlaced callback
    // before issuing requestFocus(), so it works whether the rail was
    // already mounted (RIGHT from sidebar) or just re-mounted this frame
    // (LEFT from CTA / leftmost orb / BACK from coverflow).
    val pillAnchor = rememberPlacedFocusAnchor()
    // Drive focus from panelFocus state, not from each callback. Every path
    // that flips panelFocus → Categories goes through here, so spatial
    // direction == focus movement is preserved without per-call-site bugs.
    //
    // The whole BrowseSection is wrapped in `key(contentType)` by HomeScreen,
    // so every type swap remounts this composable with a fresh pillAnchor
    // (isPlaced starts false). awaitAndRequest then deterministically
    // suspends until the new section's "All" pill calls onPlaced, then
    // fires requestFocus on the requester bound to that exact node — no
    // paranoia frame, no race against an unmounted Live TV pill.
    //
    // For the Sidebar→Categories case within the SAME type (panelFocus
    // changes but type doesn't), the pill is already placed from the
    // previous composition; isPlaced is true and awaitAndRequest fires
    // immediately. Both paths converge cleanly.
    LaunchedEffect(type, panelFocus) {
        when (panelFocus) {
            PanelFocus.Content -> {
                // Rail unmounts (categoriesVisible == false). Reset so
                // re-entry (LEFT from coverflow / leftmost CTA) waits for
                // the freshly-placed pill's onPlaced before requesting
                // focus. Without this reset, `isPlaced` is stale-true
                // from the previous mount and `awaitAndRequest` fires
                // immediately against a detached FocusRequester — the
                // request silently fails, no pill has focus, LEFT and
                // BACK both land nowhere and the next BACK closes the
                // app.
                pillAnchor.reset()
            }
            PanelFocus.Categories -> {
                // Land focus on the active pill. This works whether the
                // rail just remounted (Content → Categories: isPlaced
                // resets to false above, then onPlaced flips it true and
                // awaitAndRequest fires) or stayed mounted across the
                // Sidebar transition (isPlaced is still true from the
                // earlier mount, awaitAndRequest fires immediately).
                pillAnchor.awaitAndRequest()
            }
            PanelFocus.Sidebar -> {
                // Rail STAYS mounted (categoriesVisible == true). The
                // pill's onPlaced won't refire while the node is still in
                // the tree, so resetting isPlaced here would deadlock
                // the next Sidebar → Categories transition: awaitAndRequest
                // would wait forever. Leaving isPlaced=true means the
                // next RIGHT-from-sidebar fires requestFocus immediately
                // against the still-attached pill node. Critical: this
                // is the bug Categories→Sidebar→Categories navigation
                // tripped over before — RIGHT from sidebar appeared dead
                // because focus was waiting on a re-placement that never
                // came.
            }
        }
    }
    // Group catalogue per type. One-shot per type switch — failures degrade
    // silently to "no groups", in which case the rail still renders the
    // pinned Favorites / All pills.
    val groupsState = remember(type) { mutableStateListOf<String>() }
    // MK.33.1 — per-playlist buckets. Empty when only one playlist contributes
    // rows of this type, which is the signal to keep the flat / prefix-bucketed
    // path below. Reloaded on `type` like `groupsState`.
    val sourceBuckets = remember(type) { mutableStateListOf<SourceCategoryTreeBuilder.SourceGroups>() }
    LaunchedEffect(type) {
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching { repo.groups(type) }
                    .onFailure { Log.w("Yanco", "BrowseSection.groups($type) failed: ${it.message}", it) }
                    .getOrElse { emptyList() }
            }
        groupsState.clear()
        groupsState.addAll(loaded)

        // Ordered by the user's `sources` rows so the rail matches the Sources
        // screen rather than UUID order. Only active sources: an inactive
        // playlist's groups must not appear in the rail.
        val buckets =
            withContext(Dispatchers.IO) {
                runCatching {
                    val ordered = sourceRepo.getAll()
                        .filter { it.isActive }
                        .map { it.id to it.name }
                    repo.groupsBySource(type, ordered)
                }
                    .onFailure { Log.w("Yanco", "BrowseSection.groupsBySource($type) failed: ${it.message}", it) }
                    .getOrElse { emptyList() }
            }
        sourceBuckets.clear()
        // One bucket means one playlist supplies everything of this type, so a
        // dropdown per playlist would add a level of nesting that tells the user
        // nothing. Degrade to the flat path in that case.
        if (buckets.size > 1) sourceBuckets.addAll(buckets)
    }

    val hiddenGroups by prefs.hiddenGroupsFlow.collectAsState()
    val general by prefs.generalFlow.collectAsState()
    val visibleGroups =
        remember(groupsState.toList(), hiddenGroups) {
            visibleGroupsFor(groupsState.toList(), hiddenGroups)
        }

    // MK.20.3 — when the smart-grouping toggle is on, fork the rail's data
    // path: filter hidden first, run [com.yancotv.shared.content.CategoryTreeBuilder]
    // over the filtered list (provider-ordered already from MK.20.1), then
    // flatten per the rail's expand state.
    //
    // MK.28.8 (MB-284) — the old comment here claimed a rememberSaveable
    // Set that "preserves which parents the user opened" across Live ↔
    // Movies flips; the code was plain remember(type) and preserved
    // nothing. Now genuinely saveable (survives rotation / recreation via
    // listSaver). Cross-type flips still reset it — HomeScreen wraps
    // BrowseSection in key(contentType), which discards the subtree's
    // saveable state by design; hoisting a per-type map to the shell is
    // MK.27.B scope. The MK.20.3.2 plan text is corrected alongside this
    // fix.
    val smartGroupingEnabled = general.smartGrouping
    // MK.33.1 — resolved out here: the railRows `remember` below is not
    // composable scope.
    val allLabel = stringResource(R.string.cat_all)
    val pinnedParentsByType by prefs.pinnedParentsFlow.collectAsState()
    val pinnedParents = pinnedParentsByType[type] ?: emptyList()
    var expandedParents by rememberSaveable(
        type,
        saver = androidx.compose.runtime.saveable.listSaver(
            save = { state -> state.value.toList() },
            restore = { saved -> mutableStateOf(saved.toSet()) },
        ),
    ) { mutableStateOf(emptySet<String>()) }
    val railRows =
        remember(
            groupsState.toList(),
            sourceBuckets.toList(),
            hiddenGroups,
            smartGroupingEnabled,
            expandedParents,
            pinnedParents,
            allLabel,
        ) {
            when {
                // MK.33.1 — more than one playlist contributes rows of this
                // type: bucket by playlist and stop there.
                //
                // This takes precedence over prefix bucketing rather than
                // combining with it. Nesting both would put the rail three deep
                // (playlist -> Arabic -> Sports), and a three-deep tree on a
                // 380dp rail driven by a D-pad at 3m is not navigable. Prefix
                // bucketing still applies whenever a single playlist supplies
                // the type, which is the common case.
                sourceBuckets.isNotEmpty() -> {
                    // Hidden groups are filtered on the REAL group name, before
                    // the keys get scoped — `hiddenGroups` holds bare names, and
                    // by design a hidden group is hidden in every playlist (the
                    // prefs table is keyed on content_type + group_key with no
                    // source dimension).
                    val visible = sourceBuckets.map { bucket ->
                        bucket.copy(groups = bucket.groups.filter { it !in hiddenGroups })
                    }
                    val tree = SourceCategoryTreeBuilder.build(visible)
                    flattenCategoryTree(tree, expandedParents, wholeSourceLabel = allLabel)
                }
                !smartGroupingEnabled -> null
                else -> {
                    val filtered = applySmartGroupingHidden(groupsState.toList(), hiddenGroups)
                    val tree = com.yancotv.shared.content.CategoryTreeBuilder.build(filtered, pinnedParents)
                    flattenCategoryTree(tree, expandedParents)
                }
            }
        }

    // MB-382 — on the flat path (no smart-grouping, single playlist) fold
    // provider categories that strip to the same base name into one merged chip
    // (e.g. "JAMES BOND 007" + "DE - JAMES BOND 007"), TiviMate-style. railRows
    // is non-null only for the smart-grouping / per-playlist tree paths, which
    // keep raw provider names, so merge applies exactly when railRows == null.
    val useMerge = railRows == null
    val mergedCategories =
        remember(visibleGroups.toList(), useMerge) {
            if (useMerge) com.yancotv.shared.content.CategoryMerger.merge(visibleGroups) else emptyList()
        }
    val mergedGroupMap =
        remember(mergedCategories) { mergedCategories.associate { it.displayName to it.rawGroupNames } }

    // Selected group persists per type so flipping Live → Movies → Live
    // returns the user to whichever filter they had on Live last time.
    var selectedGroup by rememberSaveable(type) { mutableStateOf(ALL_GROUPS) }
    LaunchedEffect(hiddenGroups) {
        if (selectedGroup != ALL_GROUPS &&
            selectedGroup != FAVORITES_GROUP &&
            selectedGroup in hiddenGroups
        ) {
            selectedGroup = ALL_GROUPS
        }
    }
    // MB-382 — if a persisted selection is no longer a valid merged chip (e.g.
    // after toggling smart-grouping, or a stale raw name from before the merge),
    // fall back to All so the content pane isn't stranded empty. Only once the
    // merged list has actually loaded, so we don't reset during first load.
    LaunchedEffect(mergedGroupMap, useMerge) {
        if (useMerge &&
            mergedGroupMap.isNotEmpty() &&
            selectedGroup != ALL_GROUPS &&
            selectedGroup != FAVORITES_GROUP &&
            selectedGroup !in mergedGroupMap
        ) {
            selectedGroup = ALL_GROUPS
        }
    }

    val categoriesVisible = panelFocus != PanelFocus.Content

    Row(modifier = modifier.fillMaxSize()) {
        if (categoriesVisible) {
            CategoryRail(
                groups = if (useMerge) mergedCategories.map { it.displayName } else emptyList(),
                selected = selectedGroup,
                onSelect = { selectedGroup = it },
                onEnterContent = {
                    // CENTER on a pill commits selection AND moves focus into
                    // the coverflow — single press takes the user from pick
                    // to watch. RIGHT-arrow does the same via natural
                    // Compose focus traversal (sibling Row → next focusable).
                    onPanelFocusChanged(PanelFocus.Content)
                    runCatching { coverflowFocus.requestFocus() }
                },
                onExitToSidebar = {
                    onPanelFocusChanged(PanelFocus.Sidebar)
                    onExitToSidebar()
                },
                onPanelFocusChanged = { hasFocus ->
                    if (hasFocus) onPanelFocusChanged(PanelFocus.Categories)
                },
                selectedAnchor = pillAnchor,
                rows = railRows,
                onToggleExpand = { label ->
                    expandedParents =
                        if (label in expandedParents) expandedParents - label else expandedParents + label
                },
            )
        }
        CoverflowSectionScreen(
            type = type,
            selectedGroup = selectedGroup,
            mergedGroups = mergedGroupMap,
            onActivate = onActivate,
            onPlayNow = onPlayNow,
            entryFocus = coverflowFocus,
            onExitToCategories = {
                // Just flip the panel state — the LaunchedEffect above
                // owns the focus move via PlacedFocusAnchor. That handles
                // the rail-just-remounted race that the old direct
                // requestFocus() lost, which is what made LEFT-from-CTA
                // visually open the rail but leave focus in the content.
                onPanelFocusChanged(PanelFocus.Categories)
            },
            onPanelFocusChanged = { hasFocus ->
                if (hasFocus) onPanelFocusChanged(PanelFocus.Content)
            },
            restoreFocusOnWindowRegain = restoreFocusOnWindowRegain,
            onAddSource = onAddSource,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}
