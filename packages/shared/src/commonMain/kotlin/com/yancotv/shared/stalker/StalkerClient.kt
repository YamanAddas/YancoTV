package com.yancotv.shared.stalker

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.http.HttpResponseError
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import com.yancotv.shared.types.Result
import kotlinx.coroutines.delay

/**
 * Kotlin port of `@yancotv/core` `stalker/client.ts`. Behavior parity required.
 *
 * The HttpClient contract returns `Any?` — expected shape is what
 * `JSON.parse` would produce: Map<String, Any?>, List<Any?>, String,
 * Number, Boolean, or null. All field access is defensive.
 */

data class StalkerAuthInfo(
    val token: String,
    val portalUrl: String,
    val macAddress: String,
)

data class StalkerCategory(
    val id: String,
    val title: String,
)

data class StalkerChannel(
    val id: Int,
    val name: String,
    val cmd: String,
    val tvGenreId: String,
    val logo: String,
    val epgId: String,
    val number: Int,
    val tvArchive: Int,
    val tvArchiveDuration: Int,
)

data class StalkerVodItem(
    val id: Int,
    val name: String,
    val cmd: String,
    val categoryId: String,
    val logo: String,
    val description: String,
)

data class StalkerSeriesItem(
    val id: Int,
    val name: String,
    val categoryId: String,
    val cover: String,
    val plot: String,
    val genre: String,
)

private const val MAX_RESPONSE_BYTES: Long = 150L * 1024 * 1024
private const val MAX_RETRIES = 3
private val RETRY_DELAYS = longArrayOf(1000, 3000, 8000)
private const val MAX_PAGES = 500

private const val STALKER_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C)"
private const val STALKER_X_USER_AGENT = "Model: MAG254; Link: Ethernet"

private val STREAM_CMD_PREFIX = Regex("""^(?:ffrt|ffmpeg|auto)\s+""", RegexOption.IGNORE_CASE)

private fun isRetryableError(message: String): Boolean =
    message.contains("timed out") ||
        message.contains("ECONNRESET") ||
        message.contains("ECONNREFUSED") ||
        message.contains("ETIMEDOUT") ||
        message.contains("ENOTFOUND") ||
        message.contains("socket hang up") ||
        message.contains("HTTP 429") ||
        message.contains("HTTP 502") ||
        message.contains("HTTP 503") ||
        message.contains("HTTP 504")

private fun str(
    v: Any?,
    default: String = "",
): String = if (v == null) default else v.toString()

private fun num(
    v: Any?,
    default: Int = 0,
): Int {
    if (v == null) return default
    return when (v) {
        is Number -> v.toInt()
        is String -> v.toDoubleOrNull()?.toInt() ?: default
        else -> default
    }
}

