package com.yancotv.shared.content

/**
 * MK.33.1 — selection keys for a group that belongs to one specific playlist.
 *
 * ### Why an encoded string and not a sealed type
 *
 * The shell already addresses a category selection with a single `String`, and
 * already reserves two synthetic values for the non-group cases (`__all__`,
 * `__favorites__` in `BrowseShell.kt`). That one string is what the category
 * rail compares for the active row, what the chip bar compares, and what
 * `rememberSaveable` persists across process death.
 *
 * Introducing a sealed selection type would have been cleaner in isolation, and
 * it would also have meant a custom `Saver`, a new comparison path in the rail
 * AND the chip bar, and a signature change on every screen that passes the
 * selection down. Extending the reserved-sentinel convention that is already
 * there keeps that surface untouched: the rail's `Leaf` already separates
 * `label` (what the user reads) from `groupName` (the selection key), so a
 * scoped leaf is a normal leaf with an encoded key.
 *
 * The correctness therefore lives in one pure encode/decode pair, which is
 * unit-tested, rather than spread across six call sites.
 *
 * ### Why this separator
 *
 * [SEP] is U+001F INFORMATION SEPARATOR ONE. Group names come from providers
 * and can contain essentially any printable character — `|`, `:`, `/` and `-`
 * are all common in real M3U group titles, which rules them out. A C0 control
 * character cannot survive the M3U / Xtream parse path into a `group_name`, so
 * it is the one class of character that is safe to reserve.
 *
 * Decoding is deliberately total: anything that is not a well-formed scoped key
 * decodes as unscoped rather than throwing. A malformed key means the user sees
 * an unscoped filter, which is the pre-MK.33 behaviour — not a crash on a
 * screen they cannot leave.
 */
object SourceScopedGroup {
    /** Marks a key as carrying a source id. Matches the existing `__x__` style. */
    const val PREFIX = "__src__"

    /**
     * U+001F INFORMATION SEPARATOR ONE — see the class doc.
     *
     * Written as an escape, not a literal: as a literal it is an invisible
     * byte in the source file, which makes this line unreviewable in a diff
     * and trivial to mangle with a copy-paste or an encoding-unaware tool.
     */
    const val SEP = '\u001F'

    /**
     * A group under one specific playlist.
     *
     * @param sourceId a `sources.id` — a UUID we generate, so it never contains
     *   [SEP] itself.
     */
    fun encode(sourceId: String, groupName: String): String = "$PREFIX$sourceId$SEP$groupName"

    /** The whole of one playlist for the current type — no group filter. */
    fun encodeWholeSource(sourceId: String): String = "$PREFIX$sourceId$SEP"

    /**
     * Decode a selection key.
     *
     * Returns null when [key] is not a scoped key at all (a plain group name, or
     * one of the shell's synthetic sentinels), which the caller treats as "no
     * source filter".
     */
    fun decode(key: String): Scoped? {
        if (!key.startsWith(PREFIX)) return null
        val body = key.substring(PREFIX.length)
        val sep = body.indexOf(SEP)
        // No separator at all is malformed — not a scoped key we wrote.
        if (sep < 0) return null
        val sourceId = body.substring(0, sep)
        if (sourceId.isEmpty()) return null
        val group = body.substring(sep + 1)
        // An empty group half means "the whole playlist", which is what
        // encodeWholeSource writes.
        return Scoped(sourceId = sourceId, groupName = group.takeIf { it.isNotEmpty() })
    }

    /**
     * A decoded scoped selection.
     *
     * @param groupName null when the selection is the whole playlist rather than
     *   one of its groups.
     */
    data class Scoped(val sourceId: String, val groupName: String?)
}
