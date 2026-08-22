package com.yancotv.shared.diag

import app.cash.sqldelight.db.SqlDriver
import com.yancotv.shared.logger.Logger

/**
 * MB-356 — transaction-boundary tracing.
 *
 * The bug: writers starve forever on the primary SQLite connection while the
 * pool reports `0 active` and names no running statement. Nothing is executing,
 * so nothing is contending — the connection is simply never given back.
 *
 * The mechanism this exists to catch: **Android's `SQLiteSession` is
 * thread-local.** `execSQL("BEGIN")` is intercepted and bound to the calling
 * thread's session, which then owns the primary connection until that SAME
 * thread commits or rolls back. A transaction begun on one thread and committed
 * on another therefore leaks the connection permanently — and neither statement
 * errors, which is exactly why the failure is silent.
 *
 * Coroutines make this reachable without anyone writing obviously wrong code: a
 * `suspend` call between BEGIN and COMMIT is a legal thread-migration point.
 * `BulkEpgWriter.Session` spans exactly that shape (its caller reports progress
 * between batches), which is why it is instrumented first.
 *
 * Deliberately dumb — it logs and delegates, nothing more. It must not change
 * behaviour, because the thing being diagnosed only reproduces sometimes and a
 * fix-shaped "diagnostic" would make the result unreadable.
 */
fun beginTraced(driver: SqlDriver, logger: Logger, site: String) {
    logger.info("txn BEGIN  site=$site thread=${currentThreadName()}")
    driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
}

fun commitTraced(driver: SqlDriver, logger: Logger, site: String) {
    logger.info("txn COMMIT site=$site thread=${currentThreadName()}")
    driver.execute(null, "COMMIT", 0)
}

fun rollbackTraced(driver: SqlDriver, logger: Logger, site: String) {
    logger.info("txn ROLLBK site=$site thread=${currentThreadName()}")
    driver.execute(null, "ROLLBACK", 0)
}

/** Name of the thread this call runs on. Android returns the real thread name. */
expect fun currentThreadName(): String
