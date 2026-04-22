package com.yancotv.android.sync

import android.content.Context
import android.util.Xml
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.epg.BulkEpgWriter
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.parsers.XmltvProgramme
import com.yancotv.shared.parsers.parseXmltvTimestamp
import com.yancotv.shared.types.EpgRefreshResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

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
) {

    /** Progress callback — sent on phase transitions and every ~5 s during streaming. */
    fun interface Progress {
        suspend fun report(msg: String)
    }

    suspend fun refresh(onProgress: Progress = Progress { /* no-op */ }): EpgRefreshResult =
        withContext(Dispatchers.IO) {
            val targets = collectTargets()
            if (targets.isEmpty()) {
                onProgress.report("No EPG URLs configured")
                return@withContext EpgRefreshResult(ok = true, programmeCount = 0, channelCount = 0)
            }

            val knownTvgIds = loadKnownTvgIds()
            logger.info("EPG stream: user has ${knownTvgIds.size} live channels with tvg_id; EPG rows for unknown channels will be filtered out")

            val session = writer.openSession()
            val errors = mutableListOf<String>()
            var anySucceeded = false

            try {
                session.begin()
                for ((idx, target) in targets.withIndex()) {
                    val label = "${idx + 1}/${targets.size} (${target.sourceKey})"
                    onProgress.report("Downloading EPG $label")
                    val tempFile = File(context.cacheDir, "epg-${UUID.randomUUID()}.bin")
                    try {
                        val dlStart = System.currentTimeMillis()
                        val bytes = downloadToFile(target.url, tempFile)
                        logger.info("EPG stream: downloaded ${bytes} bytes in ${System.currentTimeMillis() - dlStart}ms from ${target.url}")

                        onProgress.report("Parsing EPG $label")
                        val parseStart = System.currentTimeMillis()
                        val beforeRows = session.rowsWritten
                        streamInto(
                            session = session,
                            sourceKey = target.sourceKey,
                            sourceIdForDb = if (target.sourceKey == GLOBAL) null else target.sourceKey,
                            file = tempFile,
                            knownTvgIds = knownTvgIds,
                            onProgress = onProgress,
                        )
                        val written = session.rowsWritten - beforeRows
                        logger.info("EPG stream: source ${target.sourceKey} ingested $written rows (filtered) in ${System.currentTimeMillis() - parseStart}ms")
                        if (written > 0) anySucceeded = true else errors.add("${target.sourceKey}: 0 programmes after filter")
                    } catch (t: Throwable) {
                        val msg = t.message ?: t::class.simpleName ?: "unknown"
                        errors.add("${target.sourceKey}: $msg")
                        logger.error("EPG stream failed for ${target.sourceKey}: $msg")
                    } finally {
                        runCatching { tempFile.delete() }
                    }
                }

                if (!anySucceeded) {
                    session.rollback()
                    val detail = if (errors.isEmpty()) "All EPG sources produced no rows" else errors.joinToString(" | ")
                    recordError(detail)
                    return@withContext EpgRefreshResult(ok = false, error = detail)
                }

                onProgress.report("Writing ${session.rowsWritten} programmes to database…")
                session.commit(lastRefreshedMs = System.currentTimeMillis())
                if (errors.isEmpty()) {
                    recordError(null)
                } else {
                    recordError("Partial: " + errors.joinToString(" | "))
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
        }

    // ───── download ─────

    private fun downloadToFile(url: String, dest: File): Long {
        val client = HTTP.newCall(
            Request.Builder()
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
        knownTvgIds: Set<String>,
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
            var kept = 0
            var lastTick = System.currentTimeMillis()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "programme") {
                    seen++
                    val prog = readProgramme(parser)
                    if (prog != null && (knownTvgIds.isEmpty() || prog.channelId in knownTvgIds)) {
                        buffer.add(prog)
                        kept++
                        if (buffer.size >= FLUSH_EVERY) {
                            session.writeBatch(sourceKey, sourceIdForDb, buffer)
                            buffer.clear()
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= PROGRESS_TICK_MS) {
                                lastTick = now
                                onProgress.report("Writing… $kept kept / $seen scanned")
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
            logger.info("EPG parser: scanned $seen programmes, kept $kept after tvg_id filter")
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
        val global = db.settingsQueries.get(EpgRepository.GLOBAL_URL_KEY).executeAsOneOrNull()?.takeIf { it.isNotBlank() }
        if (global != null) out.add(EpgTarget(url = global, sourceKey = GLOBAL))
        return out
    }

    private fun loadKnownTvgIds(): Set<String> =
        db.contentQueries.distinctLiveTvgIds().executeAsList().toHashSet()

    private fun recordError(msg: String?) {
        if (msg.isNullOrBlank()) {
            db.settingsQueries.delete(EpgRepository.LAST_ERROR_KEY)
        } else {
            db.settingsQueries.upsert(EpgRepository.LAST_ERROR_KEY, msg)
        }
    }

    private data class EpgTarget(val url: String, val sourceKey: String)

    companion object {
        private const val GLOBAL = EpgRepository.GLOBAL_SOURCE_KEY
        private const val USER_AGENT = "YancoTV/0.1 (Android)"
        private const val FLUSH_EVERY = 500
        private const val PROGRESS_TICK_MS = 5_000L

        // Shared OkHttp instance. Timeouts sized for multi-MB XMLTV files on
        // slow residential connections. Follow redirects so providers that
        // 302 to a CDN work.
        private val HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
