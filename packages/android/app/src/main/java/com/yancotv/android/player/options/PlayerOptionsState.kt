package com.yancotv.android.player.options

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MK.options.redesign — state for the lightweight player-options popup
 * + per-category floating panels.
 *
 * Replaces the heavy sheet's chassis-driven model. Two states the UI
 * cares about:
 *   - whether the popup is showing (the row list with current values)
 *   - which category, if any, has its floating panel open
 *
 * Slice 1 wires Audio / Subtitles / Aspect; the remaining categories
 * (Speed / Sleep / Record / Favorites / External) still go through
 * `PlayerActivity.showSheet()` via their row's `onPick` so the user
 * doesn't lose access during the redesign.
 */
enum class PlayerOptionCategory {
    AUDIO,
    SUBTITLES,
    ASPECT,
    SPEED,
    SLEEP,
    RECORD,
    FAVORITES,
    EXTERNAL,
    SUBTITLE_SEARCH,
    ;

    /**
     * The panel one level up. Used by [PlayerOptionsState.closePanel] so a
     * BACK / pick from a sub-panel returns to its parent panel rather than
     * jumping all the way back to the popup. Top-level categories return
     * null — closing them goes back to the popup.
     */
    fun parent(): PlayerOptionCategory? = when (this) {
        SUBTITLE_SEARCH -> SUBTITLES
        else -> null
    }
}

class PlayerOptionsState {
    private val _menuVisible = MutableStateFlow(false)
    val menuVisible: StateFlow<Boolean> = _menuVisible.asStateFlow()

    private val _activePanel = MutableStateFlow<PlayerOptionCategory?>(null)
    val activePanel: StateFlow<PlayerOptionCategory?> = _activePanel.asStateFlow()

    // When a sub-panel closes back to its parent, the parent panel needs
    // to know which one closed so it can put the focus selector on the
    // ROW that opened the sub-panel — not on the panel's default first
    // row. Without this the user ends up on "Off" after returning from
    // SUBTITLE_SEARCH, having lost their place. The parent reads this
    // once on re-entry then calls [consumeSubPanelReturn] to clear it.
    private val _returningFromSubPanel = MutableStateFlow<PlayerOptionCategory?>(null)
    val returningFromSubPanel: StateFlow<PlayerOptionCategory?> = _returningFromSubPanel.asStateFlow()

    fun showMenu() {
        _menuVisible.value = true
        _activePanel.value = null
        _returningFromSubPanel.value = null
    }

    fun hideMenu() {
        _menuVisible.value = false
        _activePanel.value = null
        _returningFromSubPanel.value = null
    }

    fun openPanel(category: PlayerOptionCategory) {
        _activePanel.value = category
        // Opening a fresh panel discards any pending sub-panel return —
        // user navigated away on purpose.
        _returningFromSubPanel.value = null
        // Keep menu state intact so BACK from a panel returns to the
        // popup. The host renders the panel on top of the popup.
    }

    /**
     * Close the active panel. If the panel has a [PlayerOptionCategory.parent],
     * land on the parent panel (one level up) rather than jumping back to
     * the popup. This keeps the user's mental model honest: BACK from a
     * sub-panel returns to where they came from, not to the root.
     *
     * Also stashes the closing category in [returningFromSubPanel] so
     * the parent panel can restore focus to the row that opened it —
     * otherwise the user lands on the panel's default first row and
     * loses their place.
     */
    fun closePanel() {
        val current = _activePanel.value ?: return
        val parent = current.parent()
        if (parent != null) {
            _returningFromSubPanel.value = current
        }
        _activePanel.value = parent
    }

    /** Parent panel calls this once it's used the [returningFromSubPanel] hint. */
    fun consumeSubPanelReturn() {
        _returningFromSubPanel.value = null
    }
}
