package com.yancotv.shared.sources

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import com.yancotv.shared.db.Sources
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.http.redactCredentials
import com.yancotv.shared.http.redactErrorMessage
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import com.yancotv.shared.parsers.M3uEntry
import com.yancotv.shared.parsers.m3uLineSequence
import com.yancotv.shared.parsers.parseM3uLines
import com.yancotv.shared.stalker.StalkerClient
import com.yancotv.shared.stalker.StalkerClientOptions
import com.yancotv.shared.types.AddSourceInput
import com.yancotv.shared.types.Result
import com.yancotv.shared.types.Source
import com.yancotv.shared.types.SourceType
import com.yancotv.shared.types.UpdateSourceInput
import com.yancotv.shared.xtream.XtreamClient
import com.yancotv.shared.xtream.XtreamClientOptions
import com.yancotv.shared.xtream.parseXtreamExpiry
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * CRUD + sync orchestrator for sources. Mirrors desktop `source-manager.ts` +
 * `source-sync.ts`. All DB writes are routed through SQLDelight; credentials
 * flow through [CredentialStore] so plaintext never touches the BLOB columns.
 *
 * [syncSource] emits progress as a cold [Flow] — a single subscriber drains
 * it and rethrows via [SyncProgress.Phase.ERROR] instead of exceptions so the
 * UI layer can render the failure without losing earlier progress.
 *
 * Clock is injected to keep tests deterministic. ID generation uses a
 * time-prefixed random suffix so newly-added sources sort predictably on
 * conflict.
 */
