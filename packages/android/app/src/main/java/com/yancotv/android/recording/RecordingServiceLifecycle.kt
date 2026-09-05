package com.yancotv.android.recording

/**
 * MB-418 — when the recording service may stop itself.
 *
 * ### What went wrong
 *
 * `handleStop` takes the job out of `activeJobs` synchronously and then does
 * the real work in a coroutine: flush the stream, read the byte count,
 * transition the row. Cancelling the job makes the recorder deliver its
 * result, and that path ends at `maybeStop()` — which looked at an already
 * empty map, called `stopSelf()`, and let `onDestroy` cancel the service
 * scope out from under the transition that was still running.
 *
 * The row stayed RECORDING for ever. The player kept offering "Stop
 * recording", every later stop returned immediately because the job was gone
 * from the map, and the next launch's orphan sweep closed it as
 * `orphaned_by_app_kill` — which the owner reported, exactly, as "I stopped it
 * and it did not stop, then the recording was deleted".
 *
 * Reported 2026-09-04 with the detail that made it diagnosable: stopping from
 * the Recordings screen ALONE works. It does, and for a reason that confirms
 * the above — leaving the player ends the tee on its own, and the natural
 * finish path writes its outcome BEFORE removing the job and calling
 * `maybeStop`. Only the explicit-stop path has the two in the wrong order.
 *
 * ### Why this is a function
 *
 * The condition is one line and the bug was one line, so the interesting part
 * is not the arithmetic — it is that "no jobs left" was never the same
 * question as "nothing left to do". Naming that here means a test can hold it,
 * and the next person reading `maybeStop` sees a question rather than a
 * `.isEmpty()`.
 */
internal fun canStopService(activeJobs: Int, finalising: Int): Boolean = activeJobs <= 0 && finalising <= 0
