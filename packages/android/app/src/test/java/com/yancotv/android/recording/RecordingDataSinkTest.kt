package com.yancotv.android.recording

import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [RecordingDataSink] — the MK.14.8 tee sink that taps
 * ExoPlayer's existing HTTP traffic for live recording.
 *
 * Pinning the sink's behaviour here protects three contracts that
 * [androidx.media3.datasource.TeeDataSource] interacts with at runtime:
 *
 *   1. **`open` / `close` are no-ops.** Tee invokes them once per
 *      [androidx.media3.datasource.DataSpec] (= once per HTTP GET for
 *      continuous MPEG-TS, once per segment for HLS). The user-driven
 *      recording lifecycle owned by [RecordingDataSink.begin] /
 *      [RecordingDataSink.end] must survive across those.
 *   2. **Writes outside `begin` / `end` are dropped.** ExoPlayer keeps
 *      reading bytes for ongoing playback; we don't want them in a
 *      closed file.
 *   3. **`end()` is idempotent.** `RecordingService.handleStop` and the
 *      tee-job's `finally` both call `end()`; a second call must be safe.
 *
 * `open(DataSpec)` is exercised indirectly via the bare [close] call —
 * `DataSpec` requires `android.net.Uri`, which is a stub in JVM unit
 * tests. The implementation makes both methods literal no-ops, so the
 * cycle test below verifies the survival contract through `close()`
 * alone (the half that takes no parameters).
 */
@UnstableApi
class RecordingDataSinkTest {
    private lateinit var sink: RecordingDataSink

    @BeforeTest
    fun setUp() {
        sink = RecordingDataSink()
    }

    @Test
    fun beginThenWriteThenEndProducesStreamWithBytes() {
        val out = ByteArrayOutputStream()
        sink.begin(out)
        val payload = byteArrayOf(0x47, 0x40, 0x00, 0x10) // 188-byte TS sync byte + header
        sink.write(payload, 0, payload.size)
        val total = sink.end()
        assertEquals(4L, total)
        assertContentEquals(payload, out.toByteArray())
    }

    @Test
    fun writeBeforeBeginIsDropped() {
        // Pre-begin: any writes from background ExoPlayer reads (e.g. from
        // before the user pressed Record) must vanish — there's no stream
        // to write to.
        sink.write(byteArrayOf(1, 2, 3), 0, 3)
        assertFalse(sink.isActive)
        assertEquals(0L, sink.bytesSinceBegin)
        // Now begin and confirm only post-begin writes count.
        val out = ByteArrayOutputStream()
        sink.begin(out)
        sink.write(byteArrayOf(4, 5), 0, 2)
        sink.end()
        assertContentEquals(byteArrayOf(4, 5), out.toByteArray())
    }

    @Test
    fun writeAfterEndIsDropped() {
        val out = ByteArrayOutputStream()
        sink.begin(out)
        sink.write(byteArrayOf(1, 2), 0, 2)
        sink.end()
        // Tee may keep calling write() for a tick after our finally fires
        // (ExoPlayer's load thread is racing the main-thread end() call).
        // Those writes must not throw and must not land anywhere.
        sink.write(byteArrayOf(99, 99, 99), 0, 3)
        assertContentEquals(byteArrayOf(1, 2), out.toByteArray())
    }

