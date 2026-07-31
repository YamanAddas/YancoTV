package com.yancotv.android.sources

import com.yancotv.shared.http.redactErrorMessage
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.sources.SyncDetail
import com.yancotv.shared.sources.SyncProgress
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-scoped sync runner. A user-started sync must outlive the Settings
 * screen — if the user backs out to Home and reopens Settings the sync
 * should still be running, and the UI should just re-bind to the live
 * progress. That rules out `rememberCoroutineScope()` (dies with the
 * composable) and a ViewModel (dies when the nav entry leaves the backstack).
 *
 * Kept single-slot on purpose: two concurrent syncs would race
 * `ContentWriter.beginXtreamSync()` (which DELETEs the source's content
 * rows) against each other's chunked inserts. A future release can add a
 * per-source mutex if multi-sync ever matters; today the user only ever has
 * one provider.
 *
 * MK.23.D.2 refactor (2026-04-28): Context dependency replaced with a
 * `kickEpgRefresh: () -> Unit` callback. The coordinator only used
 * Context to call `EpgSyncWorker.enqueueOnce(context)` after a
 * successful sync — making that an injected lambda removes the only
 * Android dependency from this class so JVM unit tests can construct
 * it directly. The DI module wires the callback to
 * `EpgSyncWorker.enqueueOnce(androidContext())`.
 *
 * `dispatcher` and `scope` are also injectable for tests so they can
 * use `UnconfinedTestDispatcher` (immediate execution, deterministic
 * ordering) instead of the real `Dispatchers.IO`.
 */
class SourceSyncCoordinator(
    private val syncSource: (String) -> Flow<SyncProgress>,
    private val logger: Logger,
    private val kickEpgRefresh: () -> Unit,
    /**
     * MK.31.18 — formats the error-bus message. Injected rather than resolved
     * here so this class needs no Context and its JVM test needs no Android
     * runtime; the DI module supplies the localized implementation.
     */
    private val describeFailure: (sourceName: String, detail: SyncDetail?) -> String =
        { name, detail -> "Sync failed for $name: ${(detail as? SyncDetail.Failure)?.text ?: "unknown error"}" },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
) {
    private val scope: CoroutineScope = scope

    private val _state = MutableStateFlow<Active?>(null)
    val state: StateFlow<Active?> = _state.asStateFlow()

    /**
     * Fire-and-forget error bus. Emits a short user-facing message when a
     * sync run crashes (bad credentials, unreachable host, parse failure).
     * [MainActivity] subscribes and shows a Toast so the user gets feedback
     * even when they navigate away from the Sources screen mid-sync.
     *
     * [BufferOverflow.DROP_OLDEST] means a fresh failure always wins over
     * a stale one the user already ignored.
     */
    private val _errors =
        MutableSharedFlow<String>(
            replay = 0,
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    // MK.28.3 (MB-253) — @Volatile: written from the sync job's IO thread
    // (finally) and the callers' main thread (start/cancel).
    @Volatile
    private var activeJob: Job? = null

    data class Active(val sourceId: String, val sourceName: String, val progress: SyncProgress, val startedAtMs: Long)

    fun start(sourceId: String, sourceName: String) {
        if (_state.value != null) {
            logger.warn("syncCoordinator refusing start: another sync is active")
            return
        }
        val startedAt = System.currentTimeMillis()
        _state.value =
            Active(
                sourceId = sourceId,
                sourceName = sourceName,
                progress = SyncProgress(SyncProgress.Phase.FETCHING, detail = SyncDetail.Starting),
                startedAtMs = startedAt,
            )
        logger.info("syncCoordinator start id=$sourceId name=$sourceName")
        activeJob =
            scope.launch {
                var completedOk = false
                try {
                    syncSource(sourceId).collect { p ->
                        // Keep startedAtMs stable across progress updates so the
                        // UI's elapsed-time ticker doesn't reset each time.
                        _state.value = _state.value?.copy(progress = p)
                        when (p.phase) {
                            SyncProgress.Phase.DONE -> completedOk = true
                            SyncProgress.Phase.ERROR -> {
                                // The repository reports credential + network
                                // failures as ERROR progress events rather than
                                // throwing. Surface them on the error bus so the
                                // Toast still fires when the user has navigated
                                // away from Sources mid-sync.
                                // MB-292 — redact before this reaches a Toast.
                                // Provider failures echo the request URL, and
                                // Xtream playback URLs carry the username and
                                // password as PATH segments.
                                // MK.31.18 — the frame and the fallback are
                                // localized by the injected formatter; redaction
                                // happens inside it, at the single point where
                                // provider text becomes user-visible.
                                _errors.tryEmit(describeFailure(sourceName, p.detail))
                            }
                            else -> Unit
                        }
                    }
                    // Kick EPG off the moment the catalog lands. The source row
                    // now carries either the user-provided `epg_url` or the
                    // Xtream auto-derived `xmltv.php` URL, so `EpgRepository`
                    // has a target to fetch. WorkManager dedupes with KEEP if a
                    // run is already in flight.
                    if (completedOk) {
                        logger.info("syncCoordinator kicking EPG refresh after catalog sync id=$sourceId")
                        runCatching { kickEpgRefresh() }
                            .onFailure { logger.warn("EPG enqueue failed: ${it.message}") }
                    }
                } catch (ce: CancellationException) {
                    // User pressed Cancel, or the app scope is being torn down —
                    // never surface this as a sync error. Re-throw so structured
                    // concurrency can clean up; the finally clears UI state.
                    logger.info("syncCoordinator cancelled id=$sourceId")
                    throw ce
                } catch (t: Throwable) {
                    // MB-292 — both sinks are credential-leak surfaces: logcat
                    // (readable over adb / in bug reports) and a user-facing
                    // Toast that ends up in support screenshots.
                    val redacted = redactErrorMessage(t)
                    logger.error("syncCoordinator crashed id=$sourceId: $redacted")
                    val reason = redacted.takeIf { it.isNotBlank() } ?: t::class.simpleName ?: "unknown error"
                    _errors.tryEmit("Sync failed for $sourceName: $reason")
                } finally {
                    // MK.28.3 (MB-253) — clear the job ref BEFORE _state (the
                    // start() gate), and only when it still points at THIS
                    // job. The old ordering (_state first, activeJob second)
                    // let a caller pass the gate and assign the NEW job
                    // between the two statements; the stale finally then
                    // wiped the new job's ref, turning Cancel into a silent
                    // no-op for that whole sync. The auto-sync loop and the
                    // RE-SYNC buttons start the next sync the instant state
                    // flips to null, so that window was routinely exercised.
                    val self = coroutineContext[Job]
                    if (activeJob === self) activeJob = null
                    _state.value = null
                }
            }
    }

    fun cancel() {
        activeJob?.cancel()
    }

    fun isSyncing(sourceId: String): Boolean = _state.value?.sourceId == sourceId
}
