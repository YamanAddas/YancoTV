package com.yancotv.android.recording

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * MK.14.8 — Live-recording sink that taps ExoPlayer's existing data flow
 * via Media3's [androidx.media3.datasource.TeeDataSource]. Wired into the
 * shared [com.yancotv.android.player.PlaybackController]'s HTTP factory
 * by [TeeingDataSourceFactory], so every byte ExoPlayer reads from an
 * `http(s)://` source flows here on its way to the decoder.
 *
 * **Why not a fresh HTTP GET** (the previous Stage 3.1 architecture):
 * Most Xtream-style IPTV providers cap concurrent streams per account.
 * The recorder's parallel GET to the same channel either hangs in
 * `performGet` (server holds the slot for the player) or kicks the
 * player off. Tapping the bytes the player is *already* pulling sidesteps
 * the cap entirely — record-while-watching works on 1-stream plans.
 *
 * **Lifecycle (user-driven, not Tee-driven):**
 *   - [begin] — opens a [java.io.FileOutputStream]-or-SAF stream and
 *     starts capturing writes. Idempotent: a duplicate begin closes the
 *     prior stream first (defensive — UI gates this, but better safe).
 *   - [end] — flushes + closes the stream and returns the byte count.
 *     Safe to call when no recording is active (idempotent no-op).
 *   - [open] / [close] — **deliberate no-ops.** Tee invokes them once per
 *     [DataSpec] (= one per HTTP GET for continuous MPEG-TS, one per
 *     segment for HLS). Routing those through to our stream would
 *     fragment the recording across segment boundaries; the user's
 *     "Record this channel" intent spans the entire viewing session,
 *     not per-segment.
 *
 * **Concurrency model.** Singleton across the app process (one instance
 * managed by Koin). Three threads touch it:
 *   - ExoPlayer's load thread → [write]
 *   - RecordingService's IO scope → [begin] / [end]
 *   - UI / random callers → [isActive] / [bytesSinceBegin] reads
 *
 * All access is gated by [lock]. Writes after [end] (or before [begin])
 * are silently dropped — the user's recording is finalized; ExoPlayer
 * keeps reading bytes for ongoing playback, and we don't want to write
 * those into a closed file.
 *
 * **Single recording at a time.** The current UI path (RecordPanel →
 * `activeForChannel` check) ensures only one user-initiated live
 * recording can be active. Catch-up / scheduled recordings on different
 * URLs go through `MpegTsRecorder`/`HlsRecorder`'s fresh-GET path and
 * don't touch this sink.
 */
