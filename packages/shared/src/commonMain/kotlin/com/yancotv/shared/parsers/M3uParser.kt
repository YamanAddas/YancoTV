package com.yancotv.shared.parsers

import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER

/**
 * Kotlin port of `@yancotv/core` `m3u-parser.ts`. Behavior parity is required —
 * tests in commonTest mirror `tests/unit/m3u-parser.test.ts`.
 */
data class M3uEntry(
    val duration: Double,
    val title: String,
    val groupTitle: String,
    val tvgId: String,
    val tvgName: String,
    val tvgLogo: String,
    val streamUrl: String,
    val rawAttributes: String,
    val catchupType: String? = null,
    val catchupSource: String? = null,
    val catchupDays: Int? = null,
    /**
     * `catchup-correction="-1.5"` (offset in HOURS). Some providers'
     * recording archives don't line up with the EPG (DST, provider-side
     * TZ misconfig, reseller offset). Player libraries (TiviMate /
     * IPTVnator / Kodi PVR) read this attribute and shift the programme
     * start by `correction * 3600 s` when computing the catch-up URL.
     */
    val catchupCorrection: Double? = null,
)

data class M3uParseResult(
    val entries: List<M3uEntry>,
    /** EPG URL extracted from the #EXTM3U url-tvg / x-tvg-url header attribute. */
    val epgUrl: String? = null,
)

/**
 * Parse a playlist held entirely in memory.
 *
 * **Prefer [parseM3uLines] with a streamed line source for anything
 * provider-sized** — see MB-230. This overload exists for tests, for the
 * TypeScript-parity corpus, and for callers that already hold the text.
 *
 * Handles: BOM markers, Windows/Unix/old-Mac line endings, empty lines,
 * malformed entries, duplicate URL collapse, and common provider quirks.
 */
fun parseM3u(content: String, logger: Logger = NOOP_LOGGER): M3uParseResult = parseM3uLines(content.m3uLineSequence(), logger)

/**
 * Genuinely streaming M3U parser: consumes [lines] lazily and never holds
 * the playlist text.
 *
 * **MB-230 (Critical, 2026-07-25).** The previous implementation took the
 * whole playlist as one `String` and then did
 * `substring` → `replace("\r\n","\n")` → `replace("\r","\n")` → `split('\n')`.
 * Every step allocates a *complete additional copy*, and JVM/ART strings are
 * UTF-16, so a playlist weighing N bytes on the wire peaked at roughly 8-10 N
 * of live heap — all of it reachable at once. On the reported install
 * (255,843 entries) that is several hundred MB against a Fire TV Stick's
 * 384 MB `largeHeap` budget, which is the runaway-GC signature captured in
 * bugs.md: heap pinned at 376/384 MB, 101 consecutive GCs freeing 0 bytes.
 *
 * The line-splitting rules are unchanged and pinned by [M3uParserTest]
 * (`handlesBomMarker`, `handlesCrlfLineEndings`, `handlesCrOnlyLineEndings`):
 * `\r\n`, `\r` and `\n` all terminate a line, and a leading U+FEFF is dropped.
 *
 * What still scales with catalogue size is the returned [M3uParseResult] —
 * one [M3uEntry] per unique stream URL, plus the URL index used to collapse
 * duplicates. That is inherent to the de-duplication contract (a later titled
 * entry replaces an earlier bare-URL one), it is an order of magnitude below
 * the text copies it replaces, and it is what [com.yancotv.shared.sources.BulkContentWriter]
 * consumes in chunks.
 */
fun parseM3uLines(lines: Sequence<String>, logger: Logger = NOOP_LOGGER): M3uParseResult {
    val entries = mutableListOf<M3uEntry>()
    // streamUrl -> index; titled entries replace bare URL entries on collision.
    val urlIndex = mutableMapOf<String, Int>()
    var duplicates = 0
    var epgUrl: String? = null

    var currentEntry: PartialEntry? = null

    fun addOrReplace(entry: M3uEntry, hasTitle: Boolean) {
        val existingIdx = urlIndex[entry.streamUrl]
        if (existingIdx == null) {
            urlIndex[entry.streamUrl] = entries.size
            entries.add(entry)
            return
        }
        duplicates++
        val existing = entries[existingIdx]
        if (hasTitle && existing.title.isEmpty()) {
            entries[existingIdx] = entry
        }
    }

    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) continue

        if (line.startsWith("#EXTM3U")) {
            epgUrl = extractAttribute(line, "url-tvg").ifEmpty { null }
                ?: extractAttribute(line, "x-tvg-url").ifEmpty { null }
            if (epgUrl != null) logger.info("M3U header contains EPG URL: $epgUrl")
            continue
        }

        if (line.startsWith("#EXTINF:")) {
            currentEntry = parseExtinfLine(line)
            continue
        }

        if (line.startsWith("#")) continue

        val ce = currentEntry
        if (ce != null) {
            addOrReplace(
                M3uEntry(
                    duration = ce.duration ?: -1.0,
                    title = ce.title ?: "",
                    groupTitle = ce.groupTitle ?: "",
                    tvgId = ce.tvgId ?: "",
                    tvgName = ce.tvgName ?: "",
                    tvgLogo = ce.tvgLogo ?: "",
                    streamUrl = line,
                    rawAttributes = ce.rawAttributes ?: "",
                    catchupType = ce.catchupType,
                    catchupSource = ce.catchupSource,
                    catchupDays = ce.catchupDays,
                    catchupCorrection = ce.catchupCorrection,
                ),
                hasTitle = !ce.title.isNullOrEmpty(),
            )
            currentEntry = null
        } else {
            addOrReplace(
                M3uEntry(
                    duration = -1.0,
                    title = extractTitleFromUrl(line),
                    groupTitle = "",
                    tvgId = "",
                    tvgName = "",
                    tvgLogo = "",
                    streamUrl = line,
                    rawAttributes = "",
                ),
                hasTitle = false,
            )
        }
    }

    if (duplicates > 0) {
        logger.warn("Parsed M3U: ${entries.size} unique entries ($duplicates duplicate URLs collapsed)")
    } else {
        logger.info("Parsed ${entries.size} entries from M3U")
    }
    return M3uParseResult(entries = entries.toList(), epgUrl = epgUrl)
}

