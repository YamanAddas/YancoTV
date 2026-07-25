package com.yancotv.shared.parsers

import kotlinx.io.Source
import kotlinx.io.readLine

/**
 * Line splitting for [parseM3uLines] — the memory-critical half of the
 * MB-230 fix.
 *
 * Both sequences implement one rule set, pinned by `M3uParserTest`
 * (`handlesBomMarker`, `handlesCrlfLineEndings`, `handlesCrOnlyLineEndings`):
 *
 *  - `\r\n`, `\r` and `\n` each terminate a line. Old-Mac playlists are real;
 *    the pre-MB-230 parser normalised them with two whole-string `replace`
 *    passes, which is precisely the allocation this file exists to avoid.
 *  - A leading U+FEFF byte-order mark is dropped from the very first line only.
 *
 * The two sequences differ in one immaterial way: [m3uLineSequence] on a
 * `CharSequence` reproduces `split('\n')` exactly and therefore emits a final
 * empty element when the text ends with a terminator, while the [Source]
 * variant simply stops. The parser skips blank lines, so nothing downstream
 * can observe the difference.
 */

/** Byte-order mark. Emitted by Windows tooling; must not leak into `#EXTM3U`. */
private const val BOM = '﻿'

/** Drop a leading BOM. Only ever applied to the first line of a playlist. */
private fun stripBom(line: String): String = if (line.isNotEmpty() && line[0] == BOM) line.substring(1) else line

/**
 * Lazily split a [CharSequence] into lines without copying the text.
 *
 * `split('\n')` on a normalised copy allocated the entire playlist twice over
 * (once per `replace`) plus a list holding every line. This walks the original
 * with an index and allocates only the individual line strings, which the
 * parser was going to materialise regardless.
 */
internal fun CharSequence.m3uLineSequence(): Sequence<String> = sequence {
    val len = length
    if (len == 0) {
        // `"".split('\n')` yields [""] — preserve that so an empty playlist
        // takes the same path it always did.
        yield("")
        return@sequence
    }
    // Match the old `content[0].code == 0xFEFF` check: a BOM only counts at the
    // very start of the payload, not after an arbitrary line break.
    var i = if (this@m3uLineSequence[0] == BOM) 1 else 0
    var start = i
    while (i < len) {
        when (this@m3uLineSequence[i]) {
            '\n' -> {
                yield(substring(start, i))
                i++
                start = i
            }
            '\r' -> {
                yield(substring(start, i))
                i++
                // Collapse \r\n into one terminator; a lone \r stands alone.
                if (i < len && this@m3uLineSequence[i] == '\n') i++
                start = i
            }
            else -> i++
        }
    }
    // Trailing segment. When the text ends with a terminator this is the empty
    // string, which is what `split` would have produced.
    yield(substring(start, len))
}

/**
 * Lazily read lines from a byte [Source] — the path that actually fixes
 * MB-230, because the playlist text is never materialised at all.
 *
 * kotlinx-io's [readLine] splits on `\n` and strips one trailing `\r`, so Unix
 * and Windows endings are handled natively. An old-Mac `\r`-only playlist
 * arrives as a single long chunk, so each chunk is split again on `\r` to
 * reproduce the pre-MB-230 `replace("\r", "\n")` behaviour; for every
 * well-formed playlist that inner split sees one physical line.
 *
 * Single-pass: consume it inside the block that owns the [Source], because the
 * reads happen as the parser pulls.
 */
internal fun Source.m3uLineSequence(): Sequence<String> = sequence {
    var first = true
    while (true) {
        val raw = readLine() ?: break
        if (raw.indexOf('\r') < 0) {
            yield(if (first) stripBom(raw) else raw)
            first = false
        } else {
            for (part in raw.split('\r')) {
                yield(if (first) stripBom(part) else part)
                first = false
            }
        }
    }
}