@Suppress("UNCHECKED_CAST")
private fun asMap(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun asList(v: Any?): List<Any?>? = v as? List<Any?>

private fun enc(s: String): String {
    val sb = StringBuilder()
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if ((ch in 'A'..'Z') ||
            (ch in 'a'..'z') ||
            (ch in '0'..'9') ||
            ch == '-' ||
            ch == '_' ||
            ch == '.' ||
            ch == '!' ||
            ch == '~' ||
            ch == '*' ||
            ch == '\'' ||
            ch == '(' ||
            ch == ')'
        ) {
            sb.append(ch)
        } else {
            sb.append('%')
            sb.append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return sb.toString()
}

/** Build `application/x-www-form-urlencoded`-style query string from a LinkedHashMap, mirroring TS `URLSearchParams.toString()`. */
private fun buildQuery(params: LinkedHashMap<String, String>): String = params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

data class StalkerClientOptions(
    val http: HttpClient,
    val logger: Logger = NOOP_LOGGER,
    val timeoutMs: Long = 60_000,
)

class StalkerClient(
    portalUrl: String,
    private val macAddress: String,
    options: StalkerClientOptions,
) {
    private val portalUrl: String = portalUrl.replace(Regex("/+$"), "")
    private val http: HttpClient = options.http
    private val logger: Logger = options.logger
    private val timeoutMs: Long = options.timeoutMs
    private var token: String? = null

    suspend fun authenticate(): Result<StalkerAuthInfo, Throwable> {
        val handshake = request("stb", "handshake", mapOf("prehash" to "0"))
        if (handshake !is Result.Ok) return handshake as Result<StalkerAuthInfo, Throwable>

        val tokenData = asMap(handshake.value)
        val jsMap = asMap(tokenData?.get("js"))
        val tok = (jsMap?.get("token") ?: tokenData?.get("token")) as? String
        if (tok.isNullOrEmpty()) {
            return Result.Err(Exception("Stalker handshake failed: no token received"))
        }

        this.token = tok

        val profile = request("stb", "get_profile")
        if (profile !is Result.Ok) return profile as Result<StalkerAuthInfo, Throwable>

        return Result.Ok(
            StalkerAuthInfo(
                token = tok,
                portalUrl = portalUrl,
                macAddress = macAddress,
            ),
        )
    }

    suspend fun getLiveCategories(): Result<List<StalkerCategory>, Throwable> {
        val data = request("itv", "get_genres")
        if (data !is Result.Ok) return data as Result<List<StalkerCategory>, Throwable>
        val raw = asList(asMap(data.value)?.get("js")) ?: emptyList()
        val cats =
            raw.mapNotNull { asMap(it) }.map { c ->
                StalkerCategory(
                    id = str(c["id"]),
                    title = str(c["title"] ?: c["name"]),
                )
            }
        return Result.Ok(cats)
    }

    suspend fun getLiveChannels(): Result<List<StalkerChannel>, Throwable> {
        val all = mutableListOf<StalkerChannel>()
        var totalItems = 0
        var lastPageReached = 0

        var page = 1
        while (page <= MAX_PAGES) {
            val data = request("itv", "get_all_channels", mapOf("p" to page.toString()))
            if (data !is Result.Ok) return data as Result<List<StalkerChannel>, Throwable>

            val js = asMap(asMap(data.value)?.get("js"))
            val items = asList(js?.get("data")) ?: emptyList()

            for (ch in items.mapNotNull { asMap(it) }) {
                all.add(
                    StalkerChannel(
                        id = num(ch["id"]),
                        name = str(ch["name"]),
                        cmd = str(ch["cmd"]),
                        tvGenreId = str(ch["tv_genre_id"]),
                        logo = str(ch["logo"]),
                        epgId = str(ch["epg_channel_id"] ?: ch["xmltv_id"]),
                        number = num(ch["number"]),
                        tvArchive = num(ch["tv_archive"]),
                        tvArchiveDuration = num(ch["tv_archive_duration"]),
                    ),
                )
            }

            val t = num(js?.get("total_items"), 0)
            totalItems = if (t != 0) t else all.size
            lastPageReached = page
            if (all.size >= totalItems) break
            page++
        }

        if (lastPageReached == MAX_PAGES && all.size < totalItems) {
            logger.warn(
                "Stalker getLiveChannels: hit MAX_PAGES ($MAX_PAGES) cap before fetching all channels — got ${all.size} of $totalItems. Increase MAX_PAGES if portal is legitimate.",
            )
        }

        return Result.Ok(all)
    }

    suspend fun getVodCategories(): Result<List<StalkerCategory>, Throwable> {
        val data = request("vod", "get_categories")
        if (data !is Result.Ok) return data as Result<List<StalkerCategory>, Throwable>
        val raw = asList(asMap(data.value)?.get("js")) ?: emptyList()
        val cats =
            raw.mapNotNull { asMap(it) }.map { c ->
                StalkerCategory(
                    id = str(c["id"]),
                    title = str(c["title"] ?: c["name"]),
                )
            }
        return Result.Ok(cats)
    }

    suspend fun getVodItems(): Result<List<StalkerVodItem>, Throwable> {
        val all = mutableListOf<StalkerVodItem>()
        var totalItems = 0
        var lastPageReached = 0

        var page = 1
        while (page <= MAX_PAGES) {
            val data =
                request(
                    "vod",
                    "get_ordered_list",
                    mapOf("category" to "*", "p" to page.toString()),
                )
            if (data !is Result.Ok) return data as Result<List<StalkerVodItem>, Throwable>

            val js = asMap(asMap(data.value)?.get("js"))
            val items = asList(js?.get("data")) ?: emptyList()

            for (v in items.mapNotNull { asMap(it) }) {
                all.add(
                    StalkerVodItem(
                        id = num(v["id"]),
                        name = str(v["name"]),
                        cmd = str(v["cmd"]),
                        categoryId = str(v["category_id"]),
                        logo = str(v["screenshot_uri"] ?: v["logo"]),
                        description = str(v["description"]),
                    ),
                )
            }

            val t = num(js?.get("total_items"), 0)
            totalItems = if (t != 0) t else all.size
            lastPageReached = page
            if (all.size >= totalItems) break
            page++
        }

        if (lastPageReached == MAX_PAGES && all.size < totalItems) {
            logger.warn(
                "Stalker getVodItems: hit MAX_PAGES ($MAX_PAGES) cap — got ${all.size} of $totalItems.",
            )
        }

        return Result.Ok(all)
    }

    suspend fun getSeriesCategories(): Result<List<StalkerCategory>, Throwable> {
        val data = request("series", "get_categories")
        if (data !is Result.Ok) return data as Result<List<StalkerCategory>, Throwable>
        val raw = asList(asMap(data.value)?.get("js")) ?: emptyList()
        val cats =
            raw.mapNotNull { asMap(it) }.map { c ->
                StalkerCategory(
                    id = str(c["id"]),
                    title = str(c["title"] ?: c["name"]),
                )
            }
        return Result.Ok(cats)
    }

    suspend fun getSeriesList(): Result<List<StalkerSeriesItem>, Throwable> {
        val all = mutableListOf<StalkerSeriesItem>()
        var totalItems = 0
        var lastPageReached = 0

        var page = 1
        while (page <= MAX_PAGES) {
            val data =
                request(
                    "series",
                    "get_ordered_list",
                    mapOf("category" to "*", "p" to page.toString()),
                )
            if (data !is Result.Ok) return data as Result<List<StalkerSeriesItem>, Throwable>

            val js = asMap(asMap(data.value)?.get("js"))
            val items = asList(js?.get("data")) ?: emptyList()

            for (s in items.mapNotNull { asMap(it) }) {
                all.add(
                    StalkerSeriesItem(
                        id = num(s["id"]),
                        name = str(s["name"]),
                        categoryId = str(s["category_id"]),
                        cover = str(s["screenshot_uri"] ?: s["cover"]),
                        plot = str(s["description"]),
                        genre = str(s["genre"]),
                    ),
                )
            }

            val t = num(js?.get("total_items"), 0)
            totalItems = if (t != 0) t else all.size
            lastPageReached = page
            if (all.size >= totalItems) break
            page++
        }

        if (lastPageReached == MAX_PAGES && all.size < totalItems) {
            logger.warn(
                "Stalker getSeriesList: hit MAX_PAGES ($MAX_PAGES) cap — got ${all.size} of $totalItems.",
            )
        }

        return Result.Ok(all)
    }

    /** Strip Stalker "cmd" playback prefixes ("ffrt", "ffmpeg", "auto") and return the URL. */
    fun buildStreamUrl(cmd: String): String = cmd.replace(STREAM_CMD_PREFIX, "").trim()

    private suspend fun request(
        type: String,
        action: String,
        extraParams: Map<String, String> = emptyMap(),
    ): Result<Any?, Throwable> {
        val params = LinkedHashMap<String, String>()
        params["type"] = type
        params["action"] = action
        params["JsHttpRequest"] = "1-xml"
        val tok = token
        if (tok != null) params["token"] = tok
        for ((k, v) in extraParams) params[k] = v

        val url = "$portalUrl/server/load.php?${buildQuery(params)}"

        val headers =
            mutableMapOf<String, String>(
                "User-Agent" to STALKER_USER_AGENT,
                "Cookie" to "mac=${enc(macAddress)}; stb_lang=en; timezone=Europe/London",
                "X-User-Agent" to STALKER_X_USER_AGENT,
            )
        if (tok != null) {
            headers["Authorization"] = "Bearer $tok"
        }

        var attempt = 0
        while (attempt <= MAX_RETRIES) {
            try {
                val body =
                    http.getJson(
                        url,
                        HttpRequestOptions(
                            timeoutMs = timeoutMs,
                            maxResponseBytes = MAX_RESPONSE_BYTES,
                            headers = headers,
                        ),
                    )
                return Result.Ok(body)
            } catch (error: Throwable) {
                val msg = error.message ?: error.toString()
                val httpMsg = if (error is HttpResponseError) "HTTP ${error.status}" else msg

                if (attempt < MAX_RETRIES && isRetryableError(httpMsg)) {
                    val d = if (attempt < RETRY_DELAYS.size) RETRY_DELAYS[attempt] else 8000L
                    logger.warn("Stalker API [$type/$action] attempt ${attempt + 1} failed: $msg — retrying in ${d}ms")
                    delay(d)
                    attempt++
                    continue
                }

                logger.error("Stalker API error [$type/$action]: $msg")
                return Result.Err(error)
            }
        }

        return Result.Err(Exception("Max retries exceeded"))
    }
}