private class PartialEntry(
    var duration: Double? = null,
    var title: String? = null,
    var groupTitle: String? = null,
    var tvgId: String? = null,
    var tvgName: String? = null,
    var tvgLogo: String? = null,
    var rawAttributes: String? = null,
    var catchupType: String? = null,
    var catchupSource: String? = null,
    var catchupDays: Int? = null,
    var catchupCorrection: Double? = null,
)

private val DURATION_REGEX = Regex("""^(-?\d+(?:\.\d+)?)""")

private fun parseExtinfLine(line: String): PartialEntry {
    val entry = PartialEntry()
    val afterPrefix = line.substring(8) // drop "#EXTINF:"

    val dm = DURATION_REGEX.find(afterPrefix)
    entry.duration = dm?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.0

    entry.tvgId = extractAttribute(line, "tvg-id")
    entry.tvgName = extractAttribute(line, "tvg-name")
    entry.tvgLogo = extractAttribute(line, "tvg-logo")
    entry.groupTitle = extractAttribute(line, "group-title")
    entry.rawAttributes = afterPrefix

    val catchupType = extractAttribute(line, "catchup").ifEmpty { extractAttribute(line, "catchup-type") }
    if (catchupType.isNotEmpty()) entry.catchupType = catchupType
    val catchupSource = extractAttribute(line, "catchup-source")
    if (catchupSource.isNotEmpty()) entry.catchupSource = catchupSource
    val catchupDaysRaw = extractAttribute(line, "catchup-days").ifEmpty { extractAttribute(line, "tvg-rec") }
    if (catchupDaysRaw.isNotEmpty()) entry.catchupDays = catchupDaysRaw.toIntOrNull()
    // `catchup-correction="-1.5"` — offset in HOURS to apply when
    // computing the catch-up URL. Honoured by TiviMate / IPTVnator /
    // Kodi PVR; without it providers whose archives don't line up
    // with the EPG play the wrong slot silently. Audit catch.
    val catchupCorrectionRaw = extractAttribute(line, "catchup-correction")
    if (catchupCorrectionRaw.isNotEmpty()) entry.catchupCorrection = catchupCorrectionRaw.toDoubleOrNull()

    val lastComma = line.lastIndexOf(',')
    entry.title = if (lastComma != -1) line.substring(lastComma + 1).trim() else ""
    return entry
}

private fun extractAttribute(line: String, attr: String): String {
    val escaped = Regex.escape(attr)
    val dbl = Regex("$escaped=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)
    if (dbl != null) return dbl.groupValues[1]
    val sgl = Regex("$escaped='([^']*)'", RegexOption.IGNORE_CASE).find(line)
    if (sgl != null) return sgl.groupValues[1]
    return ""
}

private val URL_SCHEME_REGEX = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://""")

private fun extractTitleFromUrl(url: String): String {
    // Mirrors TS `new URL(url).pathname` fallback. If it has no scheme we
    // fall through to the last-segment path, matching the TS catch branch.
    val hasScheme = URL_SCHEME_REGEX.containsMatchIn(url)
    if (hasScheme) {
        // Find path portion after the authority.
        val afterScheme = url.substringAfter("://", "")
        val pathStart = afterScheme.indexOf('/')
        val pathname = if (pathStart >= 0) afterScheme.substring(pathStart) else ""
        // Strip query/fragment.
        val clean = pathname.substringBefore('?').substringBefore('#')
        val filename = clean.substringAfterLast('/', "")
        return filename.replace(Regex("""\.[^.]+$"""), "").replace(Regex("""[_\-]"""), " ")
    }
    return url.substringAfterLast('/', "").ifEmpty { "Unknown" }
}
