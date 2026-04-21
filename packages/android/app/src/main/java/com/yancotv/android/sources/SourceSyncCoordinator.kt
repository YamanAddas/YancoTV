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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private var activeJob: Job? = null

    data class Active(
        val sourceId: String,
        val sourceName: String,
        val progress: SyncProgress,
        val startedAtMs: Long,
    )

    fun start(sourceId: String, sourceName: String) {
        if (_state.value != null) {
            logger.warn("syncCoordinator refusing start: another sync is active")
            return
        }
        val startedAt = System.currentTimeMillis()
        _state.value = Active(
            sourceId = sourceId,
            sourceName = sourceName,
            progress = SyncProgress(SyncProgress.Phase.FETCHING, message = "Starting"),
            startedAtMs = startedAt,
        )
        logger.info("syncCoordinator start id=$sourceId name=$sourceName")
        activeJob = scope.launch {
            var completedOk = false
            try {
                repo.syncSource(sourceId).collect { p ->
                    // Keep startedAtMs stable across progress updates so the
                    // UI's elapsed-time ticker doesn't reset each time.
                    _state.value = _state.value?.copy(progress = p)
                    if (p.phase == SyncProgress.Phase.DONE) completedOk = true
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
