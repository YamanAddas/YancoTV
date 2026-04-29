package com.yancotv.shared.sources

import com.yancotv.shared.content.classifyEntry
import com.yancotv.shared.content.cleanTitle
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.parsers.M3uEntry
import com.yancotv.shared.stalker.StalkerChannel
import com.yancotv.shared.stalker.StalkerSeriesItem
import com.yancotv.shared.stalker.StalkerVodItem
import com.yancotv.shared.types.ContentMetadata
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.xtream.XtreamClient
import com.yancotv.shared.xtream.XtreamLiveStream
import com.yancotv.shared.xtream.XtreamSeriesInfo
import com.yancotv.shared.xtream.XtreamStreamType
import com.yancotv.shared.xtream.XtreamVodStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Writes classified content rows to SQLDelight.
 *
 * Two write shapes:
 *  - **One-shot** ([writeM3u], [writeStalker]) — used when the caller already
 *    has the full catalog in memory. Destructive: DELETE + INSERTs inside one
 *    transaction so a half-completed sync doesn't leave a mixed state visible.
 *  - **Chunked** ([beginXtreamSync] + [appendXtreamLive]/Vod/Series) — used by
 *    the Xtream sync path, which streams a single-endpoint response in
 *    chunks. Each chunk gets its own small transaction so the caller can drop
 *    the in-memory batch and free heap immediately. A sync failure mid-stream
 *    leaves partial rows, but [SourceRepository] records `last_sync_error`
 *    and the UI can warn; the next sync begins by deleting them. Incremental
 *    writes are the only way Fire TV survives a 100k-stream catalog inside
 *    its 400MB heap.
 *
 * Xtream sort order is a single monotonic counter per content type
 * ([LIVE_BASE] / [VOD_BASE] / [SERIES_BASE] + running index), matching the
 * server-returned order of the single-endpoint response.
 */
class ContentWriter(private val db: YancoDb) {
    private val json =
        Json {
            encodeDefaults = false
            explicitNulls = false
        }

    /**
     * Encode [ContentMetadata] to JSON for the `metadata_json` column. Returns
     * null when every field is null — empty strings in the DB just add bytes
     * and force a parse on every read for no benefit.
     */
    private fun encodeMeta(meta: ContentMetadata): String? {
        if (meta == ContentMetadata()) return null
        return json.encodeToString(meta)
    }

