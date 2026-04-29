package com.yancotv.shared.update

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Stage 5.2 — sideload auto-update check.
 *
 * Polls a small JSON endpoint (`update.json`) the project publishes
 * alongside its GitHub release assets and decides whether the running
 * binary is behind. Returns an [UpdateInfo] when the remote
 * `versionCode` is strictly greater than the running build's; null
 * for any non-applicable case (endpoint unconfigured, network failure,
 * malformed response, downgrade or equal version, missing
 * `downloadUrl`).
 *
 * **Why a custom `update.json` instead of `api.github.com/.../releases/latest`:**
 * GitHub's API doesn't expose a versionCode directly — we'd have to
 * derive it from the tag name (e.g. `v0.2.0` → some integer encoding),
 * which couples our update logic to a tag-naming convention. A small
 * JSON file we control gives us:
 *   - direct numeric versionCode comparison,
 *   - the apk's `browser_download_url` already resolved (no asset-array
 *     hunt),
 *   - room for forward-compatible fields (`minOsApi`, `releaseNotes`,
 *     `forceUpdate`, etc) without depending on GitHub schema changes.
 *
 * The user is expected to publish `update.json` as a release asset
 * (or via GitHub Pages, S3, etc); the URL is injected at construction
 * time. An empty / blank URL disables the check entirely (returns null
 * unconditionally) — this is the default for development builds.
 *
 * **Pure (no platform I/O).** [HttpClient] is the only seam to the
 * outside world; all parsing + version comparison lives here so the
 * entire decision is unit-testable from a fake HTTP. Production
 * wiring (Stage 5.2.2) calls [check] from a WorkManager periodic
 * worker; a `null` return is logged + dropped, a non-null return
 * surfaces in a UI prompt (Stage 5.2.3).
 *
 * Mirrors the MK.23.C.1 / MK.24.E.3 / MK.24.G.2 pattern: extract the
 * decision logic as pure data → in/out, test the table, leave the
 * platform shim trivial.
 */
class UpdateChecker(
    private val http: HttpClient,
    /**
     * URL of the `update.json` document. Empty / blank disables the
     * check (every call to [check] returns null). Production wires
     * this from `BuildConfig.UPDATE_ENDPOINT` (set in `local.properties`
     * or the build script — see Stage 5.2.2).
     */
    private val endpointUrl: String,
    /**
     * The running build's versionCode. Production wires this from
     * `BuildConfig.VERSION_CODE`. Comparison is strict-greater so
     * equal-or-older remote versions never trigger an update.
     */
    private val currentVersionCode: Int,
    private val logger: Logger = NOOP_LOGGER,
) {
    /**
     * True when [endpointUrl] is non-blank — i.e. the update mechanism
     * is wired for this build. Dev builds without `update.endpoint` in
     * `local.properties` ship with an empty endpoint and the check is
     * an unconditional no-op; UI surfaces gate "Check now" / the
     * lastCheckedAt timestamp on this so the user gets honest feedback
     * instead of a "Just now" timestamp that masks a non-event.
     */
    val isConfigured: Boolean
        get() = endpointUrl.isNotBlank()

    /**
     * Single-shot poll. Returns an [UpdateInfo] when the remote
     * `versionCode` is strictly greater than [currentVersionCode]; null
     * for every non-applicable case:
     *   - endpoint URL is empty or blank,
     *   - HTTP fetch fails,
     *   - response isn't valid JSON / schema doesn't match,
     *   - remote `downloadUrl` is missing or blank (we won't surface
     *     an "update available" prompt with no URL to install from),
     *   - remote `versionCode` is ≤ [currentVersionCode] (no update,
     *     downgrade, or equal).
     *
     * Callers that want to distinguish "endpoint not configured" from
     * "endpoint configured but no update" should consult [isConfigured]
     * before invoking — see [com.yancotv.android.update.UpdateRepository].
     *
     * Suspending — runs on the caller's coroutine context. WorkManager
     * worker dispatches off the main thread.
     */
    suspend fun check(): UpdateInfo? {
        if (endpointUrl.isBlank()) return null
        val body =
            runCatching {
                http.getText(
                    endpointUrl,
                    HttpRequestOptions(timeoutMs = 15_000),
                )
            }.getOrElse { e ->
                logger.warn("UpdateChecker: fetch failed — ${e.message}")
                return null
            }
        val remote =
            runCatching { JSON.decodeFromString<UpdateManifest>(body) }
                .getOrElse { e ->
                    logger.warn("UpdateChecker: parse failed — ${e.message}")
                    return null
                }
        if (remote.downloadUrl.isBlank()) {
            logger.warn("UpdateChecker: manifest has blank downloadUrl; treating as no-update")
            return null
        }
        if (remote.versionCode <= currentVersionCode) {
            logger.info(
                "UpdateChecker: remote versionCode=${remote.versionCode} <= current=$currentVersionCode — no update",
            )
            return null
        }
        return UpdateInfo(
            versionCode = remote.versionCode,
            versionName = remote.versionName,
            downloadUrl = remote.downloadUrl,
            releaseNotes = remote.releaseNotes,
            minOsApi = remote.minOsApi,
        )
    }

    private companion object {
        // ignoreUnknownKeys so a future field on the remote manifest
        // doesn't break older clients. Same shape used by the catchup
        // service for symmetry.
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Public result type — UI consumes this to render a "new version
 * available" prompt.
 */
data class UpdateInfo(val versionCode: Int, val versionName: String, val downloadUrl: String, val releaseNotes: String?, val minOsApi: Int?)

/**
 * On-the-wire shape of `update.json`. Internal — callers consume the
 * pre-validated [UpdateInfo] (`downloadUrl` non-blank, `versionCode`
 * > current). Defaults on optional fields so older manifests keep
 * working as the schema grows.
 */
@Serializable
internal data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String? = null,
    /**
     * Minimum Android API level the new APK supports. Production caller
     * is expected to skip the prompt when the running device's
     * `Build.VERSION.SDK_INT` is below this. Optional; null means "no
     * floor declared".
     */
    val minOsApi: Int? = null,
)
