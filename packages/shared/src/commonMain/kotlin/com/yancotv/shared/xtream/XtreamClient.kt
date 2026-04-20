package com.yancotv.shared.xtream

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.http.HttpResponseError
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import com.yancotv.shared.types.Result
import kotlinx.coroutines.delay

/**
 * Kotlin port of `@yancotv/core` `xtream/client.ts`. Behavior parity required.
 * Commented blocks mirror the TS source so reviewers can audit line-for-line.
 *
 * The HttpClient contract returns `Any?` — expected shape is what
 * `JSON.parse` would produce: Map<String, Any?>, List<Any?>, String,
 * Number, Boolean, or null. All field access is defensive (mirrors the
 * TS `String(x ?? '')` / `Number(x) || 0` idioms).
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

private const val MAX_RESPONSE_BYTES: Long = 150L * 1024 * 1024
private const val MAX_RETRIES = 3
private val RETRY_DELAYS = longArrayOf(1000, 3000, 8000)

private val SUB_LANG_REGEX = Regex("""[._/-]([a-z]{2,3})\.(?:srt|vtt|ass|ssa)$""", RegexOption.IGNORE_CASE)

private fun inferLangFromUrl(url: String): String {
    val path = url.lowercase().split("?")[0]
    val m = SUB_LANG_REGEX.find(path)
    if (m != null) return m.groupValues[1]
    return "und"
}

private fun isRetryableError(message: String): Boolean {
    return message.contains("timed out") ||
        message.contains("ECONNRESET") ||
        message.contains("ECONNREFUSED") ||
        message.contains("ETIMEDOUT") ||
        message.contains("ENOTFOUND") ||
        message.contains("socket hang up") ||
        message.contains("HTTP 429") ||
        message.contains("HTTP 502") ||
        message.contains("HTTP 503") ||
        message.contains("HTTP 504")
}

/** Mirrors TS `String(x ?? '')`. */
private fun str(v: Any?, default: String = ""): String =
    if (v == null) default else v.toString()

/** Mirrors TS `Number(x) || 0` for integer fields. */
private fun num(v: Any?, default: Int = 0): Int {
    if (v == null) return default
    return when (v) {
        is Number -> v.toInt()
        is String -> v.toDoubleOrNull()?.toInt() ?: default
        else -> default
    }
}

/** Truthy check matching JS. Returns false for null, "", 0, false. */
private fun truthy(v: Any?): Boolean {
    if (v == null) return false
    return when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        else -> true
    }
}

