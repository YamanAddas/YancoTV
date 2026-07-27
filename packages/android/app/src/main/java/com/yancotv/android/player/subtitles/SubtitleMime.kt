package com.yancotv.android.player.subtitles

/**
 * MIME type for a subtitle file or URL, keyed off its extension.
 *
 * ExoPlayer will sniff an unlabelled subtitle, but an explicit type avoids
 * a mis-detect on the many IPTV providers that serve `.srt` as
 * `text/plain` — which ExoPlayer rejects rather than parses.
 *
 * SubRip is the fallback: it is what OpenSubtitles overwhelmingly serves
 * and what Xtream's `subtitles[]` URLs point at.
 *
 * Shared by the in-player subtitle search (`PlayerOptionsPanels`) and the
 * MK.29.3 pre-play picker (`PreviewSubtitlePicker`) so the two paths can't
 * disagree about a format.
 */
internal fun subtitleMimeFor(name: String): String = when {
    name.endsWith(".vtt", ignoreCase = true) -> "text/vtt"
    name.endsWith(".ass", ignoreCase = true) || name.endsWith(".ssa", ignoreCase = true) -> "text/x-ssa"
    name.endsWith(".ttml", ignoreCase = true) || name.endsWith(".dfxp", ignoreCase = true) -> "application/ttml+xml"
    else -> "application/x-subrip"
}
