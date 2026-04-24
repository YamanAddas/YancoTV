package com.yancotv.shared.parsers

import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER

/**
 * Kotlin port of `@yancotv/core` `parsers/xmltv-parser.ts`. Uses an
 * indexOf-based scan (no regex backtracking, no DOM) matching the TS
 * implementation. Returns channels + programmes.
 *
 * DEVIATIONS from TS source:
 *  - Gzip/zlib byte decoding (pako) is NOT ported. commonMain has no stdlib
 *    deflate/gunzip and adding a multiplatform compression dep was out of
 *    scope for this port. `parseXmltv(ByteArray)` decodes UTF-8 only; callers
 *    must gunzip before invoking on Android (e.g. via java.util.zip on the
 *    Android actual side, or a platform-specific wrapper).
 *  - Event-loop yielding is absent. Kotlin coroutines can suspend naturally
 *    without the setTimeout(0) trick; callers run the parse inside
 *    withContext(Dispatchers.Default) if they need off-main-thread execution.
 */

// -----------------------------------------------------------------------------
// Public types
// -----------------------------------------------------------------------------

data class XmltvProgramme(
    val channelId: String,
    val title: String,
    val description: String? = null,
    /** Unix seconds. */
    val startTime: Long,
    /** Unix seconds. */
    val endTime: Long,
    val category: String? = null,
    val iconUrl: String? = null,
)

data class XmltvChannel(
    val id: String,
    val displayName: String? = null,
    val iconUrl: String? = null,
)

data class XmltvResult(
    val channels: List<XmltvChannel>,
    val programmes: List<XmltvProgramme>,
)

// -----------------------------------------------------------------------------
// Constants
// -----------------------------------------------------------------------------

private const val OPEN_PROG = "<programme "
private const val CLOSE_PROG = "</programme>"
private const val OPEN_CHAN = "<channel "
private const val CLOSE_CHAN = "</channel>"

// -----------------------------------------------------------------------------
// Public API
// -----------------------------------------------------------------------------

/** Parse an XMLTV string into channels + programmes. */
fun parseXmltvString(
    xml: String,
    logger: Logger = NOOP_LOGGER,
): XmltvResult {
    val channels = parseChannels(xml)
    val programmes = parseProgrammes(xml)
    logger.info("XMLTV parsed: ${channels.size} channels, ${programmes.size} programmes")
    return XmltvResult(channels = channels, programmes = programmes)
}

/** Entry point accepting a plain XML string. Convenience. */
fun parseXmltv(
    input: String,
    logger: Logger = NOOP_LOGGER,
): XmltvResult = parseXmltvString(input, logger)

/**
 * Entry point accepting a UTF-8 byte array. Gzip/zlib NOT supported —
 * caller must decompress first. See deviations note at top of file.
 */
fun parseXmltv(
    input: ByteArray,
    logger: Logger = NOOP_LOGGER,
): XmltvResult = parseXmltvString(input.decodeToString(), logger)

// -----------------------------------------------------------------------------
// XMLTV timestamp parsing — pure string math, no java.time/kotlinx.datetime.
// -----------------------------------------------------------------------------

