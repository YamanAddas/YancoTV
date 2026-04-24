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
        if (panelFocus != PanelFocus.Categories) return@LaunchedEffect
        pillAnchor.awaitAndRequest()
    }
    // Group catalogue per type. One-shot per type switch — failures degrade
    // silently to "no groups", in which case the rail still renders the
    // pinned Favorites / All pills.
    val groupsState = remember(type) { mutableStateListOf<String>() }
    LaunchedEffect(type) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { repo.groups(type) }
                .onFailure { Log.w("Yanco", "BrowseSection.groups($type) failed: ${it.message}", it) }
                .getOrElse { emptyList() }
        }
        groupsState.clear()
        groupsState.addAll(loaded)
    }

    val hiddenGroups by prefs.hiddenGroupsFlow.collectAsState()
    val visibleGroups = remember(groupsState.toList(), hiddenGroups) {
        prioritizedGroupsFor(visibleGroupsFor(groupsState.toList(), hiddenGroups))
    }

    // Selected group persists per type so flipping Live → Movies → Live
    // returns the user to whichever filter they had on Live last time.
    var selectedGroup by rememberSaveable(type) { mutableStateOf(ALL_GROUPS) }
    LaunchedEffect(hiddenGroups) {
        if (selectedGroup != ALL_GROUPS && selectedGroup != FAVORITES_GROUP &&
            selectedGroup in hiddenGroups
        ) {
            selectedGroup = ALL_GROUPS
        }
    }

    val categoriesVisible = panelFocus != PanelFocus.Content

    Row(modifier = modifier.fillMaxSize()) {
        if (categoriesVisible) {
            CategoryRail(
                groups = visibleGroups,
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
