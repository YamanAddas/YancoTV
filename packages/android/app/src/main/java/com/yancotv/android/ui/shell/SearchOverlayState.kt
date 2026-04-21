package com.yancotv.android.ui.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped visibility flag for the global search overlay (MK.8.5 spec).
 *
 * A single-instance state holder — the shell has exactly one MainActivity,
 * so we don't need Koin or per-instance ownership here. Kept intentionally
 * tiny (one Boolean) so both the hardware-key handler in MainActivity and
 * the Compose shell in HomeScreen can drive it without a shared ViewModel.
 *
 * Invocation paths: KEYCODE_SEARCH on TV remotes, Ctrl-K on phone hardware
 * keyboards, and the sidebar "Search" destination (kept as a discoverable
 * entry point so users who don't know the shortcut can still get here).
 */
object SearchOverlayState {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun show() { _visible.value = true }
    fun hide() { _visible.value = false }
    fun toggle() { _visible.value = !_visible.value }
}
