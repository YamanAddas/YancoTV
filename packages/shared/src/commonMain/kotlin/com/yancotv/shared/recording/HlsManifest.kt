package com.yancotv.shared.recording

/**
 * Minimal HLS playlist parser — just enough to drive the recorder.
 * Distinct from [com.yancotv.shared.parsers.M3uParser], which handles
 * IPTV provider playlists (an unrelated `.m3u8` use of the same syntax).
 *
 * What we need:
 *   - List of media segments in order, each with its absolute URL.
 *   - Whether the playlist is live (sliding window) or VOD
 *     (`#EXT-X-ENDLIST`).
 *   - Target segment duration so the recorder knows how long to wait
 *     between manifest re-fetches.
 *   - Media-sequence number on the FIRST segment of THIS manifest, so
 *     a future re-fetch can detect new segments without re-downloading
 *     ones we already have.
 *
 * Master playlists (variant streams) are flagged via [isMaster]; the
 * recorder follows the first listed `#EXT-X-STREAM-INF` variant.
 */
internal data class HlsPlaylist(
    val isMaster: Boolean,
    val isVod: Boolean,
    val targetDurationSec: Int?,
    val mediaSequence: Long,
    /** Absolute URLs in playback order. */
    val segments: List<HlsSegment>,
    /** Variant URLs (master only) in declared order; first is preferred. */
    val variants: List<String>,
)

internal data class HlsSegment(
    val url: String,
    val durationSec: Double,
    /** Sequence number for this segment in the live window. */
    val sequence: Long,
)

internal object HlsManifestParser {
    fun parse(
        manifestText: String,
        manifestUrl: String,
    ): HlsPlaylist {
        val lines = manifestText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        // Master playlists carry STREAM-INF entries; media playlists
        // carry EXTINF + segment URIs. A playlist can't legally be
        // both, but we don't validate that here.
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        val isVod = lines.any { it.equals("#EXT-X-ENDLIST", ignoreCase = true) }
        val targetDuration =
            lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
                ?.removePrefix("#EXT-X-TARGETDURATION:")
                ?.toIntOrNull()
        val mediaSequence =
            lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
                ?.removePrefix("#EXT-X-MEDIA-SEQUENCE:")
                ?.toLongOrNull()
                ?: 0L

        if (isMaster) {
            val variants = mutableListOf<String>()
            var streamInfPending = false
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-STREAM-INF") -> streamInfPending = true
                    !line.startsWith("#") && streamInfPending -> {
                        variants += resolveUrl(manifestUrl, line)
                        streamInfPending = false
                    }
                }
            }
            return HlsPlaylist(
                isMaster = true,
                isVod = false,
                targetDurationSec = null,
                mediaSequence = 0L,
                segments = emptyList(),
                variants = variants,
            )
        }

        // Media playlist — pair each EXTINF with the next non-comment
        // line as its segment URI. URIs may be relative; resolve
        // against the manifest URL.
        val segments = mutableListOf<HlsSegment>()
        var pendingDuration: Double? = null
        var seq = mediaSequence
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") -> {
                    val durStr = line.removePrefix("#EXTINF:").substringBefore(",")
                    pendingDuration = durStr.toDoubleOrNull()
                }
                !line.startsWith("#") && pendingDuration != null -> {
                    segments +=
                        HlsSegment(
                            url = resolveUrl(manifestUrl, line),
                            durationSec = pendingDuration!!,
                            sequence = seq,
                        )
                    seq += 1
                    pendingDuration = null
                }
            }
        }

        return HlsPlaylist(
            isMaster = false,
            isVod = isVod,
            targetDurationSec = targetDuration,
            mediaSequence = mediaSequence,
            segments = segments,
            variants = emptyList(),
        )
    }

    /**
     * Resolve a URI from inside an HLS manifest against the manifest's
     * own URL. Handles three cases per the spec:
     *
     *   1. Absolute URL (starts with `http://` or `https://`) → return
     *      as-is.
     *   2. Protocol-relative (`//host/path`) → take scheme from base.
     *   3. Path-relative (`segment.ts` or `/abs/segment.ts`) → resolve
     *      against the base URL's directory.
     *
     * A full RFC 3986 implementation isn't necessary — HLS manifests
     * never use the unusual cases (query merge, dotless paths, etc.).
     */
    internal fun resolveUrl(
        baseUrl: String,
        ref: String,
    ): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        val schemeEnd = baseUrl.indexOf("://")
        if (schemeEnd < 0) return ref // can't resolve; let the fetcher fail with a useful error
        val scheme = baseUrl.substring(0, schemeEnd)
        val afterScheme = baseUrl.substring(schemeEnd + 3)
        if (ref.startsWith("//")) return "$scheme:$ref"
        val pathStart = afterScheme.indexOf('/')
        val host = if (pathStart < 0) afterScheme else afterScheme.substring(0, pathStart)
        val path = if (pathStart < 0) "/" else afterScheme.substring(pathStart)
        // Strip query / fragment from base path before resolving.
        val cleanPath = path.substringBefore('?').substringBefore('#')
        if (ref.startsWith("/")) return "$scheme://$host$ref"
        // Drop the trailing filename of the base path; keep the directory.
        val baseDir = cleanPath.substringBeforeLast('/', missingDelimiterValue = "") + "/"
        return "$scheme://$host$baseDir$ref"
    }
}
