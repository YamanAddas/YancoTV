package com.yancotv.shared.update

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Stage 5.2.1 — unit tests for [UpdateChecker]. Pure / no-platform; the
 * only seam is [HttpClient] which we fake.
 *
 * Coverage:
 *   - Empty endpoint URL → null (disabled — dev-build default).
 *   - HTTP failure → null (caller surfaces "no update" not crash).
 *   - Malformed JSON → null + logged.
 *   - Missing `downloadUrl` → null (won't prompt for install with no
 *     URL).
 *   - Remote versionCode ≤ current → null (no downgrade, no equal-version
 *     prompt).
 *   - Remote versionCode > current → [UpdateInfo] with all fields
 *     propagated.
 *   - Forward-compat: unknown JSON fields ignored, optional fields
 *     default to null.
 *   - URL passed to HTTP layer matches the configured endpoint (catches
 *     a copy-paste bug where the wrong field is fetched).
 */
class UpdateCheckerTest {
    private class FakeHttpClient(private val response: Result<String>, var lastUrl: String? = null) : HttpClient {
        override suspend fun getJson(url: String, options: HttpRequestOptions): Any? = error("getJson not used in UpdateChecker")

        override suspend fun getText(url: String, options: HttpRequestOptions): String {
            lastUrl = url
            return response.getOrThrow()
        }
    }

    @Test fun emptyEndpoint_returnsNull() = runTest {
        val http = FakeHttpClient(Result.success(""))
        val checker = UpdateChecker(http, endpointUrl = "", currentVersionCode = 1)
        assertNull(checker.check(), "empty endpoint must short-circuit before any HTTP call")
        assertNull(http.lastUrl, "empty endpoint must NOT call HTTP")
    }

    @Test fun blankEndpoint_returnsNull() = runTest {
        val http = FakeHttpClient(Result.success(""))
        val checker = UpdateChecker(http, endpointUrl = "   ", currentVersionCode = 1)
        assertNull(checker.check())
    }

    @Test fun httpFailure_returnsNull() = runTest {
        val http = FakeHttpClient(Result.failure(RuntimeException("connection refused")))
        val checker =
            UpdateChecker(http, endpointUrl = "https://example.com/update.json", currentVersionCode = 1)
        assertNull(checker.check(), "fetch failure must surface as 'no update', not throw")
    }

    @Test fun malformedJson_returnsNull() = runTest {
        val http = FakeHttpClient(Result.success("this is not json {{{"))
        val checker =
            UpdateChecker(http, endpointUrl = "https://example.com/update.json", currentVersionCode = 1)
        assertNull(checker.check())
    }

    @Test fun missingDownloadUrl_returnsNull() = runTest {
        // Schema-valid JSON but with blank downloadUrl. We refuse to
        // surface an "update available" prompt that has no URL to
        // install from — better to silently skip.
        val body =
            """
                {
                  "versionCode": 99,
                  "versionName": "0.99.0",
                  "downloadUrl": "",
                  "releaseNotes": "test"
                }
            """.trimIndent()
        val http = FakeHttpClient(Result.success(body))
        val checker =
            UpdateChecker(http, endpointUrl = "https://example.com/update.json", currentVersionCode = 1)
        assertNull(checker.check(), "blank downloadUrl must produce null even when versionCode is newer")
    }

    @Test fun remoteVersionEqualToCurrent_returnsNull() = runTest {
        val body =
            """
                {"versionCode": 5, "versionName": "0.5.0", "downloadUrl": "https://x/y.apk"}
            """.trimIndent()
        val http = FakeHttpClient(Result.success(body))
        val checker =
            UpdateChecker(http, endpointUrl = "https://example.com/update.json", currentVersionCode = 5)
        assertNull(checker.check(), "equal versionCode must NOT prompt update")
    }

    @Test fun remoteVersionOlderThanCurrent_returnsNull() = runTest {
        // User on v10, server still serving v3 (e.g. the user has a
        // dev build newer than the latest public release).
        val body =
            """
                {"versionCode": 3, "versionName": "0.3.0", "downloadUrl": "https://x/y.apk"}
            """.trimIndent()
        val http = FakeHttpClient(Result.success(body))
        val checker =
            UpdateChecker(http, endpointUrl = "https://example.com/update.json", currentVersionCode = 10)
        assertNull(checker.check(), "older remote versionCode must NOT prompt update")
    }

    @Test fun remoteVersionNewer_returnsUpdateInfoWithAllFields() = runTest {
        val body =
            """
                {
                  "versionCode": 7,
                  "versionName": "0.7.0-mk2",
                  "downloadUrl": "https://github.com/user/yancotv/releases/download/v0.7.0/app.apk",
                  "releaseNotes": "MK.20 polish + Stage 5.2 update flow",
                  "minOsApi": 24
                }
            """.trimIndent()
        val http = FakeHttpClient(Result.success(body))
        val checker =
            UpdateChecker(
                http,
                endpointUrl = "https://example.com/update.json",
                currentVersionCode = 5,
            )
        val info = checker.check()
        assertNotNull(info)
        assertEquals(7, info.versionCode)
        assertEquals("0.7.0-mk2", info.versionName)
        assertEquals(
            "https://github.com/user/yancotv/releases/download/v0.7.0/app.apk",
            info.downloadUrl,
        )
        assertEquals("MK.20 polish + Stage 5.2 update flow", info.releaseNotes)
        assertEquals(24, info.minOsApi)
    }

    @Test fun newerRemote_optionalFieldsAbsent_defaultsToNull() = runTest {
        // Production manifest may omit releaseNotes / minOsApi —
        // forward-compat: parser fills nulls, UI handles them.
        val body =
            """
                {"versionCode": 7, "versionName": "0.7.0", "downloadUrl": "https://x/y.apk"}
            """.trimIndent()
        val http = FakeHttpClient(Result.success(body))
        val checker =
            UpdateChecker(http, endpointUrl = "https://example.com/update.json", currentVersionCode = 5)
        val info = checker.check()
        assertNotNull(info)
        assertEquals(7, info.versionCode)
        assertNull(info.releaseNotes)
        assertNull(info.minOsApi)
    }

    @Test fun unknownFutureFields_ignoredNotErrored() = runTest {
        // A future server adds `forceUpdate` + `signatureSha256`; the
        // current parser must not throw on extras (ignoreUnknownKeys).
        val body =
            """
                {
                  "versionCode": 7,
                  "versionName": "0.7.0",
                  "downloadUrl": "https://x/y.apk",
                  "forceUpdate": true,
                  "signatureSha256": "abc123",
                  "futureField": {"nested": "value"}
                }
            """.trimIndent()
        val http = FakeHttpClient(Result.success(body))
        val checker =
            UpdateChecker(http, endpointUrl = "https://example.com/update.json", currentVersionCode = 5)
        val info = checker.check()
        assertNotNull(info, "unknown fields must NOT break parsing — older clients keep working")
        assertEquals(7, info.versionCode)
    }

    @Test fun urlPassedToHttpMatchesConfiguredEndpoint() = runTest {
        // Catches a regression where check() fetches the wrong URL
        // (e.g. a hardcoded literal slipping in during a refactor).
        val body =
            """{"versionCode": 7, "versionName": "0.7.0", "downloadUrl": "https://x/y.apk"}"""
        val http = FakeHttpClient(Result.success(body))
        val checker =
            UpdateChecker(http, endpointUrl = "https://my.example/v1/update.json", currentVersionCode = 1)
        checker.check()
        assertEquals("https://my.example/v1/update.json", http.lastUrl)
    }
}
