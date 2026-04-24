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
): String {
    val streamIdMatch = Regex("""/(\d+)\.\w+$""").find(originalStreamUrl)
    val streamId = streamIdMatch?.groupValues?.get(1) ?: "0"

    val civil = civilFromEpochSeconds(programmeStart)
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

    if (catchupSource.isEmpty() && catchupType.isEmpty()) return null

    var template = catchupSource.ifEmpty { originalUrl }

    if (catchupType == "append" && catchupSource.isEmpty()) {
        return "$originalUrl?utc=$programmeStart&lutc=$programmeStart&duration=$programmeDuration"
    }

    if (catchupType == "shift" && catchupSource.isEmpty()) {
        val shift = nowSecs - programmeStart
        return "$originalUrl?utc=$programmeStart&lutc=$programmeStart&shift=$shift"
    }

    val civil = civilFromEpochSeconds(programmeStart)

    template =
        template
            .replace(Regex("""\{start\}"""), programmeStart.toString())
            .replace(Regex("""\{end\}"""), (programmeStart + programmeDuration).toString())
            .replace(Regex("""\{duration\}"""), programmeDuration.toString())
            .replace(Regex("""\{timestamp\}"""), programmeStart.toString())
            .replace(Regex("""\{utc\}"""), programmeStart.toString())
            .replace(Regex("""\{lutc\}"""), programmeStart.toString())
            .replace(Regex("""\{Y\}"""), civil.year.toString())
            .replace(Regex("""\{m\}"""), civil.month.toString().padStart(2, '0'))
            .replace(Regex("""\{d\}"""), civil.day.toString().padStart(2, '0'))
            .replace(Regex("""\{H\}"""), civil.hour.toString().padStart(2, '0'))
            .replace(Regex("""\{M\}"""), civil.minute.toString().padStart(2, '0'))
            .replace(Regex("""\{S\}"""), civil.second.toString().padStart(2, '0'))

    val streamIdMatch = Regex("""/(\d+)\.\w+$""").find(originalUrl)
    if (streamIdMatch != null) {
        template = template.replace(Regex("""\{stream_id\}"""), streamIdMatch.groupValues[1])
    }

    return template
}
