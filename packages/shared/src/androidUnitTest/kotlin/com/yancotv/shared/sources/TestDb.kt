package com.yancotv.shared.sources

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yancotv.shared.db.YancoDb

/**
 * Spins up a fresh in-memory SQLite DB for JVM unit tests. `IN_MEMORY` is
 * per-connection, so each call returns an isolated database — tests never
 * share state even when run in parallel.
 */
internal fun testDb(): YancoDb {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    // SQLDelight 2.x requires foreign_keys to be enabled per-connection;
    // the schema relies on ON DELETE CASCADE for content cleanup.
    driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
    YancoDb.Schema.create(driver)
    return YancoDb(driver)
}
