package com.yancotv.android.player

import android.view.KeyEvent

/**
 * MB-345 — whether a key press should restart the VOD dock's 4 s auto-hide.
 *
 * The dock arms a 4 s timer in `showVodDock` and the composable resets it via
 * `onUserInteraction`, but that callback fires on CLICKS. `TransportButton`'s
 * `onFocusChanged` only flips a visual flag, and there is no
 * `Activity.onUserInteraction` override, so **D-pad traversal never reset the
 * timer**. Navigating from the initially-focused play/pause out to a far control
 * raced a timer that nothing the user was doing could postpone.
 *
 * This was filed Low and BOTH refuting agents judged it not a defect, on the
 * reasoning that 4 s is ample for a deliberate press. Device evidence says
 * otherwise: verifying MB-343 on a Fire TV, reaching the dock's NEXT button —
 * two D-pad RIGHT presses from the default focus — failed twice because the dock
 * hid mid-traversal. With the dock gone, RIGHT is a seek and CENTER re-opens the
 * dock, so the attempts did not merely time out, they did the WRONG thing. It
 * only worked by batching all four key events into a single call faster than a
 * human can press them. The refutation assumed a user who already knows exactly
 * where to go; MB-343 then made NEXT a control people must deliberately navigate
 * to, which is the case the refutation did not cover.
 *
 * Fixing it in `dispatchKeyEvent` rather than in each control is deliberate:
 * that is the one place every key reaches before the view tree, so all eleven
 * dock controls plus the progress row are covered by one predicate instead of
 * eleven `onUserInteraction` call sites that the next control added would
 * silently not join.
 *
 * Pure and framework-free apart from [KeyEvent]'s action constants, which are
 * compile-time inlined `int`s — the JVM test never loads the class.
 */
internal fun shouldResetDockAutoHide(dockVisible: Boolean, action: Int, repeatCount: Int): Boolean {
    // Nothing to keep alive, and arming a timer for a hidden dock would leave a
    // stray job to cancel.
    if (!dockVisible) return false
    // ACTION_DOWN only. ACTION_UP would double every press for no gain, and the
    // CENTER long-press arm consumes its own UP anyway.
    if (action != KeyEvent.ACTION_DOWN) return false
    // Initial press only, NOT auto-repeats. A held key must not be able to pin
    // the dock open indefinitely, and traversal — the case this fixes — is
    // discrete presses, so repeats add nothing. Note the dock's ±10 s seek row
    // is unaffected either way: MB-338's accelerated hold only runs while the
    // dock is HIDDEN, where this predicate already returns false.
    return repeatCount == 0
}
