package com.yancotv.android.player.subtitles

import com.yancotv.android.BuildConfig
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * OpenSubtitles REST API v1 client.
 *
 * Anonymous-only (no login) — the free consumer key allows 5 downloads/day
 * which is plenty for a single-user personal app. If the user later wants
 * more, we can add username/password login + JWT caching (the desktop Electron
 * build already has this path).
 */
class OpenSubtitlesClient(
    private val http: OkHttpClient,
    private val cacheDir: File,
) {
    fun search(
        query: String,
        season: Int? = null,
        episode: Int? = null,
        languages: String? = null,
        type: String? = null,
        moviehash: String? = null,
        moviebytesize: Long? = null,
    ): List<SubtitleResult> {
        val url = buildString {
            append("$API_BASE/subtitles?query=")
            append(java.net.URLEncoder.encode(query, "UTF-8"))
            season?.let { append("&season_number=$it") }
            episode?.let { append("&episode_number=$it") }
            languages?.let { append("&languages=$it") }
            type?.let { append("&type=$it") }
            // moviehash + moviebytesize must be passed together — OpenSubtitles
            // ignores hash without size and vice versa. We add them to the
            // existing query so file-level matches sort to the top while
            // title relevance still falls back when the file isn't in OS's DB.
            moviehash?.let { append("&moviehash=$it") }
            moviebytesize?.let { append("&moviebytesize=$it") }
        }
        val request = Request.Builder()
            .url(url)
            .headers(baseHeaders())
            .get()
            .build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            throw OpenSubtitlesException("Search failed: ${response.code}")
        }
        val body = response.body?.string() ?: return emptyList()
        return parseSearchResponse(body)
    }

    fun download(fileId: Int): DownloadResult {
        val dlRequest = Request.Builder()
            .url("$API_BASE/download")
            .headers(baseHeaders())
            .post("""{"file_id":$fileId}""".toRequestBody("application/json".toMediaType()))
            .build()
        val dlResponse = http.newCall(dlRequest).execute()
        if (!dlResponse.isSuccessful) {
            throw OpenSubtitlesException("Download request failed: ${dlResponse.code}")
        }
        val dlBody = dlResponse.body?.string()
            ?: throw OpenSubtitlesException("Empty download response")
        val dlJson = JSONObject(dlBody)
        val link = dlJson.optString("link", "")
            .takeIf { it.isNotBlank() }
            ?: throw OpenSubtitlesException("Download response missing link")
        val fileName = dlJson.optString("file_name", "$fileId.srt")
        val remaining = dlJson.optInt("remaining", -1)

        val fileRequest = Request.Builder().url(link).build()
        val fileResponse = http.newCall(fileRequest).execute()
        if (!fileResponse.isSuccessful) {
            throw OpenSubtitlesException("File download failed: ${fileResponse.code}")
        }
        val bytes = fileResponse.body?.bytes()
            ?: throw OpenSubtitlesException("Empty subtitle file")

        val dir = File(cacheDir, "subtitles").also { it.mkdirs() }
        val safeName = fileName.replace(Regex("[^\\w.\\- ]+"), "_")
        val outFile = File(dir, "${fileId}-$safeName")
        outFile.writeBytes(bytes)

        return DownloadResult(file = outFile, remaining = remaining)
    }

    private fun parseSearchResponse(body: String): List<SubtitleResult> {
        val root = JSONObject(body)
        val data = root.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<SubtitleResult>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val attrs = obj.optJSONObject("attributes") ?: continue
            val files = attrs.optJSONArray("files") ?: continue
            if (files.length() == 0) continue
            val firstFile = files.optJSONObject(0) ?: continue
            val subtitleId = obj.optString("id", "") .takeIf { it.isNotBlank() } ?: continue
            val fileId = firstFile.optInt("file_id", -1).takeIf { it > 0 } ?: continue
            out += SubtitleResult(
                subtitleId = subtitleId,
                fileId = fileId,
                fileName = firstFile.optString("file_name", ""),
                language = attrs.optString("language", "??"),
                release = attrs.optString("release", ""),
                downloadCount = attrs.optInt("download_count", 0),
                hearingImpaired = attrs.optBoolean("hearing_impaired", false),
                aiTranslated = attrs.optBoolean("ai_translated", false),
            )
        }
        return out
    }

    private fun baseHeaders(): okhttp3.Headers = okhttp3.Headers.Builder()
        .add("Api-Key", BuildConfig.OPENSUBTITLES_API_KEY)
        .add("Content-Type", "application/json")
        .add("Accept", "application/json")
        .add("User-Agent", "YancoTV v${BuildConfig.VERSION_NAME}")
        .build()

    companion object {
        private const val API_BASE = "https://api.opensubtitles.com/api/v1"
    }
}

data class SubtitleResult(
    val subtitleId: String,
    val fileId: Int,
    val fileName: String,
    val language: String,
    val release: String,
    val downloadCount: Int,
    val hearingImpaired: Boolean,
    val aiTranslated: Boolean,
)

data class DownloadResult(
    val file: File,
    val remaining: Int,
)

class OpenSubtitlesException(message: String) : Exception(message)
