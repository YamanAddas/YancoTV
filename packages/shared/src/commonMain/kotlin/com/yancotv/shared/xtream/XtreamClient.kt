package com.yancotv.shared.xtream

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.http.HttpResponseError
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import com.yancotv.shared.types.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.io.decodeSourceToSequence

/**
 * Kotlin port of `@yancotv/core` `xtream/client.ts`. Behavior parity required.
 * Commented blocks mirror the TS source so reviewers can audit line-for-line.
 *
 * Parses directly into [JsonElement] instead of building an intermediate
 * `Map<String, Any?>` tree — the prior double-materialize blew through ~4x the
 * raw JSON size in heap (text + JsonElement + Map/List) and OOM'd Fire TV on
 * multi-MB Xtream catalogs. The walk helpers ([strOf], [numOf], …) preserve the
 * defensive `String(x ?? '')` / `Number(x) || 0` semantics of the TS source.
 */

data class XtreamUserInfo(
    val username: String,
    val status: String,
    val expDate: String?,
    val isTrial: Boolean,
    val activeCons: Int,
    val maxConnections: Int,
)

data class XtreamServerInfo(
    val url: String,
    val port: String,
    val httpsPort: String?,
    val rtmpPort: String?,
    val serverProtocol: String,
    val timeNow: String,
    val timezone: String,
)

data class XtreamAuthInfo(
    val userInfo: XtreamUserInfo,
    val serverInfo: XtreamServerInfo,
)

data class XtreamCategory(
    val categoryId: String,
    val categoryName: String,
    val parentId: Int,
)

data class XtreamLiveStream(
    val num: Int,
    val name: String,
    val streamType: String,
    val streamId: Int,
    val streamIcon: String,
    val epgChannelId: String,
    val added: String,
    val categoryId: String,
    val categoryIds: List<Int>,
    val customSid: String,
    val tvArchive: Int,
    val directSource: String,
    val tvArchiveDuration: Int,
)

data class XtreamVodStream(
    val num: Int,
    val name: String,
    val streamType: String,
    val streamId: Int,
    val streamIcon: String,
    val rating: String,
    val added: String,
    val categoryId: String,
    val containerExtension: String,
    val directSource: String,
)

data class XtreamSeriesInfo(
    val num: Int,
    val name: String,
    val seriesId: Int,
    val cover: String,
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val releaseDate: String,
    val rating: String,
    val categoryId: String,
    val lastModified: String,
)

data class XtreamEpisodeInfo(
    val duration: String? = null,
    val season: Int? = null,
)

data class XtreamSeriesEpisode(
    val id: String,
    val episodeNum: Int,
    val title: String,
    val containerExtension: String,
    val info: XtreamEpisodeInfo,
)

data class XtreamSeasonRef(
    val seasonNumber: Int,
    val name: String,
)

data class XtreamSeriesDetailInfo(
    val name: String,
    val cover: String,
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val releaseDate: String,
    val rating: String,
)

data class XtreamSeriesDetail(
    val seasons: List<XtreamSeasonRef>,
    val episodes: Map<String, List<XtreamSeriesEpisode>>,
    val info: XtreamSeriesDetailInfo,
)

data class XtreamSubtitle(
    val language: String,
    val url: String,
)

data class XtreamVodDetail(
    val name: String,
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val releaseDate: String,
    val rating: String,
    val duration: String,
    val cover: String,
    val backdropUrl: String,
    val tagline: String,
    val youtubeTrailer: String,
    val subtitles: List<XtreamSubtitle>,
    val tmdbId: Int?,
)

enum class XtreamStreamType(val path: String) {
    LIVE("live"),
    MOVIE("movie"),
    SERIES("series"),
}

