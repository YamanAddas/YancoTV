package com.yancotv.shared.diag

/**
 * The name is what makes the trace readable — coroutine dispatchers produce
 * `DefaultDispatcher-worker-N`, so a BEGIN and COMMIT that disagree stand out
 * without needing ids threaded through.
 */
actual fun currentThreadName(): String = Thread.currentThread().name