@UnstableApi
class RecordingDataSink(
    @Suppress("unused") private val logger: Logger = NOOP_LOGGER,
) : DataSink {
    private val lock = Any()
    private var output: OutputStream? = null
    private var bytesWritten: Long = 0L

    // MB-206 (2026-04-27) — TS-alignment + PAT/PMT preroll. The user
    // can press "Record this channel" mid-broadcast; the bytes
    // ExoPlayer is feeding through Tee at that instant typically
    // begin in the middle of a TS payload, with the most recent
    // PAT/PMT already passed. Writing those bytes verbatim produced
    // a file that ExoPlayer's TsExtractor refused to parse on
    // playback (`ERROR_CODE_PARSING_CONTAINER_MALFORMED` —
    // "Loading finished before preparation is complete"). Fix: buffer
    // incoming bytes until we've seen at least one PAT followed by
    // its PMT, then flush starting from the PAT packet so the file
    // begins on a 188-byte boundary with a valid program table head.
    //
    // [headerSeen] flips true once the prelude is found; subsequent
    // writes go straight to disk with no buffering. [headerBuf] holds
    // the prelude scan window (capped at [HEADER_PROBE_MAX_BYTES] so
    // a stream that never sends PAT — pathological — doesn't OOM).
    private var headerSeen: Boolean = false
    private var headerBuf: ByteArrayOutputStream? = null

    /** True while a recording is in progress (between [begin] and [end]). */
    val isActive: Boolean
        get() = synchronized(lock) { output != null }

    /** Bytes written to the current recording. Resets on [begin]. */
    val bytesSinceBegin: Long
        get() = synchronized(lock) { bytesWritten }

    /**
     * Start capturing into [stream]. The sink takes ownership — [end]
     * closes it. Caller is responsible for buffering (we don't wrap;
     * call sites that want a buffer pass a `BufferedOutputStream`).
     *
     * If a prior recording was active without [end] being called, the
     * old stream is force-closed first. Defensive: production gates this
     * via UI / RecordingService bookkeeping, so this branch shouldn't
     * fire. Tests exercise it.
     */
    fun begin(stream: OutputStream) {
        synchronized(lock) {
            output?.let { prior ->
                // MB-206 — fail-open flush on duplicate begin too. If the
                // prior recording's prelude never resolved (PAT not seen
                // yet) we still dump whatever's buffered so the prior
                // stream isn't a silent zero-byte file. Symmetric with
                // [end].
                if (!headerSeen) {
                    val pending = headerBuf?.toByteArray()
                    if (pending != null && pending.isNotEmpty()) {
                        runCatching { prior.write(pending, 0, pending.size) }
                    }
                }
                runCatching { prior.flush() }
                runCatching { prior.close() }
            }
            output = stream
            bytesWritten = 0L
            headerSeen = false
            headerBuf = ByteArrayOutputStream()
        }
    }

    /**
     * Stop capturing and close the current stream. Returns the byte
     * count written between [begin] and now. Idempotent: returns 0 when
     * called without an active recording.
     */
    fun end(): Long {
        synchronized(lock) {
            // MB-206 — if the recording stops before a PAT/PMT pair was
            // ever seen (very short recording, or a stream that never
            // emits PAT in the buffer window), flush whatever's been
            // buffered so the user at least sees bytes on disk. The
            // file may be unplayable but it's not a silent zero-byte
            // confusion. Same fail-open posture as the buffer-cap
            // bail in [write].
            if (!headerSeen) {
                val pending = headerBuf?.toByteArray()
                val stream = output
                if (pending != null && pending.isNotEmpty() && stream != null) {
                    runCatching {
                        stream.write(pending, 0, pending.size)
                        bytesWritten += pending.size.toLong()
                    }
                }
            }
            val total = bytesWritten
            output?.let { stream ->
                runCatching { stream.flush() }
                runCatching { stream.close() }
            }
            output = null
            bytesWritten = 0L
            headerSeen = false
            headerBuf = null
            return total
        }
    }

    /**
     * No-op. [androidx.media3.datasource.TeeDataSource.open] calls this
     * once per upstream `open(DataSpec)` (= one per HTTP GET for
     * continuous MPEG-TS, one per segment for HLS). The user-driven
     * recording lifecycle owned by [begin] / [end] must survive across
     * those — opening/closing the file per DataSpec would fragment the
     * recording.
     */
    override fun open(dataSpec: DataSpec) {
        // Intentionally empty — see KDoc.
    }

    /**
     * Capture bytes into the active stream. Called from ExoPlayer's load
     * thread. When no recording is active (UI hasn't called [begin] yet,
     * or [end] already ran) writes are silently dropped — they belong to
     * regular playback, not a recording.
     *
     * IO failures during a write force-end the stream (we drop the rest
     * of the recording rather than crash the player). The UI's "Stop
     * recording" still works to finalize the row with whatever made it
     * to disk.
     */
    @Throws(IOException::class)
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            val stream = output ?: return
            try {
                if (headerSeen) {
                    stream.write(buffer, offset, length)
                    bytesWritten += length.toLong()
                    return
                }
                // MB-206 — buffer until we find a TS-aligned PAT followed
                // by its PMT, then flush from the PAT onwards. This is
                // the only branch that pays the ByteArrayOutputStream
                // cost — once headerSeen flips true above, writes go
                // straight to disk for the rest of the recording.
                val buf = headerBuf
                if (buf == null) {
                    // Defensive: shouldn't happen because begin() always
                    // allocates one. Treat as "give up on alignment".
                    stream.write(buffer, offset, length)
                    bytesWritten += length.toLong()
                    headerSeen = true
                    return
                }
                buf.write(buffer, offset, length)
                val scan = buf.toByteArray()
                val patStart = findPatStart(scan)
                if (patStart >= 0) {
                    val pmtPid = readPmtPidFromPat(scan, patStart)
                    if (pmtPid > 0) {
                        // Confirm we've also seen the PMT itself in the
                        // buffer. Without the PMT in the file head,
                        // TsExtractor still can't map elementary streams
                        // to PIDs. PMT typically follows PAT in the same
                        // batch (every ~100 ms in a continuous broadcast).
                        val pmtStart = findPidStart(scan, patStart + TS_PACKET_SIZE, pmtPid)
                        if (pmtStart >= 0) {
                            val payload = scan.size - patStart
                            stream.write(scan, patStart, payload)
                            bytesWritten += payload.toLong()
                            headerSeen = true
                            headerBuf = null
                            return
                        }
                    }
                }
                // No prelude yet. If we've buffered too much without
                // finding one, fail open: write everything we have and
                // accept that this recording may be unplayable. The user
                // sees a row of bytes in Recordings either way.
                if (buf.size() >= HEADER_PROBE_MAX_BYTES) {
                    stream.write(scan, 0, scan.size)
                    bytesWritten += scan.size.toLong()
                    headerSeen = true
                    headerBuf = null
                }
            } catch (t: IOException) {
                runCatching { stream.close() }
                output = null
                headerBuf = null
                // Do not rethrow — Tee.read() would then propagate the
                // IOException up to ExoPlayer's loader and abort playback.
                // Recording is best-effort; the player keeps going.
            }
        }
    }

    // ── MB-206 — TS sync helpers ──────────────────────────────────────

    /**
     * Find the first index `i` in [bytes] such that [bytes] contains a
     * TS sync byte (0x47) at `i` AND another sync byte 188 bytes later.
     * The two-packet check is the standard cheap defence against false
     * positives — random 0x47 bytes inside a video payload almost never
     * have another 0x47 exactly 188 bytes later.
     *
     * Further, the candidate packet must carry PID 0 (PAT). Returns -1
     * when no aligned PAT packet is present in [bytes].
     */
    private fun findPatStart(bytes: ByteArray): Int {
        var i = 0
        while (i + 2 * TS_PACKET_SIZE <= bytes.size) {
            if (bytes[i] == TS_SYNC && bytes[i + TS_PACKET_SIZE] == TS_SYNC) {
                val pid = ((bytes[i + 1].toInt() and 0x1F) shl 8) or (bytes[i + 2].toInt() and 0xFF)
                if (pid == PID_PAT) return i
            }
            i++
        }
        return -1
    }

    /**
     * Parse the PAT packet starting at [patStart] in [bytes] and return
     * the PID of the first program's PMT. Returns -1 if the packet is
     * malformed or carries no programs.
     *
     * PAT layout (after the 4-byte TS header):
     *   - 1 byte pointer_field (when payload_unit_start_indicator = 1)
     *   - section: table_id (1) + section_length (2) + transport_stream_id (2)
     *               + flags (1) + section_number (1) + last_section (1)
     *   - then N×(4 bytes) program_number/pmt_pid pairs
     */
    private fun readPmtPidFromPat(bytes: ByteArray, patStart: Int): Int {
        // Byte 1 high bit = payload_unit_start_indicator.
        val pusi = (bytes[patStart + 1].toInt() and 0x40) != 0
        if (!pusi) return -1
        // Adaptation field control = bits 5..4 of byte 3.
        val afc = (bytes[patStart + 3].toInt() and 0x30) ushr 4
        var p = patStart + 4
        if (afc == 0x2 || afc == 0x3) {
            // Skip adaptation field: byte at p is its length.
            val afLen = bytes[p].toInt() and 0xFF
            p += 1 + afLen
        }
        if (p >= bytes.size) return -1
        // pointer_field — points at the start of the section.
        val pointer = bytes[p].toInt() and 0xFF
        p += 1 + pointer
        // Section header occupies 8 bytes; first program entry sits at p+8.
        // We only inspect the first program (skip program_number 0 = NIT).
        var entry = p + 8
        while (entry + 4 <= patStart + TS_PACKET_SIZE && entry + 4 <= bytes.size) {
            val programNumber = ((bytes[entry].toInt() and 0xFF) shl 8) or (bytes[entry + 1].toInt() and 0xFF)
            val pid =
                ((bytes[entry + 2].toInt() and 0x1F) shl 8) or (bytes[entry + 3].toInt() and 0xFF)
            if (programNumber != 0 && pid > 0) return pid
            entry += 4
        }
        return -1
    }

    /** Find first TS-aligned packet at or after [from] carrying [pid]. */
    private fun findPidStart(bytes: ByteArray, from: Int, pid: Int): Int {
        var i = from
        while (i + TS_PACKET_SIZE <= bytes.size) {
            if (bytes[i] == TS_SYNC) {
                val p = ((bytes[i + 1].toInt() and 0x1F) shl 8) or (bytes[i + 2].toInt() and 0xFF)
                if (p == pid) return i
            }
            i++
        }
        return -1
    }

    /**
     * No-op. See [open]. The TeeDataSource's per-DataSpec close cycle
     * doesn't end the user's recording; only [end] does.
     */
    override fun close() {
        // Intentionally empty — see KDoc.
    }

    private companion object {
        // MB-206 sync constants. 188 = MPEG-TS packet size; 0x47 = sync
        // byte at the start of every aligned packet; PID 0 carries the
        // Program Association Table.
        const val TS_PACKET_SIZE: Int = 188
        const val TS_SYNC: Byte = 0x47
        const val PID_PAT: Int = 0

        /**
         * Cap on the prelude buffer. Continuous IPTV broadcasts repeat
         * PAT/PMT every ~100 ms (i.e. every ~10 KB at 1 Mbps, ~75 KB at
         * 10 Mbps). 1 MB covers >0.8 s of 10 Mbps headroom; if PAT/PMT
         * still hasn't shown the stream is non-standard and we bail to
         * "write everything" so the user sees bytes on disk regardless.
         */
        const val HEADER_PROBE_MAX_BYTES: Int = 1 * 1024 * 1024
    }
}
