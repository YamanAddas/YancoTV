package com.yancotv.shared.http

import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The guard on [KtorHttpClient] itself, rather than on the derivation it
 * consults — `CleartextAllowlistTest` covers that.
 *
 * The distinction matters: the allow-list was written months before
 * anything on iOS consulted it, and a set of allowed hosts that no request
 * path asks about is not a defence. These tests fail if the call is
 * removed from either request path.
 */
class CleartextGuardTest {

    private var requestsMade = 0

    private fun client(allowed: Set<String>): KtorHttpClient {
        requestsMade = 0
        val engine = MockEngine { _ ->
            requestsMade += 1
            respond(
                content = "ok",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/plain"),
            )
        }
        return KtorHttpClient(KtorClient(engine), "test-agent").apply {
            cleartextAllowlist = { StaticCleartextAllowlist(allowed) }
        }
    }

    @Test
    fun `plain http to a configured source is allowed`() = runTest {
        val http = client(setOf("provider.example"))
        assertEquals("ok", http.getText("http://provider.example/list", HttpRequestOptions()))
        assertEquals(1, requestsMade)
    }

    /**
     * The whole point: the request must not leave the device. Asserting on
     * the mock engine's counter rather than only on the exception, because
     * a guard that throws *after* dispatching would still leak the traffic
     * this exists to stop.
     */
    @Test
    fun `plain http to a host that is not a source never reaches the network`() = runTest {
        val http = client(setOf("provider.example"))
        assertFailsWith<CleartextBlockedException> {
            http.getText("http://somewhere-else.example/list", HttpRequestOptions())
        }
        assertEquals(0, requestsMade)
    }

    /** HTTPS is not what the allow-list is for. */
    @Test
    fun `https is never consulted`() = runTest {
        val http = client(emptySet())
        assertEquals("ok", http.getText("https://anything.example/list", HttpRequestOptions()))
        assertEquals(1, requestsMade)
    }

    /**
     * The streaming path is a second entry point and had to be guarded
     * separately — it is the one a recorder and a long fetch go through.
     */
    @Test
    fun `the streaming path is guarded too`() = runTest {
        val http = client(setOf("provider.example"))
        assertFailsWith<CleartextBlockedException> {
            http.getSource("http://somewhere-else.example/stream", HttpRequestOptions()) { it }
        }
        assertEquals(0, requestsMade)
    }

    /** Unwired clients behave exactly as they did before the guard existed. */
    @Test
    fun `the default permits everything`() = runTest {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK) }
        val http = KtorHttpClient(KtorClient(engine), "test-agent")
        assertEquals("ok", http.getText("http://anywhere.example/x", HttpRequestOptions()))
    }

    /** The message may be logged, so it carries the host and nothing else. */
    @Test
    fun `the message names the host and not the url`() = runTest {
        val http = client(emptySet())
        val error = assertFailsWith<CleartextBlockedException> {
            http.getText(
                "http://provider.example/live/user/secret/1.ts?password=hunter2",
                HttpRequestOptions(),
            )
        }
        assertEquals("provider.example", error.host)
        assertTrue("hunter2" !in (error.message ?: ""))
        assertTrue("secret" !in (error.message ?: ""))
    }
}
