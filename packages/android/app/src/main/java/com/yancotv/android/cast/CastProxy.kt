package com.yancotv.android.cast

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import com.yancotv.shared.http.redactCredentials
import com.yancotv.shared.logger.Logger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import java.net.NetworkInterface

/**
 * MK.26 B.2 Phase 1 — on-device cast proxy. Runs ffmpeg-kit to remux/transcode a
 * provider stream into HLS (TS->HLS + audio->AAC; video is COPIED, so HEVC is
 * Phase 2) while injecting the provider's User-Agent/Referer, and serves the HLS
 * over the phone's Wi-Fi IP with permissive CORS. A bare Chromecast's Default
 * Media Receiver then fetches + plays it: the Chromecast pulls from the phone,
 * the phone pulls from the provider.
 *
 * Runtime behaviour (ffmpeg transcode + Chromecast playback) is only verifiable
 * on real hardware — this is compile-verified scaffolding.
 */
class CastProxy(private val context: Context, private val logger: Logger) {
    // @Volatile: start() runs on Dispatchers.IO, stop() on the main thread (Cast
    // SDK callbacks) — the writes must be visible across threads. (Full
    // start/stop mutual-exclusion is tracked as MB-235.)
    @Volatile private var server: EmbeddedServer<*, *>? = null

    @Volatile private var session: FFmpegSession? = null

    @Volatile private var dir: File? = null

    /**
     * Start transcoding [providerUrl] to HLS and serving it on the LAN. Returns a
     * [CastProxyOutcome]. Idempotent: re-calling stops first.
     */
    fun start(providerUrl: String, userAgent: String?, referer: String?, isLive: Boolean): CastProxyOutcome {
        stop()
        val ip = wifiIpv4() ?: run {
            logger.warn("CastProxy: no Wi-Fi IPv4 address — cannot serve to the Chromecast")
            return CastProxyOutcome.NoNetwork
        }
        val outDir =
            File(context.cacheDir, "cast-proxy").apply {
                deleteRecursively()
                mkdirs()
            }
        dir = outDir
        val master = File(outDir, "master.m3u8")

        startFfmpeg(providerUrl, userAgent, referer, isLive, outDir, master)
        if (!startServer(ip, outDir)) {
            // Port already held (e.g. a prior cast's server leaked) or bind failed.
            // Returning NotReady lets the caller show a clean message instead of an
            // uncaught BindException crashing the IO coroutine.
            logger.warn("CastProxy: HLS server failed to bind on $ip:$DEFAULT_PORT")
            stop()
            return CastProxyOutcome.NotReady
        }

        // LIVE: hand the URL over as soon as the first segment + playlist exist.
        // VOD (MK.26.B.3 probe): wait for ffmpeg to FINISH so master.m3u8 is a
        // COMPLETE VOD playlist (#EXT-X-PLAYLIST-TYPE:VOD + real keyframe #EXTINF +
        // #EXT-X-ENDLIST). The Default Receiver decides VOD-vs-LIVE solely by
        // ENDLIST, so only a finished playlist gives a scrubber + seek + resume.
        // The full-transcode wait is short-clip-only and is removed by Piece 1
        // (hand-authored playlist + on-demand segments).
        val ready = if (isLive) awaitMaster(master, LIVE_READY_TIMEOUT_MS) else awaitComplete(master, VOD_COMPLETE_TIMEOUT_MS)
        if (!ready) {
            val listing =
                runCatching { outDir.listFiles()?.joinToString(", ") { "${it.name}=${it.length()}B" } ?: "<null>" }
                    .getOrElse { "<list error: ${it.message}>" }
            logger.warn("CastProxy: playlist not ready (isLive=$isLive). outDir=[$listing]")
            stop()
            return CastProxyOutcome.NotReady
        }
        // Log the playlist head so the receiver-facing segment URIs (relative vs
        // absolute) are visible on-device while the cast path is being proven out.
        runCatching { logger.info("CastProxy: playlist ready —\n${master.readText().take(500)}") }
        return CastProxyOutcome.Ready("http://$ip:$DEFAULT_PORT/master.m3u8")
    }

