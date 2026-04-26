package com.yancotv.shared.db

import android.content.Context
import android.util.Log
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

actual class DatabaseFactory(
    private val context: Context,
) {
    /**
     * Open the YancoTV SQLDelight database.
     *
     * On a clean launch this just constructs the [AndroidSqliteDriver]
     * with our PRAGMA configuration and wraps it in [YancoDb]. After a
     * successful open, the live `sources` table is dumped to JSON via
     * [SourcesBackup] so a future corrupt-DB recovery can restore it.
     *
     * **Corruption recovery (Stage 1.5):** if open or schema migration
     * throws (corrupt SQLite file, schema mismatch the migrator can't
     * resolve, disk full + truncated DB, etc.), the bad DB file is
     * deleted and a fresh DB is created. Sources from the last-known-good
     * launch are re-inserted from the JSON backup. Android Keystore
     * credentials survive DB deletion (they live in the OS-level
     * Keystore, not in this SQLite file), so authenticated sources keep
     * working — the encrypted blob in the restored row matches the
     * still-living Keystore entry keyed by the same source id.
     */
    actual fun create(): YancoDatabase {
        val backup = SourcesBackup(context)
        val database =
            try {
                openNormally()
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "Database open failed (${t::class.java.simpleName}: ${t.message}); " +
                        "running corruption recovery — deleting DB and restoring sources from backup.",
                    t,
                )
                recoverWithFreshDb(backup)
            }
        // Proactively dump current sources for the next launch's
        // corruption-recovery use. Best-effort — write failures don't
        // block the app from booting.
        backup.writeFromDb(database.db)
        return database
    }

    /**
     * Create the driver with the production PRAGMA configuration and
     * wrap in [YancoDb]. Throws if the underlying file is unrecoverable.
     */
    private fun openNormally(): YancoDatabase {
        val driver =
            AndroidSqliteDriver(
                schema = YancoDb.Schema,
                context = context,
                name = DB_NAME,
                callback = SchemaCallback(),
            )
        return YancoDatabase(db = YancoDb(driver), driver = driver)
    }

    /**
     * Stage 1.5 corruption recovery. Attempts to read the proactive
     * [SourcesBackup] (best-effort), deletes every file SQLite might
     * leave behind for [DB_NAME] (`<name>`, `<name>-journal`, `<name>
     * -wal`, `<name>-shm`), then opens fresh and replays the backup.
     */
    private fun recoverWithFreshDb(backup: SourcesBackup): YancoDatabase {
        val saved = backup.read()
        deleteDbArtifacts()
        val fresh = openNormally()
        if (saved != null) {
            // Schema-version guard: refuse to restore a backup created by
            // a newer schema than the current binary supports. A user
            // running an older build after a newer one (downgrade /
            // sideload) would otherwise see odd column behaviour. Better
            // to start fresh.
            if (saved.schemaVersion > YancoDb.Schema.version.toInt()) {
                Log.w(
                    TAG,
                    "Sources backup schemaVersion=${saved.schemaVersion} is newer than " +
                        "current schema ${YancoDb.Schema.version}; refusing to restore.",
                )
            } else {
                backup.restoreInto(fresh.db, saved.sources)
            }
        } else {
            Log.i(TAG, "No sources backup found; fresh DB will be empty.")
        }
        return fresh
    }

    /**
     * Delete the SQLite file plus its journal / WAL / shm sidecars. WAL
     * mode (enabled in [SchemaCallback.onConfigure]) creates the latter
     * two on every open; leaving them around after deleting the main file
     * causes the next open to find an inconsistent state.
     */
    private fun deleteDbArtifacts() {
        val dbFile = context.getDatabasePath(DB_NAME)
        listOf(
            dbFile,
            File("${dbFile.path}-journal"),
            File("${dbFile.path}-wal"),
            File("${dbFile.path}-shm"),
        ).forEach { f ->
            if (f.exists() && !f.delete()) {
                Log.w(TAG, "Failed to delete DB artifact ${f.path} during recovery.")
            }
        }
    }

    /**
     * PRAGMA configuration callback. Same as before — extracted so the
     * normal-open and recovery paths share one source of truth.
     */
    private inner class SchemaCallback :
        AndroidSqliteDriver.Callback(YancoDb.Schema) {
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
    }

    private companion object {
        const val DB_NAME = "yancotv.db"
        const val TAG = "YancoDatabaseFactory"
    }
}
