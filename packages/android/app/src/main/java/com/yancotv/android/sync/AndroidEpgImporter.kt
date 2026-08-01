package com.yancotv.android.sync

import android.content.Context
import android.util.Xml
import com.yancotv.android.R
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.epg.BulkEpgWriter
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.parsers.XmltvProgramme
import com.yancotv.shared.parsers.parseXmltvTimestamp
import com.yancotv.shared.types.EpgRefreshResult
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.xmlpull.v1.XmlPullParser

/**
 * Android-native EPG refresh pipeline. **Streams end to end** so peak memory
 * never exceeds a few MB regardless of feed size.
 *
 * Why this exists separately from [EpgRepository.refresh]:
 *
 * The shared-core refresh path materializes the entire XMLTV body as a
 * [String] and the full programme list as a [List], then writes. Fine on
 * desktop (8 GB+ RAM). Fatal on Fire TV (~320 MB heap cap) — a 200 MB
 * XMLTV response decoded to UTF-16 [String] hits ~400 MB and OOMs before
 * the parser even gets a chance to run. Real-world test: a user with
 * 254k channels hit `Failed to allocate 165 MB allocation` during decode.
 *
 * The Android path instead:
 *
 *  1. Streams the HTTP body straight to a temp file via OkHttp (no big
 *     ByteArray).
 *  2. Detects gzip via magic bytes (0x1F 0x8B) on the first 2 bytes of
 *     the file, wraps with [GZIPInputStream] for on-the-fly decompression.
 *  3. Uses Android's native [XmlPullParser] (Expat-backed, bounded memory)
 *     to walk events one at a time. Never holds the full document.
 *  4. Filters each programme against the user's actual live-channel
 *     `tvg_id` set — a huge provider EPG covering every channel on earth
 *     becomes just the rows we'll show, cutting DB writes 50–90%.
 *  5. Accumulates 500 filtered programmes at a time, flushes through
 *     [BulkEpgWriter.Session] (multi-row INSERT inside one transaction),
 *     then frees the buffer. The full refresh is still atomic — success
 *     commits, any exception rolls back so the old data survives.
 *
 * Mirrors the desktop `refreshEpg()` contract (parse-everything-first /
 * write-once / atomic swap) but using bounded-memory primitives.
 */
