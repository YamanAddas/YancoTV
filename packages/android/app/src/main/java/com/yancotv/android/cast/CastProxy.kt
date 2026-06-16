package com.yancotv.android.cast

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
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
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import java.net.NetworkInterface
import java.util.Locale
import kotlin.math.ceil

/**
 * MK.26 Track B — on-device cast proxy. Repackages a provider stream to HLS the
 * Chromecast Default Receiver can play, injecting the provider's UA/Referer, and
 * serves it over the phone's Wi-Fi IP.
 *
 * Two pipelines:
 *  - LIVE: one continuous ffmpeg writes a sliding-window event playlist (no seek).
 *  - VOD (MK.26.B.3 Piece 1): we HAND-AUTHOR a complete VOD playlist up front from
 *    the duration the local player already knows — so the receiver shows a scrubber
 *    + seek + resume INSTANTLY, with no probe wait. Segments are produced by a
 *    CONTINUOUS transcode "head" (the Plex/Jellyfin model): one ffmpeg input-seeks
 *    to the playing offset and segments forward at keyframes (so `-c:v copy` stays
 *    clean — the vendored ffmpeg-kit has no x264). A seek just relaunches the head
 *    at the new offset. Start is fast (no probe, first segment only) and a 4-hour
 *    movie works because only the watched/sought region is ever transcoded.
 *
 * Runtime behaviour is only verifiable on real hardware.
 */
class CastProxy(private val context: Context, private val logger: Logger) {
    // @Volatile: start() runs on Dispatchers.IO, stop() on the main thread (Cast
    // SDK callbacks), segment routes on Ktor workers — writes must be visible
    // across threads. (Full start/stop mutual-exclusion is tracked as MB-235.)
    @Volatile private var server: EmbeddedServer<*, *>? = null

    @Volatile private var liveSession: FFmpegSession? = null

    @Volatile private var dir: File? = null

    @Volatile private var vod: VodPlan? = null

    // The continuous VOD transcode head + the segment index it was launched from.
    @Volatile private var headSession: FFmpegSession? = null

    @Volatile private var headBase: Int = -1

    // The segment the receiver most recently asked for ≈ its play frontier. Drives
    // both how far the head is allowed to run ahead and which segments we keep.
    @Volatile private var lastReqSeg: Int = -1

    // Serializes head launch/restart across concurrent segment requests.
    private val headLock = Any()

    @Volatile private var generation = 0

    private class VodPlan(
        val gen: Int,
        val url: String,
        val headers: String,
        val outDir: File,
        val segSec: Double,
        val segCount: Int,
        val durationSec: Double,
    )

    /**
     * Start serving [providerUrl] to the LAN. [durationMs] (the local player's known
     * VOD length) lets the VOD path skip probing entirely; 0 falls back to a probe.
     * Returns a [CastProxyOutcome]. Idempotent: re-calling stops first.
     */
    fun start(providerUrl: String, userAgent: String?, referer: String?, isLive: Boolean, durationMs: Long): CastProxyOutcome {
        stop()
        val gen = ++generation
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
        val headers = buildHeaders(userAgent, referer)
        return if (isLive) {
            startLive(providerUrl, headers, ip, outDir)
        } else {
            startVod(gen, providerUrl, headers, ip, outDir, durationMs)
        }
    }

