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
import java.util.concurrent.Semaphore
import kotlin.math.ceil

/**
 * MK.26 Track B — on-device cast proxy. Repackages a provider stream to HLS the
 * Chromecast Default Receiver can play, injecting the provider's UA/Referer, and
 * serves it over the phone's Wi-Fi IP.
 *
 * Two pipelines:
 *  - LIVE: one continuous ffmpeg writes a sliding-window event playlist (no seek).
 *  - VOD (MK.26.B.3 Piece 1, keyframe-map): we PROBE the source's real video
 *    keyframes and HAND-AUTHOR a complete VOD playlist whose segment boundaries +
 *    #EXTINF are exactly where `-c:v copy` will cut (so there is no playlist↔PTS
 *    drift and no segment-count mismatch — sound for a 4-hour movie). Segments are
 *    produced by a CONTINUOUS transcode "head" (the Plex/Jellyfin model): one ffmpeg
 *    input-seeks to a keyframe boundary and segments forward with `-copyts` (video
 *    copied — the vendored ffmpeg-kit has no x264). A seek/lead-cap relaunch lands on
 *    another keyframe boundary, so the seam is clean. Only the watched/sought region
 *    is ever transcoded; the lone start cost is the keyframe probe.
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

    // The receiver's play frontier (segment index): the most recent forward request,
    // resets only on a genuine backward jump (a small in-buffer back-scrub keeps the
    // window — finding #8). Drives which cached segments we keep.
    @Volatile private var frontier: Int = -1

    // Cap concurrent on-demand segment transcodes so the receiver's read-ahead can't
    // spawn a swarm of ffmpeg processes.
    private val segGate = Semaphore(MAX_CONCURRENT_SEGMENTS, true)

    @Volatile private var generation = 0

    // boundaries[i]..boundaries[i+1] is segment i, and both ends are REAL source
    // keyframes (from the probe) so `-c:v copy` cuts land exactly on them and the
    // declared #EXTINF matches what ffmpeg actually produces — no drift, no count
    // mismatch, clean keyframe-aligned relaunch seams (findings #1/#2/#7).
    private class VodPlan(val gen: Int, val url: String, val headers: String, val outDir: File, val boundaries: DoubleArray, val durationSec: Double) {
        val segCount: Int get() = boundaries.size - 1

        fun segStart(i: Int): Double = boundaries[i]

        fun segDur(i: Int): Double = boundaries[i + 1] - boundaries[i]
    }

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
        frontier = -1
        runCatching { liveSession?.cancel() }
        liveSession = null
        // Cancel any in-flight live / on-demand segment ffmpeg sessions.
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
        // The local player already knows the duration — no probe needed for it. Only
        // fall back to a container probe if it didn't (e.g. duration unknown).
        val durationSec =
            if (durationMs > 0L) {
                durationMs / 1000.0
            } else {
                probeDuration(url, headers)
            }
        if (durationSec <= 0.0) {
            stop()
            return CastProxyOutcome.NotReady
        }

        // Read the real video keyframe layout so the playlist boundaries match what
        // `-c:v copy` will actually cut. This is the single added start cost; measured
        // on-device. If it can't be read, fall back to a coarse fixed grid (sound only
        // for short / frequent-keyframe content — same as the pre-keyframe-map build).
        val t0 = System.currentTimeMillis()
        val keyframes = probeKeyframes(url, headers)
        val probeMs = System.currentTimeMillis() - t0
        val boundaries =
            if (keyframes != null && keyframes.size >= 2) {
                keyframeBoundaries(keyframes, durationSec, TARGET_SEC)
            } else {
                logger.warn("CastProxy: VOD keyframe probe unusable (${keyframes?.size ?: 0} kf) — fixed-grid fallback")
                fixedBoundaries(durationSec, TARGET_SEC)
            }
        logger.info(
            "CastProxy: VOD probe ${probeMs}ms → ${boundaries.size - 1} segments, " +
                "${"%.1f".format(Locale.ROOT, durationSec / 60)} min (${keyframes?.size ?: 0} keyframes)",
        )

        val plan = VodPlan(gen, url, headers, outDir, boundaries, durationSec)
        vod = plan
        File(outDir, "master.m3u8").writeText(buildVodPlaylist(plan))
        if (!startServer(ip, outDir)) {
            stop()
            return CastProxyOutcome.NotReady
        }
        return CastProxyOutcome.Ready("http://$ip:$DEFAULT_PORT/master.m3u8")
    }

    /** Hand-authored complete VOD media playlist — the receiver reads this as seekable VOD. */
    private fun buildVodPlaylist(plan: VodPlan): String {
        val maxSeg = (0 until plan.segCount).maxOf { plan.segDur(it) }
        return buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:${ceil(maxSeg).toInt().coerceAtLeast(1)}\n")
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            for (i in 0 until plan.segCount) {
                append("#EXTINF:${"%.3f".format(Locale.ROOT, plan.segDur(i).coerceAtLeast(0.001))},\n")
                append("seg_%05d.ts\n".format(Locale.ROOT, i))
            }
            append("#EXT-X-ENDLIST\n")
        }
    }

    /**
     * Produce VOD segment [n] on demand by EXTRACTING it as its OWN clip. This is the
     * one ffmpeg invocation this build can't misbehave on: no muxer cutting logic to
     * ignore our boundaries. We input-seek to n's keyframe start, copy video / re-encode
     * audio for exactly n's keyframe window (`-t` = the keyframe gap), and shift the
     * written timestamps to absolute (`-output_ts_offset`) so the segment's PTS equals
     * its playlist position — adjacent segments stay continuous, seeks land exact.
     * Cached + served atomically (write `.part`, rename). Bounded concurrency.
     */
    private fun produceSegment(plan: VodPlan, n: Int): File? {
        if (plan.gen != generation) return null
        updateFrontier(n)
        val out = File(plan.outDir, "seg_%05d.ts".format(Locale.ROOT, n))
        if (out.exists() && out.length() > 0L) return out
        segGate.acquire()
        try {
            if (plan.gen != generation) return null
            if (out.exists() && out.length() > 0L) return out
            val tmp = File(plan.outDir, "seg_%05d.ts.part".format(Locale.ROOT, n))
            val start = plan.segStart(n)
            val args = mutableListOf<String>()
            args += "-y"
            if (plan.headers.isNotEmpty()) {
                args += "-headers"
                args += plan.headers
            }
            // -ss biased a hair past the boundary keyframe (so a rounded value can't snap
            // to the PREVIOUS keyframe). NO -copyts: that makes `-t` measure from PTS 0
            // and breaks the clip. Plain `-t` (relative duration) is reliable, then
            // -output_ts_offset shifts the written PTS to the segment's absolute start so
            // segments are continuous on the receiver's timeline.
            args += listOf("-ss", "%.3f".format(Locale.ROOT, if (n == 0) 0.0 else start + SS_EPSILON))
            args += listOf("-i", plan.url)
            args += listOf("-c:v", "copy", "-c:a", "aac", "-ac", "2", "-b:a", "128k")
            args += listOf("-t", "%.3f".format(Locale.ROOT, plan.segDur(n)))
            args += listOf("-output_ts_offset", "%.3f".format(Locale.ROOT, start))
            args += listOf("-muxpreload", "0", "-muxdelay", "0")
            args += listOf("-f", "mpegts", tmp.absolutePath)
            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            if (ReturnCode.isSuccess(session.returnCode) && tmp.exists() && tmp.length() > 0L) {
                runCatching { out.delete() }
                if (!tmp.renameTo(out)) {
                    runCatching {
                        tmp.copyTo(out, overwrite = true)
                        tmp.delete()
                    }
                }
                return out.takeIf { it.exists() && it.length() > 0L }
            }
            runCatching { tmp.delete() }
            logFfmpegEnd("seg$n", session)
            return null
        } finally {
            segGate.release()
        }
    }

    // Advance the play frontier on forward progress; reset only on a genuine backward
    // jump (a small in-buffer back-scrub keeps the window — finding #8).
    private fun updateFrontier(n: Int) {
        frontier = when {
            n > frontier -> n
            n < frontier - SEEK_BACK_THRESHOLD -> n
            else -> frontier
        }
    }

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

    /**
     * Video keyframe timestamps (seconds), read from the demux packet index (no
     * decode). Returns null if ffprobe failed. Fast for indexed containers (MP4);
     * a raw stream that needs a full scan just makes start slower (still correct).
     */
    private fun probeKeyframes(url: String, headers: String): List<Double>? {
        val args = mutableListOf<String>()
        if (headers.isNotEmpty()) {
            args += "-headers"
            args += headers
        }
        args += listOf("-v", "error", "-select_streams", "v:0")
        args += listOf("-show_entries", "packet=pts_time,flags", "-of", "csv=p=0", url)
        val session = FFprobeKit.executeWithArguments(args.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) {
            logger.warn("CastProxy: keyframe probe failed — ${redactCredentials(session.allLogsAsString?.trim()?.takeLast(400).orEmpty())}")
            return null
        }
        // Each line: "<pts_time>,<flags>"; a keyframe packet has 'K' in its flags.
        return session.allLogsAsString
            ?.lineSequence()
            ?.mapNotNull { line ->
                val parts = line.split(',')
                if (parts.size >= 2 && parts[1].contains('K')) parts[0].trim().toDoubleOrNull() else null
            }
            ?.toList()
    }

    /**
     * Grouped segment boundaries: starting at 0, take the first keyframe at least
     * [target] seconds past the current boundary. Every boundary is a REAL keyframe, so
     * the segment muxer (which we drive with these as explicit `-segment_times`) cuts
     * exactly here with `-c:v copy` — the produced .ts match the declared #EXTINF and
     * relaunching at any boundary is a clean keyframe seam. Standard ~target-sized
     * segments (receiver-friendly), unlike per-keyframe cutting.
     */
    private fun keyframeBoundaries(keyframes: List<Double>, durationSec: Double, target: Double): DoubleArray {
        val kf = keyframes.asSequence().filter { it.isFinite() && it in 0.0..durationSec }.sorted().toList()
        val b = ArrayList<Double>()
        b += 0.0
        for (k in kf) {
            if (k - b.last() >= target) b += k
        }
        // Close out the final segment at the exact duration.
        if (durationSec - b.last() > MIN_SEG_EPS) {
            b += durationSec
        } else if (b.size >= 2) {
            b[b.size - 1] = durationSec
        }
        return if (b.size >= 2) b.toDoubleArray() else doubleArrayOf(0.0, durationSec)
    }

    /** Coarse fixed grid — only the fallback when keyframes can't be read. */
    private fun fixedBoundaries(durationSec: Double, target: Double): DoubleArray {
        val n = ceil(durationSec / target).toInt().coerceAtLeast(1)
        return DoubleArray(n + 1) { i -> (i * target).coerceAtMost(durationSec) }
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
                            val seg = produceSegment(plan, n)
                            // Read the bytes into memory BEFORE evicting: a concurrent
                            // request's evictOutsideWindow could unlink this file between
                            // produce and the read, and a bare readBytes() on a deleted
                            // file throws out of the route (→ a dropped 500 the receiver
                            // sees as a stalled segment).
                            val bytes = seg?.let { runCatching { it.readBytes() }.getOrNull() }
                            if (bytes == null) {
                                call.respond(HttpStatusCode.NotFound)
                                return@get
                            }
                            evictOutsideWindow(outDir, frontier)
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

    // ── helpers ──────────────────────────────────────────────────────────

    private fun buildHeaders(userAgent: String?, referer: String?): String = buildString {
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

        // Target segment length: keyframeBoundaries() takes the first keyframe at least
        // this far past the previous boundary, giving ~target-sized, receiver-friendly
        // segments that each begin on a real keyframe.
        private const val TARGET_SEC = 4.0

        // Seek bias so a rounded `-ss` still lands on (not before) the boundary keyframe.
        // Must be smaller than any real keyframe gap; 10ms is well under 25fps all-intra.
        private const val SS_EPSILON = 0.01

        // Tiny tail-segment guard when closing the boundary list at the duration.
        private const val MIN_SEG_EPS = 0.2

        // A backward request more than this many segments below the frontier is a real
        // seek (recenters the cache window); smaller is read-ahead jitter / a tiny scrub.
        private const val SEEK_BACK_THRESHOLD = 6

        // Concurrent on-demand segment extractions.
        private const val MAX_CONCURRENT_SEGMENTS = 3

        // Cache window around the play frontier (segments). Generous look-ahead so the
        // receiver's read-ahead stays cached; BACK_BUFFER allows instant back-scrub.
        private const val BACK_BUFFER = 30
        private const val AHEAD_KEEP = 90
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