    /** Writes M3U entries for [sourceId]. Returns number of rows inserted. */
    fun writeM3u(sourceId: String, entries: List<M3uEntry>, now: Long, onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val total = entries.size
        var written = 0
        db.transaction {
            db.contentQueries.deleteFtsBySource(sourceId)
            db.contentQueries.deleteBySource(sourceId)
            for ((index, entry) in entries.withIndex()) {
                val type = classifyEntry(entry)
                val cleanedTitle = cleanTitle(entry.title)
                val id = ContentIds.m3u(sourceId, entry.title, entry.streamUrl)
                val groupName = entry.groupTitle.ifBlank { null }
                // Persist M3U catchup attributes so the catchup UI (and the
                // programme-tap "watch recording" affordance) can find them
                // without re-parsing the playlist. `catchup-days` maps to the
                // same "seek-back window" semantics as Xtream tv_archive_duration.
                val meta =
                    ContentMetadata(
                        catchupType = entry.catchupType,
                        catchupSource = entry.catchupSource,
                        tvArchiveDuration = entry.catchupDays,
                    )
                db.contentQueries.insert(
                    id = id,
                    source_id = sourceId,
                    type = serializeType(type),
                    title = entry.title,
                    clean_title = cleanedTitle,
                    group_name = groupName,
                    stream_url = entry.streamUrl,
                    logo_url = entry.tvgLogo.ifBlank { null },
                    tvg_id = entry.tvgId.ifBlank { null },
                    metadata_json = encodeMeta(meta),
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

    // ───── Xtream chunked writes ─────

    /** Clears the source's content rows. Called once at the start of a sync. */
    fun beginXtreamSync(sourceId: String) {
        db.transaction {
            // FTS rows must be wiped BEFORE the content DELETE now that the
            // per-row `content_ad` trigger is gone. Order matters: once
            // `content` rows are gone, `deleteFtsBySource`'s subquery returns
            // nothing and the FTS index leaks.
            db.contentQueries.deleteFtsBySource(sourceId)
            db.contentQueries.deleteBySource(sourceId)
        }
    }

    /**
     * Writes a batch of live streams. [sortOrderStart] is the first sort-order
     * value for this chunk (caller maintains a monotonic running index across
     * chunks). Each chunk runs in its own transaction so the caller can drop
     * the list for GC immediately after return.
     */
    fun appendXtreamLive(
        sourceId: String,
        client: XtreamClient,
        items: List<XtreamLiveStream>,
        categoryNames: Map<String, String>,
        sortOrderStart: Long,
        now: Long,
    ): Int {
        if (items.isEmpty()) return 0
        db.transaction {
            for ((i, s) in items.withIndex()) {
                val id = ContentIds.xtreamLive(sourceId, s.streamId.toString())
                val cleaned = cleanTitle(s.name)
                val groupName = categoryNames[s.categoryId] ?: s.categoryId.ifBlank { null }
                // Persist `tv_archive` + `tv_archive_duration` so the catchup
                // UI can tell, per-channel, whether the "rewind programme"
                // affordance is available and how far back it can seek.
                // Pre-fix we threw these away and catchup silently no-op'd.
                val meta =
                    ContentMetadata(
                        streamId = s.streamId.toLong(),
                        tvArchive = if (s.tvArchive != 0) s.tvArchive else null,
                        tvArchiveDuration = if (s.tvArchiveDuration != 0) s.tvArchiveDuration else null,
                    )
                db.contentQueries.insert(
                    id = id,
                    source_id = sourceId,
                    type = "live",
                    title = s.name,
                    clean_title = cleaned,
                    group_name = groupName,
                    stream_url = client.buildStreamUrl(s.streamId, XtreamStreamType.LIVE),
                    logo_url = s.streamIcon.ifBlank { null },
                    tvg_id = s.epgChannelId.ifBlank { null },
                    metadata_json = encodeMeta(meta),
                    sort_order = sortOrderStart + i.toLong(),
                    created_at = now,
                )
            }
        }
        return items.size
    }

    fun appendXtreamVod(
        sourceId: String,
        client: XtreamClient,
        items: List<XtreamVodStream>,
        categoryNames: Map<String, String>,
        sortOrderStart: Long,
        now: Long,
    ): Int {
        if (items.isEmpty()) return 0
        db.transaction {
            for ((i, v) in items.withIndex()) {
                val id = ContentIds.xtreamVod(sourceId, v.streamId.toString())
                val cleaned = cleanTitle(v.name)
                val groupName = categoryNames[v.categoryId] ?: v.categoryId.ifBlank { null }
                // Store every field the VOD list endpoint actually returns.
                // Deep metadata (plot, cast, backdrop, subtitles, trailer,
                // tmdbId) requires a per-item `get_vod_info` call — that's
                // too expensive to fan out for 20k+ titles at sync time, so
                // it's fetched lazily on the detail screen and merged into
                // this metadata then.
                val meta =
                    ContentMetadata(
                        streamId = v.streamId.toLong(),
                        rating = v.rating.ifBlank { null },
                    )
                db.contentQueries.insert(
                    id = id,
                    source_id = sourceId,
                    type = "movie",
                    title = v.name,
                    clean_title = cleaned,
                    group_name = groupName,
                    stream_url =
                    client.buildStreamUrl(
                        v.streamId,
                        XtreamStreamType.MOVIE,
                        v.containerExtension,
                    ),
                    logo_url = v.streamIcon.ifBlank { null },
                    tvg_id = null,
                    metadata_json = encodeMeta(meta),
                    sort_order = sortOrderStart + i.toLong(),
                    created_at = now,
                )
            }
        }
        return items.size
    }

    fun appendXtreamSeries(sourceId: String, items: List<XtreamSeriesInfo>, categoryNames: Map<String, String>, sortOrderStart: Long, now: Long): Int {
        if (items.isEmpty()) return 0
        db.transaction {
            for ((i, sr) in items.withIndex()) {
                val id = ContentIds.xtreamSeries(sourceId, sr.seriesId.toString())
                val cleaned = cleanTitle(sr.name)
                val groupName = categoryNames[sr.categoryId] ?: sr.categoryId.ifBlank { null }
                // The series list endpoint already returns every poster/plot/
                // cast/director/genre field — pre-fix we stored only the cover
                // as logo_url and silently discarded the rest, leaving detail
                // pages blank until the user tapped into a series (which then
                // fired a per-series get_series_info). Persisting here means
                // the grid can render a rich hover card without an extra
                // round-trip per card.
                val meta =
                    ContentMetadata(
                        seriesId = sr.seriesId.toLong(),
                        plot = sr.plot.ifBlank { null },
                        cast = sr.cast.ifBlank { null },
                        director = sr.director.ifBlank { null },
                        genre = sr.genre.ifBlank { null },
                        releaseDate = sr.releaseDate.ifBlank { null },
                        rating = sr.rating.ifBlank { null },
                    )
                db.contentQueries.insert(
                    id = id,
                    source_id = sourceId,
                    type = "series",
                    title = sr.name,
                    clean_title = cleaned,
                    group_name = groupName,
                    // Series don't have a directly-playable URL — the user must pick
                    // an episode. Store the info endpoint so downstream code can
                    // lazily resolve episodes without another client round-trip.
                    stream_url = "xtream-series://${sr.seriesId}",
                    logo_url = sr.cover.ifBlank { null },
                    tvg_id = null,
                    metadata_json = encodeMeta(meta),
                    sort_order = sortOrderStart + i.toLong(),
                    created_at = now,
                )
            }
        }
        return items.size
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
            db.contentQueries.deleteFtsBySource(sourceId)
            db.contentQueries.deleteBySource(sourceId)

            for ((i, c) in live.items.withIndex()) {
                val id = ContentIds.stalkerLive(sourceId, c.id.toString())
                val cleaned = cleanTitle(c.name)
                val groupName = live.categories[c.tvGenreId] ?: c.tvGenreId.ifBlank { null }
                val meta =
                    ContentMetadata(
                        stalkerId = c.id.toString(),
                        tvArchive = if (c.tvArchive != 0) c.tvArchive else null,
                        tvArchiveDuration = if (c.tvArchiveDuration != 0) c.tvArchiveDuration else null,
                    )
                db.contentQueries.insert(
                    id = id,
                    source_id = sourceId,
                    type = "live",
                    title = c.name,
                    clean_title = cleaned,
                    group_name = groupName,
                    stream_url = c.cmd,
                    logo_url = c.logo.ifBlank { null },
                    tvg_id = c.epgId.ifBlank { null },
                    metadata_json = encodeMeta(meta),
                    sort_order = i.toLong(),
                    created_at = now,
                )
                written++
                if (written % 250 == 0) onProgress(written, total)
            }

            for ((i, v) in vod.items.withIndex()) {
                val id = ContentIds.stalkerVod(sourceId, v.id.toString())
                val cleaned = cleanTitle(v.name)
                val groupName = vod.categories[v.categoryId] ?: v.categoryId.ifBlank { null }
                val meta =
                    ContentMetadata(
                        stalkerId = v.id.toString(),
                        description = v.description.ifBlank { null },
                    )
                db.contentQueries.insert(
                    id = id,
                    source_id = sourceId,
                    type = "movie",
                    title = v.name,
                    clean_title = cleaned,
                    group_name = groupName,
                    stream_url = v.cmd,
                    logo_url = v.logo.ifBlank { null },
                    tvg_id = null,
                    metadata_json = encodeMeta(meta),
                    sort_order = (live.items.size + i).toLong(),
                    created_at = now,
                )
                written++
                if (written % 250 == 0) onProgress(written, total)
            }

            for ((i, sr) in series.items.withIndex()) {
                val id = ContentIds.stalkerSeries(sourceId, sr.id.toString())
                val cleaned = cleanTitle(sr.name)
                val groupName = series.categories[sr.categoryId] ?: sr.categoryId.ifBlank { null }
                val meta =
                    ContentMetadata(
                        stalkerId = sr.id.toString(),
                        plot = sr.plot.ifBlank { null },
                        genre = sr.genre.ifBlank { null },
                    )
                db.contentQueries.insert(
                    id = id,
                    source_id = sourceId,
                    type = "series",
                    title = sr.name,
                    clean_title = cleaned,
                    group_name = groupName,
                    stream_url = "stalker-series://${sr.id}",
                    logo_url = sr.cover.ifBlank { null },
                    tvg_id = null,
                    metadata_json = encodeMeta(meta),
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

    companion object {
        const val LIVE_BASE = 0L
        const val VOD_BASE = 10_000_000_000L
        const val SERIES_BASE = 20_000_000_000L
    }
}

data class StalkerBundle<T>(val items: List<T>, val categories: Map<String, String>)
