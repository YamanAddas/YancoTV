package com.yancotv.android.ui.focus

import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * JVM unit tests for [PlacedFocusAnchor], the primitive that fixed the
 * cascade focus bug in `4a8a46e`. The composable wiring (`key(contentType)`
 * causing remount, `BrowseSection` issuing `awaitAndRequest()` from a
 * `LaunchedEffect`) is locked down by the human smoke-test list in the
 * native-android-mk skill — these tests pin the underlying mechanism so
 * a future refactor of the anchor itself can't silently regress.
 *
 * Why JVM and not :androidTest:
 *   - The bug was about the anchor's await-then-request contract — that
 *     contract is fully testable in JVM with the snapshot system + a
 *     test coroutine dispatcher.
 *   - `requestFocus()` failing (no real composition behind it) is wrapped
 *     in `runCatching` inside the anchor, so the absence of a real focus
 *     graph here doesn't matter — we're verifying the suspend resumes,
 *     not that focus visually moved.
 *   - Instrumented Compose UI tests on Fire TV would catch the wiring
 *     drift but cost 2–4h of scaffolding for a personal app with one
 *     tester. Smoke-test list in the skill closes that gap cheaper.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlacedFocusAnchorTest {
    @Test fun awaitAndRequestSuspendsUntilPlaced() = runTest(StandardTestDispatcher()) {
        val anchor = PlacedFocusAnchor()
        var resumed = false
        val job =
            launch {
                anchor.awaitAndRequest()
                resumed = true
            }

        // Before the anchor is placed, the coroutine must be parked
        // inside snapshotFlow — not racing through to requestFocus().
        // This is the exact contract the cascade bug violated when
        // the old delay-ladder pattern fired before placement.
        runCurrent()
        assertFalse(resumed, "awaitAndRequest must suspend until placed")
        assertTrue(job.isActive)

        anchor.markPlaced()
        // snapshotFlow only sees state writes after a global snapshot
        // apply notification — without this the test would hang.
        Snapshot.sendApplyNotifications()
        runCurrent()

        assertTrue(resumed, "awaitAndRequest must resume once placed")
        assertTrue(job.isCompleted)
    }

    @Test fun awaitAndRequestFiresImmediatelyWhenAlreadyPlaced() = runTest(StandardTestDispatcher()) {
        // Sidebar→Categories within the SAME content type: the pill
        // is already placed from the previous composition. The anchor
        // must short-circuit instead of waiting for a fresh onPlaced
        // callback that will never come.
        val anchor = PlacedFocusAnchor()
        anchor.markPlaced()
        Snapshot.sendApplyNotifications()

        var resumed = false
        launch {
            anchor.awaitAndRequest()
            resumed = true
        }
        runCurrent()

        assertTrue(resumed, "must not block when isPlaced is already true")
    }

    @Test fun resetFlipsIsPlacedBackToFalse() = runTest(StandardTestDispatcher()) {
        // Reset is the escape hatch for "we know the underlying node
        // is about to unmount, force the next awaitAndRequest to
        // wait for fresh placement." Currently unused by callers —
        // the `key(contentType)` boundary creates a brand-new anchor
        // instance instead — but the contract has to hold for the
        // case where someone wires it manually (e.g. mid-screen
        // overlay teardown).
        val anchor = PlacedFocusAnchor()
        anchor.markPlaced()
        Snapshot.sendApplyNotifications()

        var firstResumed = false
        launch {
            anchor.awaitAndRequest()
            firstResumed = true
        }
        runCurrent()
        assertTrue(firstResumed)

        anchor.reset()
        Snapshot.sendApplyNotifications()

        var secondResumed = false
        val secondJob =
            launch {
                anchor.awaitAndRequest()
                secondResumed = true
            }
        runCurrent()
        assertFalse(secondResumed, "reset must un-place the anchor")
        assertTrue(secondJob.isActive)

        anchor.markPlaced()
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertTrue(secondResumed)
    }

    @Test fun multipleAwaitAndRequestCallsAllResolve() = runTest(StandardTestDispatcher()) {
        // The composable side calls awaitAndRequest from a LaunchedEffect
        // keyed on (type, panelFocus). Both keys can flip in one frame
        // (sidebar → categories AND type swap), producing two stacked
        // calls. Both must resolve once placement occurs — the anchor
        // can't gate later calls behind the first one's completion.
        val anchor = PlacedFocusAnchor()
        var firstResumed = false
        var secondResumed = false

        launch {
            anchor.awaitAndRequest()
            firstResumed = true
        }
        launch {
            anchor.awaitAndRequest()
            secondResumed = true
        }
        runCurrent()
        assertFalse(firstResumed)
        assertFalse(secondResumed)

        anchor.markPlaced()
        Snapshot.sendApplyNotifications()
        runCurrent()

        assertTrue(firstResumed)
        assertTrue(secondResumed)
    }

    @Test fun markPlacedIsIdempotent() = runTest(StandardTestDispatcher()) {
        // onPlaced can fire more than once across recompositions —
        // the anchor must not blow up or alter behaviour on the
        // second + nth call.
        val anchor = PlacedFocusAnchor()
        anchor.markPlaced()
        anchor.markPlaced()
        anchor.markPlaced()
        Snapshot.sendApplyNotifications()

        var resumed = false
        launch {
            anchor.awaitAndRequest()
            resumed = true
        }
        runCurrent()
        assertTrue(resumed)
    }

    @Test fun rememberPlacedFocusAnchorIsConstructable() {
        // Sanity: the public constructor surface is reachable from outside
        // a composition for tests like this. If someone tightens visibility
        // by accident, this test catches it before the next refactor.
        val anchor = PlacedFocusAnchor()
        // Reset on a fresh anchor must not throw — defensive contract for
        // callers that defensively reset before first use.
        anchor.reset()
    }
}