// Sanity cap for non-catalog endpoints (auth, get_*_info, single-entity
// responses). The catalog streaming path ([streamLiveStreams]/VOD/Series) is
// memory-safe via `Json.decodeToSequence` and does NOT enforce this cap —
// big providers routinely ship 100MB+ VOD catalogs and the old 50MB ceiling
// was the reason VOD/series sync silently failed. The streaming path decodes
// each array element and drops it as each 500-chunk is flushed to SQLite, so
// peak heap stays bounded regardless of payload size.
private const val MAX_SMALL_RESPONSE_BYTES: Long = 16L * 1024 * 1024
private const val MAX_RETRIES = 3
private val RETRY_DELAYS = longArrayOf(1000, 3000, 8000)

/**
 * Timeout for catalog fetches. Live-streams endpoints are fast, but VOD and
 * series on big providers can push 50–150MB of JSON; 60s at 5 Mbps = barely
 * enough for 30MB, so the old 60s default was the second reason VOD/series
 * was "broken". 180s covers the 99th-percentile real-provider catalog over
 * a 5 Mbps residential link.
 */
private const val CATALOG_TIMEOUT_MS: Long = 180_000

private val SUB_LANG_REGEX = Regex("""[._/-]([a-z]{2,3})\.(?:srt|vtt|ass|ssa)$""", RegexOption.IGNORE_CASE)

private fun inferLangFromUrl(url: String): String {
    val path = url.lowercase().split("?")[0]
    val m = SUB_LANG_REGEX.find(path)
    if (m != null) return m.groupValues[1]
    return "und"
}

private fun isRetryableError(message: String): Boolean {
    // Matchers are case-insensitive because Ktor's HttpRequestTimeoutException
    // prints "Request timeout has expired …" while OkHttp lowers to "timeout".
    // Pre-MK.6.c we only matched "timed out" and silently skipped retries on
    // the first auth stall — worst of both worlds (no retry + no feedback).
    val lower = message.lowercase()
    return lower.contains("timeout") ||
        lower.contains("timed out") ||
        lower.contains("econnreset") ||
        lower.contains("econnrefused") ||
        lower.contains("etimedout") ||
        lower.contains("enotfound") ||
        lower.contains("socket hang up") ||
        lower.contains("unable to resolve host") ||
        message.contains("HTTP 429") ||
        message.contains("HTTP 502") ||
        message.contains("HTTP 503") ||
        message.contains("HTTP 504")
}

// ───── JsonElement accessors (mirror the TS defensive idioms) ─────

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

/** Mirrors TS `String(x ?? '')`. */
private fun strOf(v: JsonElement?, default: String = ""): String {
    if (v == null || v is JsonNull) return default
    if (v is JsonPrimitive) return v.content
    return v.toString()
}

/** Mirrors TS `Number(x) || 0` for integer fields. */
private fun numOf(v: JsonElement?, default: Int = 0): Int {
    if (v == null || v is JsonNull) return default
    val prim = v as? JsonPrimitive ?: return default
    return prim.content.toDoubleOrNull()?.toInt() ?: default
}

/** Truthy check matching JS. Returns false for null, "", 0, false. */
private fun truthyOf(v: JsonElement?): Boolean {
    if (v == null || v is JsonNull) return false
    val prim = v as? JsonPrimitive ?: return true
    val s = prim.content
    if (s.isEmpty()) return false
    if (!prim.isString) {
        s.toBooleanStrictOrNull()?.let { return it }
        s.toDoubleOrNull()?.let { return it != 0.0 }
    }
    return true
}

