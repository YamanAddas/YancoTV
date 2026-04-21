package com.yancotv.shared.epg

import com.yancotv.shared.db.Epg_programmes
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import com.yancotv.shared.parsers.XmltvProgramme
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
        val rows = db.epgProgrammesQueries
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

    fun getProgrammesForChannel(tvgId: String, startTime: Long, endTime: Long): List<EpgProgramme> =
        db.epgProgrammesQueries
            .forChannelRange(tvgId, startTime, endTime)
            .executeAsList()
            .map { it.toDomain() }

    /**
     * Guide grid data: the [startTime, endTime) window projected across all
     * live channels (that have guide data) as a list of
     * `EpgGuideChannel { programmes[] }`.
     */
    fun getGuideData(startTime: Long, endTime: Long, sourceId: String? = null): EpgGuideData {
        val channels = if (sourceId == null) {
            db.contentQueries.guideChannelsAll(startTime, endTime).executeAsList().map {
                GuideChannelRow(it.tvg_id, it.title, it.clean_title, it.logo_url, it.stream_url)
            }
        } else {
            db.contentQueries.guideChannelsBySource(sourceId, startTime, endTime).executeAsList().map {
                GuideChannelRow(it.tvg_id, it.title, it.clean_title, it.logo_url, it.stream_url)
            }
        }
        if (channels.isEmpty()) {
            return EpgGuideData(channels = emptyList(), startTime = startTime, endTime = endTime)
        }

        // Fetch programmes per channel. Same reasoning as getNowNextBatch: the
        // (channel_tvg_id, end_time, start_time) index makes each query fast,
        // and per-channel fetching keeps the mapping trivial.
        val result = ArrayList<EpgGuideChannel>(channels.size)
        for (ch in channels) {
            val tvgId = ch.tvgId ?: continue
            val progs = db.epgProgrammesQueries
                .forChannelRange(tvgId, startTime, endTime)
                .executeAsList()
                .map { it.toDomain() }
            result.add(
                EpgGuideChannel(
                    tvgId = tvgId,
                    name = ch.cleanTitle?.takeIf { it.isNotBlank() } ?: ch.title,
                    logoUrl = ch.logoUrl,
                    streamUrl = ch.streamUrl,
                    programmes = progs,
                ),
            )
        }
        return EpgGuideData(channels = result, startTime = startTime, endTime = endTime)
    }

    fun getStats(): EpgStats {
        val programmes = db.epgProgrammesQueries.countAll().executeAsOne()
        val channels = db.epgProgrammesQueries.countChannels().executeAsOne()
        val lastRefreshed = db.settingsQueries.get(LAST_REFRESHED_KEY).executeAsOneOrNull()?.toLongOrNull()
        return EpgStats(programmeCount = programmes, channelCount = channels, lastRefreshedAt = lastRefreshed)
    }

    fun getGlobalEpgUrl(): String? =
        db.settingsQueries.get(GLOBAL_URL_KEY).executeAsOneOrNull()?.takeIf { it.isNotBlank() }

    fun setGlobalEpgUrl(url: String?) {
        if (url.isNullOrBlank()) db.settingsQueries.delete(GLOBAL_URL_KEY)
        else db.settingsQueries.upsert(GLOBAL_URL_KEY, url)
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

        data class Parsed(val sourceKey: String, val sourceIdForDb: String?, val programmes: List<XmltvProgramme>)

        val parsed = mutableListOf<Parsed>()
        targets.forEachIndexed { idx, t ->
            onProgress("Fetching EPG ${idx + 1}/${targets.size} (${t.sourceKey})")
            try {
                val text = fetchXmltvText(t.url)
                onProgress("Parsing EPG ${idx + 1}/${targets.size} (${t.sourceKey})")
                val result = withContext(Dispatchers.Default) { parseXmltv(text, logger) }
                if (result.programmes.isEmpty()) {
                    logger.warn("EPG source ${t.sourceKey} returned no programmes")
                } else {
                    parsed.add(
                        Parsed(
                            sourceKey = t.sourceKey,
                            sourceIdForDb = if (t.sourceKey == GLOBAL_SOURCE_KEY) null else t.sourceKey,
                            programmes = result.programmes,
                        ),
                    )
                }
            } catch (error: Throwable) {
                logger.error("EPG fetch/parse failed for ${t.sourceKey}: ${error.message}")
            }
        }

        if (parsed.isEmpty()) {
            return EpgRefreshResult(ok = false, error = "All EPG sources failed to load")
        }

        onProgress("Writing ${parsed.sumOf { it.programmes.size }} programmes")
        var total = 0
        val channels = HashSet<String>()
        withContext(Dispatchers.Default) {
            db.transaction {
                db.epgProgrammesQueries.deleteAll()
                for (p in parsed) {
                    for (prog in p.programmes) {
                        val id = "${prog.channelId}|${prog.startTime}|${p.sourceKey}"
                        db.epgProgrammesQueries.upsert(
                            id = id,
                            source_id = p.sourceIdForDb,
                            channel_tvg_id = prog.channelId,
                            title = prog.title,
                            description = prog.description,
                            start_time = prog.startTime,
                            end_time = prog.endTime,
                            category = prog.category,
                            icon_url = prog.iconUrl,
                        )
                        total++
                        channels.add(prog.channelId)
                    }
                }
                db.settingsQueries.upsert(LAST_REFRESHED_KEY, clock().toString())
            }
        }

        return EpgRefreshResult(
            ok = true,
            programmeCount = total,
            channelCount = channels.size,
        )
    }

    /** Drop programmes whose end_time is before [cutoffSeconds]. */
    fun deleteStale(cutoffSeconds: Long = nowSeconds()): Unit =
        db.epgProgrammesQueries.deleteStale(cutoffSeconds)

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
        val looksGz = url.substringBefore('?').endsWith(".gz", ignoreCase = true)
        val options = HttpRequestOptions(
            timeoutMs = FETCH_TIMEOUT_MS,
            maxResponseBytes = MAX_EPG_BYTES,
        )
        if (!looksGz) return http.getText(url, options)

        val bytes = http.getBytes(url, options)
        val inflated = if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            gunzip(bytes)
        } else {
            // URL ended with .gz but server already gave us plain XML (some
            // providers do this when the client sends Accept-Encoding: gzip
            // and the proxy decompresses). Trust the bytes.
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
        val tvgId: String?,
        val title: String,
        val cleanTitle: String?,
        val logoUrl: String?,
        val streamUrl: String,
    )

    companion object {
        // 2 minutes matches the desktop FETCH_TIMEOUT. EPG XMLs can be multi-
        // MB on slow phone connections.
        private const val FETCH_TIMEOUT_MS: Long = 120_000

        // 500MB compressed ceiling, same as desktop. Anything bigger is almost
        // certainly a misconfigured URL returning an HTML error page.
        private const val MAX_EPG_BYTES: Long = 500L * 1024 * 1024

        const val GLOBAL_URL_KEY: String = "epg_global_url"
        const val LAST_REFRESHED_KEY: String = "epg_last_refreshed"
        const val GLOBAL_SOURCE_KEY: String = "global"
    }
}

data class EpgStats(
    val programmeCount: Long,
    val channelCount: Long,
    /** Unix millis of last successful refresh. */
    val lastRefreshedAt: Long?,
)
