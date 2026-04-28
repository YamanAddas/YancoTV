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
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.shared.content.ContentRepository
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
@UnstableApi
@Composable
fun BrowseSection(
    type: ContentType,
    panelFocus: PanelFocus,
    onPanelFocusChanged: (PanelFocus) -> Unit,
    onActivate: (List<ContentItem>, Int) -> Unit,
    onExitToSidebar: () -> Unit,
    restoreFocusOnWindowRegain: Boolean,
    repo: ContentRepository = koinInject(),
    prefs: AppPreferences = koinInject(),
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
    LaunchedEffect(type) {
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching { repo.groups(type) }
                    .onFailure { Log.w("Yanco", "BrowseSection.groups($type) failed: ${it.message}", it) }
                    .getOrElse { emptyList() }
            }
        groupsState.clear()
        groupsState.addAll(loaded)
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
    // flatten per the rail's expand state. Per-type expand state is kept in
    // a rememberSaveable Set so flipping Live ↔ Movies preserves which
    // parents the user opened. Process-death survival isn't critical (it's
    // a UI nicety) so we accept that a Set<String> isn't trivially Saveable
    // — we wrap it via the standard MutableStateFlow + remember pattern.
    val smartGroupingEnabled = general.smartGrouping
    val pinnedParentsByType by prefs.pinnedParentsFlow.collectAsState()
    val pinnedParents = pinnedParentsByType[type] ?: emptyList()
    var expandedParents by remember(type) { mutableStateOf(emptySet<String>()) }
    val railRows =
        remember(groupsState.toList(), hiddenGroups, smartGroupingEnabled, expandedParents, pinnedParents) {
            if (!smartGroupingEnabled) {
                null
            } else {
                val filtered = applySmartGroupingHidden(groupsState.toList(), hiddenGroups)
                val tree = com.yancotv.shared.content.CategoryTreeBuilder.build(filtered, pinnedParents)
                flattenCategoryTree(tree, expandedParents)
            }
        }

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

    val categoriesVisible = panelFocus != PanelFocus.Content

    Row(modifier = modifier.fillMaxSize()) {
        if (categoriesVisible) {
            CategoryRail(
                groups = if (smartGroupingEnabled) emptyList() else visibleGroups,
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
            onActivate = onActivate,
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
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}
