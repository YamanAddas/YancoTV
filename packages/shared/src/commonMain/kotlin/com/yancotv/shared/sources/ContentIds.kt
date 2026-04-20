package com.yancotv.shared.sources

/**
 * Stable content-id helpers. Content rows key favorites / history / reminders,
 * so IDs must survive re-sync when title + stream URL are unchanged — using
 * array-index alone would invalidate all favorites the moment the provider
 * shuffles their playlist.
 *
 * We use a simple FNV-1a 32-bit hash of `"$title|$streamUrl"` per entry. The
 * final ID is `"$sourceId-$hash"`, which is:
 *   - deterministic (re-sync is a no-op for favorites)
 *   - scoped per-source (two sources with the same stream URL don't collide)
 *   - compact enough to index cheaply
 */
internal object ContentIds {
    fun m3u(sourceId: String, title: String, streamUrl: String): String =
        "$sourceId-${fnv1a("$title|$streamUrl")}"

    fun xtreamLive(sourceId: String, streamId: String): String =
        "$sourceId-xt-live-$streamId"

    fun xtreamVod(sourceId: String, streamId: String): String =
        "$sourceId-xt-vod-$streamId"

    fun xtreamSeries(sourceId: String, seriesId: String): String =
        "$sourceId-xt-series-$seriesId"

    fun stalkerLive(sourceId: String, channelId: String): String =
        "$sourceId-stk-live-$channelId"

    fun stalkerVod(sourceId: String, itemId: String): String =
        "$sourceId-stk-vod-$itemId"

    fun stalkerSeries(sourceId: String, seriesId: String): String =
        "$sourceId-stk-series-$seriesId"

    private fun fnv1a(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (c in s) {
            h = h xor c.code
            h = h * 0x01000193
        }
        return h.toUInt().toString(16).padStart(8, '0')
    }
}
