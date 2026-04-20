package com.yancotv.shared.sources

import com.yancotv.shared.content.classifyEntry
import com.yancotv.shared.content.cleanTitle
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.parsers.M3uEntry
import com.yancotv.shared.stalker.StalkerChannel
import com.yancotv.shared.stalker.StalkerSeriesItem
import com.yancotv.shared.stalker.StalkerVodItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.xtream.XtreamClient
import com.yancotv.shared.xtream.XtreamLiveStream
import com.yancotv.shared.xtream.XtreamSeriesInfo
import com.yancotv.shared.xtream.XtreamStreamType
import com.yancotv.shared.xtream.XtreamVodStream

/**
 * Writes classified content rows to SQLDelight.
 *
 * A sync is always destructive: we `DELETE FROM content WHERE source_id = ?`
 * inside the same transaction as the inserts, so a half-completed sync doesn't
 * leave a mixed old/new state visible to the UI. Desktop uses the same pattern
 * (src/main/services/content-store.ts).
 */
class ContentWriter(private val db: YancoDb) {

    /** Writes M3U entries for [sourceId]. Returns number of rows inserted. */
    fun writeM3u(
        sourceId: String,
        entries: List<M3uEntry>,
        now: Long,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val total = entries.size
        var written = 0
        db.transaction {
            db.contentQueries.deleteBySource(sourceId)
            for ((index, entry) in entries.withIndex()) {
                val type = classifyEntry(entry)
                val cleanedTitle = cleanTitle(entry.title)
                val id = ContentIds.m3u(sourceId, entry.title, entry.streamUrl)
                db.contentQueries.insert(
                    id = id,
                    source_id = sourceId,
                    type = serializeType(type),
                    title = entry.title,
                    clean_title = cleanedTitle,
                    group_name = entry.groupTitle.ifBlank { null },
                    stream_url = entry.streamUrl,
                    logo_url = entry.tvgLogo.ifBlank { null },
                    tvg_id = entry.tvgId.ifBlank { null },
                    metadata_json = null,
                    sort_order = index.toLong(),
                    created_at = now,
                )
                written++
                if (written % 250 == 0) onProgress(written, total)
            }
        }
        onProgress(written, total)
        return written
    }

    /** Writes Xtream streams + VODs + series (no per-series episode expansion). */
    fun writeXtream(
        sourceId: String,
        client: XtreamClient,
        live: XtreamBundle<XtreamLiveStream>,
        vod: XtreamBundle<XtreamVodStream>,
        series: XtreamBundle<XtreamSeriesInfo>,
        now: Long,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val total = live.items.size + vod.items.size + series.items.size
        var written = 0
        db.transaction {
            db.contentQueries.deleteBySource(sourceId)

            for ((i, s) in live.items.withIndex()) {
                db.contentQueries.insert(
                    id = ContentIds.xtreamLive(sourceId, s.streamId.toString()),
                    source_id = sourceId,
                    type = "live",
                    title = s.name,
                    clean_title = cleanTitle(s.name),
                    group_name = live.categories[s.categoryId],
                    stream_url = client.buildStreamUrl(s.streamId, XtreamStreamType.LIVE),
                    logo_url = s.streamIcon.ifBlank { null },
                    tvg_id = s.epgChannelId.ifBlank { null },
                    metadata_json = null,
                    sort_order = i.toLong(),
                    created_at = now,
                )
                written++
                if (written % 250 == 0) onProgress(written, total)
            }

            for ((i, v) in vod.items.withIndex()) {
                db.contentQueries.insert(
                    id = ContentIds.xtreamVod(sourceId, v.streamId.toString()),
                    source_id = sourceId,
                    type = "movie",
                    title = v.name,
                    clean_title = cleanTitle(v.name),
                    group_name = vod.categories[v.categoryId],
                    stream_url = client.buildStreamUrl(
                        v.streamId,
                        XtreamStreamType.MOVIE,
                        v.containerExtension,
                    ),
                    logo_url = v.streamIcon.ifBlank { null },
                    tvg_id = null,
                    metadata_json = null,
                    sort_order = (live.items.size + i).toLong(),
                    created_at = now,
                )
                written++
                if (written % 250 == 0) onProgress(written, total)
            }

            for ((i, sr) in series.items.withIndex()) {
                db.contentQueries.insert(
                    id = ContentIds.xtreamSeries(sourceId, sr.seriesId.toString()),
                    source_id = sourceId,
                    type = "series",
                    title = sr.name,
                    clean_title = cleanTitle(sr.name),
                    group_name = series.categories[sr.categoryId],
                    // Series don't have a directly-playable URL — the user must pick
                    // an episode. Store the info endpoint so downstream code can
                    // lazily resolve episodes without another client round-trip.
                    stream_url = "xtream-series://${sr.seriesId}",
                    logo_url = sr.cover.ifBlank { null },
                    tvg_id = null,
                    metadata_json = null,
                    sort_order = (live.items.size + vod.items.size + i).toLong(),
                    created_at = now,
                )
                written++
                if (written % 250 == 0) onProgress(written, total)
            }
        }
        onProgress(written, total)
        return written
    }

