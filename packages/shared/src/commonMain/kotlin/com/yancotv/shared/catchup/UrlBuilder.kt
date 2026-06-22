package com.yancotv.shared.catchup

import com.yancotv.shared.util.civilFromEpochSeconds

/**
 * Catch-up URL builders. Pure — no DB, no credential storage, no network.
 * Mirrors `@yancotv/core` `catchup/url-builder.ts`.
 */

/**
 * Build Xtream Codes timeshift/catch-up URL.
 *
 * Xtream standard format:
 *   {baseUrl}/timeshift/{username}/{password}/{duration}/{start}/{streamId}.ts
 */
fun buildXtreamTimeshiftUrl(
    baseUrl: String,
    username: String,
    password: String,
    originalStreamUrl: String,
    programmeStart: Long,
    programmeDuration: Long,
    /**
     * `catchup-correction` value from the EXTINF line (HOURS). Offsets
     * [programmeStart] when computing the civil-time URL components so
     * providers whose recording archive doesn't line up with the EPG
     * (DST boundary, reseller offset, provider-side TZ misconfig)
     * play the right slot. Null / 0 → no offset. Audit catch.
     */
    correctionHours: Double? = null,
): String {
    // Strip query/fragment before extracting the numeric stream id.
    // Xtream live URLs frequently include `?token=...` / `?wmsAuthSign=...`
    // / `#frag` suffixes; the `$` anchor in the regex would otherwise
    // miss those entirely and `streamId` would fall back to "0",
    // producing a timeshift URL like `.../0.ts` that the provider 404s
    // with no user-visible clue. Audit catch.
    val cleanUrl = originalStreamUrl.substringBefore('?').substringBefore('#')
    val streamIdMatch = Regex("""/(\d+)\.\w+$""").find(cleanUrl)
    val streamId = streamIdMatch?.groupValues?.get(1) ?: "0"

    val correctionSec = ((correctionHours ?: 0.0) * 3600.0).toLong()
    val civil = civilFromEpochSeconds(programmeStart + correctionSec)
    val month = civil.month.toString().padStart(2, '0')
    val day = civil.day.toString().padStart(2, '0')
    val hours = civil.hour.toString().padStart(2, '0')
    val minutes = civil.minute.toString().padStart(2, '0')
    val startStr = "${civil.year}-$month-$day:$hours-$minutes"

    // Duration in minutes, rounded up
    val durationMins = ((programmeDuration + 59) / 60)

    return "$baseUrl/timeshift/$username/$password/$durationMins/$startStr/$streamId.ts"
}

/**
 * Build catch-up URL for M3U sources using catchup-source patterns.
 *
 * `metadata` is a loosely-typed map mirroring the TS `Record<string, unknown>`.
 * Supported keys: `catchupSource`, `catchupType`.
 */
fun buildM3uCatchupUrl(
    originalUrl: String,
    metadata: Map<String, Any?>,
    programmeStart: Long,
    programmeDuration: Long,
    /** Current time in Unix seconds, used for `shift`-style catch-up. Callers pass `System.currentTimeMillis()/1000` or platform equivalent. */
    nowSecs: Long,
): String? {
    val catchupSource = metadata["catchupSource"]?.toString() ?: ""
    val catchupType = metadata["catchupType"]?.toString() ?: ""
    val catchupCorrectionHours = (metadata["catchupCorrection"] as? Number)?.toDouble() ?: 0.0
    val correctionSec = (catchupCorrectionHours * 3600.0).toLong()
    val correctedStart = programmeStart + correctionSec

    if (catchupSource.isEmpty() && catchupType.isEmpty()) return null

    var template = catchupSource.ifEmpty { originalUrl }

    if (catchupType == "append" && catchupSource.isEmpty()) {
        return "$originalUrl?utc=$correctedStart&lutc=$correctedStart&duration=$programmeDuration"
    }

    if (catchupType == "shift" && catchupSource.isEmpty()) {
        val shift = nowSecs - correctedStart
        return "$originalUrl?utc=$correctedStart&lutc=$correctedStart&shift=$shift"
    }

    val civil = civilFromEpochSeconds(correctedStart)

    template =
        template
            .replace(Regex("""\{start\}"""), correctedStart.toString())
            .replace(Regex("""\{end\}"""), (correctedStart + programmeDuration).toString())
            .replace(Regex("""\{duration\}"""), programmeDuration.toString())
            .replace(Regex("""\{timestamp\}"""), correctedStart.toString())
            .replace(Regex("""\{utc\}"""), correctedStart.toString())
            .replace(Regex("""\{lutc\}"""), correctedStart.toString())
            .replace(Regex("""\{Y\}"""), civil.year.toString())
            .replace(Regex("""\{m\}"""), civil.month.toString().padStart(2, '0'))
            .replace(Regex("""\{d\}"""), civil.day.toString().padStart(2, '0'))
            .replace(Regex("""\{H\}"""), civil.hour.toString().padStart(2, '0'))
            .replace(Regex("""\{M\}"""), civil.minute.toString().padStart(2, '0'))
            .replace(Regex("""\{S\}"""), civil.second.toString().padStart(2, '0'))

    // Strip query/fragment so URLs with `?token=...` / `?wmsAuthSign=...`
    // resolve `{stream_id}` against the numeric id rather than silently
    // skipping the substitution.
    val cleanOriginal = originalUrl.substringBefore('?').substringBefore('#')
    val streamIdMatch = Regex("""/(\d+)\.\w+$""").find(cleanOriginal)
    if (streamIdMatch != null) {
        template = template.replace(Regex("""\{stream_id\}"""), streamIdMatch.groupValues[1])
    }

    return template
}