    /** Idempotent teardown: bump generation, cancel ffmpeg, stop the server, wipe HLS. */
    fun stop() {
        generation++
        vod = null
        headBase = -1
        lastReqSeg = -1
        headSession = null
        runCatching { liveSession?.cancel() }
        liveSession = null
        // Cancel any in-flight head / segment ffmpeg sessions.
        runCatching { FFmpegKit.cancel() }
        runCatching { server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS) }
        server = null
        runCatching { dir?.deleteRecursively() }
        dir = null
    }

    // ── LIVE ─────────────────────────────────────────────────────────────

    private fun startLive(url: String, headers: String, ip: String, outDir: File): CastProxyOutcome {
        val master = File(outDir, "master.m3u8")
        val args = mutableListOf<String>()
        if (headers.isNotEmpty()) {
            args += "-headers"
            args += headers
        }
        args += listOf("-i", url)
        args += listOf("-c:v", "copy", "-c:a", "aac", "-ac", "2", "-b:a", "128k")
        args += listOf("-f", "hls", "-hls_time", "4", "-hls_list_size", "6", "-hls_flags", "delete_segments+omit_endlist")
        args += listOf("-hls_segment_filename", File(outDir, "seg_%05d.ts").absolutePath)
        args += master.absolutePath
        logger.info("CastProxy: starting LIVE transcode src=${redactCredentials(url)}")
        liveSession = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { c -> logFfmpegEnd("live", c) }
        if (!startServer(ip, outDir)) {
            stop()
            return CastProxyOutcome.NotReady
        }
        if (!awaitMaster(master, LIVE_READY_TIMEOUT_MS)) {
            stop()
            return CastProxyOutcome.NotReady
        }
        return CastProxyOutcome.Ready("http://$ip:$DEFAULT_PORT/master.m3u8")
    }

    // ── VOD (Piece 1) ────────────────────────────────────────────────────

    private fun startVod(gen: Int, url: String, headers: String, ip: String, outDir: File, durationMs: Long): CastProxyOutcome {
        // The local player already knows the duration — no probe needed. Only fall
        // back to a (slower) container probe if it didn't (e.g. duration unknown).
        val durationSec =
            if (durationMs > 0L) {
                durationMs / 1000.0
            } else {
                val t0 = System.currentTimeMillis()
                val probed = probeDuration(url, headers)
                logger.info("CastProxy: VOD duration probe = ${probed}s in ${System.currentTimeMillis() - t0}ms")
                probed
            }
        if (durationSec <= 0.0) {
            stop()
            return CastProxyOutcome.NotReady
        }
        val segCount = ceil(durationSec / SEGMENT_SEC).toInt().coerceAtLeast(1)
        val plan = VodPlan(gen, url, headers, outDir, SEGMENT_SEC, segCount, durationSec)
        vod = plan
        File(outDir, "master.m3u8").writeText(buildVodPlaylist(plan))
        if (!startServer(ip, outDir)) {
            stop()
            return CastProxyOutcome.NotReady
        }
        logger.info("CastProxy: VOD ready — $segCount segments, ${"%.1f".format(Locale.ROOT, durationSec / 60)} min")
        return CastProxyOutcome.Ready("http://$ip:$DEFAULT_PORT/master.m3u8")
    }

    /** Hand-authored complete VOD media playlist — the receiver reads this as seekable VOD. */
    private fun buildVodPlaylist(plan: VodPlan): String =
        buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:${ceil(plan.segSec).toInt()}\n")
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            for (i in 0 until plan.segCount) {
                val segDur = if (i < plan.segCount - 1) plan.segSec else plan.durationSec - i * plan.segSec
                append("#EXTINF:${"%.3f".format(Locale.ROOT, segDur.coerceAtLeast(0.001))},\n")
                append("seg_%05d.ts\n".format(Locale.ROOT, i))
            }
            append("#EXT-X-ENDLIST\n")
        }

    /**
     * Make sure the continuous head is producing segment [n]: launch (or relaunch on a
     * seek) so ffmpeg input-seeks to n's offset and segments forward from there.
     * Already-produced/cached segments short-circuit. Synchronized so concurrent
     * read-ahead requests don't spawn duplicate heads.
     */
    private fun ensureHeadCovers(plan: VodPlan, n: Int) {
        synchronized(headLock) {
            if (plan.gen != generation) return
            lastReqSeg = n
            val tip = maxProducedIndex(plan.outDir)
            // A just-launched FFmpegKit session is briefly CREATED before RUNNING;
            // treat both as "alive" so concurrent cold-start read-ahead requests
            // don't each see "no head" and relaunch at a higher index, orphaning
            // the lower segments (MB-235 race).
            val state = headSession?.state
            val alive = state == SessionState.RUNNING || state == SessionState.CREATED

            // 1. n isn't on disk and the alive head won't reach it soon → (re)launch
            //    at n. Covers first play, forward seek, back-seek into evicted
            //    territory, and a head stopped (lead-capped) with n past its tip.
            if (!segmentReady(plan.outDir, n)) {
                val willProduce = alive && headBase in 0..n && n > tip && n <= tip + FORWARD_WAIT_MARGIN
                if (!willProduce) {
                    launchHead(plan, n)
                    return
                }
            }
            val running = state == SessionState.RUNNING

            // 2. Bound the head's lead so a long movie isn't transcoded + cached all at
            //    once: stop it once it's far enough ahead, and resume when the receiver
            //    has caught up — while still RESUME_GAP segments buffered, so the
            //    relaunch (a reconnect) finishes before playback would ever starve.
            if (running) {
                if (tip - lastReqSeg >= LEAD_CAP) runCatching { headSession?.cancel() }
            } else if (tip in 0 until plan.segCount - 1 && tip - lastReqSeg <= RESUME_GAP) {
                launchHead(plan, tip + 1)
            }
        }
    }

    private fun launchHead(plan: VodPlan, base: Int) {
        runCatching { headSession?.cancel() }
        // A cancelled head abandons its in-progress seg_*.ts.tmp (temp_file flag) —
        // those never match the .ts eviction filter, so sweep them here or they leak
        // for the whole session (MB-234).
        runCatching {
            plan.outDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".tmp") }
                ?.forEach { runCatching { it.delete() } }
        }
        val args = mutableListOf<String>()
        args += "-y"
        if (plan.headers.isNotEmpty()) {
            args += "-headers"
            args += plan.headers
        }
        // Input-seek to the segment offset, keep absolute timestamps (-copyts) so every
        // head's segments line up on the receiver's timeline regardless of where it
        // started. (Provider-friendly pacing via -readrate is a follow-up — left off
        // here so the initial buffer fills at full speed for the fastest start.)
        args += listOf("-ss", "%.3f".format(Locale.ROOT, base * plan.segSec))
        args += listOf("-i", plan.url)
        args += listOf("-copyts")
        args += listOf("-c:v", "copy", "-c:a", "aac", "-ac", "2", "-b:a", "128k")
        args += listOf("-f", "hls", "-hls_time", "%.3f".format(Locale.ROOT, plan.segSec), "-hls_list_size", "0")
        args += listOf("-start_number", base.toString())
        args += listOf("-hls_flags", "temp_file+independent_segments")
        args += listOf("-hls_segment_filename", File(plan.outDir, "seg_%05d.ts").absolutePath)
        args += File(plan.outDir, "_head.m3u8").absolutePath
        headBase = base
        headSession = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { c -> logFfmpegEnd("head@$base", c) }
    }

    private fun segmentReady(outDir: File, n: Int): Boolean {
        val f = File(outDir, "seg_%05d.ts".format(Locale.ROOT, n))
        return f.exists() && f.length() > 0L
    }

    /** Highest produced segment index on disk, or -1 if none. */
    private fun maxProducedIndex(outDir: File): Int =
        runCatching {
            outDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".ts") }
                ?.maxOfOrNull { it.name.removePrefix("seg_").removeSuffix(".ts").toIntOrNull() ?: -1 }
                ?: -1
        }.getOrDefault(-1)

    /**
     * Keep only a window of segments around the receiver's play position [center]:
     * a back-buffer for scrubbing back + the head's allowed look-ahead. Deleting by
     * POSITION (not recency) is what prevents evicting segments the receiver is about
     * to play — the bug that caused mid-playback relaunch stutter.
     */
    private fun evictOutsideWindow(outDir: File, center: Int) {
        runCatching {
            val low = center - BACK_BUFFER
            val high = center + AHEAD_KEEP
            outDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".ts") }?.forEach { f ->
                val idx = f.name.removePrefix("seg_").removeSuffix(".ts").toIntOrNull() ?: return@forEach
                if (idx < low || idx > high) runCatching { f.delete() }
            }
        }
    }

    /** Total duration (seconds) from the container metadata (VOD fallback only). */
    private fun probeDuration(url: String, headers: String): Double {
        val args = mutableListOf<String>()
        if (headers.isNotEmpty()) {
            args += "-headers"
            args += headers
        }
        args += listOf("-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", url)
        val session = FFprobeKit.executeWithArguments(args.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) return 0.0
        return session.allLogsAsString?.trim()?.lineSequence()?.firstOrNull()?.trim()?.toDoubleOrNull() ?: 0.0
    }

    // ── server ───────────────────────────────────────────────────────────

    private fun startServer(ip: String, outDir: File): Boolean {
        val srv =
            embeddedServer(CIO, port = DEFAULT_PORT, host = ip) {
                routing {
                    get("/{path...}") {
                        val name = call.parameters.getAll("path")?.lastOrNull().orEmpty()
                        if (name.isBlank()) {
                            call.respond(HttpStatusCode.NotFound)
                            return@get
                        }
                        if (name.endsWith(".m3u8")) {
                            val f = File(outDir, name)
                            if (!f.exists() || !f.canonicalPath.startsWith(outDir.canonicalPath)) {
                                call.respond(HttpStatusCode.NotFound)
                                return@get
                            }
                            call.response.header("Access-Control-Allow-Origin", "*")
                            call.respondText(f.readText(), ContentType.parse("application/x-mpegurl"))
                            return@get
                        }
                        val plan = vod
                        if (plan != null && name.startsWith("seg_") && name.endsWith(".ts")) {
                            val n = name.removePrefix("seg_").removeSuffix(".ts").toIntOrNull()
                            if (n == null || n < 0 || n >= plan.segCount) {
                                call.respond(HttpStatusCode.NotFound)
                                return@get
                            }
                            val seg = awaitSegment(plan, n)
                            // Read the bytes into memory BEFORE evicting: a concurrent
                            // request's evictOutsideWindow could unlink this file between
                            // the awaitSegment existence check and the read, and a bare
                            // readBytes() on a deleted file throws out of the route (→ a
                            // dropped 500 the receiver sees as a stalled segment).
                            val bytes = seg?.let { runCatching { it.readBytes() }.getOrNull() }
                            if (bytes == null) {
                                call.respond(HttpStatusCode.NotFound)
                                return@get
                            }
                            evictOutsideWindow(outDir, n)
                            call.response.header("Access-Control-Allow-Origin", "*")
                            call.respondBytes(bytes, ContentType.parse("video/mp2t"))
                            return@get
                        }
                        // Live segment already on disk.
                        val f = File(outDir, name)
                        if (!f.exists() || !f.canonicalPath.startsWith(outDir.canonicalPath)) {
                            call.respond(HttpStatusCode.NotFound)
                            return@get
                        }
                        call.response.header("Access-Control-Allow-Origin", "*")
                        call.respondBytes(f.readBytes(), ContentType.parse("video/mp2t"))
                    }
                }
            }
        return runCatching {
            srv.start(wait = false)
            server = srv
            logger.info("CastProxy: serving on http://$ip:$DEFAULT_PORT/")
            true
        }.getOrElse {
            logger.warn("CastProxy: server start failed — ${it.message}")
            runCatching { srv.stop(0, 0) }
            false
        }
    }

    /** Block until VOD segment [n] is on disk (launching/relaunching the head as needed). */
    private fun awaitSegment(plan: VodPlan, n: Int): File? {
        ensureHeadCovers(plan, n)
        val f = File(plan.outDir, "seg_%05d.ts".format(Locale.ROOT, n))
        val end = System.currentTimeMillis() + SEGMENT_WAIT_MS
        while (System.currentTimeMillis() < end) {
            if (plan.gen != generation) return null
            if (f.exists() && f.length() > 0L) return f
            // The head died (provider error, unsupported) — fail fast.
            if (headSession?.state == SessionState.FAILED) return null
            Thread.sleep(POLL_MS)
        }
        return f.takeIf { it.exists() && it.length() > 0L }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun buildHeaders(userAgent: String?, referer: String?): String =
        buildString {
            userAgent?.takeIf { it.isNotBlank() }?.let { append("User-Agent: ").append(it).append("\r\n") }
            referer?.takeIf { it.isNotBlank() }?.let { append("Referer: ").append(it).append("\r\n") }
        }

    private fun logFfmpegEnd(tag: String, session: FFmpegSession) {
        val rc = session.returnCode
        when {
            ReturnCode.isSuccess(rc) -> {}
            ReturnCode.isCancel(rc) -> {}
            else -> logger.warn("CastProxy: ffmpeg[$tag] FAILED rc=$rc\n${redactCredentials(session.allLogsAsString?.trim()?.takeLast(1200).orEmpty())}")
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
            if (master.exists() && runCatching { master.readText().contains(".ts") }.getOrDefault(false)) return true
            val state = liveSession?.state
            if (state == SessionState.FAILED || state == SessionState.COMPLETED) break
            Thread.sleep(POLL_MS)
        }
        return master.exists() && runCatching { master.readText().contains(".ts") }.getOrDefault(false)
    }

    companion object {
        const val DEFAULT_PORT: Int = 8732
        private const val STOP_GRACE_MS = 200L
        private const val STOP_TIMEOUT_MS = 500L
        private const val LIVE_READY_TIMEOUT_MS = 15_000L
        private const val POLL_MS = 50L
        private const val SEGMENT_SEC = 4.0

        // How far ahead of the head's produced tip a request may be before we treat it
        // as a seek (relaunch) rather than wait.
        private const val FORWARD_WAIT_MARGIN = 12

        // Per-segment serve timeout (covers the head's connect + first-segment encode).
        private const val SEGMENT_WAIT_MS = 15_000L

        // Lead control (segments, ~4s each): let the head run up to LEAD_CAP (~4 min)
        // ahead of the play frontier, then stop; resume once only RESUME_GAP (~1.5 min)
        // of buffer remains — so the reconnect is always covered by buffered segments.
        private const val LEAD_CAP = 60
        private const val RESUME_GAP = 22

        // Cache window around the play frontier. AHEAD_KEEP must exceed LEAD_CAP so the
        // head's look-ahead is never evicted; BACK_BUFFER allows instant back-scrub.
        private const val BACK_BUFFER = 40
        private const val AHEAD_KEEP = 75
    }
}

/** Result of [CastProxy.start] — distinguishes the user-facing failure messages. */
sealed interface CastProxyOutcome {
    /** Ready to cast: [url] is the Chromecast-facing master playlist. */
    data class Ready(val url: String) : CastProxyOutcome

    /** No usable Wi-Fi IPv4 to serve from (phone likely off Wi-Fi). */
    data object NoNetwork : CastProxyOutcome

    /** Couldn't prepare the source (no duration, server bind failed, …). */
    data object NotReady : CastProxyOutcome
}
