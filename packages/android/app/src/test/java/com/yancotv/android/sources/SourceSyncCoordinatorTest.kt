package com.yancotv.android.sources

import com.yancotv.shared.logger.Logger
import com.yancotv.shared.sources.SyncDetail
import com.yancotv.shared.sources.SyncProgress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * MK.23.D.2 — single-slot re-entrancy guard. The coordinator's first
 * line of defense against two concurrent syncs of the same source: a
 * second `start()` while one is in flight returns early and never
 * invokes `syncSource(...)`. Without this guard, two prepareSource
 * calls would race — the second's `DELETE FROM content WHERE
 * source_id = ?` would wipe the first's chunks mid-write.
 *
 * Audit (2026-04-28) flagged: "rejected by `if (_state.value != null)`
 * but unpinned — a future refactor could let two prepare passes
 * overlap, double-DELETE-ing content."
 *
 * Test setup uses an injectable `syncSource: (String) -> Flow<...>`
 * lambda (added in this commit's refactor) so the coordinator can be
 * constructed in pure JVM without standing up a Context, real
 * SourceRepository, or DI graph.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSyncCoordinatorTest {
    @Test fun `second start while first in-flight is no-op and does not invoke syncSource again`() = runTest {
        val invocations = AtomicInteger(0)
        // Flow stays open — never emits DONE — so the first start()
        // remains "active". Without re-entrancy guard the second
        // start would see an empty `_state` and fire syncSource
        // again.
        val openFlow = MutableSharedFlow<SyncProgress>(replay = 1)
        openFlow.tryEmit(SyncProgress(SyncProgress.Phase.FETCHING, detail = SyncDetail.Connecting))

        val coordinator =
            SourceSyncCoordinator(
                syncSource = { _ ->
                    invocations.incrementAndGet()
                    openFlow as Flow<SyncProgress>
                },
                logger = NoopLogger,
                kickEpgRefresh = {},
                dispatcher = UnconfinedTestDispatcher(testScheduler),
                scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
            )

        coordinator.start(sourceId = "src-A", sourceName = "A")
        // First start must observe Active state.
        assertNotNull(coordinator.state.value, "first start must populate state")
        assertEquals("src-A", coordinator.state.value?.sourceId)
        assertEquals(1, invocations.get(), "first start invokes syncSource once")

        // Second start while first still in flight — must early-return.
        coordinator.start(sourceId = "src-A", sourceName = "A")
        assertEquals(
            1,
            invocations.get(),
            "second start MUST be a silent no-op — the existing _state.value != null guard is the contract",
        )

        // Different source same window — also rejected. The coordinator
        // is single-slot regardless of source identity.
        coordinator.start(sourceId = "src-B", sourceName = "B")
        assertEquals(
            1,
            invocations.get(),
            "single-slot: even a different source can't sneak in while another is active",
        )

        // State still reflects the original source.
        assertEquals("src-A", coordinator.state.value?.sourceId)
    }

    /**
     * MK.24.E.2 — full lifecycle teardown: after the first run's flow
     * terminates (DONE), the launch's `finally` must clear `_state.value`
     * so a fresh start() with the SAME or a different source can succeed.
     * Without this, the re-entrancy guard would lock out the source for
     * the rest of the process — once-and-done. Pins that the production
     * code's `finally { _state.value = null; activeJob = null }` actually
     * fires on the success path AND that a follow-up start() is observed
     * by the fake repo (one repo invocation per call, total of 2 here).
     */
    @Test fun `start completed then restarted observes second invocation and clears state between runs`() = runTest {
        val invocations = AtomicInteger(0)
        val coordinator =
            SourceSyncCoordinator(
                syncSource = { _ ->
                    invocations.incrementAndGet()
                    // Finite flow that terminates after DONE so the
                    // launch's collect returns and finally fires.
                    flowOf(SyncProgress(SyncProgress.Phase.DONE))
                },
                logger = NoopLogger,
                kickEpgRefresh = {},
                dispatcher = UnconfinedTestDispatcher(testScheduler),
                scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
            )

        coordinator.start("src-A", "A")
        testScheduler.advanceUntilIdle()
        assertEquals(1, invocations.get(), "first start invokes syncSource")
        assertNull(
            coordinator.state.value,
            "finally MUST clear _state after DONE so subsequent starts can run",
        )

        // Fresh start after teardown — the re-entrancy guard sees
        // _state.value == null and lets it through. A regression
        // that left state non-null after DONE would fail right here.
        coordinator.start("src-B", "B")
        testScheduler.advanceUntilIdle()
        assertEquals(
            2,
            invocations.get(),
            "second start after first completes MUST invoke syncSource a second time",
        )
        assertNull(coordinator.state.value, "second run also tears down")
    }

    /**
     * MK.24.E.2 — failure-path teardown. The launch's outer catch logs +
     * emits an error toast but the `finally` still runs, so a non-DONE
     * exit (network failure, parse crash) leaves the coordinator ready
     * for the user to retry. This is the recovery contract: a transient
     * failure must NOT lock the source out for the session.
     */
    @Test fun `start failed then restarted clears state and allows second invocation`() = runTest {
        val invocations = AtomicInteger(0)
        val coordinator =
            SourceSyncCoordinator(
                syncSource = { _ ->
                    invocations.incrementAndGet()
                    flow<SyncProgress> { throw RuntimeException("network down") }
                },
                logger = NoopLogger,
                kickEpgRefresh = {},
                dispatcher = UnconfinedTestDispatcher(testScheduler),
                scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
            )

        coordinator.start("src-A", "A")
        testScheduler.advanceUntilIdle()
        assertEquals(1, invocations.get())
        assertNull(
            coordinator.state.value,
            "finally MUST clear _state on the failure path too — user retry depends on it",
        )

        coordinator.start("src-A", "A")
        testScheduler.advanceUntilIdle()
        assertEquals(
            2,
            invocations.get(),
            "retry after failure MUST invoke syncSource a second time",
        )
    }

    @Test fun `cancel sets activeJob to null but state observable until launch finally fires`() = runTest {
        val openFlow = MutableSharedFlow<SyncProgress>(replay = 1)
        openFlow.tryEmit(SyncProgress(SyncProgress.Phase.FETCHING, detail = SyncDetail.Connecting))

        val coordinator =
            SourceSyncCoordinator(
                syncSource = { _ -> openFlow as Flow<SyncProgress> },
                logger = NoopLogger,
                kickEpgRefresh = {},
                dispatcher = UnconfinedTestDispatcher(testScheduler),
                scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
            )

        coordinator.start("src-A", "A")
        assertNotNull(coordinator.state.value)

        coordinator.cancel()
        testScheduler.advanceUntilIdle()
        // After cancel + finally clears, state should be null again so
        // a subsequent start() can succeed.
        assertNull(coordinator.state.value, "finally clears _state on cancellation")
    }

    private object NoopLogger : Logger {
        override fun info(msg: String) {}

        override fun warn(msg: String) {}

        override fun error(msg: String) {}
    }
}