/** URL-encode matching JS `encodeURIComponent`. */
private fun enc(s: String): String {
    val sb = StringBuilder()
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if ((ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') ||
            ch == '-' || ch == '_' || ch == '.' || ch == '!' ||
            ch == '~' || ch == '*' || ch == '\'' || ch == '(' || ch == ')'
        ) {
            sb.append(ch)
        } else {
            sb.append('%')
            sb.append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return sb.toString()
}

data class XtreamClientOptions(
    val http: HttpClient,
    val logger: Logger = NOOP_LOGGER,
    val timeoutMs: Long = 60_000,
    /** Shorter timeout for the initial auth probe. Catches unreachable/wrong-URL servers in ~25s instead of the full request budget × retries. */
    val authTimeoutMs: Long = 25_000,
)

class XtreamClient(
    url: String,
    private val username: String,
    private val password: String,
    options: XtreamClientOptions,
) {
    private val baseUrl: String = url
        .replace(Regex("/+$"), "")
        .replace(Regex("/player_api\\.php$"), "")
    private val http: HttpClient = options.http
    private val logger: Logger = options.logger
    private val timeoutMs: Long = options.timeoutMs
    private val authTimeoutMs: Long = options.authTimeoutMs

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun authenticate(): Result<XtreamAuthInfo, Throwable> {
        val data = request("get_account_info", authTimeoutMs)
        if (data !is Result.Ok) return data as Result<XtreamAuthInfo, Throwable>

        val raw = data.value.obj()
            ?: return Result.Err(Exception("Invalid auth response: not a JSON object"))
        val userInfo = raw["user_info"].obj()
            ?: return Result.Err(Exception("Invalid auth response: missing user_info"))

        val authField = userInfo["auth"]
        if (numOf(authField, -1) == 0 || strOf(userInfo["status"]) == "Disabled") {
            return Result.Err(Exception("Account disabled or invalid credentials"))
        }

        val serverInfo = raw["server_info"].obj() ?: JsonObject(emptyMap())

        return Result.Ok(
            XtreamAuthInfo(
                userInfo = XtreamUserInfo(
                    username = strOf(userInfo["username"]),
                    status = strOf(userInfo["status"], "Unknown"),
                    expDate = userInfo["exp_date"]?.let { if (it is JsonNull) null else strOf(it) },
                    isTrial = strOf(userInfo["is_trial"]) == "1",
                    activeCons = numOf(userInfo["active_cons"]),
                    maxConnections = numOf(userInfo["max_connections"]),
                ),
                serverInfo = XtreamServerInfo(
                    url = strOf(serverInfo["url"]),
                    port = strOf(serverInfo["port"]),
                    httpsPort = serverInfo["https_port"]?.let { if (it is JsonNull) null else strOf(it) },
                    rtmpPort = serverInfo["rtmp_port"]?.let { if (it is JsonNull) null else strOf(it) },
                    serverProtocol = strOf(serverInfo["server_protocol"], "http"),
                    timeNow = strOf(serverInfo["time_now"]),
                    timezone = strOf(serverInfo["timezone"]),
                ),
            ),
        )
    }

    suspend fun getLiveCategories(): Result<List<XtreamCategory>, Throwable> =
        fetchCategories("get_live_categories")

    suspend fun getVodCategories(): Result<List<XtreamCategory>, Throwable> =
        fetchCategories("get_vod_categories")

    suspend fun getSeriesCategories(): Result<List<XtreamCategory>, Throwable> =
        fetchCategories("get_series_categories")

    suspend fun getLiveStreams(categoryId: String? = null): Result<List<XtreamLiveStream>, Throwable> {
        val extra = if (categoryId != null) "&category_id=$categoryId" else ""
        val data = request("get_live_streams$extra")
        if (data !is Result.Ok) return data as Result<List<XtreamLiveStream>, Throwable>

        val list = data.value.arr() ?: return Result.Ok(emptyList())
        val streams = ArrayList<XtreamLiveStream>(list.size)
        for (elem in list) {
            val s = elem.obj() ?: continue
            streams.add(parseLiveStream(s))
        }
        return Result.Ok(streams)
    }

    /**
     * Streaming variant: one HTTP request, items handed to [onChunk] in
     * [chunkSize] batches so the caller can write and drop each batch without
     * materializing a whole-catalog List. Returns the total item count.
     *
     * Uses [Json.decodeToSequence] over the response body as a [Source] so no
     * JSON *tree* is ever resident. Each array element is decoded lazily and
     * dropped as soon as its chunk is flushed to SQLite — peak memory is
     * bounded by `chunkSize × sizeof(parsedRow)` (~500 × ~200 bytes ≈ 100KB),
     * not by the raw payload. This is how a 150MB VOD catalog survives the
     * Fire TV 400MB heap budget.
     */
    suspend fun streamLiveStreams(
        chunkSize: Int = 500,
        onChunk: suspend (List<XtreamLiveStream>) -> Unit,
    ): Result<Int, Throwable> = streamArray(
        action = "get_live_streams",
        chunkSize = chunkSize,
        onChunk = onChunk,
        parse = ::parseLiveStream,
    )

    private fun parseLiveStream(s: JsonObject): XtreamLiveStream {
        val catIds = s["category_ids"].arr()?.map { numOf(it) } ?: emptyList()
        return XtreamLiveStream(
            num = numOf(s["num"]),
            name = strOf(s["name"]),
            streamType = strOf(s["stream_type"], "live"),
            streamId = numOf(s["stream_id"]),
            streamIcon = strOf(s["stream_icon"]),
            epgChannelId = strOf(s["epg_channel_id"]),
            added = strOf(s["added"]),
            categoryId = strOf(s["category_id"]),
            categoryIds = catIds,
            customSid = strOf(s["custom_sid"]),
            tvArchive = numOf(s["tv_archive"]),
            directSource = strOf(s["direct_source"]),
            tvArchiveDuration = numOf(s["tv_archive_duration"]),
        )
    }

    suspend fun getVodStreams(categoryId: String? = null): Result<List<XtreamVodStream>, Throwable> {
        val extra = if (categoryId != null) "&category_id=$categoryId" else ""
        val data = request("get_vod_streams$extra")
        if (data !is Result.Ok) return data as Result<List<XtreamVodStream>, Throwable>

        val list = data.value.arr() ?: return Result.Ok(emptyList())
        val streams = ArrayList<XtreamVodStream>(list.size)
        for (elem in list) {
            val s = elem.obj() ?: continue
            streams.add(parseVodStream(s))
        }
        return Result.Ok(streams)
    }

    suspend fun streamVodStreams(
        chunkSize: Int = 500,
        onChunk: suspend (List<XtreamVodStream>) -> Unit,
    ): Result<Int, Throwable> = streamArray(
        action = "get_vod_streams",
        chunkSize = chunkSize,
        onChunk = onChunk,
        parse = ::parseVodStream,
    )

    private fun parseVodStream(s: JsonObject): XtreamVodStream = XtreamVodStream(
        num = numOf(s["num"]),
        name = strOf(s["name"]),
        streamType = strOf(s["stream_type"], "movie"),
        streamId = numOf(s["stream_id"]),
        streamIcon = strOf(s["stream_icon"]),
        rating = strOf(s["rating"]),
        added = strOf(s["added"]),
        categoryId = strOf(s["category_id"]),
        containerExtension = strOf(s["container_extension"], "mp4"),
        directSource = strOf(s["direct_source"]),
    )

    suspend fun getSeriesList(categoryId: String? = null): Result<List<XtreamSeriesInfo>, Throwable> {
        val extra = if (categoryId != null) "&category_id=$categoryId" else ""
        val data = request("get_series$extra")
        if (data !is Result.Ok) return data as Result<List<XtreamSeriesInfo>, Throwable>

        val list = data.value.arr() ?: return Result.Ok(emptyList())
        val series = ArrayList<XtreamSeriesInfo>(list.size)
        for (elem in list) {
            val s = elem.obj() ?: continue
            series.add(parseSeriesInfo(s))
        }
        return Result.Ok(series)
    }

    suspend fun streamSeriesList(
        chunkSize: Int = 500,
        onChunk: suspend (List<XtreamSeriesInfo>) -> Unit,
    ): Result<Int, Throwable> = streamArray(
        action = "get_series",
        chunkSize = chunkSize,
        onChunk = onChunk,
        parse = ::parseSeriesInfo,
    )

    /**
     * Shared streaming array walker. Decodes a JSON array element-by-element
     * from [HttpClient.getSource] — never buffers the full response to String
     * and never materializes a whole [kotlinx.serialization.json.JsonArray]
     * tree. Each item is parsed, batched into [chunkSize], handed to
     * [onChunk], and discarded.
     *
     * Retries via the same policy as [request] since transient 5xx/timeout
     * errors on catalog endpoints are common. A failure mid-stream re-issues
     * the whole request; SQLite-side partial writes are cleaned up by the
     * next successful sync's `beginXtreamSync` DELETE.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private suspend fun <T : Any> streamArray(
        action: String,
        chunkSize: Int,
        onChunk: suspend (List<T>) -> Unit,
        parse: (JsonObject) -> T,
    ): Result<Int, Throwable> {
        val url =
            "$baseUrl/player_api.php?username=${enc(username)}&password=${enc(password)}&action=$action"

        var attempt = 0
        while (attempt <= MAX_RETRIES) {
            try {
                logger.info("xtream [$action] attempt ${attempt + 1} GET (streaming, timeout=${CATALOG_TIMEOUT_MS}ms)")
                val mark = kotlin.time.TimeSource.Monotonic.markNow()

                // No maxResponseBytes here — the streaming decoder is bounded
                // by its own internal buffer + the per-chunk list, not by the
                // raw payload size. Big providers ship 100MB+ VOD catalogs.
                val total = http.getSource(
                    url,
                    HttpRequestOptions(timeoutMs = CATALOG_TIMEOUT_MS),
                ) { source ->
                    withContext(Dispatchers.Default) {
                        var count = 0
                        var buf = ArrayList<T>(chunkSize)
                        val seq = json.decodeSourceToSequence(
                            source,
                            JsonObject.serializer(),
                            DecodeSequenceMode.ARRAY_WRAPPED,
                        )
                        for (obj in seq) {
                            buf.add(parse(obj))
                            if (buf.size >= chunkSize) {
                                onChunk(buf)
                                count += buf.size
                                buf = ArrayList(chunkSize)
                            }
                        }
                        if (buf.isNotEmpty()) {
                            onChunk(buf)
                            count += buf.size
                        }
                        count
                    }
                }

                logger.info("xtream [$action] streamed $total items in ${mark.elapsedNow().inWholeMilliseconds}ms")
                return Result.Ok(total)
            } catch (error: Throwable) {
                val msg = error.message ?: error.toString()
                val httpMsg = if (error is HttpResponseError) "HTTP ${error.status}" else msg

                if (attempt < MAX_RETRIES && isRetryableError(httpMsg)) {
                    val d = if (attempt < RETRY_DELAYS.size) RETRY_DELAYS[attempt] else 8000L
                    logger.warn("Xtream API [$action] attempt ${attempt + 1} failed: $msg — retrying in ${d}ms")
                    delay(d)
                    attempt++
                    continue
                }

                logger.error("Xtream API error [$action]: $msg")
                return Result.Err(error)
            }
        }

        return Result.Err(Exception("Max retries exceeded"))
    }

    private fun parseSeriesInfo(s: JsonObject): XtreamSeriesInfo = XtreamSeriesInfo(
        num = numOf(s["num"]),
        name = strOf(s["name"]),
        seriesId = numOf(s["series_id"]),
        cover = strOf(s["cover"]),
        plot = strOf(s["plot"]),
        cast = strOf(s["cast"]),
        director = strOf(s["director"]),
        genre = strOf(s["genre"]),
        releaseDate = strOf(s["releaseDate"] ?: s["release_date"]),
        rating = strOf(s["rating"]),
        categoryId = strOf(s["category_id"]),
        lastModified = strOf(s["last_modified"]),
    )

    suspend fun getSeriesInfo(seriesId: Int): Result<XtreamSeriesDetail, Throwable> {
        val data = request("get_series_info&series_id=$seriesId")
        if (data !is Result.Ok) return data as Result<XtreamSeriesDetail, Throwable>

        val raw = data.value.obj() ?: JsonObject(emptyMap())
        val info = raw["info"].obj() ?: JsonObject(emptyMap())

        val seasonsRaw = raw["seasons"].arr() ?: JsonArray(emptyList())
        val seasons = seasonsRaw.mapNotNull { it.obj() }.map { s ->
            val sn = numOf(s["season_number"] ?: s["season"])
            XtreamSeasonRef(
                seasonNumber = sn,
                name = strOf(s["name"], "Season $sn"),
            )
        }

        val episodes = mutableMapOf<String, List<XtreamSeriesEpisode>>()
        val epMap = raw["episodes"].obj()
        if (epMap != null) {
            for ((seasonNum, eps) in epMap) {
                val epList = eps.arr() ?: JsonArray(emptyList())
                episodes[seasonNum] = epList.mapNotNull { it.obj() }.map { e ->
                    val epInfo = e["info"].obj()
                    XtreamSeriesEpisode(
                        id = strOf(e["id"]),
                        episodeNum = numOf(e["episode_num"]),
                        title = strOf(e["title"]),
                        containerExtension = strOf(e["container_extension"], "mp4"),
                        info = XtreamEpisodeInfo(
                            duration = epInfo?.get("duration")?.let { if (truthyOf(it)) strOf(it) else null },
                            season = epInfo?.get("season")?.let { if (truthyOf(it)) numOf(it) else null },
                        ),
                    )
                }
            }
        }

        return Result.Ok(
            XtreamSeriesDetail(
                seasons = seasons,
                episodes = episodes,
                info = XtreamSeriesDetailInfo(
                    name = strOf(info["name"]),
                    cover = strOf(info["cover"]),
                    plot = strOf(info["plot"]),
                    cast = strOf(info["cast"]),
                    director = strOf(info["director"]),
                    genre = strOf(info["genre"]),
                    releaseDate = strOf(info["releaseDate"] ?: info["release_date"]),
                    rating = strOf(info["rating"]),
                ),
            ),
        )
    }

    suspend fun getVodInfo(vodId: Int): Result<XtreamVodDetail, Throwable> {
        val data = request("get_vod_info&vod_id=$vodId")
        if (data !is Result.Ok) return data as Result<XtreamVodDetail, Throwable>

        val raw = data.value.obj() ?: JsonObject(emptyMap())
        val info = raw["info"].obj() ?: raw["movie_data"].obj() ?: JsonObject(emptyMap())
        val movieData = raw["movie_data"].obj() ?: JsonObject(emptyMap())

        val backdropField = info["backdrop_path"] ?: info["backdropPath"]
        val backdropUrl = when (backdropField) {
            is JsonArray -> strOf(backdropField.firstOrNull())
            null, is JsonNull -> ""
            else -> strOf(backdropField)
        }

        val subs = mutableListOf<XtreamSubtitle>()
        val rawSubs = info["subtitles"].arr() ?: raw["subtitles"].arr() ?: JsonArray(emptyList())
        for (s in rawSubs) {
            if (s is JsonPrimitive && s.isString) {
                val url = s.content
                if (url.isNotEmpty()) subs.add(XtreamSubtitle(inferLangFromUrl(url), url))
            } else {
                val m = s.obj() ?: continue
                val url = strOf(m["url"] ?: m["href"])
                if (url.isEmpty()) continue
                val language = strOf(m["language"] ?: m["lang"] ?: m["locale"])
                    .ifEmpty { inferLangFromUrl(url) }
                subs.add(XtreamSubtitle(language, url))
            }
        }

        val tmdbRaw = info["tmdb_id"] ?: info["tmdb"] ?: movieData["tmdb_id"]
        val tmdbId = if (truthyOf(tmdbRaw)) {
            val n = numOf(tmdbRaw, 0)
            if (n == 0) null else n
        } else null

        val ratingStr = when {
            truthyOf(info["rating"]) -> strOf(info["rating"])
            truthyOf(info["rating_5based"]) -> "${strOf(info["rating_5based"])}/5"
            else -> ""
        }

        return Result.Ok(
            XtreamVodDetail(
                name = strOf(info["name"] ?: info["title"]),
                plot = strOf(info["plot"] ?: info["description"]),
                cast = strOf(info["cast"] ?: info["actors"]),
                director = strOf(info["director"]),
                genre = strOf(info["genre"] ?: info["category_name"]),
                releaseDate = strOf(info["releasedate"] ?: info["release_date"] ?: info["releaseDate"]),
                rating = ratingStr,
                duration = strOf(info["duration"] ?: info["duration_secs"]),
                cover = strOf(info["movie_image"] ?: info["cover_big"] ?: info["cover"]),
                backdropUrl = backdropUrl,
                tagline = strOf(info["tagline"]),
                youtubeTrailer = strOf(info["youtube_trailer"] ?: info["youtubeTrailer"]),
                subtitles = subs,
                tmdbId = tmdbId,
            ),
        )
    }

    fun buildEpgUrl(): String =
        "$baseUrl/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    fun buildStreamUrl(streamId: Int, type: XtreamStreamType, extension: String? = null): String {
        val defaultExt = if (type == XtreamStreamType.LIVE) "ts" else "mp4"
        val trimmed = extension?.trim().orEmpty()
        val ext = if (trimmed.isNotEmpty()) trimmed else defaultExt
        return "$baseUrl/${type.path}/$username/$password/$streamId.$ext"
    }

    private suspend fun request(
        action: String,
        perRequestTimeoutMs: Long = timeoutMs,
    ): Result<JsonElement, Throwable> {
        val url =
            "$baseUrl/player_api.php?username=${enc(username)}&password=${enc(password)}&action=$action"

        var attempt = 0
        while (attempt <= MAX_RETRIES) {
            try {
                // Breadcrumb so a long "fetching…" in the UI maps to a specific
                // action in logcat — pre-MK.6.c we had no visibility into which
                // of {auth, categories×3, streams×3} was stalling.
                logger.info("xtream [$action] attempt ${attempt + 1} GET (timeout=${perRequestTimeoutMs}ms)")
                val mark = kotlin.time.TimeSource.Monotonic.markNow()
                val text = http.getText(
                    url,
                    HttpRequestOptions(
                        timeoutMs = perRequestTimeoutMs,
                        maxResponseBytes = MAX_SMALL_RESPONSE_BYTES,
                    ),
                )
                logger.info("xtream [$action] ${text.length} chars in ${mark.elapsedNow().inWholeMilliseconds}ms — parsing")
                // Parse inside this client (not via http.getJson) so we can
                // materialize a single JsonElement tree instead of the
                // JsonElement→Map/List double-walk that ships in KtorHttpClient.
                // On a 20MB response that cuts peak heap roughly in half. The
                // parse itself is sync CPU work — Dispatchers.Default keeps it
                // off whatever dispatcher the caller used (typically Main via
                // the Compose collect site) so a 20MB catalog can't ANR.
                val element = withContext(Dispatchers.Default) { json.parseToJsonElement(text) }
                logger.info("xtream [$action] parsed OK")
                return Result.Ok(element)
            } catch (error: Throwable) {
                val msg = error.message ?: error.toString()
                val httpMsg = if (error is HttpResponseError) "HTTP ${error.status}" else msg

                if (attempt < MAX_RETRIES && isRetryableError(httpMsg)) {
                    val d = if (attempt < RETRY_DELAYS.size) RETRY_DELAYS[attempt] else 8000L
                    logger.warn("Xtream API [$action] attempt ${attempt + 1} failed: $msg — retrying in ${d}ms")
                    delay(d)
                    attempt++
                    continue
                }

                logger.error("Xtream API error [$action]: $msg")
                return Result.Err(error)
            }
        }

        return Result.Err(Exception("Max retries exceeded"))
    }

    private suspend fun fetchCategories(action: String): Result<List<XtreamCategory>, Throwable> {
        val data = request(action)
        if (data !is Result.Ok) return data as Result<List<XtreamCategory>, Throwable>

        val list = data.value.arr() ?: return Result.Ok(emptyList())
        val cats = ArrayList<XtreamCategory>(list.size)
        for (elem in list) {
            val c = elem.obj() ?: continue
            cats.add(
                XtreamCategory(
                    categoryId = strOf(c["category_id"]),
                    categoryName = strOf(c["category_name"]),
                    parentId = numOf(c["parent_id"]),
                ),
            )
        }
        return Result.Ok(cats)
    }
}
