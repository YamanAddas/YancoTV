package com.yancotv.shared

import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.db.DatabaseFactory
import com.yancotv.shared.db.YancoDatabase
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.createHttpClient
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import com.yancotv.shared.sources.CredentialStore
import com.yancotv.shared.sources.IosFileContentReader
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.sources.SyncProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * The iOS object graph — Android's `di/AppModules.kt` Koin module, expressed
 * as a plain class.
 *
 * Koin is KMP-native and could have been reused, but exporting a Koin scope
 * across the Obj-C bridge means Swift asking for dependencies by type at
 * runtime and getting `Any?` back. A concrete class with typed properties
 * gives Swift real types and compile-time errors instead, and the graph is
 * small enough that the DSL earns nothing here.
 *
 * ### Why `credentialStore` is injected rather than built
 *
 * [CredentialStore] is the one piece Swift implements. The Keychain and
 * CryptoKit have first-class Swift APIs and awkward Kotlin/Native cinterop
 * (CFDictionary construction with manual CFRelease), and — unlike the backup
 * cipher, which must agree byte-for-byte across platforms — the interface's
 * own contract says the ciphertext format is opaque to the caller and never
 * leaves the device. So the safest binding wins with nothing given up.
 *
 * Everything else stays Kotlin: the constructors here take default arguments
 * and function-typed parameters (`clock: () -> Long`) that are unpleasant to
 * satisfy from Swift.
 */
class YancoServices(
    credentialStore: CredentialStore,
    private val logger: Logger = NOOP_LOGGER,
) {
    val database: YancoDatabase = DatabaseFactory().create()

    val http: HttpClient = createHttpClient(DEFAULT_USER_AGENT)

    val sources: SourceRepository =
        SourceRepository(
            db = database.db,
            driver = database.driver,
            credentialStore = credentialStore,
            http = http,
            fileReader = IosFileContentReader(),
            clock = ::nowMillis,
            logger = logger,
        )

    val content: ContentRepository = ContentRepository(database.db)

    val favorites: FavoritesRepository = FavoritesRepository(database.db, ::nowMillis)

    /**
     * Scope for work Swift starts and may abandon (a sync the user backs out
     * of). Supervised so one failed sync doesn't tear down the others.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Runs [SourceRepository.syncSource] and reports progress through
     * callbacks.
     *
     * `Flow` has no representation in the Obj-C bridge, so Swift cannot
     * collect one. Rather than leak that limitation into every call site,
     * the flow is drained here and surfaced as the two callbacks Swift can
     * actually take. [SyncHandle.cancel] cancels the underlying job, which
     * is what makes a half-finished catalogue import abandonable.
     *
     * Callbacks fire on a background dispatcher — the caller is responsible
     * for hopping to the main actor before touching UI.
     */
    fun startSync(
        sourceId: String,
        onProgress: (SyncProgress) -> Unit,
        onComplete: (String?) -> Unit,
    ): SyncHandle {
        val job =
            scope.launch {
                var failure: String? = null
                sources
                    .syncSource(sourceId)
                    .catch { throwable ->
                        // syncSource reports failure as an ERROR phase rather
                        // than by throwing, so anything caught here is a
                        // genuine escape (cancellation aside) and must not be
                        // swallowed into a silent success.
                        failure = throwable.message ?: "Sync failed"
                        logger.error("startSync[$sourceId] $failure")
                    }.onCompletion { onComplete(failure) }
                    .collect { progress ->
                        if (progress.phase == SyncProgress.Phase.ERROR) {
                            failure = "Sync failed"
                        }
                        onProgress(progress)
                    }
            }
        return SyncHandle(job)
    }

    /** Releases the coroutine scope. Call from Swift on teardown. */
    fun close() {
        scope.cancel()
    }

    companion object {
        /**
         * Providers routinely gate playlists on a recognised player UA, and
         * several reject the default Darwin one outright.
         */
        const val DEFAULT_USER_AGENT: String = "YancoTV/1.0 (iOS)"
    }
}

/**
 * Wall clock in epoch milliseconds — the unit every timestamp column in the
 * schema uses, bar the three documented exceptions (media offsets and
 * XMLTV's epoch *seconds*).
 *
 * Android injects `System.currentTimeMillis`. This module deliberately has
 * no `kotlinx-datetime` dependency (see the note in `BulkContentWriter`), so
 * the iOS side reads Foundation directly rather than adding one for a single
 * call.
 */
internal fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/** Cancellation token for an in-flight sync. */
class SyncHandle(private val job: Job) {
    fun cancel() {
        job.cancel()
    }

    val isActive: Boolean get() = job.isActive
}
