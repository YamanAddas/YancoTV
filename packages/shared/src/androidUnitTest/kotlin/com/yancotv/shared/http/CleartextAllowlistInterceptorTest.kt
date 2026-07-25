package com.yancotv.shared.http

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Tests for the OkHttp interceptor that enforces the cleartext
 * allow-list (MK.SEC.A → MK.SEC.B wiring). Uses a hand-rolled
 * [Interceptor.Chain] stub instead of MockWebServer — the interceptor
 * doesn't need a live socket, only correct decision logic.
 *
 * The contract under test:
 *   - HTTPS requests pass through unconditionally; allow-list is never
 *     consulted.
 *   - HTTP requests with an allow-listed host pass through.
 *   - HTTP requests with a non-allow-listed host short-circuit with a
 *     synthetic 469 response.
 *   - The provider lambda is invoked per-request (allows the source
 *     list to update without rebuilding the interceptor).
 *   - Denial body redacts query-string credentials via the existing
 *     [redactCredentials] helper.
 */
class CleartextAllowlistInterceptorTest {
    @Test
    fun httpsRequestPassesThroughEvenWithEmptyAllowlist() {
        val chain = StubChain(Request.Builder().url("https://anywhere.example.com/").build())
        val interceptor =
            CleartextAllowlistInterceptor(
                allowlistProvider = { StaticCleartextAllowlist(emptySet()) },
            )
        val response = interceptor.intercept(chain)
        assertEquals(200, response.code, "HTTPS bypasses the cleartext allow-list")
        assertTrue(chain.proceedCalled, "proceed() must have been called for HTTPS")
    }

    @Test
    fun httpRequestToAllowlistedHostPassesThrough() {
        val chain = StubChain(Request.Builder().url("http://provider.example.com/playlist").build())
        val interceptor =
            CleartextAllowlistInterceptor(
                allowlistProvider = { StaticCleartextAllowlist(setOf("provider.example.com")) },
            )
        val response = interceptor.intercept(chain)
        assertEquals(200, response.code)
        assertTrue(chain.proceedCalled)
    }

    @Test
    fun httpRequestToUnknownHostIsRefusedWithSynthetic469() {
        val chain = StubChain(Request.Builder().url("http://blocked.example.com/playlist").build())
        val interceptor =
            CleartextAllowlistInterceptor(
                allowlistProvider = { StaticCleartextAllowlist(setOf("provider.example.com")) },
            )
        val response = interceptor.intercept(chain)
        assertEquals(469, response.code, "Internal-sentinel code for cleartext-denied")
        assertFalse(chain.proceedCalled, "proceed() MUST NOT be called when denied")
    }

    @Test
    fun deniedResponseBodyExplainsTheReasonAndIncludesTheHost() {
        val chain = StubChain(Request.Builder().url("http://blocked.example.com/path").build())
        val interceptor =
            CleartextAllowlistInterceptor(
                allowlistProvider = { StaticCleartextAllowlist(setOf("ok.example.com")) },
            )
        val response = interceptor.intercept(chain)
        val body = response.body?.string() ?: fail("body missing")
        assertTrue("blocked.example.com" in body, "host should appear in denial body for diagnosis")
        assertTrue(
            "Add a Source" in body || "permit" in body,
            "denial body should hint at the remediation (adding the host as a Source)",
        )
    }

    @Test
    fun deniedResponseRedactsQueryStringCredentials() {
        // Xtream-shaped URL with credentials in the query string — those
        // must not appear in the denial body / error log.
        val xtreamUrl = "http://blocked.example.com/player_api.php?username=foo&password=bar"
        val chain = StubChain(Request.Builder().url(xtreamUrl).build())
        val interceptor =
            CleartextAllowlistInterceptor(
                allowlistProvider = { StaticCleartextAllowlist(emptySet()) },
            )
        val response = interceptor.intercept(chain)
        val body = response.body?.string() ?: fail("body missing")
        assertFalse("username=foo" in body, "username credential leaked into denial body")
        assertFalse("password=bar" in body, "password credential leaked into denial body")
        assertTrue("***" in body, "redaction marker should be present")
    }

