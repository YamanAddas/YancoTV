package com.yancotv.shared.http

import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer

/**
 * MB-355 regression — the bug that made every scheduled recording write 0
 * bytes lived in a layer with NO tests. Every recorder test fakes the
 * `HttpClient` interface directly, so `KtorHttpClient` was never exercised
 * and `performGet`'s buffering went unnoticed for months.
 *
 * The contract under test: with `streamLive = true`, bytes must reach the
 * caller **while the response body is still open**. `ktor.get()` is a
 * non-prepared call that reads the entire body before returning, so on a
 * live stream that never ends it never returns at all — the recorder's read
 * loop, its duration cap and its heartbeat all sit inside a block that is
 * never reached.
 *
 * The body here deliberately never closes, which is what a live MPEG-TS
 * stream looks like. On the pre-fix code this test fails by timeout rather
 * than hanging forever, which is why the `withTimeout` is load-bearing and
 * not just belt-and-braces.
 */
class StreamLiveDoesNotBufferTest {
    @Test
    fun streamLiveDeliversBytesBeforeTheBodyEnds() {
        val payload = ByteArray(CHUNK) { (it and 0xFF).toByte() }
        val body = ByteChannel(autoFlush = true)

        val engine = MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "video/mp2t"),
            )
        }
        val ktor = KtorClient(engine)
        val tmp = createTempDirectory("streamlive").toFile()
        val client = AndroidKtorHttpClient(ktor, { "UA" }, { null }, tmp)

        val writer = CoroutineScope(Dispatchers.IO)
        // Emit one chunk and then STAY OPEN forever — never close the
        // channel. A buffering transport can never satisfy this.
        writer.launch { body.writeFully(payload) }

        val read = runBlocking {
            withTimeout(TIMEOUT_MS) {
                client.getSource(
                    "http://stream.example/live.ts",
                    HttpRequestOptions(timeoutMs = Long.MAX_VALUE, streamLive = true),
                ) { source ->
                    val staging = Buffer()
                    var total = 0L
                    while (total < CHUNK) {
                        val n = source.readAtMostTo(staging, CHUNK.toLong())
                        if (n <= 0L) break
                        total += n
                        staging.clear()
                    }
                    total
                }
            }
        }

        assertEquals(
            CHUNK.toLong(),
            read,
            "streamLive must hand bytes to the caller while the body is still open; " +
                "a buffering transport would time out here",
        )
    }

    private companion object {
        const val CHUNK = 16 * 1024
        const val TIMEOUT_MS = 10_000L
    }
}
