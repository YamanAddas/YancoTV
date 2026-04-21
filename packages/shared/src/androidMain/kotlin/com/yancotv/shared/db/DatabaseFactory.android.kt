package com.yancotv.shared.db

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DatabaseFactory(private val context: Context) {
    actual fun create(): YancoDatabase {
        val driver = AndroidSqliteDriver(
            schema = YancoDb.Schema,
            context = context,
            name = "yancotv.db",
            callback = object : AndroidSqliteDriver.Callback(YancoDb.Schema) {
                override fun onConfigure(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    super.onConfigure(db)
                    // WAL lets readers and writers coexist. Without this, a
                    // long EPG sync transaction would block `getAll()` from
                    // Sources screen — exactly the symptom that surfaced on
                    // MK.6 ("source list disappears after opening Guide").
                    db.enableWriteAheadLogging()
                    // Busy-timeout so a contending write waits instead of
                    // failing immediately with SQLITE_BUSY. 5s is plenty for
                    // the biggest transaction we issue (EPG upsert batch).
                    //
                    // Must be issued via `query()` — `execSQL` rejects any
                    // PRAGMA that returns a row, and `busy_timeout = N` does
                    // (returns the new value). Learned the hard way: the
                    // first MK.6.b build boot-crashed at exactly this line.
                    db.query("PRAGMA busy_timeout = 5000").close()
                    // synchronous = NORMAL is the recommended setting for
                    // WAL mode: commits are durable across app crashes (only
                    // a kernel panic or power loss can lose the last commit
                    // window), and per-commit fsync cost drops roughly 5×
                    // on eMMC. Critical for catalog sync on Fire TV where
                    // 400+ per-chunk commits otherwise spend most of their
                    // wall-time waiting on flash fsyncs. SQLite's own docs
                    // recommend this combination; FULL is only needed on
                    // non-WAL or for protection against OS-level crashes
                    // which we already accept for content/EPG state.
                    db.query("PRAGMA synchronous = NORMAL").close()
                    // Bigger page cache ⇒ fewer B-tree page reloads during a
                    // 100k-row insert. 2000 × 4KB = 8MB — well within the
                    // Fire TV heap budget and paid back many times over for
                    // index maintenance on the five content indexes.
                    db.query("PRAGMA cache_size = -8000").close()
                    // Keep the in-memory page cache hot for temp tables
                    // (FTS4 uses them during bulk inserts).
                    db.query("PRAGMA temp_store = MEMORY").close()
                }

                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.setForeignKeyConstraintsEnabled(true)
                }
            },
        )
        return YancoDatabase(db = YancoDb(driver), driver = driver)
    }
}