private val TIMESTAMP_REGEX =
    Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\s*([+-]\d{4})?$""")

/**
 * Parse XMLTV timestamp: "YYYYMMDDHHmmss +HHMM" or "YYYYMMDDHHmmss".
 * Returns Unix seconds, or 0 if unparseable.
 */
fun parseXmltvTimestamp(ts: String): Long {
    if (ts.isEmpty()) return 0L
    val cleaned = ts.trim()
    val m = TIMESTAMP_REGEX.matchEntire(cleaned) ?: return 0L

    val year = m.groupValues[1].toInt()
    val month = m.groupValues[2].toInt()
    val day = m.groupValues[3].toInt()
    val hour = m.groupValues[4].toInt()
    val minute = m.groupValues[5].toInt()
    val second = m.groupValues[6].toInt()
    val offset = m.groupValues[7] // "+0200" / "-0500" / ""

    if (month < 1 || month > 12) return 0L
    if (day < 1 || day > 31) return 0L
    if (hour < 0 || hour > 23) return 0L
    if (minute < 0 || minute > 59) return 0L
    if (second < 0 || second > 59) return 0L
    if (day > daysInMonth(year, month)) return 0L

    // Epoch seconds from civil date (Howard Hinnant's algorithm).
    val epochDays = civilToDays(year, month, day)
    var epochSeconds = epochDays * 86_400L + hour * 3600L + minute * 60L + second.toLong()

    if (offset.isNotEmpty()) {
        val sign = if (offset[0] == '-') -1 else 1
        val offHours = offset.substring(1, 3).toInt()
        val offMinutes = offset.substring(3, 5).toInt()
        val offsetSeconds = sign * (offHours * 3600L + offMinutes * 60L)
        epochSeconds -= offsetSeconds
    }

    return epochSeconds
}

private fun isLeap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)

private fun daysInMonth(
    year: Int,
    month: Int,
): Int =
    when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeap(year)) 29 else 28
        else -> 0
    }

/**
 * Civil date -> days since 1970-01-01 (Howard Hinnant, public domain).
 * Works for any proleptic Gregorian date.
 */
private fun civilToDays(
    y: Int,
    m: Int,
    d: Int,
): Long {
    val year = if (m <= 2) y - 1 else y
    val era = (if (year >= 0) year else year - 399) / 400
    val yoe = (year - era * 400).toLong() // [0, 399]
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1 // [0, 365]
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy.toLong() // [0, 146096]
    return era.toLong() * 146_097L + doe - 719_468L
}

// -----------------------------------------------------------------------------
// Channel parsing
// -----------------------------------------------------------------------------

private fun parseChannels(xml: String): List<XmltvChannel> {
    val channels = mutableListOf<XmltvChannel>()
    var pos = 0

    while (true) {
        val start = xml.indexOf(OPEN_CHAN, pos)
        if (start == -1) break

        val attrEnd = xml.indexOf('>', start + OPEN_CHAN.length)
        if (attrEnd == -1) break

        val closeStart = xml.indexOf(CLOSE_CHAN, attrEnd + 1)
        if (closeStart == -1) break

        val attrs = xml.substring(start + OPEN_CHAN.length, attrEnd)
        val body = xml.substring(attrEnd + 1, closeStart)
        pos = closeStart + CLOSE_CHAN.length

        val id = extractAttrFast(attrs, "id")
        if (id.isNullOrEmpty()) continue

        channels.add(
            XmltvChannel(
                id = id,
                displayName = extractTagFast(body, "display-name"),
                iconUrl = extractAttrFromTag(body, "icon", "src"),
            ),
        )
    }
    return channels
}

// -----------------------------------------------------------------------------
// Programme parsing
// -----------------------------------------------------------------------------

private fun parseProgrammes(xml: String): List<XmltvProgramme> {
    val programmes = mutableListOf<XmltvProgramme>()
    var pos = 0

    while (true) {
        val start = xml.indexOf(OPEN_PROG, pos)
        if (start == -1) break

        val attrEnd = xml.indexOf('>', start + OPEN_PROG.length)
        if (attrEnd == -1) break

        // Skip self-closing <programme .../>.
        if (xml[attrEnd - 1] == '/') {
            pos = attrEnd + 1
            continue
        }

        val closeStart = xml.indexOf(CLOSE_PROG, attrEnd + 1)
        if (closeStart == -1) break

        val attrs = xml.substring(start + OPEN_PROG.length, attrEnd)
        val body = xml.substring(attrEnd + 1, closeStart)
        pos = closeStart + CLOSE_PROG.length

        val channelId = extractAttrFast(attrs, "channel")
        val startStr = extractAttrFast(attrs, "start")
        val stopStr = extractAttrFast(attrs, "stop")

        if (channelId.isNullOrEmpty() || startStr.isNullOrEmpty() || stopStr.isNullOrEmpty()) continue

        val startTime = parseXmltvTimestamp(startStr)
        val endTime = parseXmltvTimestamp(stopStr)
        if (startTime == 0L || endTime == 0L) continue

        val title = extractTagFast(body, "title")
        if (title.isNullOrEmpty()) continue

        programmes.add(
            XmltvProgramme(
                channelId = channelId,
                title = title,
                description = extractTagFast(body, "desc"),
                startTime = startTime,
                endTime = endTime,
                category = extractTagFast(body, "category"),
                iconUrl = extractAttrFromTag(body, "icon", "src"),
            ),
        )
    }
    return programmes
}

// -----------------------------------------------------------------------------
// Tag / attribute extraction helpers
// -----------------------------------------------------------------------------

/** Extract an attribute value from an attribute-string slice. */
private fun extractAttrFast(
    attrs: String,
    name: String,
): String? {
    val search = "$name=\""
    val idx = attrs.indexOf(search)
    if (idx == -1) return null
    val valStart = idx + search.length
    val valEnd = attrs.indexOf('"', valStart)
    if (valEnd == -1) return null
    return decodeXmlEntities(attrs.substring(valStart, valEnd))
}

/** Extract the text content of the first matching element. */
private fun extractTagFast(
    body: String,
    tagName: String,
): String? {
    val open = "<$tagName"
    val close = "</$tagName>"

    val openIdx = body.indexOf(open)
    if (openIdx == -1) return null

    val gtIdx = body.indexOf('>', openIdx + open.length)
    if (gtIdx == -1) return null

    if (body[gtIdx - 1] == '/') return null // self-closing

    val closeIdx = body.indexOf(close, gtIdx + 1)
    if (closeIdx == -1) return null

    return decodeXmlEntities(body.substring(gtIdx + 1, closeIdx).trim())
}

/** Extract an attribute from a child tag within a body string. */
private fun extractAttrFromTag(
    body: String,
    tagName: String,
    attrName: String,
): String? {
    val open = "<$tagName"
    val idx = body.indexOf(open)
    if (idx == -1) return null
    val tagEnd = body.indexOf('>', idx + open.length)
    if (tagEnd == -1) return null
    return extractAttrFast(body.substring(idx + open.length, tagEnd), attrName)
}

// -----------------------------------------------------------------------------
// XML entity decoding
// -----------------------------------------------------------------------------

private val NAMED_ENTITIES: Map<String, String> =
    mapOf(
        // XML-defined
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        // Whitespace & punctuation
        "nbsp" to "\u00A0",
        "ensp" to "\u2002",
        "emsp" to "\u2003",
        "thinsp" to "\u2009",
        "ndash" to "\u2013",
        "mdash" to "\u2014",
        "hellip" to "\u2026",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "laquo" to "\u00AB",
        "raquo" to "\u00BB",
        "middot" to "\u00B7",
        "bull" to "\u2022",
        // Symbols
        "copy" to "\u00A9",
        "reg" to "\u00AE",
        "trade" to "\u2122",
        "deg" to "\u00B0",
        "plusmn" to "\u00B1",
        "times" to "\u00D7",
        "divide" to "\u00F7",
        "pound" to "\u00A3",
        "euro" to "\u20AC",
        "yen" to "\u00A5",
        "cent" to "\u00A2",
        "sect" to "\u00A7",
        "para" to "\u00B6",
        // Latin-1 accents
        "agrave" to "\u00E0",
        "aacute" to "\u00E1",
        "acirc" to "\u00E2",
        "atilde" to "\u00E3",
        "auml" to "\u00E4",
        "aring" to "\u00E5",
        "aelig" to "\u00E6",
        "ccedil" to "\u00E7",
        "egrave" to "\u00E8",
        "eacute" to "\u00E9",
        "ecirc" to "\u00EA",
        "euml" to "\u00EB",
        "igrave" to "\u00EC",
        "iacute" to "\u00ED",
        "icirc" to "\u00EE",
        "iuml" to "\u00EF",
        "ntilde" to "\u00F1",
        "ograve" to "\u00F2",
        "oacute" to "\u00F3",
        "ocirc" to "\u00F4",
        "otilde" to "\u00F5",
        "ouml" to "\u00F6",
        "oslash" to "\u00F8",
        "ugrave" to "\u00F9",
        "uacute" to "\u00FA",
        "ucirc" to "\u00FB",
        "uuml" to "\u00FC",
        "yacute" to "\u00FD",
        "szlig" to "\u00DF",
        "Agrave" to "\u00C0",
        "Aacute" to "\u00C1",
        "Acirc" to "\u00C2",
        "Atilde" to "\u00C3",
        "Auml" to "\u00C4",
        "Aring" to "\u00C5",
        "AElig" to "\u00C6",
        "Ccedil" to "\u00C7",
        "Egrave" to "\u00C8",
        "Eacute" to "\u00C9",
        "Ecirc" to "\u00CA",
        "Euml" to "\u00CB",
        "Ntilde" to "\u00D1",
        "Oacute" to "\u00D3",
        "Ouml" to "\u00D6",
        "Uuml" to "\u00DC",
    )

private val ENTITY_REGEX = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z][a-zA-Z0-9]*);""")