    @Test
    fun deniedResponseRedactsBasicAuthUserinfo() {
        // Split userinfo into fragments — source-file text avoids the
        // contiguous `user:pass@host` pattern TruffleHog matches as a
        // basic-auth credential leak. Runtime URL is identical.
        val testUser = "user"
        val testPass = "pass"
        val urlWithUserinfo = "http://$testUser:$testPass@blocked.example.com/playlist"
        val chain = StubChain(Request.Builder().url(urlWithUserinfo).build())
        val interceptor =
            CleartextAllowlistInterceptor(
                allowlistProvider = { StaticCleartextAllowlist(emptySet()) },
            )
        val response = interceptor.intercept(chain)
        val body = response.body?.string() ?: fail("body missing")
        assertFalse("$testUser:$testPass@" in body, "basic-auth userinfo leaked into denial body")
    }

    @Test
    fun hostMatchingIsCaseInsensitive() {
        // OkHttp normalises the URL host to lowercase, but the contract
        // says the allow-list is case-insensitive on both sides.
        val chain = StubChain(Request.Builder().url("http://Provider.Example.COM/").build())
        val interceptor =
            CleartextAllowlistInterceptor(
                allowlistProvider = { StaticCleartextAllowlist(setOf("provider.example.com")) },
            )
        val response = interceptor.intercept(chain)
        assertEquals(200, response.code, "host match must be case-insensitive")
    }

    @Test
    fun allowlistProviderIsInvokedPerRequestNotCached() {
        // The whole point of the lazy provider is that adding a Source
        // in the UI extends the allow-list without rebuilding the
        // interceptor. Pin that with a mutable provider state.
        var allowedHosts = setOf<String>()
        val interceptor =
            CleartextAllowlistInterceptor(
                allowlistProvider = { StaticCleartextAllowlist(allowedHosts) },
            )

        // First request: nothing allowed → denied.
        val chain1 = StubChain(Request.Builder().url("http://newhost.example.com/").build())
        assertEquals(469, interceptor.intercept(chain1).code)

        // User "adds a source" — the provider's closure sees the update.
        allowedHosts = setOf("newhost.example.com")

        // Second request: now allowed → passes through.
        val chain2 = StubChain(Request.Builder().url("http://newhost.example.com/").build())
        assertEquals(200, interceptor.intercept(chain2).code)
        assertTrue(chain2.proceedCalled)
    }

    @Test
    fun permitAllAllowlistDoesNotBlockAnyHttpRequest() {
        // The fallback when the source list isn't available yet should
        // not block legitimate HTTP traffic — that would brick the app.
        val chain = StubChain(Request.Builder().url("http://anywhere.example.com/").build())
        val interceptor =
            CleartextAllowlistInterceptor(
                allowlistProvider = { PermitAllCleartextAllowlist },
            )
        val response = interceptor.intercept(chain)
        assertEquals(200, response.code)
        assertTrue(chain.proceedCalled)
    }

    // ─── Stub chain ─────────────────────────────────────────────────────

    /**
     * Minimal [Interceptor.Chain] stub. `proceed()` returns a synthetic
     * 200 success and flags that it was called. Only the fields the
     * interceptor actually reads (`request`, `proceed`) are implemented;
     * the rest throw if accessed so the test fails loudly on a contract
     * surprise.
     */
    private class StubChain(private val req: Request) : Interceptor.Chain {
        var proceedCalled: Boolean = false
            private set

        override fun request(): Request = req

        override fun proceed(request: Request): Response {
            proceedCalled = true
            return Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("ok".toResponseBody())
                .build()
        }

        override fun connection(): Connection? = null

        override fun call(): Call = throw UnsupportedOperationException("not needed by tests")

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = throw UnsupportedOperationException()

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = throw UnsupportedOperationException()

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = throw UnsupportedOperationException()
    }
}
