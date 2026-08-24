package com.yancotv.shared.epg

import app.cash.sqldelight.db.SqlDriver
import com.yancotv.shared.db.Epg_programmes
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.http.redactCredentials
import com.yancotv.shared.http.redactErrorMessage
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import com.yancotv.shared.parsers.parseXmltv
import com.yancotv.shared.types.EpgGuideChannel
import com.yancotv.shared.types.EpgGuideData
import com.yancotv.shared.types.EpgProgramme
import com.yancotv.shared.types.EpgRefreshResult
import com.yancotv.shared.types.NowNext
import com.yancotv.shared.types.NowNextMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * EPG read + refresh facade. Mirrors desktop `src/main/services/epg-service.ts`.
 *
 * Refresh semantics match desktop:
 *  - Every active source's `epg_url` + the optional global `epg_global_url`
 *    setting contributes a stream.
 *  - ALL streams are fetched + parsed BEFORE any DB writes. If every source
 *    fails we leave the existing table intact (so a transient network blip
 *    never wipes the user's guide).
 *  - On any-parse-succeeds we delete the whole table and re-insert in a
 *    single transaction — ONE transaction is ~10× faster than per-source
 *    and avoids a window where half the channels show stale data.
 *
 * The natural PK `channelId|startTime|sourceKey` avoids a UUID per programme
 * (300k rows × `Random.nextLong()` was a measurable cost on Fire TV).
 *
 * Gzip note: this class calls [HttpClient.getText]. OkHttp (our Android
 * engine) auto-decompresses responses whose `Content-Encoding: gzip` header
 * is set, which covers most providers. `.xml.gz` URLs where the server
 * doesn't advertise compression are NOT handled here — the Android sync
 * service wraps those via `java.util.zip.GZIPInputStream` before calling
 * the parser directly (see MK.7.2).
 */
class EpgRepository(
    private val db: YancoDb,
    private val driver: SqlDriver,
    private val http: HttpClient,
    private val clock: () -> Long,
    private val logger: Logger = NOOP_LOGGER,
    /**
     * Platform-side gunzip hook. Android wires in `GZIPInputStream`; iOS will
     * do so in MK.iOS. The default is the identity function — fine for
     * providers that already send `Content-Encoding: gzip` (OkHttp decompresses
     * those transparently) or serve uncompressed XML.
     */
    private val gunzip: (ByteArray) -> ByteArray = { it },
) {
    private val bulkWriter = BulkEpgWriter(driver, logger)

    // ───── Queries ─────

    /** Currently-airing programme for [tvgId], or null if no guide data covers now. */
    fun getNowProgramme(tvgId: String): EpgProgramme? {
        val now = nowSeconds()
        return db.epgProgrammesQueries
            .nowForChannel(tvgId, now, now)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    /**
     * Now + next pair. Uses a single 2-row query ordered by start_time — the
     * first row is "now" if it has already started, otherwise it's "next"
     * (with no "now" available, which happens in brief gaps between shows).
     */
    fun getNowNext(tvgId: String): NowNext {
        val now = nowSeconds()
        val rows =
            db.epgProgrammesQueries
                .nowNextForChannel(tvgId, now)
                .executeAsList()
        return buildNowNext(tvgId, rows, now)
    }

    /**
     * Now/next for a batch of channels. Desktop uses a single `WHERE IN (...)`
     * chunked at 500. Here we iterate per-channel — the channel_tvg_id + end_time
     * composite index makes each lookup O(log n) + 2 rows, so 500 channels is
     * ~1ms cumulative on Fire TV and not worth chasing a batch query + result
     * grouping in Kotlin. If profiling ever says otherwise we can add a
     * collection-IN query to EpgProgrammes.sq.
     */
    fun getNowNextBatch(tvgIds: List<String>): NowNextMap {
        if (tvgIds.isEmpty()) return emptyMap()
        val now = nowSeconds()
        val result = LinkedHashMap<String, NowNext>(tvgIds.size)
        for (id in tvgIds) {
            val rows = db.epgProgrammesQueries.nowNextForChannel(id, now).executeAsList()
            result[id] = buildNowNext(id, rows, now)
        }
        return result
    }

    /**
     * MK.14.6 — find future occurrences of a programme by title on a
     * channel, within `[nowSec, nowSec + windowSec)`. Used by the manual
     * series-binding flow ("Record all on this channel"). Exact-match on
     * the EPG-canonical title — caller has the snapshot from the
     * long-pressed row.
     *
     * UNITS: both args are XMLTV epoch **SECONDS**. `epg_programmes.start_time`
     * stores XMLTV seconds (NOT ms), so the predicate is
     * `start_time >= nowSec AND start_time < nowSec + windowSec`. The
     * pre-MK.28 spelling was `now: Long, windowMs: Long` — a `*Ms`
     * suffix on what semantically had to be seconds — and the call site
     * passed `System.currentTimeMillis()` (~1.78e12 ms) which dwarfs
     * every real row's start_time (~1.78e9 s), so the query returned 0
     * rows and the Record-all-series flow silently bound nothing.
     * Audit catch.
     */
    fun findFutureByChannelAndTitle(tvgId: String, title: String, nowSec: Long, windowSec: Long): List<EpgProgramme> = db.epgProgrammesQueries
        .futureByChannelAndTitle(tvgId, title, nowSec, nowSec + windowSec)
        .executeAsList()
        .map { it.toDomain() }

    fun getProgrammesForChannel(tvgId: String, startTime: Long, endTime: Long): List<EpgProgramme> = db.epgProgrammesQueries
        .forChannelRange(tvgId, startTime, endTime)
        .executeAsList()
        .map { it.toDomain() }

    /**
     * Guide grid data: the [startTime, endTime) window projected across
     * [limit] live channels starting at [offset].
     *
     * **Paged** — callers MUST not request the whole catalog at once.
     * A provider with 250 k live channels would materialise ~10 million
     * programme objects across every matching channel for a 6 h window;
     * far past the 320 MB heap cap on Fire TV. GuideScreen loads in
     * ~100-row pages and extends as the user scrolls.
     */
    fun getGuideData(
        startTime: Long,
        endTime: Long,
        sourceId: String? = null,
        groupName: String? = null,
        limit: Long = 100L,
        offset: Long = 0L,
    ): EpgGuideData {
        // MK.guide.groups — the three-way switch matters for query
        // selectivity. Group-filtered installs hit the smallest result
        // set; sourceId-filtered next; everything else falls through to
        // the unfiltered All path. Combining group + source isn't
        // supported yet (no caller needs it); add a dedicated query if
        // a future surface does.
        // MB-382 — the channel queries no longer take start/end (they list ALL
        // live channels, not just EPG-covered ones); start/end are still used
        // below to fetch each channel's programmes.
        val channels =
            when {
                groupName != null ->
                    db.contentQueries.guideChannelsByGroupPaged(groupName, limit, offset).executeAsList().map {
                        GuideChannelRow(it.id, it.tvg_id, it.title, it.clean_title, it.logo_url, it.stream_url, it.sort_order)
                    }
                sourceId != null ->
                    db.contentQueries.guideChannelsBySourcePaged(sourceId, limit, offset).executeAsList().map {
                        GuideChannelRow(it.id, it.tvg_id, it.title, it.clean_title, it.logo_url, it.stream_url, it.sort_order)
                    }
                else ->
                    db.contentQueries.guideChannelsAllPaged(limit, offset).executeAsList().map {
                        GuideChannelRow(it.id, it.tvg_id, it.title, it.clean_title, it.logo_url, it.stream_url, it.sort_order)
                    }
            }
        if (channels.isEmpty()) {
            return EpgGuideData(channels = emptyList(), startTime = startTime, endTime = endTime)
        }

        // MK.EPG.F — batched programme fetch. Was 101 queries per page
        // (one channel-list query + one `forChannelRange` per channel);
        // now one channel-list query + ⌈N/CHUNK⌉ `forChannelsRange` calls
        // (CHUNK=500 stays under SQLite's 999-variable limit). At the
        // default 100-channel page that's two queries total. The planner
        // does one index seek per IN-list element using
        // `idx_epg_channel_end_start`, and Kotlin groups the rows by
        // tvg_id in one O(N) pass over the result.
        // MB-382 — the guide now lists channels with no tvg_id, so filter null
        // AND blank here (the channel queries no longer do): a blank tvg_id has
        // no EPG mapping and would just waste an IN-list slot.
        val tvgIds = channels.mapNotNull { it.tvgId?.takeIf { t -> t.isNotBlank() } }
        val programmesByTvgId: Map<String, List<EpgProgramme>> =
            if (tvgIds.isEmpty()) {
                emptyMap()
            } else {
                val grouped = LinkedHashMap<String, MutableList<EpgProgramme>>(tvgIds.size)
                tvgIds.chunked(GUIDE_FETCH_CHUNK).forEach { chunk ->
                    db.epgProgrammesQueries
                        .forChannelsRange(chunk, startTime, endTime)
                        .executeAsList()
                        .forEach { row ->
                            val list = grouped.getOrPut(row.channel_tvg_id) { mutableListOf() }
                            list.add(row.toDomain())
                        }
                }
                grouped
            }

        val result = ArrayList<EpgGuideChannel>(channels.size)
        for (ch in channels) {
            // MB-382 — no longer skip channels without a tvg_id; they appear with
            // an empty programme list (the UI renders "No information"). Programmes
            // attach by tvg_id only when the channel has one.
            val progs = ch.tvgId?.let { programmesByTvgId[it] } ?: emptyList()
            result.add(
                EpgGuideChannel(
                    id = ch.id,
                    tvgId = ch.tvgId,
                    name = ch.cleanTitle?.takeIf { it.isNotBlank() } ?: ch.title,
                    logoUrl = ch.logoUrl,
                    streamUrl = ch.streamUrl,
                    programmes = progs,
                    channelNumber = ch.sortOrder?.takeIf { it > 0 }?.toInt(),
                ),
            )
        }
        return EpgGuideData(channels = result, startTime = startTime, endTime = endTime)
    }

    /** Total distinct live channels with guide data in the window. Used by the guide's "X of Y" header. */
    // MB-382 — start/end kept for signature stability (callers pass the window)
    // but the count now covers ALL live channels, not just EPG-covered ones.
    fun countGuideChannels(startTime: Long, endTime: Long, sourceId: String? = null, groupName: String? = null): Long = when {
        groupName != null -> db.contentQueries.countGuideChannelsByGroup(groupName).executeAsOne()
        sourceId != null -> db.contentQueries.countGuideChannelsBySource(sourceId).executeAsOne()
        else -> db.contentQueries.countGuideChannelsAll().executeAsOne()
    }

    /** MK.guide.groups — distinct live group names with guide-covered
     *  channels in the window. Drives GuideScreen's group filter chip
     *  strip; only groups the user can actually filter by appear. */
    fun getGuideGroups(startTime: Long, endTime: Long): List<String> =
        db.contentQueries.distinctGuideGroups(startTime, endTime).executeAsList().mapNotNull { it }

    fun getStats(): EpgStats {
        val programmes = db.epgProgrammesQueries.countAll().executeAsOne()
        val channels = db.epgProgrammesQueries.countChannels().executeAsOne()
        val lastRefreshed =
            db.settingsQueries
                .get(LAST_REFRESHED_KEY)
                .executeAsOneOrNull()
                ?.toLongOrNull()
        return EpgStats(programmeCount = programmes, channelCount = channels, lastRefreshedAt = lastRefreshed)
    }

    fun getGlobalEpgUrl(): String? = db.settingsQueries
        .get(GLOBAL_URL_KEY)
        .executeAsOneOrNull()
        ?.takeIf { it.isNotBlank() }

    fun setGlobalEpgUrl(url: String?) {
        if (url.isNullOrBlank()) {
            db.settingsQueries.delete(GLOBAL_URL_KEY)
        } else {
            db.settingsQueries.upsert(GLOBAL_URL_KEY, url)
        }
    }

    // ───── Refresh ─────

    /**
     * Fetch every active source's EPG + the global EPG URL, parse all, then
     * atomically swap the table contents. Returns programme + channel counts,
     * or an error if every source failed.
     *
     * [onProgress] is called with per-source phase strings so the UI can show
     * "Fetching 1/3…", "Parsing 1/3…", etc. — matches desktop's
     * `EPG_REFRESH_PROGRESS` emits.
     */
    suspend fun refresh(onProgress: suspend (String) -> Unit = {}): EpgRefreshResult {
        val targets = collectEpgTargets()
        if (targets.isEmpty()) {
            onProgress("No EPG URLs configured")
            return EpgRefreshResult(ok = true, programmeCount = 0, channelCount = 0)
        }

        val errors = mutableListOf<String>()
        val batches = mutableListOf<BulkEpgWriter.ProgrammeBatch>()

        targets.forEachIndexed { idx, t ->
            onProgress("Fetching EPG ${idx + 1}/${targets.size} (${t.sourceKey})")
            try {
                val fetchStart = clock()
                val text = fetchXmltvText(t.url)
                val fetchMs = clock() - fetchStart
                logger.info("EPG fetch: ${t.sourceKey} took ${fetchMs}ms, ${text.length} chars")

                onProgress("Parsing EPG ${idx + 1}/${targets.size} (${t.sourceKey})")
                val parseStart = clock()
                val result = withContext(Dispatchers.Default) { parseXmltv(text, logger) }
                logger.info("EPG parse: ${t.sourceKey} yielded ${result.programmes.size} programmes in ${clock() - parseStart}ms")

                if (result.programmes.isEmpty()) {
                    errors.add("${t.sourceKey}: response parsed but contained 0 programmes (check URL / XMLTV format)")
                    logger.warn("EPG source ${t.sourceKey} returned no programmes")
                } else {
                    batches.add(
                        BulkEpgWriter.ProgrammeBatch(
                            sourceKey = t.sourceKey,
                            sourceIdForDb = if (t.sourceKey == GLOBAL_SOURCE_KEY) null else t.sourceKey,
                            programmes = result.programmes,
                        ),
                    )
                }
            } catch (error: Throwable) {
                // Redact before logging / persisting: Ktor + OkHttp exception
                // messages routinely echo the request URL, which for Xtream
                // xmltv.php endpoints includes `?username=…&password=…`. Raw
                // propagation hits three leak sinks (logger.error → Sentry,
                // the `errors` list shown to the user, Android logcat).
                // Mirror the SourceRepository pattern.
                val msg = redactErrorMessage(error)
                errors.add("${t.sourceKey}: $msg")
                logger.error("EPG fetch/parse failed for ${t.sourceKey}: $msg")
            }
        }

        if (batches.isEmpty()) {
            val detail = if (errors.isEmpty()) "All EPG sources failed to load" else errors.joinToString(" | ")
            setLastError(detail)
            return EpgRefreshResult(ok = false, error = detail)
        }

        val total = batches.sumOf { it.programmes.size }
        onProgress("Writing $total programmes (bulk-insert)")

        val writeStart = clock()
        val result =
            withContext(Dispatchers.Default) {
                bulkWriter.replaceAll(
                    batches = batches,
                    onBatch = { written, t -> onProgress("Writing $written/$t programmes") },
                    lastRefreshedMs = clock(),
                )
            }
        val writeMs = clock() - writeStart
        logger.info("EPG bulk write: ${result.rowsWritten} rows across ${result.channels} channels in ${writeMs}ms")

        clearLastError()
        if (errors.isNotEmpty()) {
            // Partial success — some sources failed but at least one succeeded.
            // Persist the warning so the panel can flag it without failing the
            // whole refresh.
            setLastError("Partial: " + errors.joinToString(" | "))
        }

        return EpgRefreshResult(
            ok = true,
            programmeCount = result.rowsWritten,
            channelCount = result.channels,
        )
    }

    fun getLastError(): String? = db.settingsQueries
        .get(LAST_ERROR_KEY)
        .executeAsOneOrNull()
        ?.takeIf { it.isNotBlank() }

    private fun setLastError(msg: String) {
        db.settingsQueries.upsert(LAST_ERROR_KEY, msg)
    }

    private fun clearLastError() {
        db.settingsQueries.delete(LAST_ERROR_KEY)
    }

    /** Drop programmes whose end_time is before [cutoffSeconds]. */
    fun deleteStale(cutoffSeconds: Long = nowSeconds()): Unit = db.epgProgrammesQueries.deleteStale(cutoffSeconds)

    // ───── internals ─────

    /**
     * Fetch XMLTV from [url], handling the two gzip flavours:
     *  1. Server sets `Content-Encoding: gzip` — OkHttp decompresses, getText
     *     returns the already-inflated XML.
     *  2. URL is a `.xml.gz` static file served as binary — no Content-Encoding
     *     header, so OkHttp doesn't touch it. We fetch bytes, check the gzip
     *     magic (0x1F 0x8B), and gunzip via the platform hook.
     */
    private suspend fun fetchXmltvText(url: String): String {
        // Always fetch bytes and sniff the gzip magic. Xtream's `xmltv.php`
        // routinely gzips without sending `Content-Encoding: gzip`, so
        // `http.getText()` would UTF-8-decode binary bytes into mojibake and
        // the parser would report zero programmes — silently breaking EPG.
        val options =
            HttpRequestOptions(
                timeoutMs = FETCH_TIMEOUT_MS,
                maxResponseBytes = MAX_EPG_BYTES,
            )
        val bytes = http.getBytes(url, options)
        val inflated =
            if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
                logger.info("EPG fetch: gzip detected at ${redactCredentials(url)} (${bytes.size} B compressed)")
                gunzip(bytes)
            } else {
                bytes
            }
        return inflated.decodeToString()
    }

    private fun collectEpgTargets(): List<EpgTarget> {
        val out = mutableListOf<EpgTarget>()
        for (s in db.sourcesQueries.selectActive().executeAsList()) {
            val url = s.epg_url?.trim().orEmpty()
            if (url.isNotEmpty()) out.add(EpgTarget(url = url, sourceKey = s.id))
        }
        getGlobalEpgUrl()?.let { out.add(EpgTarget(url = it, sourceKey = GLOBAL_SOURCE_KEY)) }
        return out
    }

    private fun buildNowNext(tvgId: String, rows: List<Epg_programmes>, now: Long): NowNext {
        if (rows.isEmpty()) return NowNext(channelTvgId = tvgId)
        val first = rows[0]
        return if (first.start_time <= now) {
            NowNext(
                channelTvgId = tvgId,
                now = first.toDomain(),
                next = rows.getOrNull(1)?.toDomain(),
            )
        } else {
            NowNext(channelTvgId = tvgId, now = null, next = first.toDomain())
        }
    }

    private fun nowSeconds(): Long = clock() / 1000L

    private fun Epg_programmes.toDomain(): EpgProgramme = EpgProgramme(
        id = id,
        channelTvgId = channel_tvg_id,
        title = title,
        description = description,
        startTime = start_time,
        endTime = end_time,
        category = category,
        iconUrl = icon_url,
    )

    private data class EpgTarget(val url: String, val sourceKey: String)

    private data class GuideChannelRow(
        /** MB-382 — stable guide-row identity (the representative content id).
         *  Used because ~85% of live channels have no tvg_id. */
        val id: String,
        val tvgId: String?,
        val title: String,
        val cleanTitle: String?,
        val logoUrl: String?,
        val streamUrl: String,
        /** MK.16.5 — channel number rendered in the guide. Currently
         *  the sequential playlist position from `content.sort_order`;
         *  a follow-up migration will split out `tvg-chno` properly. */
        val sortOrder: Long?,
    )

    companion object {
        // 2 minutes matches the desktop FETCH_TIMEOUT. EPG XMLs can be multi-
        // MB on slow phone connections.
        private const val FETCH_TIMEOUT_MS: Long = 120_000

        // 500MB compressed ceiling, same as desktop. Anything bigger is almost
        // certainly a misconfigured URL returning an HTML error page.
        private const val MAX_EPG_BYTES: Long = 500L * 1024 * 1024

        // MK.EPG.F — chunk size for the batched `forChannelsRange` IN-list
        // query. SQLite's default `SQLITE_MAX_VARIABLE_NUMBER` is 999;
        // 500 leaves headroom for the two extra positional params
        // (start_time, end_time) and for any future driver compiled with
        // a tighter limit. The default Guide page is 100 channels, so
        // chunking only kicks in when a future caller raises the page.
        private const val GUIDE_FETCH_CHUNK: Int = 500

        const val GLOBAL_URL_KEY: String = "epg_global_url"
        const val LAST_REFRESHED_KEY: String = "epg_last_refreshed"
        const val LAST_ERROR_KEY: String = "epg_last_error"
        const val GLOBAL_SOURCE_KEY: String = "global"
    }
}

data class EpgStats(
    val programmeCount: Long,
    val channelCount: Long,
    /** Unix millis of last successful refresh. */
    val lastRefreshedAt: Long?,
)
