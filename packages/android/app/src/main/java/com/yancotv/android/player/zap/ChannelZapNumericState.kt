package com.yancotv.android.player.zap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MK.10.4 — state for the numeric channel-jump overlay.
 *
 * Single instance owned by `PlayerActivity` (no Koin — the activity has
 * exactly one and the state is meaningless outside it). Compose
 * collects [digits] / [visible]; the activity drives writes from the
 * key handler.
 *
 * **Why not a snackbar / toast.** A toast can't show partial input
 * (each digit press would re-show one), and snackbars block the
 * stream. The overlay wins by composing right next to the picture and
 * clearing itself on commit / cancel / timeout.
 */
class ChannelZapNumericState {
    private val _digits = MutableStateFlow("")
    val digits: StateFlow<String> = _digits.asStateFlow()

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Append [digit] (0–9) to the entry. Caps at [MAX_DIGITS] so a stuck
     *  remote button can't grow the buffer unboundedly. */
    fun pushDigit(digit: Char) {
        if (digit !in '0'..'9') return
        if (_digits.value.length >= MAX_DIGITS) return
        _digits.value += digit
        _visible.value = true
    }

    /** Snapshot the current entry as an Int and clear. Returns null if
     *  the buffer is empty or somehow non-numeric (defensive). */
    fun consume(): Int? {
        val text = _digits.value
        _digits.value = ""
        _visible.value = false
        return text.toIntOrNull()
    }

    /** Cancel without committing — user pressed BACK or the auto-hide
     *  timer fired. */
    fun clear() {
        _digits.value = ""
        _visible.value = false
    }

    companion object {
        const val MAX_DIGITS = 4
    }
}
