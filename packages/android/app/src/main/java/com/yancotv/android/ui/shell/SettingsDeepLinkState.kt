package com.yancotv.android.ui.shell

import com.yancotv.android.ui.settings.SettingsTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MK.30.4 — "open Settings on this tab" request from outside the shell.
 *
 * Mirrors [SearchOverlayState]: MainActivity's intent handler cannot reach
 * into HomeScreen's local `section` state, so an external trigger parks its
 * request here and the shell consumes it on the next frame.
 *
 * Used by the update notification, which is only useful if tapping it lands
 * on the surface that can actually install the update (Settings → About)
 * rather than dropping the user on Home to go find it.
 *
 * [consume] is one-shot for the same reason the deep-link extras in
 * MainActivity are removed after reading: a configuration-change recreate
 * re-runs the intent handler, and a sticky value would yank the user back
 * into Settings every rotation.
 */
object SettingsDeepLinkState {
    private val _pendingTab = MutableStateFlow<SettingsTab?>(null)
    val pendingTab: StateFlow<SettingsTab?> = _pendingTab.asStateFlow()

    fun request(tab: SettingsTab) {
        _pendingTab.value = tab
    }

    fun consume(): SettingsTab? {
        val tab = _pendingTab.value
        if (tab != null) _pendingTab.value = null
        return tab
    }
}