    @Test
    fun multipleCloseCyclesFromTeeDontTerminateRecording() {
        // Simulates the HLS segment lifecycle: Tee opens/closes the sink
        // once per segment. Our sink no-ops both, so concatenated bytes
        // from N "segments" all land in one file. We only call `close()`
        // here because `open(DataSpec)` requires android.net.Uri, which
        // is a stub on the JVM (the implementation no-ops both methods).
        val out = ByteArrayOutputStream()
        sink.begin(out)

        sink.write(byteArrayOf(1, 2, 3), 0, 3)
        sink.close()

        sink.write(byteArrayOf(4, 5, 6), 0, 3)
        sink.close()

        sink.write(byteArrayOf(7, 8), 0, 2)
        sink.close()

        val total = sink.end()
        assertEquals(8L, total)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), out.toByteArray())
    }

    @Test
    fun endIsIdempotent() {
        val out = ByteArrayOutputStream()
        sink.begin(out)
        sink.write(byteArrayOf(1), 0, 1)
        // First end finalizes; subsequent ends must be safe no-ops that
        // return 0 (the in-progress counter has been reset).
        assertEquals(1L, sink.end())
        assertEquals(0L, sink.end())
        assertEquals(0L, sink.end())
        assertFalse(sink.isActive)
    }

    @Test
    fun beginWithoutEndForceClosesPriorStream() {
        // Defensive: production gates this through UI bookkeeping, but if
        // a duplicate begin slips through it shouldn't leak the prior
        // stream and should reset the byte count.
        val first = ByteArrayOutputStream()
        sink.begin(first)
        sink.write(byteArrayOf(1, 2, 3), 0, 3)

        val second = ByteArrayOutputStream()
        sink.begin(second)
        assertEquals(0L, sink.bytesSinceBegin)
        sink.write(byteArrayOf(9, 9), 0, 2)
        sink.end()

        assertContentEquals(byteArrayOf(1, 2, 3), first.toByteArray())
        assertContentEquals(byteArrayOf(9, 9), second.toByteArray())
    }

    @Test
    fun isActiveTracksBeginEndCorrectly() {
        assertFalse(sink.isActive)
        sink.begin(ByteArrayOutputStream())
        assertTrue(sink.isActive)
        sink.end()
        assertFalse(sink.isActive)
    }

    @Test
    fun bytesSinceBeginAccumulatesOnlyWritesAfterBegin() {
        // MB-206 (2026-04-27) — `bytesSinceBegin` reflects bytes
        // **flushed to disk**, not bytes buffered in the PAT/PMT
        // preroll. Small non-TS-shaped writes therefore stay at 0
        // until end()'s fail-open flush dumps them. Bytes pre-begin
        // are dropped entirely. Bytes post-end are dropped entirely.
        sink.write(byteArrayOf(0, 0), 0, 2) // dropped (pre-begin)
        val out = ByteArrayOutputStream()
        sink.begin(out)
        assertEquals(0L, sink.bytesSinceBegin)
        sink.write(byteArrayOf(1, 2, 3, 4), 0, 4)
        sink.write(byteArrayOf(5, 6), 0, 2)
        // Still buffered (no PAT detected) — counter at 0 until flush.
        assertEquals(0L, sink.bytesSinceBegin)
        assertEquals(6L, sink.end())
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), out.toByteArray())
    }

    // ── MB-206 — TS PAT/PMT preroll ──────────────────────────────────

    /**
     * The PAT/PMT preroll is the difference between a recording that
     * plays in ExoPlayer and one that fails with
     * `ERROR_CODE_PARSING_CONTAINER_MALFORMED`. These tests pin the
     * three behaviours the user depends on:
     *
     *   1. Garbage at the head is dropped — file starts at the first
     *      PAT-aligned packet whose buffer also contains the PMT.
     *   2. After the prelude lands, subsequent bytes flow straight to
     *      disk (the buffer is one-shot for a recording).
     *   3. If a recording ends before any PAT/PMT was seen, whatever
     *      was buffered still flushes to the file (fail-open) so the
     *      user doesn't see a 0-byte row when bytes were captured.
     */

    @Test
    fun preroll_dropsGarbageBeforePat_thenFlushesFromPat() {
        val out = ByteArrayOutputStream()
        sink.begin(out)
        // 200 bytes of mid-payload garbage that happens to contain a 0x47
        // at offset 50 but no follow-up sync 188 bytes later — the
        // two-packet alignment check rejects it as a false positive.
        val garbage = ByteArray(200) { (it * 7 + 13).toByte() }
        garbage[50] = 0x47
        sink.write(garbage, 0, garbage.size)
        // Nothing flushed yet — no PAT in sight.
        assertEquals(0, out.size())

        // Now feed a real PAT followed by a PMT packet.
        val pat = patPacket(programNumber = 1, pmtPid = 0x0100)
        val pmt = pmtPacket(pmtPid = 0x0100)
        sink.write(pat + pmt, 0, pat.size + pmt.size)

        // File should now contain bytes starting from the PAT — the
        // 200-byte garbage at the head is dropped. Total written =
        // pat.size + pmt.size.
        assertEquals((pat.size + pmt.size).toLong(), sink.bytesSinceBegin)
        assertEquals(pat.size + pmt.size, out.size())
        // First byte of the file is the TS sync of the PAT packet.
        assertEquals(0x47.toByte(), out.toByteArray()[0])
    }

    @Test
    fun preroll_subsequentWritesBypassBufferAfterHeaderSeen() {
        val out = ByteArrayOutputStream()
        sink.begin(out)
        // Land the prelude in one go.
        val pat = patPacket(programNumber = 1, pmtPid = 0x0100)
        val pmt = pmtPacket(pmtPid = 0x0100)
        sink.write(pat + pmt, 0, pat.size + pmt.size)
        val afterHeader = out.size()

        // Now write a non-TS-shaped chunk. With the prelude already
        // seen, the buffer is bypassed — these bytes must flush through
        // immediately, even though they don't contain a sync byte.
        val tail = byteArrayOf(1, 2, 3, 4, 5)
        sink.write(tail, 0, tail.size)
        assertEquals(afterHeader + tail.size, out.size())
        assertContentEquals(
            tail,
            out.toByteArray().copyOfRange(afterHeader, out.size()),
        )
    }

    @Test
    fun preroll_endFlushesBufferIfPatNeverArrives() {
        // Short recording: user pressed Stop before the broadcast emitted
        // its periodic PAT (every ~100 ms in practice). Without the
        // fail-open in end(), the buffered bytes would silently vanish.
        val out = ByteArrayOutputStream()
        sink.begin(out)
        val payload = ByteArray(50) { it.toByte() }
        sink.write(payload, 0, payload.size)
        assertEquals(0, out.size()) // still buffered

        val total = sink.end()
        assertEquals(payload.size.toLong(), total)
        assertContentEquals(payload, out.toByteArray())
    }

    @Test
    fun preroll_secondPatStartsCleanBuffer() {
        // Each begin() resets the buffer + headerSeen so the prelude
        // logic re-runs from scratch. A second recording in the same
        // session (sequential, not concurrent) must not inherit any
        // state from the first.
        val first = ByteArrayOutputStream()
        sink.begin(first)
        val pat = patPacket(programNumber = 1, pmtPid = 0x0200)
        val pmt = pmtPacket(pmtPid = 0x0200)
        sink.write(pat + pmt, 0, pat.size + pmt.size)
        sink.end()

        val second = ByteArrayOutputStream()
        sink.begin(second)
        // Junk before any prelude; new recording should buffer until
        // PAT lands again.
        sink.write(ByteArray(100), 0, 100)
        assertEquals(0, second.size())
        sink.end() // fail-open dump
        assertTrue(second.size() > 0)
    }

    /** Build a 188-byte TS packet for [pid] with optional [payload]. */
    private fun tsPacket(
        pid: Int,
        pusi: Boolean = false,
        payload: ByteArray = ByteArray(184),
    ): ByteArray {
        require(pid in 0..0x1FFF) { "pid out of range: $pid" }
        require(payload.size <= 184)
        val packet = ByteArray(188)
        packet[0] = 0x47
        // byte 1: payload_unit_start_indicator + PID high (5 bits)
        packet[1] = ((if (pusi) 0x40 else 0) or ((pid shr 8) and 0x1F)).toByte()
        // byte 2: PID low (8 bits)
        packet[2] = (pid and 0xFF).toByte()
        // byte 3: AFC=01 (payload only) + cc=0
        packet[3] = 0x10.toByte()
        System.arraycopy(payload, 0, packet, 4, payload.size)
        return packet
    }

    /** PAT (PID=0) advertising one program → [pmtPid]. */
    private fun patPacket(
        programNumber: Int,
        pmtPid: Int,
    ): ByteArray {
        val payload = ByteArray(184)
        payload[0] = 0x00 // pointer_field
        // section header (table_id, length, tsid, flags, section nums)
        payload[1] = 0x00 // table_id = PAT
        payload[2] = 0x80.toByte()
        payload[3] = 0x09
        payload[4] = 0x00
        payload[5] = 0x01
        payload[6] = 0xC1.toByte()
        payload[7] = 0x00
        payload[8] = 0x00
        // first program entry
        payload[9] = ((programNumber shr 8) and 0xFF).toByte()
        payload[10] = (programNumber and 0xFF).toByte()
        payload[11] = (((pmtPid shr 8) and 0x1F) or 0xE0).toByte()
        payload[12] = (pmtPid and 0xFF).toByte()
        return tsPacket(pid = 0, pusi = true, payload = payload)
    }

    /** Minimal PMT packet — content doesn't matter, only PID match. */
    private fun pmtPacket(pmtPid: Int): ByteArray =
        tsPacket(pid = pmtPid, pusi = true)
}
