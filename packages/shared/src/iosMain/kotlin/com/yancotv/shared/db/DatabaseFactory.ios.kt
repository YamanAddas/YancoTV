package com.yancotv.shared.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DatabaseFactory {
    actual fun create(): YancoDatabase {
        val driver = NativeSqliteDriver(YancoDb.Schema, "yancotv.db")
        return YancoDatabase(db = YancoDb(driver), driver = driver)
    }
}
