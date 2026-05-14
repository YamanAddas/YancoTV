package com.yancotv.shared.http

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.UnknownHostException

/**
 * OkHttp interceptor that enforces the application-layer cleartext
 * allow-list (MK.SEC.A). HTTP requests to hosts NOT on the allow-list
 * are short-circuited at the OkHttp layer with an `IOException` — the
 * request never leaves the device.
 *
 * Why a lazy `allowlistProvider` and not a direct [CleartextAllowlist]
 * field: the allow-list is derived from `SourceRepository.getAll()`,
 * and `SourceRepository` is constructed AFTER `OkHttpClient` /
 * `HttpClient` in the Koin graph. A direct field would create a
 * circular dependency. The provider lambda defers the lookup until
 * the first HTTP request runs — by which point every Koin single is
 * resolvable.
 *
 * Caching: the provider is invoked on EVERY request — cheap because
 * `cleartextAllowlistFromSources` is a pure pass over a small list
 * (worst case ~50 sources). If profiling later shows this matters we
 * can wrap in a memoised holder backed by `SourceRepository.allFlow()`
 * — for now correctness > micro-optimisation.
 *
 * Behaviour matrix:
 *
 *   - `https://` request → pass through, allow-list never consulted.
 *   - `http://allowed.example.com/...` → pass through.
 *   - `http://blocked.example.com/...` → throws `IOException` (subclass
 *     `CleartextNotAllowedException`) with the host name redacted via
 *     [redactCredentials] in the message. The exception travels up the
 *     normal OkHttp error path; calling code already handles `IOException`.
 *
 * What this does NOT cover:
 *
 *   - Media3 `ExoPlayer` traffic. The player uses its own
 *     `HttpDataSource.Factory` (DefaultHttpDataSource or
 *     OkHttpDataSource depending on Stage 5.x wiring). MK.SEC.C lands
 *     the parallel enforcement for player traffic.
 *   - Any OkHttpClient instance that doesn't have this interceptor
 *     registered. The Koin module in `AppModules.kt` registers it on
 *     the shared OkHttpClient + the Ktor engine; PlaybackController's
 *     dedicated OkHttp instance (per-source UA/Referer) needs its own
 *     registration when MK.SEC.C ships.
 *
 * @see CleartextAllowlist contract.
 * @see cleartextAllowlistFromSources derivation from sources list.
 */
class CleartextAllowlistInterceptor(
    private val allowlistProvider: () -> CleartextAllowlist,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val scheme = req.url.scheme.lowercase()

        // HTTPS bypasses the allow-list entirely — the threat model the
        // allow-list addresses is on-path attackers in cleartext, not
        // encrypted traffic.
        if (scheme != "http") return chain.proceed(req)

        val host = req.url.host.lowercase()
        val allowlist = allowlistProvider()
        if (allowlist.isHostAllowed(host)) return chain.proceed(req)

        // Block. Return a synthetic 451-class response rather than
        // throwing, so error UI gets a structured failure (status code +
        // body) rather than a blanket IOException — `redactCredentials`
        // strips any userinfo / query auth from the URL in the body so
        // we don't leak provider creds into logs.
        //
        // Code 469 isn't a real HTTP status — it's an internal sentinel
        // ("CleartextDeniedInternal") chosen outside the 4xx/5xx allocated
        // ranges so logs reading the status can grep for it uniquely.
        return Response
            .Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(469)
            .message("Cleartext denied: host not in allow-list")
            .body(
                buildDenialBody(host, req.url.toString()).toResponseBody(),
            ).build()
    }

    private fun buildDenialBody(host: String, fullUrl: String): String =
        "Cleartext HTTP refused at the application layer for host '$host'. " +
            "Add a Source with this host as its URL to permit it. " +
            "Request: ${redactCredentials(fullUrl)}"
}

/**
 * Thrown when a network caller wants the cleartext-denied condition as
 * an exception rather than a synthetic response. Currently unused
 * (interceptor returns a synthetic 469 response, see above) but
 * provided so future call sites can throw / catch this explicitly if
 * the synthetic-response approach proves inconvenient.
 */
class CleartextNotAllowedException(host: String) : UnknownHostException(
    "Cleartext HTTP refused for host '$host' (not in application-layer allow-list).",
)
