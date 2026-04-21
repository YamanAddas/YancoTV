package com.yancotv.shared.sources

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.db.YancoDatabase

/**
 * Spins up a fresh in-memory SQLite DB for JVM unit tests. `IN_MEMORY` is
 * per-connection, so each call returns an isolated database — tests never
 * share state even when run in parallel.
 */
internal fun testDb(): YancoDb = testDatabase().db

internal fun testDatabase(): YancoDatabase {
    val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    // SQLDelight 2.x requires foreign_keys to be enabled per-connection;
    // the schema relies on ON DELETE CASCADE for content cleanup.
    driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
    YancoDb.Schema.create(driver)
    return YancoDatabase(db = YancoDb(driver), driver = driver)
}
