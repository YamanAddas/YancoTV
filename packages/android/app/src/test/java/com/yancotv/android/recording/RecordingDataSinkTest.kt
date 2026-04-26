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
        sink.write(byteArrayOf(0, 0), 0, 2) // dropped (pre-begin)
        sink.begin(ByteArrayOutputStream())
        assertEquals(0L, sink.bytesSinceBegin)
        sink.write(byteArrayOf(1, 2, 3, 4), 0, 4)
        assertEquals(4L, sink.bytesSinceBegin)
        sink.write(byteArrayOf(5, 6), 0, 2)
        assertEquals(6L, sink.bytesSinceBegin)
    }
}
