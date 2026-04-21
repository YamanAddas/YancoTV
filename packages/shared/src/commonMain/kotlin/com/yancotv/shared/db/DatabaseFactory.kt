package com.yancotv.shared.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Bundles the [YancoDb] SQLDelight wrapper with its raw [SqlDriver].
 *
 * The driver is exposed so hot-path bulk writers (catalog sync) can execute
 * raw SQL — multi-row INSERTs, DDL (DROP/CREATE TRIGGER, DROP/CREATE INDEX)
 * and bulk FTS repopulation statements that SQLDelight's generated query
 * API can't express. Normal read/write goes through [db] as usual.
 */
data class YancoDatabase(
    val db: YancoDb,
    val driver: SqlDriver,
)

/**
 * Platform-specific builder for the SQLDelight database.
 * Android wires AndroidSqliteDriver; iOS wires NativeSqliteDriver.
 */
expect class DatabaseFactory {
    fun create(): YancoDatabase
}
