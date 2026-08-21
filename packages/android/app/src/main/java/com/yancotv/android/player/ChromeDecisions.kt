package com.yancotv.android.player

import com.yancotv.android.player.options.PlayerOptionCategory

/**
 * MK.34.10 — the small rules the player chrome runs on, pulled out of the
 * composables and the activity so they can be tested without a device.
 *
 * Each of these was a `when` or an `if` embedded in UI code. None of them are
 * complicated; all of them are things a future edit could invert without
 * anything failing to build.
 */

/** How the programme title behaves when it is longer than its box. */
internal enum class MarqueeMode {
    /** Scroll it, after a delay, at the brief's 30dp/s. */
    ANIMATE,

    /** Truncate with an ellipsis and never move. */
    ELLIPSIS,
}

/**
 * Whether the title may scroll.
 *
 * **Overflow is deliberately not an input.** `basicMarquee` measures content
 * against container itself and stays completely still when the text fits, so
 * re-deriving "does it overflow" here would be a second, worse copy of a
 * measurement Compose already does at layout time — and the two would disagree
 * the first time padding changed.
 *
 * What IS decided here is the accessibility rule: the system reduce-motion
 * preference asks for NO movement, not slower movement, so it maps to a plain
 * ellipsis rather than a gentler scroll.
 */
internal fun marqueeMode(reduceMotion: Boolean): MarqueeMode = if (reduceMotion) MarqueeMode.ELLIPSIS else MarqueeMode.ANIMATE

/**
 * Which options panel a dock control opens, or null for the sheet ROOT.
 *
 * MK.34.7 — [SheetMode.MENU] returning null is the fix, not an oversight. The
 * three-dot control used to send [SheetMode.AUDIO], which opened the Audio panel
 * directly; the options popup hides itself whenever a panel is active, so the
 * sheet was unreachable from the dock entirely. A three-dot menu that lands you
 * inside one setting is not a menu.
 *
 * CAST and LOOK also return null — they never had a V2 panel, and dropping the
 * user at the root lets them navigate to whatever they actually wanted.
 */
internal fun optionCategoryFor(mode: SheetMode): PlayerOptionCategory? = when (mode) {
    SheetMode.AUDIO -> PlayerOptionCategory.AUDIO
    SheetMode.SUBS -> PlayerOptionCategory.SUBTITLES
    SheetMode.SPEED -> PlayerOptionCategory.SPEED
    SheetMode.ASPECT -> PlayerOptionCategory.ASPECT
    SheetMode.SLEEP -> PlayerOptionCategory.SLEEP
    SheetMode.RECORD -> PlayerOptionCategory.RECORD
    SheetMode.FAV -> PlayerOptionCategory.FAVORITES
    SheetMode.EXT -> PlayerOptionCategory.EXTERNAL
    SheetMode.MENU, SheetMode.CAST, SheetMode.LOOK -> null
}

/**
 * Whether closing the options sheet should bring the dock back.
 *
 * The dock is hidden when the sheet opens, so without this BACK drops the user
 * on a bare video surface with no visible focus at all — the brief's "focus must
 * never disappear", failing at the one moment a user is most likely to press
 * BACK.
 *
 * Gated on where the sheet was opened FROM. Arriving via the MENU key means
 * there was no dock on screen to return to, and conjuring one would be a
 * surprise rather than a restoration.
 */
internal fun shouldRestoreDockOnOptionsClose(openedFromDock: Boolean): Boolean = openedFromDock

/**
 * Surfaces that are pinned left-to-right regardless of locale.
 *
 * MK.31.2 fixed the seek KEYS as physical — LEFT rewinds in every locale — and
 * MK.34.9 extended that to the matching visuals, because a mirrored bar driven
 * by unmirrored keys means pressing LEFT makes the fill grow toward the press.
 *
 * The dock is included because -10 / +10 / next are directional controls bound
 * to a timeline that does not mirror. The brief's rule that icons must not
 * reverse unless their meaning is directional cuts this way: their meaning IS
 * directional, and the direction they refer to is the timeline's, not the
 * text's.
 *
 * Everything else — the Now Playing block, the options sheet — is text, and text
 * mirrors.
 */
internal enum class ChromeSurface {
    NOW_PLAYING,
    TIMELINE,
    CONTROL_DOCK,
    OPTIONS_SHEET,
}

internal fun pinsLeftToRight(surface: ChromeSurface): Boolean = when (surface) {
    ChromeSurface.TIMELINE, ChromeSurface.CONTROL_DOCK -> true
    ChromeSurface.NOW_PLAYING, ChromeSurface.OPTIONS_SHEET -> false
}
