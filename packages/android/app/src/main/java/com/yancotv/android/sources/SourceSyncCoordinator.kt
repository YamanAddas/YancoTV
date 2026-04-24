package com.yancotv.android.sources

import android.content.Context
import com.yancotv.android.sync.EpgSyncWorker
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.sources.SyncProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
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
 */
class SourceSyncCoordinator(
    private val context: Context,
    private val repo: SourceRepository,
    private val logger: Logger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    private var activeJob: Job? = null

    data class Active(
        val sourceId: String,
        val sourceName: String,
        val progress: SyncProgress,
        val startedAtMs: Long,
    )

    fun start(
        sourceId: String,
        sourceName: String,
    ) {
        if (_state.value != null) {
            logger.warn("syncCoordinator refusing start: another sync is active")
            return
        }
        val startedAt = System.currentTimeMillis()
        _state.value =
            Active(
                sourceId = sourceId,
                sourceName = sourceName,
                progress = SyncProgress(SyncProgress.Phase.FETCHING, message = "Starting"),
                startedAtMs = startedAt,
            )
        logger.info("syncCoordinator start id=$sourceId name=$sourceName")
        activeJob =
            scope.launch {
                var completedOk = false
                try {
                    repo.syncSource(sourceId).collect { p ->
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
                                val reason = p.message?.takeIf { it.isNotBlank() } ?: "unknown error"
                                _errors.tryEmit("Sync failed for $sourceName: $reason")
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
                        runCatching { EpgSyncWorker.enqueueOnce(context) }
                            .onFailure { logger.warn("EPG enqueue failed: ${it.message}") }
                    }
                } catch (ce: CancellationException) {
                    // User pressed Cancel, or the app scope is being torn down —
                    // never surface this as a sync error. Re-throw so structured
                    // concurrency can clean up; the finally clears UI state.
                    logger.info("syncCoordinator cancelled id=$sourceId")
                    throw ce
                } catch (t: Throwable) {
                    logger.error("syncCoordinator crashed id=$sourceId: ${t.message}")
                    val reason = t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName ?: "unknown error"
                    _errors.tryEmit("Sync failed for $sourceName: $reason")
                } finally {
                    _state.value = null
                    activeJob = null
                }
            }
    }

    fun cancel() {
        activeJob?.cancel()
    }

    fun isSyncing(sourceId: String): Boolean = _state.value?.sourceId == sourceId
}
