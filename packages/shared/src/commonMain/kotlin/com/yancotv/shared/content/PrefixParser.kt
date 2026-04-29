package com.yancotv.shared.content

/**
 * MK.20.2 — Pure parser for IPTV-style group-name prefixes.
 *
 * Real-world M3U group_name shapes seen in the field:
 *   "AR| Sports"               pipe-delimited, code first
 *   "|AR| Sports"              double-pipe wrapper
 *   "[AR] Sports"              bracket
 *   "AR - Sports"              dash (en/em-dash too)
 *   "AR: Sports"               colon
 *   "AR Sports"                space-only (matched only when next token is
 *                              uppercase so we don't eat "US Open" → "Open")
 *   "Arabic | Sports"          full-word, looked up via reverse catalog
 *   "Saudi Arabia | beIN HD"   multi-word full-name (MK.20 polish-sweep)
 *   "Sports"                   unprefixed → null prefix
 *
 * Returns a [ParsedGroup] with the original name, the detected (uppercase)
 * code or full-word match, the catalog [PrefixCatalog.Entry] when resolvable,
 * and the trimmed remainder. Pure / no I/O so iOS shares it.
 */
data class ParsedGroup(val originalName: String, val prefix: String?, val resolved: PrefixCatalog.Entry?, val remainder: String)

object PrefixParser {
    // 2–3 letter code at start, optionally wrapped by a leading pipe/bracket,
    // followed by a structural delimiter (pipe, bracket-close, colon, dash).
    // Group 1: the code. Group 2: trailing remainder.
    private val codeRegex =
        Regex(
            "^\\|?\\s*\\[?\\s*([A-Za-z]{2,3})\\s*[]\\\\|:\\-\\u2013\\u2014]\\s*(.*)$",
        )

    // Code followed by a single space and then an uppercase letter — the
    // safer space-only prefix shape ("AR Sports" yes; "ar sports" no, since
    // the latter is just a lowercase title that happens to start with two
    // letters that look like a code).
    private val codeSpaceRegex =
        Regex("^([A-Z]{2,3})\\s+([A-Z].*)$")

    fun parse(groupName: String): ParsedGroup {
        val raw = groupName.trim()
        if (raw.isEmpty()) return ParsedGroup(groupName, null, null, "")

        // 1) Try delimiter-based code match (covers AR|, [AR], AR -, AR:, |AR| …).
        codeRegex.matchEntire(raw)?.let { m ->
            val code = m.groupValues[1]
            val rest = m.groupValues[2].trim().trimStart('|', ']').trim()
            val resolved = PrefixCatalog.resolve(code)
            if (resolved != null) {
                return ParsedGroup(groupName, code.uppercase(), resolved, rest.ifEmpty { code.uppercase() })
            }
        }

        // 2) Try full-word match. Find the first structural delimiter and
        //    look up everything before it in the catalog by displayName
        //    (case-insensitive). Handles single-word (`Arabic | Foo`) AND
        //    multi-word (`Saudi Arabia | Foo`, `Hong Kong | Foo`,
        //    `South Korea - News`) — the regex-based version this replaced
        //    only matched a single `[A-Za-zÀ-ÿ]{3,}` token before the
        //    delimiter, so any multi-word name fell through silently even
        //    though [PrefixCatalog] already knew them.
        //
        //    Hyphen handling is tricky: catalog displayNames don't contain
        //    `-`, so any hyphen in `raw` is a structural delimiter. If
        //    that ever changes (e.g. `Bosnia-Herzegovina`), prefer the
        //    delimiter set `[|:]` and add explicit handling for the new
        //    name shape.
        val delimChars = charArrayOf('|', ':', '-', '–', '—')
        val firstDelim = raw.indexOfAny(delimChars)
        if (firstDelim > 0) {
            val before = raw.substring(0, firstDelim).trim()
            val rest = raw.substring(firstDelim + 1).trim()
            val resolved = PrefixCatalog.resolveByDisplayName(before)
            if (resolved != null) {
                return ParsedGroup(groupName, before, resolved, rest.ifEmpty { resolved.displayName })
            }
        }

        // 3) Space-only code (uppercase code + uppercase next token).
        codeSpaceRegex.matchEntire(raw)?.let { m ->
            val code = m.groupValues[1]
            val rest = m.groupValues[2].trim()
            val resolved = PrefixCatalog.resolve(code)
            if (resolved != null) {
                return ParsedGroup(groupName, code, resolved, rest)
            }
        }

        return ParsedGroup(groupName, null, null, raw)
    }
}
