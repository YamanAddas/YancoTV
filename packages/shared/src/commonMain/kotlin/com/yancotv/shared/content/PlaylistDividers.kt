package com.yancotv.shared.content

/**
 * Rows a playlist ships as *headings* rather than as channels.
 *
 * ### What these are
 *
 * A flat M3U has no notion of a section, so providers fake one by putting a
 * row in the list whose name is a banner:
 *
 * ```
 * ##### beIN SP⚽RTS ᴴᴰ #####
 * ###### RELAX ᵁᴴᴰ 3840P ######
 * ### ARABIC 24/7 4K UHD 3840P ###
 * ```
 *
 * They carry a stream URL like any other row — usually one that answers
 * nothing — because the format has nowhere else to put them. In a player
 * that groups by category, which is every player this codebase ships, they
 * are pure noise: the grouping already does their job.
 *
 * ### Why this is not cosmetic
 *
 * Measured on the owner's account, 2026-09-03: **909 of 273,869 rows** are
 * these, every one of them `live`. `isPlayable` says yes to all of them,
 * they sort to the front of a group, and the Home preview put one first —
 * so tapping the first channel on the screen opened a spinner that never
 * resolved. It cost forty-eight seconds of a recording test and a wrong
 * conclusion about the recorder before anyone looked at the name.
 *
 * ### The rule, and why it is this strict
 *
 * The title must **begin and end with a run of three or more of the same
 * separator character**. Both ends, three or more, and the same character:
 * anything looser starts eating real titles — "Ping-Pong -- Live" has a
 * run, "24/7 SINGER" has none, and a channel legitimately named with one
 * leading dash is not a heading.
 *
 * Verified against the whole catalogue: 909 matches, all of them banners,
 * and no title that a person would want to watch. Only `#` appears on this
 * provider; the rest of the set is what other playlists in the wild use,
 * and costs nothing to accept.
 */
private const val DIVIDER_CHARS = "#=*_~+-•▬═◆★"

private const val MIN_RUN = 3

fun isPlaylistDivider(rawTitle: String): Boolean {
    val title = rawTitle.trim()
    if (title.length < MIN_RUN * 2) return false
    val marker = title.first()
    if (marker !in DIVIDER_CHARS) return false

    var lead = 0
    while (lead < title.length && title[lead] == marker) lead++
    if (lead < MIN_RUN) return false

    var trail = 0
    while (trail < title.length && title[title.length - 1 - trail] == marker) trail++
    if (trail < MIN_RUN) return false

    // A row of nothing but the marker is a divider too — and the two runs
    // must not be the same run counted twice, which is what this catches.
    return lead + trail >= MIN_RUN * 2 || lead == title.length
}