class SourceRepository(
    private val db: YancoDb,
    private val driver: SqlDriver,
    private val credentialStore: CredentialStore,
    private val http: HttpClient,
    private val fileReader: FileContentReader,
    private val clock: () -> Long,
    private val idGenerator: () -> String = { defaultId(clock) },
    private val logger: Logger = NOOP_LOGGER,
) {
    fun addSource(input: AddSourceInput): Source {
        // Breadcrumbs so a silent hang shows up in logcat: each step logs
        // before it starts, so whichever message is last in the log is the
        // step that stalled. Hooked up MK.6 while chasing the Save-hang that
        // wedged the AddSource dialog on Fire TV.
        logger.info("addSource[${input.type}] validating")
        validate(input)
        val id = idGenerator()
        val now = clock()

        logger.info("addSource[$id] encrypt username")
        val usernameBlob =
            input.username
                ?.takeIf { it.isNotEmpty() }
                ?.let { credentialStore.encrypt(it) }
        logger.info("addSource[$id] encrypt password")
        val passwordBlob =
            input.password
                ?.takeIf { it.isNotEmpty() }
                ?.let { credentialStore.encrypt(it) }
        logger.info("addSource[$id] encrypt mac")
        val macBlob =
            input.macAddress
                ?.takeIf { it.isNotEmpty() }
                ?.let { credentialStore.encrypt(it) }

        logger.info("addSource[$id] next priority")
        val priority = nextPriority()

        // Auto-derive an Xtream provider's XMLTV endpoint when the user didn't
        // paste one explicitly. Beats TiviMate's UX: most users never know the
        // EPG URL is just `xmltv.php` on the same host as `player_api.php` —
        // without this, Xtream sources land with no EPG and the Guide sits
        // empty until the user pokes at settings.
        val derivedEpgUrl =
            input.epgUrl?.takeIf { it.isNotBlank() }
                ?: if (input.type == SourceType.XTREAM &&
                    !input.url.isNullOrBlank() &&
                    !input.username.isNullOrBlank() &&
                    !input.password.isNullOrBlank()
                ) {
                    XtreamClient(
                        input.url,
                        input.username,
                        input.password,
                        XtreamClientOptions(http, logger),
                    ).buildEpgUrl()
                } else {
                    null
                }

        logger.info("addSource[$id] insert")
        db.sourcesQueries.insert(
            id = id,
            name = input.name,
            type = serializeType(input.type),
            url = input.url,
            file_path = input.filePath,
            username_encrypted = usernameBlob,
            password_encrypted = passwordBlob,
            mac_address_encrypted = macBlob,
            epg_url = derivedEpgUrl,
            user_agent = input.userAgent,
            referer = input.referer,
            last_synced = null,
            last_sync_error = null,
            is_active = true,
            priority = priority.toLong(),
            channel_count = 0,
            auto_sync_interval = 0,
            epg_priority = 0,
            auto_sync_on_start = false,
            created_at = now,
            updated_at = now,
        )
        logger.info("addSource[$id] insert complete")

        val saved = getById(id) ?: error("insert succeeded but row missing: $id")
        logger.info("addSource[$id] done")
        return saved
    }

    fun getAll(): List<Source> = db.sourcesQueries
        .selectAll()
        .executeAsList()
        .map { it.toDomain() }

    fun getById(id: String): Source? = db.sourcesQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toDomain()

    fun updateSource(input: UpdateSourceInput): Source {
        val existing =
            db.sourcesQueries.selectById(input.id).executeAsOneOrNull()
                ?: error("Source not found: ${input.id}")
        val now = clock()

        val usernameBlob =
            input.username
                ?.takeIf { it.isNotEmpty() }
                ?.let { credentialStore.encrypt(it) } ?: existing.username_encrypted
        val passwordBlob =
            input.password
                ?.takeIf { it.isNotEmpty() }
                ?.let { credentialStore.encrypt(it) } ?: existing.password_encrypted
        val macBlob =
            input.macAddress
                ?.takeIf { it.isNotEmpty() }
                ?.let { credentialStore.encrypt(it) } ?: existing.mac_address_encrypted

        db.sourcesQueries.updateFields(
            name = input.name ?: existing.name,
            url = input.url ?: existing.url,
            username_encrypted = usernameBlob,
            password_encrypted = passwordBlob,
            mac_address_encrypted = macBlob,
            epg_url = input.epgUrl ?: existing.epg_url,
            user_agent = input.userAgent ?: existing.user_agent,
            referer = input.referer ?: existing.referer,
            auto_sync_interval = input.autoSyncInterval?.toLong() ?: existing.auto_sync_interval,
            updated_at = now,
            id = existing.id,
        )

        return getById(input.id) ?: error("update failed: ${input.id}")
    }

    /**
     * Decrypted Xtream credentials for [sourceId]. Returns null for non-Xtream
     * sources, for missing/blank URL, or if either credential is absent. Used
     * by the catchup builder to construct timeshift stream URLs without
     * teaching that layer about the credential store.
     */
    fun xtreamCredentials(sourceId: String): XtreamCredentials? {
        val source = getById(sourceId) ?: return null
        if (source.type != SourceType.XTREAM) return null
        val baseUrl = source.url?.takeIf { it.isNotBlank() } ?: return null
        val row = db.sourcesQueries.selectById(sourceId).executeAsOneOrNull() ?: return null
        val userBlob = row.username_encrypted ?: return null
        val passBlob = row.password_encrypted ?: return null
        val username = runCatching { credentialStore.decrypt(userBlob) }.getOrNull() ?: return null
        val password = runCatching { credentialStore.decrypt(passBlob) }.getOrNull() ?: return null
        return XtreamCredentials(baseUrl = baseUrl, username = username, password = password)
    }

    fun reorder(idsInOrder: List<String>) {
        val now = clock()
        db.transaction {
            idsInOrder.forEachIndexed { idx, id ->
                db.sourcesQueries.setPriority(idx.toLong(), now, id)
            }
        }
    }

    fun setActive(id: String, active: Boolean) {
        db.sourcesQueries.setActive(active, clock(), id)
    }

    /** MK.15.7 — set the EPG merge priority for a source. Higher values
     *  win when two sources cover the same `tvg_id`. Independent of
     *  `priority` (which orders sources in the shell rail). */
    fun setEpgPriority(id: String, priority: Int) {
        db.sourcesQueries.setEpgPriority(priority.toLong(), clock(), id)
    }

    /** v9 → v10 — toggle whether [id] auto-syncs every time the app
     *  starts. Read by [autoSyncOnStartList] from the Android shell. */
    fun setAutoSyncOnStart(id: String, enabled: Boolean) {
        db.sourcesQueries.setAutoSyncOnStart(enabled, clock(), id)
    }

    /** v9 → v10 — every source whose `auto_sync_on_start` flag is set.
     *  Skips inactive sources (the user may have toggled the flag on a
     *  source they later deactivated; respect the deactivation). The
     *  Android shell calls this once per MainActivity creation and
     *  enqueues a sync via SourceSyncCoordinator for each row. */
    fun autoSyncOnStartList(): List<Source> = db.sourcesQueries
        .selectAutoSyncOnStart()
        .executeAsList()
        .map { it.toDomain() }

    /** Reactive source list for Settings UIs that need to repaint after a
     *  setEpgPriority / setActive write. SQLDelight emits on any sources-
     *  table change, so writes from any other path light this up too. */
    fun allFlow(): Flow<List<Source>> = db.sourcesQueries
        .selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)
        .map { rows -> rows.map { it.toDomain() } }

    /**
     * MB-351 — removing a source has to clean the two side tables by hand.
     *
     * `content_first_seen` and `recent_channels` deliberately carry NO FOREIGN
     * KEY to `content(id)`. That is not an oversight: a sync is a full
     * replacement, so `ON DELETE CASCADE` would erase both tables on every
     * refresh — the bug they were built to avoid. The cost of that choice is
     * that the one case where a cascade WOULD be right, deleting the source
     * outright, has to be written out.
     *
     * Until now it was not, and `content_first_seen.deleteBySource` sat unused.
     * A Fire TV carrying a real catalogue held 963 stamps belonging to a source
     * with no content left; deleting the 272,419-item provider would have
     * stranded roughly 15 MB of rows that nothing could ever read or remove.
     *
     * Ordering is load-bearing, not incidental:
     *
     *  * `recent_channels` FIRST, because it keys on `content_id` and can only
     *    be scoped to this source by joining through `content`. Once the source
     *    goes, the FK cascade takes that content and the rows become
     *    unidentifiable — deletable afterwards only by a content-existence
     *    sweep, which is unsafe near a sync.
     *  * `content_first_seen` keys on `source_id` directly, so it is
     *    order-independent; it sits here for symmetry.
     *  * `sources` LAST, which cascades `content` (and through it `episodes`,
     *    `favorites`, `watch_history`).
     *
     * One transaction, so a failure part-way cannot leave a live source whose
     * stamps have been thrown away — that state would make the source's next
     * sync look like a first import and silently suppress its "Recently added"
     * for a further two syncs.
     */
    fun removeSource(id: String) {
        db.transaction {
            db.recentChannelsQueries.deleteBySourceContent(id)
            db.contentFirstSeenQueries.deleteBySource(id)
            // Content rows cascade via the FK.
            db.sourcesQueries.deleteById(id)
        }
    }

    /**
     * Destructive sync. M3U and Stalker paths use a single transaction so a
     * mid-sync failure leaves the previous catalog intact. The Xtream path
     * fetches per-category in parallel and writes chunks incrementally (large
     * catalogs otherwise OOM the Fire TV heap) — a mid-sync failure there
     * leaves partial rows, which [updateSyncResult] flags via
     * `last_sync_error` so the UI can warn the user.
     *
     * Uses [channelFlow] so parallel fetchers can emit progress concurrently
     * without racing a shared flow collector.
     */
    fun syncSource(id: String): Flow<SyncProgress> = channelFlow {
        val source =
            getById(id) ?: run {
                logger.warn("syncSource[$id] not found")
                send(SyncProgress(SyncProgress.Phase.ERROR, detail = SyncDetail.SourceNotFound(id)))
                return@channelFlow
            }

        logger.info("syncSource[$id] start type=${source.type} name=${source.name}")
        // MB-353 — a previous sync for this source started and never finished,
        // so its catalogue is missing an unknown number of rows and
        // `sources.channel_count` is still reporting the size from the last
        // COMPLETED sync. No recovery action is needed here — this sync is about
        // to replace the catalogue anyway — but the line means "the app looked
        // empty yesterday" can be traced to a specific abandoned run instead of
        // guessed at, which is how the original incident had to be diagnosed.
        if (BulkContentWriter.syncWasInterrupted(driver, id)) {
            logger.warn("syncSource[$id] previous sync never completed — catalogue has been incomplete since; this run replaces it")
        }
        try {
            send(SyncProgress(SyncProgress.Phase.FETCHING, detail = SyncDetail.Connecting))
            val writer = ContentWriter(db)
            val now = clock()
            val inserted =
                when (source.type) {
                    SourceType.M3U_URL ->
                        syncM3uUrl(source, writer, now) { cur, total ->
                            send(SyncProgress(SyncProgress.Phase.WRITING, cur, total))
                        }
                    SourceType.M3U_FILE ->
                        syncM3uFile(source, writer, now) { cur, total ->
                            send(SyncProgress(SyncProgress.Phase.WRITING, cur, total))
                        }
                    SourceType.XTREAM ->
                        syncXtream(source, writer, now) { phase, cur, total, msg ->
                            send(SyncProgress(phase, cur, total, msg))
                        }
                    SourceType.STALKER ->
                        syncStalker(source, writer, now) { phase, cur, total, msg ->
                            send(SyncProgress(phase, cur, total, msg))
                        }
                }

            db.sourcesQueries.updateSyncResult(
                last_synced = now,
                last_sync_error = null,
                channel_count = inserted.toLong(),
                updated_at = now,
                id = id,
            )
            logger.info("syncSource[$id] done inserted=$inserted")
            send(SyncProgress(SyncProgress.Phase.DONE, inserted, inserted))
        } catch (ce: CancellationException) {
            // User pressed Cancel (or scope was torn down). Cancellation isn't
            // a "failure" the user needs to be told about on next open, so the
            // error text stays null. Must rethrow so the Flow shuts down
            // instead of being treated as a normal error emission.
            //
            // MB-291 — but the STORED COUNT must not be left lying. The inner
            // sync's `catch (t: Throwable)` also catches CancellationException
            // (it is a Throwable) and runs `bulk.abortSource`, which drops
            // every row written so far — and `prepareSource` already deleted
            // the previous catalog before the first chunk. So a cancel taken
            // after prepareSource leaves the source EMPTY while
            // `channel_count` still holds the pre-sync figure: the Sources
            // screen cheerfully reports "12.3k items - Ready" over an empty
            // table, and Home/Guide render nothing with no explanation.
            //
            // Re-reading the real count is what makes this correct in BOTH
            // directions — a cancel during the network fetch (before
            // prepareSource) leaves the old catalog intact, and a blanket
            // `channel_count = 0` would then be just as much of a lie in the
            // other direction. Ground truth is right either way.
            //
            // Blocking SQLDelight calls still run inside a cancelled
            // coroutine (cancellation only interrupts suspension points), so
            // this write lands before the rethrow.
            logger.info("syncSource[$id] cancelled")
            runCatching {
                val remaining = db.contentQueries.countBySource(id).executeAsOne()
                if (remaining != source.channelCount.toLong()) {
                    db.sourcesQueries.updateSyncResult(
                        last_synced = source.lastSynced,
                        // Cancelling after the catalog was cleared is the one
                        // cancellation the user does need surfaced: the source
                        // is now empty and only a re-sync restores it.
                        last_sync_error =
                        if (remaining == 0L) "Sync cancelled before any content was written - re-sync to restore this source." else null,
                        channel_count = remaining,
                        updated_at = clock(),
                        id = id,
                    )
                    logger.info("syncSource[$id] cancelled — channel_count corrected to $remaining")
                }
            }.onFailure { logger.warn("syncSource[$id] cancel-path count refresh failed: ${it.message}") }
            throw ce
        } catch (t: Throwable) {
            // Redact credentials before this string lands in three
            // PII-leak-prone surfaces: Android logcat (visible to adb /
            // bug reports), `sources.last_sync_error` (persisted), and
            // the Sources screen's failure-text rendering. Xtream
            // 401/404 URLs include `?username=…&password=…` verbatim.
            val msg = redactErrorMessage(t)
            logger.error("sync failed for ${source.id}: $msg")
            db.sourcesQueries.updateSyncResult(
                last_synced = source.lastSynced,
                last_sync_error = msg,
                channel_count = source.channelCount.toLong(),
                updated_at = clock(),
                id = id,
            )
            send(SyncProgress(SyncProgress.Phase.ERROR, detail = SyncDetail.Failure(msg)))
        }
    }

    // ───── sync helpers ─────

    private suspend fun syncM3uUrl(
        source: Source,
        @Suppress("UNUSED_PARAMETER") legacyWriter: ContentWriter,
        now: Long,
        onProgress: suspend (Int, Int) -> Unit,
    ): Int {
        val url = source.url ?: error("m3u_url source missing url")
        // MB-230 — stream the playlist through the parser instead of
        // materialising it. `getText` buffered the whole body into a UTF-16
        // String and the parser then allocated several more full copies of it
        // (see parseM3uLines); on a 255k-entry provider that peaked at
        // hundreds of MB against a Fire TV Stick's 384 MB heap. getSource is
        // the same memory-bounded path the Xtream catalog fetches already use.
        val parsed =
            http.getSource(
                url,
                HttpRequestOptions(
                    timeoutMs = 120_000,
                    headers = source.userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap(),
                ),
            ) { body -> parseM3uLines(body.m3uLineSequence(), logger) }
        adoptDiscoveredEpgUrl(source, parsed.epgUrl, now)
        return writeM3uBulk(source.id, parsed.entries, now, onProgress)
    }

    private suspend fun syncM3uFile(
        source: Source,
        @Suppress("UNUSED_PARAMETER") legacyWriter: ContentWriter,
        now: Long,
        onProgress: suspend (Int, Int) -> Unit,
    ): Int {
        val path = source.filePath ?: error("m3u_file source missing filePath")
        // MB-230 — same streaming contract as the URL path above. A local
        // playlist is routinely the same provider dump, just off a USB stick.
        val parsed =
            fileReader.readSource(path) { body ->
                parseM3uLines(body.m3uLineSequence(), logger)
            }
        adoptDiscoveredEpgUrl(source, parsed.epgUrl, now)
        return writeM3uBulk(source.id, parsed.entries, now, onProgress)
    }

    /**
     * Bulk-write path for M3U sources (MK.3.e, 2026-04-21). Mirrors the
     * Xtream `prepareSource → writeChunk+ → finishSource` lifecycle so large
     * playlists don't hold the WAL write lock for minutes. Chunks the parsed
     * entry list into 500-row batches; each chunk runs in its own short
     * transaction and is committed before the next one starts, so the Guide
     * and Home screens can keep querying while sync is in flight.
     */
    private suspend fun writeM3uBulk(sourceId: String, entries: List<M3uEntry>, now: Long, onProgress: suspend (Int, Int) -> Unit): Int {
        val bulk = BulkContentWriter(driver, clock, logger)
        val total = entries.size
        onProgress(0, total)
        bulk.prepareSource(sourceId)
        var written = 0
        var sort = 0L
        try {
            var i = 0
            while (i < entries.size) {
                val end = minOf(i + CHUNK_SIZE, entries.size)
                val chunk = entries.subList(i, end)
                val wrote =
                    withContext(Dispatchers.Default) {
                        bulk.writeM3uChunk(sourceId, chunk, now, sort)
                    }
                sort += wrote
                written += wrote
                onProgress(written, total)
                i = end
            }
            withContext(Dispatchers.Default) { bulk.finishSource(sourceId) }
            return written
        } catch (t: Throwable) {
            bulk.abortSource(sourceId)
            throw t
        }
    }

    /**
     * If the M3U header carried a `url-tvg` / `x-tvg-url` and the source has
     * no `epg_url` configured, persist it so the next EPG refresh picks it up
     * automatically. Many providers ship the right XMLTV URL in the header —
     * not using it means the user has to paste it by hand or live without EPG.
     */
    private fun adoptDiscoveredEpgUrl(source: Source, discovered: String?, now: Long) {
        val d = discovered?.takeIf { it.isNotBlank() } ?: return
        if (!source.epgUrl.isNullOrBlank()) return
        // Redact in case the M3U header EPG URL itself carries credentials —
        // some providers serve `url-tvg=http://provider/?username=…`.
        logger.info("syncSource[${source.id}] adopting M3U-header EPG URL: ${redactCredentials(d)}")
        db.sourcesQueries.setEpgUrl(d, now, source.id)
    }

    /**
     * Xtream sync — parallel-fetch + serialized-writer (MK.3.e, 2026-04-21).
     *
     * Prior design fetched live → VOD → series strictly sequentially. Since
     * `readRemaining()` buffers the full HTTP body before the parser starts,
     * each phase's network TTFB+download latency was pure dead time with
     * zero DB activity. Three phases in series = 3× the network wait.
     *
     * New shape:
     *   1. Three category maps fetched in parallel (unchanged — small).
     *   2. **All three catalog endpoints fetched in parallel.** A 100MB
     *      VOD download now overlaps with live's write phase and series's
     *      parse phase. Peak heap stays bounded because each phase still
     *      drops chunks after writing; only one raw response body is
     *      resident at a time in steady state.
     *   3. A single [Mutex] serializes the three writer coroutines so
     *      `BulkContentWriter`'s `BEGIN IMMEDIATE` transactions never
     *      race each other (SQLite is single-writer; contention here
     *      would just spin on `busy_timeout`). The mutex costs nothing
     *      when one writer is CPU-bound — the other two continue
     *      downloading/parsing concurrently.
     *
     * Expected wall-clock: ≈ max(fetch_live, fetch_vod, fetch_series) +
     * serialized-write time, vs. sum-of-three previously. On a 200k-item
     * provider over a 5 Mbps link that's a 2–3× speedup.
     */
    private suspend fun syncXtream(
        source: Source,
        @Suppress("UNUSED_PARAMETER") legacyWriter: ContentWriter,
        now: Long,
        onProgress: suspend (SyncProgress.Phase, Int, Int, SyncDetail?) -> Unit,
    ): Int {
        val url = source.url ?: error("xtream source missing url")
        val username = source.usernameOrThrow()
        val password = source.passwordOrThrow()
        val client = XtreamClient(url, username, password, XtreamClientOptions(http, logger))
        val bulk = BulkContentWriter(driver, clock, logger)

        onProgress(SyncProgress.Phase.FETCHING, 0, 0, SyncDetail.Authenticating)
        val auth = client.authenticate()
        if (auth is Result.Err) throw auth.error

        // MK.30.3 — the handshake already carried the account expiry; before
        // this it was parsed and then dropped on the floor. Persist it so
        // Settings → Sources can answer "when does this playlist stop
        // working". Only written on the success path, so a later failed sync
        // can't wipe a known expiry (see `setExpiresAt` in Sources.sq).
        if (auth is Result.Ok) {
            val expiresAt = parseXtreamExpiry(auth.value.userInfo.expDate)
            db.sourcesQueries.setExpiresAt(expires_at = expiresAt, updated_at = now, id = source.id)
        }

        onProgress(SyncProgress.Phase.FETCHING, 0, 0, SyncDetail.FetchingCategories)
        val fetchMark =
            kotlin.time.TimeSource.Monotonic
                .markNow()

        val (liveCats, vodCats, seriesCats) =
            coroutineScope {
                val a = async { client.getLiveCategories().unwrap().associate { it.categoryId to it.categoryName } }
                val b = async { client.getVodCategories().unwrap().associate { it.categoryId to it.categoryName } }
                val c = async { client.getSeriesCategories().unwrap().associate { it.categoryId to it.categoryName } }
                Triple(a.await(), b.await(), c.await())
            }

        bulk.prepareSource(source.id)

        // Running totals. Each phase tracks its own sort_order via the
        // LIVE/VOD/SERIES _BASE constants + local counter — no cross-phase
        // mutation, so no extra synchronization needed for sort assignment.
        // Shared `total` + the progress emit are guarded by the same
        // [writeMutex] that serializes the DB transactions.
        var liveWritten = 0
        var vodWritten = 0
        var seriesWritten = 0
        var total = 0
        val writeMutex = Mutex()
        val ioCtx = Dispatchers.Default

        try {
            coroutineScope {
                onProgress(SyncProgress.Phase.FETCHING, 0, 0, SyncDetail.FetchingCatalog)

                val liveJob =
                    async {
                        var sort = ContentWriter.LIVE_BASE
                        val res =
                            client.streamLiveStreams(chunkSize = 500) { chunk ->
                                writeMutex.withLock {
                                    val wrote =
                                        withContext(ioCtx) {
                                            bulk.writeLiveChunk(source.id, client, chunk, liveCats, now, sort)
                                        }
                                    sort += wrote
                                    liveWritten += wrote
                                    total += wrote
                                    onProgress(SyncProgress.Phase.WRITING, total, 0, SyncDetail.WritingLive(liveWritten))
                                }
                            }
                        if (res is Result.Err) throw res.error
                    }

                val vodJob =
                    async {
                        var sort = ContentWriter.VOD_BASE
                        val res =
                            client.streamVodStreams(chunkSize = 500) { chunk ->
                                writeMutex.withLock {
                                    val wrote =
                                        withContext(ioCtx) {
                                            bulk.writeVodChunk(source.id, client, chunk, vodCats, now, sort)
                                        }
                                    sort += wrote
                                    vodWritten += wrote
                                    total += wrote
                                    onProgress(SyncProgress.Phase.WRITING, total, 0, SyncDetail.WritingMovies(vodWritten))
                                }
                            }
                        if (res is Result.Err) throw res.error
                    }

                val seriesJob =
                    async {
                        var sort = ContentWriter.SERIES_BASE
                        val res =
                            client.streamSeriesList(chunkSize = 500) { chunk ->
                                writeMutex.withLock {
                                    val wrote =
                                        withContext(ioCtx) {
                                            bulk.writeSeriesChunk(source.id, chunk, seriesCats, now, sort)
                                        }
                                    sort += wrote
                                    seriesWritten += wrote
                                    total += wrote
                                    onProgress(SyncProgress.Phase.WRITING, total, 0, SyncDetail.WritingSeries(seriesWritten))
                                }
                            }
                        if (res is Result.Err) throw res.error
                    }

                awaitAll(liveJob, vodJob, seriesJob)
            }

            onProgress(SyncProgress.Phase.WRITING, total, total, SyncDetail.Finalizing)
            withContext(ioCtx) { bulk.finishSource(source.id) }

            val elapsedMs = fetchMark.elapsedNow().inWholeMilliseconds
            logger.info(
                "syncSource[${source.id}] done — live=$liveWritten vod=$vodWritten series=$seriesWritten " +
                    "(total=$total) in ${elapsedMs}ms",
            )
            return total
        } catch (t: Throwable) {
            // Redact before logging: Ktor exception messages echo the
            // request URL, which for Xtream `player_api.php` includes
            // `?username=…&password=…`. The sibling outer-handler at
            // line 323 already redacts for the persisted column, but
            // the logcat / Sentry breadcrumb sink leaked uncensored
            // until this redaction landed.
            logger.error("syncSource[${source.id}] failed: ${redactErrorMessage(t)} — rolling back partial writes")
            bulk.abortSource(source.id)
            throw t
        }
    }

    /**
     * Stalker sync — bulk-write path (MK.3.e, 2026-04-21).
     *
     * Categories and catalogs are fetched in parallel (Stalker's endpoints
     * are independent), then written through [BulkContentWriter] in 500-row
     * chunks. Mirrors the Xtream lifecycle so a 50k-item portal doesn't
     * freeze the UI for minutes inside a single giant transaction.
     */
    private suspend fun syncStalker(
        source: Source,
        @Suppress("UNUSED_PARAMETER") legacyWriter: ContentWriter,
        now: Long,
        onProgress: suspend (SyncProgress.Phase, Int, Int, SyncDetail?) -> Unit,
    ): Int {
        val url = source.url ?: error("stalker source missing url")
        val mac = source.macOrThrow()
        val client = StalkerClient(url, mac, StalkerClientOptions(http, logger))
        val bulk = BulkContentWriter(driver, clock, logger)

        onProgress(SyncProgress.Phase.FETCHING, 0, 0, SyncDetail.Authenticating)
        val auth = client.authenticate()
        if (auth is Result.Err) throw auth.error

        onProgress(SyncProgress.Phase.FETCHING, 0, 0, SyncDetail.FetchingCatalog)

        val (liveCats, vodCats, seriesCats, live, vod, series) =
            coroutineScope {
                val a = async { client.getLiveCategories().unwrap().associate { it.id to it.title } }
                val b = async { client.getVodCategories().unwrap().associate { it.id to it.title } }
                val c = async { client.getSeriesCategories().unwrap().associate { it.id to it.title } }
                val d = async { client.getLiveChannels().unwrap() }
                val e = async { client.getVodItems().unwrap() }
                val f = async { client.getSeriesList().unwrap() }
                StalkerFetch(a.await(), b.await(), c.await(), d.await(), e.await(), f.await())
            }

        val total = live.size + vod.size + series.size
        onProgress(SyncProgress.Phase.WRITING, 0, total, null)
        bulk.prepareSource(source.id)
        var written = 0
        val ioCtx = Dispatchers.Default
        try {
            var sort = 0L
            var i = 0
            while (i < live.size) {
                val end = minOf(i + CHUNK_SIZE, live.size)
                val wrote =
                    withContext(ioCtx) {
                        bulk.writeStalkerLiveChunk(source.id, live.subList(i, end), liveCats, now, sort)
                    }
                sort += wrote
                written += wrote
                onProgress(SyncProgress.Phase.WRITING, written, total, SyncDetail.WritingLive(written))
                i = end
            }
            i = 0
            while (i < vod.size) {
                val end = minOf(i + CHUNK_SIZE, vod.size)
                val wrote =
                    withContext(ioCtx) {
                        bulk.writeStalkerVodChunk(source.id, vod.subList(i, end), vodCats, now, sort)
                    }
                sort += wrote
                written += wrote
                onProgress(SyncProgress.Phase.WRITING, written, total, SyncDetail.WritingMovies(written))
                i = end
            }
            i = 0
            while (i < series.size) {
                val end = minOf(i + CHUNK_SIZE, series.size)
                val wrote =
                    withContext(ioCtx) {
                        bulk.writeStalkerSeriesChunk(source.id, series.subList(i, end), seriesCats, now, sort)
                    }
                sort += wrote
                written += wrote
                onProgress(SyncProgress.Phase.WRITING, written, total, SyncDetail.WritingSeries(written))
                i = end
            }
            withContext(ioCtx) { bulk.finishSource(source.id) }
            return written
        } catch (t: Throwable) {
            bulk.abortSource(source.id)
            throw t
        }
    }

    private data class StalkerFetch(
        val liveCats: Map<String, String>,
        val vodCats: Map<String, String>,
        val seriesCats: Map<String, String>,
        val live: List<com.yancotv.shared.stalker.StalkerChannel>,
        val vod: List<com.yancotv.shared.stalker.StalkerVodItem>,
        val series: List<com.yancotv.shared.stalker.StalkerSeriesItem>,
    )

    // ───── private ─────

    private fun nextPriority(): Int {
        val rows = db.sourcesQueries.selectAll().executeAsList()
        return if (rows.isEmpty()) 0 else (rows.maxOf { it.priority } + 1).toInt()
    }

    private fun Source.usernameOrThrow(): String {
        val row = db.sourcesQueries.selectById(id).executeAsOne()
        val blob = row.username_encrypted ?: error("xtream source missing username")
        return credentialStore.decrypt(blob)
    }

    private fun Source.passwordOrThrow(): String {
        val row = db.sourcesQueries.selectById(id).executeAsOne()
        val blob = row.password_encrypted ?: error("xtream source missing password")
        return credentialStore.decrypt(blob)
    }

    private fun Source.macOrThrow(): String {
        val row = db.sourcesQueries.selectById(id).executeAsOne()
        val blob = row.mac_address_encrypted ?: error("stalker source missing mac")
        return credentialStore.decrypt(blob)
    }

    private fun Sources.toDomain(): Source = Source(
        id = id,
        name = name,
        type = deserializeType(type),
        url = url,
        filePath = file_path,
        epgUrl = epg_url,
        userAgent = user_agent,
        referer = referer,
        lastSynced = last_synced,
        isActive = is_active,
        priority = priority.toInt(),
        channelCount = channel_count.toInt(),
        lastSyncError = last_sync_error,
        autoSyncInterval = auto_sync_interval.toInt(),
        epgPriority = epg_priority.toInt(),
        autoSyncOnStart = auto_sync_on_start,
        expiresAt = expires_at,
        createdAt = created_at,
        updatedAt = updated_at,
    )

    private fun validate(input: AddSourceInput) {
        require(input.name.isNotBlank()) { "name is required" }
        when (input.type) {
            SourceType.M3U_URL -> require(!input.url.isNullOrBlank()) { "m3u_url requires url" }
            SourceType.M3U_FILE -> require(!input.filePath.isNullOrBlank()) { "m3u_file requires filePath" }
            SourceType.XTREAM -> {
                require(!input.url.isNullOrBlank()) { "xtream requires url" }
                require(!input.username.isNullOrBlank()) { "xtream requires username" }
                require(!input.password.isNullOrBlank()) { "xtream requires password" }
            }
            SourceType.STALKER -> {
                require(!input.url.isNullOrBlank()) { "stalker requires url" }
                require(!input.macAddress.isNullOrBlank()) { "stalker requires macAddress" }
            }
        }
    }

    companion object {
        internal fun serializeType(t: SourceType): String = when (t) {
            SourceType.M3U_URL -> "m3u_url"
            SourceType.M3U_FILE -> "m3u_file"
            SourceType.XTREAM -> "xtream"
            SourceType.STALKER -> "stalker"
        }

        internal fun deserializeType(s: String): SourceType = when (s) {
            "m3u_url" -> SourceType.M3U_URL
            "m3u_file" -> SourceType.M3U_FILE
            "xtream" -> SourceType.XTREAM
            "stalker" -> SourceType.STALKER
            else -> error("unknown source type: $s")
        }

        private fun defaultId(clock: () -> Long): String {
            val ts = clock().toString(16)
            val rnd = Random.nextInt(0x10000).toString(16).padStart(4, '0')
            return "src-$ts-$rnd"
        }

        /**
         * Rows per bulk-writer chunk. Larger than `BulkContentWriter.BATCH_ROWS`
         * (80 rows per multi-row INSERT) — a 500-row chunk issues ~6 INSERTs
         * inside one short transaction, then releases the WAL write lock so
         * UI queries can interleave.
         */
        private const val CHUNK_SIZE = 500
    }
}

private fun <T, E : Throwable> Result<T, E>.unwrap(): T = when (this) {
    is Result.Ok -> value
    is Result.Err -> throw error
}
