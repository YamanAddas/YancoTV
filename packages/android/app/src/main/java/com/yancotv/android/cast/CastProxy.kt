package com.yancotv.android.cast

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
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
    private var server: EmbeddedServer<*, *>? = null
    private var session: FFmpegSession? = null
    private var dir: File? = null

    /**
     * Start transcoding [providerUrl] to HLS and serving it on the LAN. Returns
     * the Chromecast-facing `http://<wifi-ip>:<port>/master.m3u8` URL, or null
     * when there's no usable Wi-Fi address. Idempotent: re-calling stops first.
     */
    fun start(providerUrl: String, userAgent: String?, referer: String?, isLive: Boolean): String? {
        stop()
        val ip = wifiIpv4() ?: run {
            logger.warn("CastProxy: no Wi-Fi IPv4 address — cannot serve to the Chromecast")
            return null
        }
        val outDir =
            File(context.cacheDir, "cast-proxy").apply {
                deleteRecursively()
                mkdirs()
            }
        dir = outDir
        val master = File(outDir, "master.m3u8")

        startFfmpeg(providerUrl, userAgent, referer, isLive, outDir, master)
        startServer(ip, outDir)

        // Wait for ffmpeg to write the first playlist before handing the URL to
        // the Chromecast (otherwise it 404s master.m3u8). Bounded; start() runs
        // on Dispatchers.IO (CastController), so the blocking wait is fine.
        if (!awaitMaster(master)) {
            logger.warn("CastProxy: ffmpeg wrote no playlist within ${READY_TIMEOUT_MS}ms")
            stop()
            return null
        }
        return "http://$ip:$DEFAULT_PORT/master.m3u8"
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
                listOf("-hls_list_size", "0", "-hls_playlist_type", "vod")
            }
        args += listOf("-hls_segment_filename", File(outDir, "seg_%05d.ts").absolutePath)
        args += master.absolutePath

        logger.info("CastProxy: starting ffmpeg transcode (live=$isLive)")
        session =
            FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { completed ->
                logger.info("CastProxy: ffmpeg ended rc=${completed.returnCode}")
            }
    }

    private fun startServer(ip: String, outDir: File) {
        server =
            embeddedServer(CIO, port = DEFAULT_PORT, host = ip) {
                routing {
                    get("/{file}") {
                        val name = call.parameters["file"].orEmpty()
                        val f = File(outDir, name)
                        if (!f.exists() || !f.canonicalPath.startsWith(outDir.canonicalPath)) {
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
            }.also { it.start(wait = false) }
        logger.info("CastProxy: serving HLS on http://$ip:$DEFAULT_PORT/")
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

    private fun awaitMaster(master: File): Boolean {
        val end = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < end) {
            if (master.exists() && master.length() > 0L) return true
            Thread.sleep(POLL_MS)
        }
        return master.exists() && master.length() > 0L
    }

    companion object {
        const val DEFAULT_PORT: Int = 8732
        private const val STOP_GRACE_MS = 200L
        private const val STOP_TIMEOUT_MS = 500L
        private const val READY_TIMEOUT_MS = 12_000L
        private const val POLL_MS = 200L
    }
}
