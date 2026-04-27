package com.yancotv.shared.content

/**
 * MK.20.2 — Pure parser for IPTV-style group-name prefixes.
 *
 * Real-world M3U group_name shapes seen in the field:
 *   "AR| Sports"           pipe-delimited, code first
 *   "|AR| Sports"          double-pipe wrapper
 *   "[AR] Sports"          bracket
 *   "AR - Sports"          dash (en/em-dash too)
 *   "AR: Sports"           colon
 *   "AR Sports"            space-only (matched only when next token is uppercase
 *                          so we don't eat "US Open" → "Open")
 *   "Arabic | Sports"      full-word, looked up via reverse catalog
 *   "Sports"               unprefixed → null prefix
 *
 * Returns a [ParsedGroup] with the original name, the detected (uppercase)
 * code or full-word match, the catalog [PrefixCatalog.Entry] when resolvable,
 * and the trimmed remainder. Pure / no I/O so iOS shares it.
 */
data class ParsedGroup(
    val originalName: String,
    val prefix: String?,
    val resolved: PrefixCatalog.Entry?,
    val remainder: String,
)

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

        // 2) Try full-word match (Arabic | Foo, English - Foo, …).
        val wordMatch =
            Regex("^([A-Za-zÀ-ÿ]{3,})\\s*[\\|:\\-\\u2013\\u2014]\\s*(.*)$").matchEntire(raw)
        if (wordMatch != null) {
            val word = wordMatch.groupValues[1]
            val rest = wordMatch.groupValues[2].trim()
            val resolved = PrefixCatalog.resolveByDisplayName(word)
            if (resolved != null) {
                return ParsedGroup(groupName, word, resolved, rest.ifEmpty { resolved.displayName })
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
