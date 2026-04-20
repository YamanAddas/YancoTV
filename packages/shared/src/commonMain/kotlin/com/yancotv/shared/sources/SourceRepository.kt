package com.yancotv.shared.sources

import com.yancotv.shared.db.Sources
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import com.yancotv.shared.parsers.parseM3u
import com.yancotv.shared.stalker.StalkerClient
import com.yancotv.shared.stalker.StalkerClientOptions
import com.yancotv.shared.types.AddSourceInput
import com.yancotv.shared.types.Result
import com.yancotv.shared.types.Source
import com.yancotv.shared.types.SourceType
import com.yancotv.shared.types.UpdateSourceInput
import com.yancotv.shared.xtream.XtreamClient
import com.yancotv.shared.xtream.XtreamClientOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

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
    private val credentialStore: CredentialStore,
    private val http: HttpClient,
    private val fileReader: FileContentReader,
    private val clock: () -> Long,
    private val idGenerator: () -> String = { defaultId(clock) },
    private val logger: Logger = NOOP_LOGGER,
) {

    fun addSource(input: AddSourceInput): Source {
        validate(input)
        val id = idGenerator()
        val now = clock()

        val usernameBlob = input.username?.takeIf { it.isNotEmpty() }
            ?.let { credentialStore.encrypt(it) }
        val passwordBlob = input.password?.takeIf { it.isNotEmpty() }
            ?.let { credentialStore.encrypt(it) }
        val macBlob = input.macAddress?.takeIf { it.isNotEmpty() }
            ?.let { credentialStore.encrypt(it) }

        val priority = nextPriority()

        db.sourcesQueries.insert(
            id = id,
            name = input.name,
            type = serializeType(input.type),
            url = input.url,
            file_path = input.filePath,
            username_encrypted = usernameBlob,
            password_encrypted = passwordBlob,
            mac_address_encrypted = macBlob,
            epg_url = input.epgUrl,
            user_agent = input.userAgent,
            last_synced = null,
            last_sync_error = null,
            is_active = true,
            priority = priority.toLong(),
            channel_count = 0,
            auto_sync_interval = 0,
            created_at = now,
            updated_at = now,
        )

        return getById(id) ?: error("insert succeeded but row missing: $id")
    }

    fun getAll(): List<Source> =
        db.sourcesQueries.selectAll().executeAsList().map { it.toDomain() }

    fun getById(id: String): Source? =
        db.sourcesQueries.selectById(id).executeAsOneOrNull()?.toDomain()

    fun updateSource(input: UpdateSourceInput): Source {
        val existing = db.sourcesQueries.selectById(input.id).executeAsOneOrNull()
            ?: error("Source not found: ${input.id}")
        val now = clock()

        val usernameBlob = input.username?.takeIf { it.isNotEmpty() }
            ?.let { credentialStore.encrypt(it) } ?: existing.username_encrypted
        val passwordBlob = input.password?.takeIf { it.isNotEmpty() }
            ?.let { credentialStore.encrypt(it) } ?: existing.password_encrypted
        val macBlob = input.macAddress?.takeIf { it.isNotEmpty() }
            ?.let { credentialStore.encrypt(it) } ?: existing.mac_address_encrypted

        db.sourcesQueries.updateFields(
            name = input.name ?: existing.name,
            url = input.url ?: existing.url,
            username_encrypted = usernameBlob,
            password_encrypted = passwordBlob,
            mac_address_encrypted = macBlob,
            epg_url = input.epgUrl ?: existing.epg_url,
            user_agent = input.userAgent ?: existing.user_agent,
            auto_sync_interval = input.autoSyncInterval?.toLong() ?: existing.auto_sync_interval,
            updated_at = now,
            id = existing.id,
        )

        return getById(input.id) ?: error("update failed: ${input.id}")
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

    fun removeSource(id: String) {
        // Content rows cascade via the FK.
        db.sourcesQueries.deleteById(id)
    }

    /**
     * Destructive sync. The [ContentWriter] wraps DELETE + INSERT in a single
     * transaction so a mid-sync failure leaves the previous catalog intact.
     */
    fun syncSource(id: String): Flow<SyncProgress> = flow {
        val source = getById(id) ?: run {
            emit(SyncProgress(SyncProgress.Phase.ERROR, message = "Source not found: $id"))
            return@flow
        }

        try {
            emit(SyncProgress(SyncProgress.Phase.FETCHING, message = "Fetching ${source.name}"))
            val writer = ContentWriter(db)
            val now = clock()
            val inserted = when (source.type) {
                SourceType.M3U_URL -> syncM3uUrl(source, writer, now) { cur, total ->
                    emit(SyncProgress(SyncProgress.Phase.WRITING, cur, total))
                }
                SourceType.M3U_FILE -> syncM3uFile(source, writer, now) { cur, total ->
                    emit(SyncProgress(SyncProgress.Phase.WRITING, cur, total))
                }
                SourceType.XTREAM -> syncXtream(source, writer, now) { phase, cur, total, msg ->
                    emit(SyncProgress(phase, cur, total, msg))
                }
                SourceType.STALKER -> syncStalker(source, writer, now) { phase, cur, total, msg ->
                    emit(SyncProgress(phase, cur, total, msg))
                }
            }

            db.sourcesQueries.updateSyncResult(
                last_synced = now,
                last_sync_error = null,
                channel_count = inserted.toLong(),
                updated_at = now,
                id = id,
            )
            emit(SyncProgress(SyncProgress.Phase.DONE, inserted, inserted))
        } catch (t: Throwable) {
            val msg = t.message ?: t.toString()
            logger.error("sync failed for ${source.id}: $msg")
            db.sourcesQueries.updateSyncResult(
                last_synced = source.lastSynced,
                last_sync_error = msg,
                channel_count = source.channelCount.toLong(),
                updated_at = clock(),
                id = id,
            )
            emit(SyncProgress(SyncProgress.Phase.ERROR, message = msg))
        }
    }

    // ───── sync helpers ─────

    private suspend fun syncM3uUrl(
        source: Source,
        writer: ContentWriter,
        now: Long,
        onProgress: suspend (Int, Int) -> Unit,
    ): Int {
        val url = source.url ?: error("m3u_url source missing url")
        val text = http.getText(
            url,
            HttpRequestOptions(
                timeoutMs = 120_000,
                headers = source.userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap(),
            ),
        )
        val parsed = parseM3u(text, logger)
        // Intermediate ContentWriter progress ticks can't suspend out of the
        // SQLDelight transaction, so we emit a single WRITING tick after the
        // transaction commits. Total is known up front.
        onProgress(0, parsed.entries.size)
        val inserted = writer.writeM3u(source.id, parsed.entries, now)
        onProgress(inserted, parsed.entries.size)
        return inserted
    }

    private suspend fun syncM3uFile(
        source: Source,
        writer: ContentWriter,
        now: Long,
        onProgress: suspend (Int, Int) -> Unit,
    ): Int {
        val path = source.filePath ?: error("m3u_file source missing filePath")
        val text = fileReader.readText(path)
        val parsed = parseM3u(text, logger)
        onProgress(0, parsed.entries.size)
        val inserted = writer.writeM3u(source.id, parsed.entries, now)
        onProgress(inserted, parsed.entries.size)
        return inserted
    }

    private suspend fun syncXtream(
        source: Source,
        writer: ContentWriter,
        now: Long,
        onProgress: suspend (SyncProgress.Phase, Int, Int, String?) -> Unit,
    ): Int {
        val url = source.url ?: error("xtream source missing url")
        val username = source.usernameOrThrow()
        val password = source.passwordOrThrow()
        val client = XtreamClient(url, username, password, XtreamClientOptions(http, logger))

        onProgress(SyncProgress.Phase.FETCHING, 0, 0, "Authenticating")
        val auth = client.authenticate()
        if (auth is Result.Err) throw auth.error

        onProgress(SyncProgress.Phase.FETCHING, 0, 0, "Fetching catalog")

        val liveCatsR = client.getLiveCategories()
        val vodCatsR = client.getVodCategories()
        val seriesCatsR = client.getSeriesCategories()
        val liveR = client.getLiveStreams()
        val vodR = client.getVodStreams()
        val seriesR = client.getSeriesList()

        val liveCats = liveCatsR.unwrap().associate { it.categoryId to it.categoryName }
        val vodCats = vodCatsR.unwrap().associate { it.categoryId to it.categoryName }
        val seriesCats = seriesCatsR.unwrap().associate { it.categoryId to it.categoryName }

        val liveBundle = XtreamBundle(liveR.unwrap(), liveCats)
        val vodBundle = XtreamBundle(vodR.unwrap(), vodCats)
        val seriesBundle = XtreamBundle(seriesR.unwrap(), seriesCats)

        val total = liveBundle.items.size + vodBundle.items.size + seriesBundle.items.size
        onProgress(SyncProgress.Phase.WRITING, 0, total, null)
        val inserted = writer.writeXtream(source.id, client, liveBundle, vodBundle, seriesBundle, now)
        onProgress(SyncProgress.Phase.WRITING, inserted, total, null)
        return inserted
    }

    private suspend fun syncStalker(
        source: Source,
        writer: ContentWriter,
        now: Long,
        onProgress: suspend (SyncProgress.Phase, Int, Int, String?) -> Unit,
    ): Int {
        val url = source.url ?: error("stalker source missing url")
        val mac = source.macOrThrow()
        val client = StalkerClient(url, mac, StalkerClientOptions(http, logger))

        onProgress(SyncProgress.Phase.FETCHING, 0, 0, "Authenticating")
        val auth = client.authenticate()
        if (auth is Result.Err) throw auth.error

        onProgress(SyncProgress.Phase.FETCHING, 0, 0, "Fetching catalog")

        val liveCats = client.getLiveCategories().unwrap().associate { it.id to it.title }
        val vodCats = client.getVodCategories().unwrap().associate { it.id to it.title }
        val seriesCats = client.getSeriesCategories().unwrap().associate { it.id to it.title }
        val live = client.getLiveChannels().unwrap()
        val vod = client.getVodItems().unwrap()
        val series = client.getSeriesList().unwrap()

        val liveBundle = StalkerBundle(live, liveCats)
        val vodBundle = StalkerBundle(vod, vodCats)
        val seriesBundle = StalkerBundle(series, seriesCats)

        val total = liveBundle.items.size + vodBundle.items.size + seriesBundle.items.size
        onProgress(SyncProgress.Phase.WRITING, 0, total, null)
        val inserted = writer.writeStalker(source.id, liveBundle, vodBundle, seriesBundle, now)
        onProgress(SyncProgress.Phase.WRITING, inserted, total, null)
        return inserted
    }

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
        lastSynced = last_synced,
        isActive = is_active,
        priority = priority.toInt(),
        channelCount = channel_count.toInt(),
        lastSyncError = last_sync_error,
        autoSyncInterval = auto_sync_interval.toInt(),
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
    }
}

private fun <T, E : Throwable> Result<T, E>.unwrap(): T = when (this) {
    is Result.Ok -> value
    is Result.Err -> throw error
}