class AndroidEpgImporter(
    private val context: Context,
    private val db: YancoDb,
    private val writer: BulkEpgWriter,
    private val logger: Logger,
    sharedHttp: OkHttpClient,
) {
    /**
     * MK.24.I.7 / MB-230 — derived from the shared app-level [OkHttpClient]
     * (Koin singleton), so we share the same connection pool + dispatcher
     * + DNS cache + TLS session cache as Coil. `newBuilder()` keeps those
     * resources shared while letting us override the timeouts EPG needs:
     * residential connections frequently take 30+ seconds to start streaming
     * a multi-MB XMLTV body and full downloads can run several minutes.
     */
    private val http: OkHttpClient =
        sharedHttp
            .newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .build()

    /** Progress callback — sent on phase transitions and every ~5 s during streaming. */
    fun interface Progress {
        suspend fun report(msg: String)
    }

    /**
     * Two-phase refresh — MK.EPG.B (2026-05-04).
     *
     * **Phase 1: download all sources to temp files. No SQLite transaction.**
     *   - Per-source try/catch isolates download failures; one failed
     *     source doesn't kill the others.
     *   - Pre-existing `epg-*.bin` cache leftovers from process-killed
     *     prior runs are swept here.
     *
     * **Phase 2: open one transaction, parse + write each downloaded
     *   file, then commit.**
     *   - Transaction holds for the parse + insert window only —
     *     typically seconds, not the minutes the previous architecture
     *     held it (downloads inside the transaction blocked every other
     *     UI write — favourites toggle, watch history persist — for the
     *     full duration of the slowest network leg).
     *   - All-or-nothing semantics preserved: the swap is atomic at
     *     commit; rollback restores the prior snapshot if anything in
     *     this phase throws.
     *
     * **Cleanup:** every temp file we created is deleted in `finally`,
     *   regardless of which phase failed.
     */
    suspend fun refresh(onProgress: Progress = Progress { /* no-op */ }): EpgRefreshResult =
        withContext(Dispatchers.IO) {
            val targets = collectTargets()
            if (targets.isEmpty()) {
                onProgress.report(context.getString(R.string.epg_no_urls))
                return@withContext EpgRefreshResult(ok = true, programmeCount = 0, channelCount = 0)
            }

            val canonicalById = loadCanonicalTvgIdMap()
            logger.info(
                "EPG stream: user has ${canonicalById.size} live channels with tvg_id (case+whitespace normalised); EPG rows for unknown channels will be filtered out",
            )

            // Sweep stale temp files from process-killed prior runs. Only
            // touches our `epg-*.bin` prefix so other apps' caches stay
            // intact.
            sweepStaleTempFiles()

            val downloaded = mutableListOf<DownloadedTarget>()
            val errors = mutableListOf<String>()

            try {
                // ───── Phase 1: downloads (NO transaction held) ─────
                for ((idx, target) in targets.withIndex()) {
                    val label = "${idx + 1}/${targets.size} (${target.sourceKey})"
                    onProgress.report(context.getString(R.string.epg_downloading, label))
                    val tempFile = File(context.cacheDir, "epg-${UUID.randomUUID()}.bin")
                    try {
                        val dlStart = System.currentTimeMillis()
                        val bytes = downloadToFile(target.url, tempFile)
                        logger.info("EPG download: ${target.sourceKey} fetched $bytes B in ${System.currentTimeMillis() - dlStart}ms")
                        downloaded.add(DownloadedTarget(target, tempFile))
                    } catch (t: Throwable) {
                        val msg = t.message ?: t::class.simpleName ?: "unknown"
                        errors.add("${target.sourceKey} download: $msg")
                        logger.error("EPG download failed for ${target.sourceKey}: $msg")
                        runCatching { tempFile.delete() }
                    }
                }

                if (downloaded.isEmpty()) {
                    val detail = if (errors.isEmpty()) {
                        context.getString(R.string.ei_no_rows)
                    } else {
                        errors.joinToString(" | ")
                    }
                    recordError(detail)
                    return@withContext EpgRefreshResult(ok = false, error = detail)
                }

                // ───── Phase 2: parse + write (transaction held only here) ─────
                val session = writer.openSession()
                var anySucceeded = false
                try {
                    session.begin()
                    for ((idx, df) in downloaded.withIndex()) {
                        val label = "${idx + 1}/${downloaded.size} (${df.target.sourceKey})"
                        onProgress.report(context.getString(R.string.epg_parsing, label))
                        val parseStart = System.currentTimeMillis()
                        val beforeRows = session.rowsWritten
                        try {
                            streamInto(
                                session = session,
                                sourceKey = df.target.sourceKey,
                                sourceIdForDb = if (df.target.sourceKey == GLOBAL) null else df.target.sourceKey,
                                file = df.file,
                                canonicalById = canonicalById,
                                onProgress = onProgress,
                            )
                            val written = session.rowsWritten - beforeRows
                            logger.info(
                                "EPG parse: ${df.target.sourceKey} ingested $written rows (filtered) in ${System.currentTimeMillis() - parseStart}ms",
                            )
                            if (written > 0) anySucceeded = true else errors.add("${df.target.sourceKey}: 0 programmes after filter")
                        } catch (t: Throwable) {
                            val msg = t.message ?: t::class.simpleName ?: "unknown"
                            errors.add("${df.target.sourceKey} parse: $msg")
                            logger.error("EPG parse failed for ${df.target.sourceKey}: $msg")
                        }
                    }

                    if (!anySucceeded) {
                        session.rollback()
                        val detail = if (errors.isEmpty()) {
                            context.getString(R.string.ei_no_rows)
                        } else {
                            errors.joinToString(" | ")
                        }
                        recordError(detail)
                        return@withContext EpgRefreshResult(ok = false, error = detail)
                    }

                    onProgress.report(
                        context.resources.getQuantityString(
                            R.plurals.epg_writing,
                            session.rowsWritten,
                            session.rowsWritten,
                        ),
                    )
                    session.commit(lastRefreshedMs = System.currentTimeMillis())
                    if (errors.isEmpty()) {
                        recordError(null)
                    } else {
                        recordError(context.getString(R.string.ei_partial, errors.joinToString(" | ")))
                    }

                    logger.info("EPG stream: total ${session.rowsWritten} rows across ${session.channelCount} channels committed")
                    EpgRefreshResult(
                        ok = true,
                        programmeCount = session.rowsWritten,
                        channelCount = session.channelCount,
                    )
                } catch (t: Throwable) {
                    runCatching { session.rollback() }
                    val msg = t.message ?: t::class.simpleName ?: "unknown"
                    logger.error("EPG stream aborted: $msg")
                    recordError(msg)
                    EpgRefreshResult(ok = false, error = msg)
                }
            } finally {
                // Always clean up our temp files — Phase-1 successes,
                // Phase-2 thrown-mid, and anything we managed to add to
                // `downloaded` before an outer abort.
                downloaded.forEach { runCatching { it.file.delete() } }
            }
        }

    private fun sweepStaleTempFiles() {
        runCatching {
            context.cacheDir.listFiles { _, name -> name.startsWith("epg-") && name.endsWith(".bin") }
                ?.forEach { file -> runCatching { file.delete() } }
        }
    }

    // ───── download ─────

    private fun downloadToFile(url: String, dest: File): Long {
        val client =
            http.newCall(
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Encoding", "gzip")
                    .build(),
            )
        client.execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code} ${response.message}")
            }
            val body = response.body ?: throw RuntimeException("Empty response body")
            dest.sink().buffer().use { sink ->
                body.source().use { source ->
                    sink.writeAll(source)
                }
            }
            return dest.length()
        }
    }

    // ───── stream parse + insert ─────

    private suspend fun streamInto(
        session: BulkEpgWriter.Session,
        sourceKey: String,
        sourceIdForDb: String?,
        file: File,
        canonicalById: Map<String, String>,
        onProgress: Progress,
    ) {
        openMaybeGzipped(file).use { raw ->
            val input = BufferedInputStream(raw, 64 * 1024)
            val parser = Xml.newPullParser()
            // XMLTV doesn't use namespaces; leaving it off simplifies name checks.
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)

            val buffer = ArrayList<XmltvProgramme>(FLUSH_EVERY)
            var eventType = parser.eventType
            var seen = 0
            var keptExact = 0
            var keptNormalised = 0
            var lastTick = System.currentTimeMillis()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "programme") {
                    seen++
                    val prog = readProgramme(parser)
                    val accepted: XmltvProgramme? =
                        when {
                            prog == null -> null
                            // No filter (empty user catalog) — keep everything.
                            canonicalById.isEmpty() -> prog
                            else -> {
                                val canonical = canonicalById[prog.channelId.trim().lowercase()]
                                when {
                                    canonical == null -> null
                                    canonical == prog.channelId -> {
                                        keptExact++
                                        prog
                                    }
                                    else -> {
                                        // Re-write to the user's canonical
                                        // case so downstream queries that
                                        // join `epg_programmes.channel_tvg_id`
                                        // against `content.tvg_id` match.
                                        keptNormalised++
                                        prog.copy(channelId = canonical)
                                    }
                                }
                            }
                        }
                    if (accepted != null) {
                        buffer.add(accepted)
                        if (buffer.size >= FLUSH_EVERY) {
                            session.writeBatch(sourceKey, sourceIdForDb, buffer)
                            buffer.clear()
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= PROGRESS_TICK_MS) {
                                lastTick = now
                                val totalKept = keptExact + keptNormalised
                                onProgress.report(context.getString(R.string.epg_writing_scan, totalKept, seen))
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
            if (buffer.isNotEmpty()) {
                session.writeBatch(sourceKey, sourceIdForDb, buffer)
                buffer.clear()
            }
            val totalKept = keptExact + keptNormalised
            logger.info(
                "EPG parser: scanned $seen, kept $totalKept ($keptExact exact + $keptNormalised normalised), dropped ${seen - totalKept}",
            )
        }
    }

    /**
     * Parse a single `<programme ...>...</programme>` subtree. Advances the
     * parser past the matching END_TAG. Returns null for malformed entries
     * (missing title, unparseable timestamps) rather than aborting the whole
     * import — one bad row in a 300k feed shouldn't kill the refresh.
     */
    private fun readProgramme(parser: XmlPullParser): XmltvProgramme? {
        val startStr = parser.getAttributeValue(null, "start")
        val stopStr = parser.getAttributeValue(null, "stop")
        val channelId = parser.getAttributeValue(null, "channel")
        if (channelId.isNullOrEmpty() || startStr.isNullOrEmpty() || stopStr.isNullOrEmpty()) {
            skipToEnd(parser, "programme")
            return null
        }
        val startTime = parseXmltvTimestamp(startStr)
        val endTime = parseXmltvTimestamp(stopStr)
        if (startTime == 0L || endTime == 0L) {
            skipToEnd(parser, "programme")
            return null
        }

        var title: String? = null
        var description: String? = null
        var category: String? = null
        var iconUrl: String? = null

        // Iterate children. Walk events inside the programme element —
        // remember that getName() is only valid on START_TAG / END_TAG, so we
        // track depth relative to the current programme.
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "title" -> {
                            if (title.isNullOrEmpty()) title = readTextThenEnd(parser)?.trim()
                            depth--
                        }
                        "desc" -> {
                            if (description.isNullOrEmpty()) description = readTextThenEnd(parser)?.trim()
                            depth--
                        }
                        "category" -> {
                            if (category.isNullOrEmpty()) category = readTextThenEnd(parser)?.trim()
                            depth--
                        }
                        "icon" -> {
                            if (iconUrl.isNullOrEmpty()) iconUrl = parser.getAttributeValue(null, "src")
                            // `<icon src="..." />` is usually self-closing but
                            // some feeds write a paired end-tag; either way,
                            // skipToEnd handles both.
                            skipToEnd(parser, "icon")
                            depth--
                        }
                        else -> {
                            // Skip unknown children in O(1) extra memory.
                            skipToEnd(parser, parser.name)
                            depth--
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    depth--
                }
                XmlPullParser.END_DOCUMENT -> return null
                else -> { /* TEXT / CDSECT / IGNORABLE_WHITESPACE — ignore */ }
            }
        }

        if (title.isNullOrEmpty()) return null
        return XmltvProgramme(
            channelId = channelId,
            title = title!!,
            description = description,
            startTime = startTime,
            endTime = endTime,
            category = category,
            iconUrl = iconUrl,
        )
    }

    /** Read the text content of a START_TAG element, then advance past END_TAG. */
    private fun readTextThenEnd(parser: XmlPullParser): String? {
        var text: String? = null
        val startName = parser.name
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (text == null) text = parser.text else text += parser.text
                }
                XmlPullParser.START_TAG -> {
                    depth++
                    skipToEnd(parser, parser.name)
                    depth--
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == startName) depth--
                }
                XmlPullParser.END_DOCUMENT -> return text
            }
        }
        return text
    }

    /** Advance past the matching END_TAG for a [tagName] the caller just entered. */
    private fun skipToEnd(parser: XmlPullParser, tagName: String) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> {
                    if (parser.name == tagName) depth--
                }
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    // ───── helpers ─────

    private fun openMaybeGzipped(file: File): InputStream {
        val raw = file.inputStream()
        val b = ByteArray(2)
        val read = raw.read(b, 0, 2)
        raw.close()
        val stream = file.inputStream()
        return if (read == 2 && b[0] == 0x1F.toByte() && b[1] == 0x8B.toByte()) {
            GZIPInputStream(stream)
        } else {
            stream
        }
    }

    private fun collectTargets(): List<EpgTarget> {
        val out = mutableListOf<EpgTarget>()
        for (s in db.sourcesQueries.selectActive().executeAsList()) {
            val url = s.epg_url?.trim().orEmpty()
            if (url.isNotEmpty()) out.add(EpgTarget(url = url, sourceKey = s.id))
        }
        val global =
            db.settingsQueries
                .get(EpgRepository.GLOBAL_URL_KEY)
                .executeAsOneOrNull()
                ?.takeIf { it.isNotBlank() }
        if (global != null) out.add(EpgTarget(url = global, sourceKey = GLOBAL))
        return out
    }

    /**
     * MK.EPG.C.2 (2026-05-05) — map from normalized form
     * (`trim().lowercase()`) → the user's canonical `tvg_id` casing.
     *
     * Real-world IPTV providers ship XMLTV with `<channel id="CNN.us">`
     * while the same provider's M3U ships `tvg-id="cnn.us"` (or vice
     * versa, or with trailing whitespace). The pre-fix exact-match
     * filter dropped every such programme silently — the channel then
     * appeared with empty EPG (or didn't appear in the Guide at all
     * thanks to the `EXISTS` clause on `epg_programmes`). With this
     * map the importer accepts case- and whitespace-mismatched ids
     * and re-writes the programme with the canonical id so downstream
     * queries (which join `epg_programmes.channel_tvg_id` against
     * `content.tvg_id`) still match.
     *
     * Empty / null / duplicate-after-normalize ids: first canonical
     * form wins. There shouldn't be intra-catalog collisions in
     * practice (a provider doesn't ship two channels with `cnn.us`
     * and `CNN.us`); if they ever do, the first one keeps its EPG.
     */
    private fun loadCanonicalTvgIdMap(): Map<String, String> {
        val ids = db.contentQueries.distinctLiveTvgIds().executeAsList()
        val map = HashMap<String, String>(ids.size)
        for (id in ids) {
            val key = id.trim().lowercase()
            if (key.isNotEmpty()) {
                map.putIfAbsent(key, id)
            }
        }
        return map
    }

    private fun recordError(msg: String?) {
        if (msg.isNullOrBlank()) {
            db.settingsQueries.delete(EpgRepository.LAST_ERROR_KEY)
        } else {
            db.settingsQueries.upsert(EpgRepository.LAST_ERROR_KEY, msg)
        }
    }

    private data class EpgTarget(val url: String, val sourceKey: String)

    /** MK.EPG.B — Phase-1 result: a target paired with its downloaded temp file, ready for Phase-2 ingest. */
    private data class DownloadedTarget(val target: EpgTarget, val file: File)

    companion object {
        private const val GLOBAL = EpgRepository.GLOBAL_SOURCE_KEY
        private const val USER_AGENT = "YancoTV/0.1 (Android)"
        private const val FLUSH_EVERY = 500
        private const val PROGRESS_TICK_MS = 5_000L

        // MK.24.I.7 / MB-230 — replaced the per-class private OkHttpClient
        // with a `newBuilder()` from the Koin-provided shared instance
        // (see constructor's `sharedHttp` + `http` field). Eliminates the
        // duplicate connection pool / dispatcher / DNS / TLS that the old
        // private static `HTTP` field carried.
    }
}
