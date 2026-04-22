package com.yancotv.android.ui.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shell reveal + focus state. Mirrors TiviMate's "menu + groups slide in
 * from the left one panel at a time" behaviour: pressing LEFT from the
 * content list reveals the groups panel; pressing LEFT from the groups
 * reveals the sidebar. RIGHT walks focus back toward the content.
 *
 * Keeping the state in a plain object (not a ViewModel) so the Activity's
 * `onKeyDown` can reach it without pushing key handling down into the
 * Compose tree. [HomeScreen] and [MainActivity] are the only callers.
 *
 * Only sections that actually render a groups panel (Live TV, Movies,
 * Series) participate in the progressive reveal. Other sections force
 * level to 2 so the sidebar is always reachable.
 */
object ShellUiState {

    enum class Zone { CONTENT, GROUPS, SIDEBAR }

    /**
     * 0 = content only (groups + sidebar hidden),
     * 1 = content + groups (sidebar hidden),
     * 2 = all three panels visible.
     */
    private val _revealLevel = MutableStateFlow(0)
    val revealLevel: StateFlow<Int> = _revealLevel.asStateFlow()

    /**
     * The zone the user is currently navigating within. Drives focus
     * requests in [HomeScreen] so revealing a panel also moves D-pad
     * focus into it — no wasted presses.
     */
    private val _focusZone = MutableStateFlow(Zone.CONTENT)
    val focusZone: StateFlow<Zone> = _focusZone.asStateFlow()

    /**
     * Incremented every time the shell wants to move focus to the current
     * zone — Compose's [androidx.compose.ui.focus.FocusRequester] doesn't
     * re-fire on identical values, so we key a LaunchedEffect on this
     * integer to force the request.
     */
    private val _focusTick = MutableStateFlow(0)
    val focusTick: StateFlow<Int> = _focusTick.asStateFlow()

    /**
     * Called when the active section doesn't support progressive reveal
     * (Home, Guide, Favorites, Search, Settings — they don't have a groups
     * panel). Forces the full layout so navigation stays intuitive.
     */
    fun forceFull() {
        if (_revealLevel.value != 2) _revealLevel.value = 2
    }

    /** Called when the user enters a progressive-reveal section. */
    fun resetToContent() {
        _revealLevel.value = 0
        _focusZone.value = Zone.CONTENT
        _focusTick.value = _focusTick.value + 1
    }

    /**
     * LEFT key while the shell is in a progressive section. Returns true
     * if the key was consumed (Activity should swallow it); false if the
     * zone can't go further left, so default focus traversal applies.
     */
    fun onLeft(): Boolean {
        return when (_focusZone.value) {
            Zone.CONTENT -> {
                if (_revealLevel.value < 1) _revealLevel.value = 1
                _focusZone.value = Zone.GROUPS
                _focusTick.value = _focusTick.value + 1
                true
            }
            Zone.GROUPS -> {
                if (_revealLevel.value < 2) _revealLevel.value = 2
                _focusZone.value = Zone.SIDEBAR
                _focusTick.value = _focusTick.value + 1
                true
            }
            Zone.SIDEBAR -> false
        }
    }

    /**
     * RIGHT key. Moves focus one zone toward the content without
     * collapsing panels — the user is likely still browsing, and
     * a sudden slide-out on every RIGHT is jarring. BACK handles the
     * collapse path.
     */
    fun onRight(): Boolean {
        return when (_focusZone.value) {
            Zone.SIDEBAR -> {
                _focusZone.value = Zone.GROUPS
                _focusTick.value = _focusTick.value + 1
                true
            }
            Zone.GROUPS -> {
                _focusZone.value = Zone.CONTENT
                _focusTick.value = _focusTick.value + 1
                true
            }
            Zone.CONTENT -> false
        }
    }

    /**
     * BACK when a panel is revealed: collapse the outermost visible
     * panel first. Only returns true (consumes BACK) while there's
     * something to collapse — otherwise the Activity handles it as a
     * normal back press (exit / finish).
     */
    fun onBack(): Boolean {
        return when {
            _revealLevel.value >= 2 -> {
                _revealLevel.value = 1
                _focusZone.value = Zone.GROUPS
                _focusTick.value = _focusTick.value + 1
                true
            }
            _revealLevel.value == 1 -> {
                _revealLevel.value = 0
                _focusZone.value = Zone.CONTENT
                _focusTick.value = _focusTick.value + 1
                true
            }
            else -> false
        }
    }

    /** Called by a zone when focus actually lands there (e.g. via mouse). */
    fun reportFocus(zone: Zone) {
        if (_focusZone.value != zone) _focusZone.value = zone
    }
}