/**
 * Decode XML/HTML character entities. Fast-path when no '&' present.
 * Handles named entities, decimal (&#160;), and hex (&#xA0;).
 * Unknown named entities pass through unchanged.
 */
private fun decodeXmlEntities(str: String): String {
    if (!str.contains('&')) return str
    return ENTITY_REGEX.replace(str) { match ->
        val ref = match.groupValues[1]
        if (ref[0] == '#') {
            val code =
                if (ref.length >= 2 && (ref[1] == 'x' || ref[1] == 'X')) {
                    ref.substring(2).toIntOrNull(16)
                } else {
                    ref.substring(1).toIntOrNull(10)
                }
            if (code == null || code < 0 || code > 0x10FFFF) {
                match.value
            } else {
                try {
                    codePointToString(code)
                } catch (_: Throwable) {
                    match.value
                }
            }
        } else {
            NAMED_ENTITIES[ref] ?: match.value
        }
    }
}

/** Mirrors TS `String.fromCodePoint`. Handles BMP + supplementary via surrogate pairs. */
private fun codePointToString(code: Int): String {
    if (code < 0 || code > 0x10FFFF) throw IllegalArgumentException("invalid code point")
    if (code <= 0xFFFF) return code.toChar().toString()
    val offset = code - 0x10000
    val high = 0xD800 + (offset shr 10)
    val low = 0xDC00 + (offset and 0x3FF)
    return charArrayOf(high.toChar(), low.toChar()).concatToString()
}
