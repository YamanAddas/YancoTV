package com.yancotv.android.ui.focus

/**
 * MB-98 — focused-card context-menu action registry.
 *
 * Why this exists: Compose `onPreviewKeyEvent` proved unreliable for D-pad
 * long-press detection on Fire TV / Android TV. The system's
 * `KeyEvent.isLongPress` flag only gets set when `startTracking()` was called
 * on the matching DOWN — which `onPreviewKeyEvent` doesn't do. KEYCODE_MENU
 * also seemed to bypass the modifier chain in practice. So we moved both
 * triggers up to the Activity (`MainActivity.onKeyDown` + a manual timer),
 * and have whichever card holds focus register its menu-action here.
 *
 * Threading: only main-thread reads/writes are expected (focus changes and
 * Activity key callbacks both run on main). No synchronisation needed.
 *
 * Identity is by token (a stable per-card `Any()` from `remember { }`) so
 * we can distinguish "this card lost focus, clear" from "another card took
 * over, leave alone" — lambda equality won't work because the action lambda
 * is recreated on every recomposition.
 */
object TvContextActionState {
    private var token: Any? = null
    private var action: (() -> Unit)? = null

    /** Card-gained-focus: bind this token's action as the active one. */
    fun set(
        owner: Any,
        handler: () -> Unit,
    ) {
        token = owner
        action = handler
    }

    /**
     * Card-lost-focus: clear iff WE are still the active token. Avoids racing
     * the next card's `set(...)` if the framework dispatches focus-loss for
     * the outgoing card AFTER focus-gain on the incoming one.
     */
    fun clearIf(owner: Any) {
        if (token === owner) {
            token = null
            action = null
        }
    }

    /**
     * Invoke the active action (long-press or MENU). Returns true if an
     * action fired so the Activity knows to consume the originating key.
     */
    fun fire(): Boolean {
        val handler = action ?: return false
        handler()
        return true
    }
}