/** URL-encode matching JS `encodeURIComponent`. */
private fun enc(s: String): String {
    // kotlin.net.URLEncoder isn't in common. Hand-roll percent-encoding
    // matching JS encodeURIComponent (does NOT encode: A-Z a-z 0-9 - _ . ! ~ * ' ( )).
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

@Suppress("UNCHECKED_CAST")
private fun asMap(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun asList(v: Any?): List<Any?>? = v as? List<Any?>

data class XtreamClientOptions(
    val http: HttpClient,
    val logger: Logger = NOOP_LOGGER,
    val timeoutMs: Long = 60_000,
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

    suspend fun authenticate(): Result<XtreamAuthInfo, Throwable> {
        val data = request("get_account_info")
        if (data !is Result.Ok) return data as Result<XtreamAuthInfo, Throwable>

        val raw = asMap(data.value)
        val userInfo = raw?.let { asMap(it["user_info"]) }
            ?: return Result.Err(Exception("Invalid auth response: missing user_info"))

        if (userInfo["auth"] == 0 || userInfo["auth"] == 0.0 || userInfo["status"] == "Disabled") {
            return Result.Err(Exception("Account disabled or invalid credentials"))
        }

        val serverInfo = asMap(raw["server_info"]) ?: emptyMap()

        return Result.Ok(
            XtreamAuthInfo(
                userInfo = XtreamUserInfo(
                    username = str(userInfo["username"]),
                    status = str(userInfo["status"], "Unknown"),
                    expDate = if (userInfo["exp_date"] != null) str(userInfo["exp_date"]) else null,
                    isTrial = str(userInfo["is_trial"]) == "1",
                    activeCons = num(userInfo["active_cons"]),
                    maxConnections = num(userInfo["max_connections"]),
                ),
                serverInfo = XtreamServerInfo(
                    url = str(serverInfo["url"]),
                    port = str(serverInfo["port"]),
                    httpsPort = if (serverInfo["https_port"] != null) str(serverInfo["https_port"]) else null,
                    rtmpPort = if (serverInfo["rtmp_port"] != null) str(serverInfo["rtmp_port"]) else null,
                    serverProtocol = str(serverInfo["server_protocol"], "http"),
                    timeNow = str(serverInfo["time_now"]),
                    timezone = str(serverInfo["timezone"]),
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

        val list = asList(data.value) ?: emptyList()
        val streams = list.mapNotNull { asMap(it) }.map { s ->
            XtreamLiveStream(
                num = num(s["num"]),
                name = str(s["name"]),
                streamType = str(s["stream_type"], "live"),
                streamId = num(s["stream_id"]),
                streamIcon = str(s["stream_icon"]),
                epgChannelId = str(s["epg_channel_id"]),
                added = str(s["added"]),
                categoryId = str(s["category_id"]),
                categoryIds = (asList(s["category_ids"]) ?: emptyList()).map { num(it) },
                customSid = str(s["custom_sid"]),
                tvArchive = num(s["tv_archive"]),
                directSource = str(s["direct_source"]),
                tvArchiveDuration = num(s["tv_archive_duration"]),
            )
        }
        return Result.Ok(streams)
    }

    suspend fun getVodStreams(categoryId: String? = null): Result<List<XtreamVodStream>, Throwable> {
        val extra = if (categoryId != null) "&category_id=$categoryId" else ""
        val data = request("get_vod_streams$extra")
        if (data !is Result.Ok) return data as Result<List<XtreamVodStream>, Throwable>

        val list = asList(data.value) ?: emptyList()
        val streams = list.mapNotNull { asMap(it) }.map { s ->
            XtreamVodStream(
                num = num(s["num"]),
                name = str(s["name"]),
                streamType = str(s["stream_type"], "movie"),
                streamId = num(s["stream_id"]),
                streamIcon = str(s["stream_icon"]),
                rating = str(s["rating"]),
                added = str(s["added"]),
                categoryId = str(s["category_id"]),
                containerExtension = str(s["container_extension"], "mp4"),
                directSource = str(s["direct_source"]),
            )
        }
        return Result.Ok(streams)
    }

    suspend fun getSeriesList(categoryId: String? = null): Result<List<XtreamSeriesInfo>, Throwable> {
        val extra = if (categoryId != null) "&category_id=$categoryId" else ""
        val data = request("get_series$extra")
        if (data !is Result.Ok) return data as Result<List<XtreamSeriesInfo>, Throwable>

        val list = asList(data.value) ?: emptyList()
        val series = list.mapNotNull { asMap(it) }.map { s ->
            XtreamSeriesInfo(
                num = num(s["num"]),
                name = str(s["name"]),
                seriesId = num(s["series_id"]),
                cover = str(s["cover"]),
                plot = str(s["plot"]),
                cast = str(s["cast"]),
                director = str(s["director"]),
                genre = str(s["genre"]),
                releaseDate = str(s["releaseDate"] ?: s["release_date"]),
                rating = str(s["rating"]),
                categoryId = str(s["category_id"]),
                lastModified = str(s["last_modified"]),
            )
        }
        return Result.Ok(series)
    }

    suspend fun getSeriesInfo(seriesId: Int): Result<XtreamSeriesDetail, Throwable> {
        val data = request("get_series_info&series_id=$seriesId")
        if (data !is Result.Ok) return data as Result<XtreamSeriesDetail, Throwable>

        val raw = asMap(data.value) ?: emptyMap()
        val info = asMap(raw["info"]) ?: emptyMap()

        val seasonsRaw = asList(raw["seasons"]) ?: emptyList()
        val seasons = seasonsRaw.mapNotNull { asMap(it) }.map { s ->
            val sn = num(s["season_number"] ?: s["season"])
            XtreamSeasonRef(
                seasonNumber = sn,
                name = str(s["name"], "Season $sn"),
            )
        }

        val episodes = mutableMapOf<String, List<XtreamSeriesEpisode>>()
        val epMap = asMap(raw["episodes"])
        if (epMap != null) {
            for ((seasonNum, eps) in epMap) {
                val epList = asList(eps) ?: emptyList()
                episodes[seasonNum] = epList.mapNotNull { asMap(it) }.map { e ->
                    val epInfo = asMap(e["info"])
                    XtreamSeriesEpisode(
                        id = str(e["id"]),
                        episodeNum = num(e["episode_num"]),
                        title = str(e["title"]),
                        containerExtension = str(e["container_extension"], "mp4"),
                        info = XtreamEpisodeInfo(
                            duration = epInfo?.get("duration")?.let { if (truthy(it)) str(it) else null },
                            season = epInfo?.get("season")?.let { if (truthy(it)) num(it) else null },
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
                    name = str(info["name"]),
                    cover = str(info["cover"]),
                    plot = str(info["plot"]),
                    cast = str(info["cast"]),
                    director = str(info["director"]),
                    genre = str(info["genre"]),
                    releaseDate = str(info["releaseDate"] ?: info["release_date"]),
                    rating = str(info["rating"]),
                ),
            ),
        )
    }

    suspend fun getVodInfo(vodId: Int): Result<XtreamVodDetail, Throwable> {
        val data = request("get_vod_info&vod_id=$vodId")
        if (data !is Result.Ok) return data as Result<XtreamVodDetail, Throwable>

        val raw = asMap(data.value) ?: emptyMap()
        val info = asMap(raw["info"]) ?: asMap(raw["movie_data"]) ?: emptyMap()
        val movieData = asMap(raw["movie_data"]) ?: emptyMap()

        val backdrop = info["backdrop_path"] ?: info["backdropPath"] ?: ""
        val backdropUrl = when (backdrop) {
            is List<*> -> str(backdrop.firstOrNull())
            else -> str(backdrop)
        }

        val subs = mutableListOf<XtreamSubtitle>()
        val rawSubs = asList(info["subtitles"]) ?: asList(raw["subtitles"]) ?: emptyList()
        for (s in rawSubs) {
            when (s) {
                is String -> if (s.isNotEmpty()) subs.add(XtreamSubtitle(inferLangFromUrl(s), s))
                else -> {
                    val m = asMap(s) ?: continue
                    val url = str(m["url"] ?: m["href"])
                    if (url.isEmpty()) continue
                    val language = str(m["language"] ?: m["lang"] ?: m["locale"] ?: inferLangFromUrl(url))
                    subs.add(XtreamSubtitle(language, url))
                }
            }
        }

        val tmdbRaw = info["tmdb_id"] ?: info["tmdb"] ?: movieData["tmdb_id"]
        val tmdbId = if (truthy(tmdbRaw)) {
            val n = num(tmdbRaw, 0)
            if (n == 0) null else n
        } else null

        val ratingStr = when {
            truthy(info["rating"]) -> str(info["rating"])
            truthy(info["rating_5based"]) -> "${info["rating_5based"]}/5"
            else -> ""
        }

        return Result.Ok(
            XtreamVodDetail(
                name = str(info["name"] ?: info["title"]),
                plot = str(info["plot"] ?: info["description"]),
                cast = str(info["cast"] ?: info["actors"]),
                director = str(info["director"]),
                genre = str(info["genre"] ?: info["category_name"]),
                releaseDate = str(info["releasedate"] ?: info["release_date"] ?: info["releaseDate"]),
                rating = ratingStr,
                duration = str(info["duration"] ?: info["duration_secs"]),
                cover = str(info["movie_image"] ?: info["cover_big"] ?: info["cover"]),
                backdropUrl = backdropUrl,
                tagline = str(info["tagline"]),
                youtubeTrailer = str(info["youtube_trailer"] ?: info["youtubeTrailer"]),
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

    private suspend fun request(action: String): Result<Any?, Throwable> {
        val url =
            "$baseUrl/player_api.php?username=${enc(username)}&password=${enc(password)}&action=$action"

        var attempt = 0
        while (attempt <= MAX_RETRIES) {
            try {
                val body = http.getJson(
                    url,
                    HttpRequestOptions(
                        timeoutMs = timeoutMs,
                        maxResponseBytes = MAX_RESPONSE_BYTES,
                    ),
                )
                return Result.Ok(body)
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

        val list = asList(data.value) ?: emptyList()
        val cats = list.mapNotNull { asMap(it) }.map { c ->
            XtreamCategory(
                categoryId = str(c["category_id"]),
                categoryName = str(c["category_name"]),
                parentId = num(c["parent_id"]),
            )
        }
        return Result.Ok(cats)
    }
}