    /** Idempotent teardown: cancel ffmpeg, stop the server, delete the temp HLS. */
    fun stop() {
        runCatching { session?.cancel() }
        session = null
        runCatching { server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS) }
        server = null
        runCatching { dir?.deleteRecursively() }
        dir = null
    }

    private fun startFfmpeg(providerUrl: String, userAgent: String?, referer: String?, isLive: Boolean, outDir: File, master: File) {
        val headers =
            buildString {
                userAgent?.takeIf { it.isNotBlank() }?.let { append("User-Agent: ").append(it).append("\r\n") }
                referer?.takeIf { it.isNotBlank() }?.let { append("Referer: ").append(it).append("\r\n") }
            }
        // Phase 1: copy video (H.264), always re-encode audio to stereo AAC
        // (covers AC-3/E-AC-3 + unknown audio, whose passthrough is unreliable).
        // HEVC video is copied and will fail on Cast — Phase 2 adds a hardware
        // HEVC->H.264 transcode. Args as an array to avoid command-string quoting.
        // NOTE: resume (-ss input seek) was tried + reverted — with -c:v copy it
        // gave the receiver a few seconds then stalled at buffered=0 (the copy-seek
        // timestamp-discontinuity hazard). Resume is deferred to MB-240.
        val args = mutableListOf<String>()
        if (headers.isNotEmpty()) {
            args += "-headers"
            args += headers
        }
        args += listOf("-i", providerUrl)
        args += listOf("-c:v", "copy", "-c:a", "aac", "-ac", "2", "-b:a", "128k")
        args += listOf("-f", "hls", "-hls_time", "4")
        args +=
            if (isLive) {
                listOf("-hls_list_size", "6", "-hls_flags", "delete_segments+omit_endlist")
            } else {
                // VOD (MK.26.B.3 probe): a "vod" playlist makes ffmpeg write the
                // segments now and finalize master.m3u8 with #EXT-X-PLAYLIST-TYPE:VOD
                // + real keyframe #EXTINF + #EXT-X-ENDLIST when it finishes. start()
                // waits for that finish (awaitComplete) so the Default Receiver — which
                // decides VOD-vs-LIVE solely by ENDLIST — gives a scrubber + seek +
                // resume. (Piece 1 replaces this with a hand-authored playlist +
                // on-demand segments to drop the full-transcode wait.)
                listOf("-hls_list_size", "0", "-hls_playlist_type", "vod")
            }
        args += listOf("-hls_segment_filename", File(outDir, "seg_%05d.ts").absolutePath)
        args += master.absolutePath

        logger.info("CastProxy: starting ffmpeg transcode (live=$isLive) src=${redactCredentials(providerUrl)}")
        session =
            FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { completed ->
                val rc = completed.returnCode
                when {
                    ReturnCode.isSuccess(rc) -> logger.info("CastProxy: ffmpeg finished ok")
                    ReturnCode.isCancel(rc) -> logger.info("CastProxy: ffmpeg cancelled (proxy stopped)")
                    else -> {
                        // The actual reason a cast failed — provider 403/401,
                        // unsupported codec, an MP4 ffmpeg couldn't open, etc.
                        // Tail the ffmpeg log (credential-redacted — it echoes the
                        // input URL) so it's diagnosable without a repro.
                        val tail = redactCredentials(completed.allLogsAsString?.trim()?.takeLast(1800).orEmpty())
                        logger.warn("CastProxy: ffmpeg FAILED rc=$rc\n$tail")
                    }
                }
            }
    }

    private fun startServer(ip: String, outDir: File): Boolean {
        val srv =
            embeddedServer(CIO, port = DEFAULT_PORT, host = ip) {
                routing {
                    get("/{path...}") {
                        // ffmpeg writes ABSOLUTE segment paths into the playlist
                        // (/data/user/0/.../seg_00001.ts), so the receiver requests
                        // that whole path. Resolve by basename so both relative
                        // (master.m3u8, seg_00001.ts) and absolute requests map into
                        // outDir. The canonicalPath guard still blocks traversal.
                        val name = call.parameters.getAll("path")?.lastOrNull().orEmpty()
                        val f = File(outDir, name)
                        if (name.isBlank() || !f.exists() || !f.canonicalPath.startsWith(outDir.canonicalPath)) {
                            call.respond(HttpStatusCode.NotFound)
                            return@get
                        }
                        call.response.header("Access-Control-Allow-Origin", "*")
                        val type =
                            if (name.endsWith(".m3u8")) {
                                ContentType.parse("application/x-mpegurl")
                            } else {
                                ContentType.parse("video/mp2t")
                            }
                        call.respondBytes(f.readBytes(), type)
                    }
                }
            }
        // Guard the bind: a leaked prior server (MB-235) can still hold 8732, and
        // an uncaught BindException here would crash the IO coroutine.
        return runCatching {
            srv.start(wait = false)
            server = srv
            logger.info("CastProxy: serving HLS on http://$ip:$DEFAULT_PORT/")
            true
        }.getOrElse {
            logger.warn("CastProxy: server start failed — ${it.message}")
            runCatching { srv.stop(0, 0) }
            false
        }
    }

    /** First non-loopback IPv4 (the Wi-Fi LAN address the Chromecast can reach). */
    private fun wifiIpv4(): String? = runCatching {
        NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 && !it.hostAddress.isNullOrBlank() }
            ?.hostAddress
    }.getOrNull()

    private fun awaitMaster(master: File, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (playlistReady(master)) return true
            // ffmpeg already ended without writing a playlist (provider 403,
            // unreadable input, …) — fail fast instead of blocking the whole
            // timeout. Only a still-running session is worth waiting on.
            val state = session?.state
            if (state == SessionState.FAILED || state == SessionState.COMPLETED) break
            Thread.sleep(POLL_MS)
        }
        return playlistReady(master)
    }

    // Ready only once the playlist actually references a segment — a freshly
    // created EVENT playlist holds just the #EXTM3U header (length>0) for a beat
    // before seg_00000.ts is added; handing that to the receiver makes it fetch a
    // segment-less playlist and re-poll (an extra round-trip / false start).
    private fun playlistReady(master: File): Boolean =
        master.exists() && runCatching { master.readText().contains(".ts") }.getOrDefault(false)

    // VOD probe: block until ffmpeg ENDS and master.m3u8 holds a COMPLETE vod
    // playlist (#EXT-X-ENDLIST present). This waits out the whole transcode — short
    // clips only; Piece 1's hand-authored playlist + on-demand segments removes it.
    private fun awaitComplete(master: File, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            when (session?.state) {
                SessionState.COMPLETED ->
                    return master.exists() &&
                        runCatching { master.readText().contains("#EXT-X-ENDLIST") }.getOrDefault(false)
                SessionState.FAILED -> return false
                else -> Thread.sleep(POLL_MS)
            }
        }
        return false
    }

    companion object {
        const val DEFAULT_PORT: Int = 8732
        private const val STOP_GRACE_MS = 200L
        private const val STOP_TIMEOUT_MS = 500L
        private const val LIVE_READY_TIMEOUT_MS = 15_000L

        // VOD probe waits for the FULL transcode to finish (a short clip races
        // through at many ×realtime). 3 min covers ~10-15 min clips; long movies
        // time out (probe = short clips). Piece 1 removes the wait entirely.
        private const val VOD_COMPLETE_TIMEOUT_MS = 180_000L

        // Poll the cache file every 50ms (was 200ms) — a cheap stat that trims up
        // to ~150ms of slack off the playlist-ready wait.
        private const val POLL_MS = 50L
    }
}

/** Result of [CastProxy.start] — distinguishes the user-facing failure messages. */
sealed interface CastProxyOutcome {
    /** Ready to cast: [url] is the Chromecast-facing master playlist. */
    data class Ready(val url: String) : CastProxyOutcome

    /** No usable Wi-Fi IPv4 to serve from (phone likely off Wi-Fi). */
    data object NoNetwork : CastProxyOutcome

    /** ffmpeg produced no playlist in time (bad source, unsupported codec, …). */
    data object NotReady : CastProxyOutcome
}