    /** Writes Stalker live channels + VOD items + series (no episode expansion). */
    fun writeStalker(
        sourceId: String,
        live: StalkerBundle<StalkerChannel>,
        vod: StalkerBundle<StalkerVodItem>,
        series: StalkerBundle<StalkerSeriesItem>,
        now: Long,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val total = live.items.size + vod.items.size + series.items.size
        var written = 0
        db.transaction {
            db.contentQueries.deleteBySource(sourceId)

            for ((i, c) in live.items.withIndex()) {
                db.contentQueries.insert(
                    id = ContentIds.stalkerLive(sourceId, c.id.toString()),
                    source_id = sourceId,
                    type = "live",
                    title = c.name,
                    clean_title = cleanTitle(c.name),
                    group_name = live.categories[c.tvGenreId] ?: c.tvGenreId.ifBlank { null },
                    stream_url = c.cmd,
                    logo_url = c.logo.ifBlank { null },
                    tvg_id = c.epgId.ifBlank { null },
                    metadata_json = null,
                    sort_order = i.toLong(),
                    created_at = now,
                )
                written++
                if (written % 250 == 0) onProgress(written, total)
            }

            for ((i, v) in vod.items.withIndex()) {
                db.contentQueries.insert(
                    id = ContentIds.stalkerVod(sourceId, v.id.toString()),
                    source_id = sourceId,
                    type = "movie",
                    title = v.name,
                    clean_title = cleanTitle(v.name),
                    group_name = vod.categories[v.categoryId] ?: v.categoryId.ifBlank { null },
                    stream_url = v.cmd,
                    logo_url = v.logo.ifBlank { null },
                    tvg_id = null,
                    metadata_json = null,
                    sort_order = (live.items.size + i).toLong(),
                    created_at = now,
                )
                written++
                if (written % 250 == 0) onProgress(written, total)
            }

            for ((i, sr) in series.items.withIndex()) {
                db.contentQueries.insert(
                    id = ContentIds.stalkerSeries(sourceId, sr.id.toString()),
                    source_id = sourceId,
                    type = "series",
                    title = sr.name,
                    clean_title = cleanTitle(sr.name),
                    group_name = series.categories[sr.categoryId] ?: sr.categoryId.ifBlank { null },
                    stream_url = "stalker-series://${sr.id}",
                    logo_url = sr.cover.ifBlank { null },
                    tvg_id = null,
                    metadata_json = null,
                    sort_order = (live.items.size + vod.items.size + i).toLong(),
                    created_at = now,
                )
                written++
                if (written % 250 == 0) onProgress(written, total)
            }
        }
        onProgress(written, total)
        return written
    }

    private fun serializeType(type: ContentType): String = when (type) {
        ContentType.LIVE -> "live"
        ContentType.MOVIE -> "movie"
        ContentType.SERIES -> "series"
    }
}

data class XtreamBundle<T>(val items: List<T>, val categories: Map<String, String>)
data class StalkerBundle<T>(val items: List<T>, val categories: Map<String, String>)
