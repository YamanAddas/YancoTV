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
}

class PlayerOptionsState {
    private val _menuVisible = MutableStateFlow(false)
    val menuVisible: StateFlow<Boolean> = _menuVisible.asStateFlow()

    private val _activePanel = MutableStateFlow<PlayerOptionCategory?>(null)
    val activePanel: StateFlow<PlayerOptionCategory?> = _activePanel.asStateFlow()

    fun showMenu() {
        _menuVisible.value = true
        _activePanel.value = null
    }

    fun hideMenu() {
        _menuVisible.value = false
        _activePanel.value = null
    }

    fun openPanel(category: PlayerOptionCategory) {
        _activePanel.value = category
        // Keep menu state intact so BACK from a panel returns to the
        // popup. The host renders the panel on top of the popup.
    }

    fun closePanel() {
        _activePanel.value = null
    }
}
