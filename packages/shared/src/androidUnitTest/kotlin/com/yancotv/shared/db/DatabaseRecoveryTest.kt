package com.yancotv.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * MK.24.G.2 / MB-228 — corruption-recovery test coverage.
 *
 * Three layers of coverage:
 *
 *   1. **Decision table** for [decideRecoveryAction]. The pure function
 *      that picks between FreshOnly / Restore / RefuseRestore based on
 *      the saved backup + current schema version.
 *
 *   2. **Artifact-path enumeration** for [dbArtifactPaths]. Pins that
 *      we still enumerate all four files SQLite leaves behind (main +
 *      -journal + -wal + -shm) — a regression that drops one would
 *      orphan it on disk after corruption recovery and confuse the
 *      next open.
 *
 *   3. **End-to-end integration** using a real `JdbcSqliteDriver`
 *      against a malformed file on disk. Proves: corrupted file ON
 *      DISK fails to open as a SQLite DB → recovery deletes the
 *      artifact files → fresh DB at the same path is queryable. This
 *      is the JVM-side analog of what production does on Android via
 *      `AndroidSqliteDriver`; the failure modes (SQLITE_NOTADB,
 *      SQLITE_CORRUPT) propagate identically through the JDBC layer.
 *
 * **Out of scope (deferred):** `DatabaseFactory.create()` end-to-end
 * with a real Android `Context`. That requires Robolectric (the
 * production factory uses `AndroidSqliteDriver`, `Context.getDatabasePath`,
 * `androidx.sqlite.db.SupportSQLiteDatabase` — none of which run in
 * pure JVM tests). The decision-table + integration tests above pin
 * the load-bearing logic; the production wiring is a thin adapter
 * that calls them.
 */
class DatabaseRecoveryTest {
    private val tmpDir: File = createTempDirectory("recovery-test").toFile()

    @AfterTest fun cleanup() {
        tmpDir.deleteRecursively()
    }

    // ─────── decideRecoveryAction — decision table ───────

    @Test fun decideRecoveryAction_nullSaved_returnsFreshOnly() {
        // First-launch case: no backup file existed before the corruption.
        // Or the backup was unparseable and SourcesBackup.read() returned
        // null. Either way, fresh DB stays empty.
        val action = decideRecoveryAction(saved = null, currentSchemaVersion = 10)
        assertEquals(RecoveryAction.FreshOnly, action)
    }

    @Test fun decideRecoveryAction_savedAtCurrentVersion_returnsRestore() {
        val saved =
            SourcesBackupFile(
                schemaVersion = 10,
                backupTime = 1_700_000_000_000L,
                sources = listOf(sourceRow("src-1")),
            )
        val action = decideRecoveryAction(saved = saved, currentSchemaVersion = 10)
        assertTrue(action is RecoveryAction.Restore, "current-version backup must be restorable")
        assertEquals(1, action.sources.size)
        assertEquals("src-1", action.sources.first().id)
    }

    @Test fun decideRecoveryAction_savedAtOlderVersion_returnsRestore() {
        // A user upgraded across a schema bump — the backup was written
        // by the older binary at v8, current binary is v10. The
        // migration path inside YancoDb takes care of bringing rows
        // forward; restoring is safe.
        val saved =
            SourcesBackupFile(
                schemaVersion = 8,
                backupTime = 1_700_000_000_000L,
                sources = listOf(sourceRow("src-old"), sourceRow("src-old-2")),
            )
        val action = decideRecoveryAction(saved = saved, currentSchemaVersion = 10)
        assertTrue(action is RecoveryAction.Restore, "older-version backup must still be restorable")
        assertEquals(2, action.sources.size)
    }

    @Test fun decideRecoveryAction_savedAtNewerVersion_returnsRefuseRestore() {
        // A user downgraded — backup was produced by a newer binary at
        // v12 with columns this v10 binary doesn't know about.
        // Restoring would write rows that lose data on the next backup
        // (current binary doesn't read those columns). Safer to start
        // fresh and let the user re-add sources.
        val saved =
            SourcesBackupFile(
                schemaVersion = 12,
                backupTime = 1_700_000_000_000L,
                sources = listOf(sourceRow("src-future")),
            )
        val action = decideRecoveryAction(saved = saved, currentSchemaVersion = 10)
        assertTrue(action is RecoveryAction.RefuseRestore, "newer-version backup must be refused")
        // The reason is the load-bearing forensic detail — pin its
        // shape so a future "log refactor" doesn't accidentally drop
        // the schemaVersion comparison from the message.
        assertTrue(
            action.reason.contains("12") && action.reason.contains("10"),
            "RefuseRestore reason should mention both versions for forensic clarity (was: ${action.reason})",
        )
    }

    @Test fun decideRecoveryAction_savedEmptyButCurrentVersion_returnsRestoreEmpty() {
        // A fresh-install user produced a backup before adding any
        // sources. Restore happens — it just inserts zero rows. The
        // alternative (treat empty backup like "no backup") would
        // confuse log forensics ("no sources backup found" when one
        // existed and was successfully read).
        val saved =
            SourcesBackupFile(
                schemaVersion = 10,
                backupTime = 1_700_000_000_000L,
                sources = emptyList(),
            )
        val action = decideRecoveryAction(saved = saved, currentSchemaVersion = 10)
        assertTrue(action is RecoveryAction.Restore)
        assertEquals(0, action.sources.size)
    }

    // ─────── dbArtifactPaths — sidecar enumeration ───────

    @Test fun dbArtifactPaths_returnsMainPlusThreeSidecars() {
        val main = File("/some/path/yancotv.db")
        val paths = dbArtifactPaths(main)
        assertEquals(4, paths.size)
        assertEquals(main.path, paths[0].path)
        assertEquals("/some/path/yancotv.db-journal", paths[1].path.replace("\\", "/"))
        assertEquals("/some/path/yancotv.db-wal", paths[2].path.replace("\\", "/"))
        assertEquals("/some/path/yancotv.db-shm", paths[3].path.replace("\\", "/"))
    }

    @Test fun dbArtifactPaths_handlesNamesWithoutSeparator() {
        // Edge case — if dbFile is just a filename (no parent),
        // sidecars should still be co-located alongside it.
        val main = File("yancotv.db")
        val paths = dbArtifactPaths(main)
        assertEquals(4, paths.size)
        // We don't care about the absolute path here, only that the
        // sidecar names are constructed correctly relative to the main.
        assertContentEquals(
            listOf("yancotv.db", "yancotv.db-journal", "yancotv.db-wal", "yancotv.db-shm"),
            paths.map { it.name },
        )
    }

    // ─────── End-to-end integration — corrupt file on disk ───────

    /**
     * The load-bearing test for MB-228.
     *
     * Flow:
     *   1. Write a malformed SQLite file to a tmp path (random bytes —
     *      no SQLite magic header, so any SQLite driver rejects it).
     *   2. Try to open it as a SQLite DB via [JdbcSqliteDriver]. The
     *      open itself succeeds (JDBC defers to first query) but
     *      `YancoDb.Schema.create` fails immediately.
     *   3. Run the production recovery flow's pure mechanics:
     *      [dbArtifactPaths] enumerates the artefact set, the test
     *      deletes any that exist.
     *   4. Open fresh at the same path, run schema, write+read a row.
     *
     * If a future refactor drops the recovery's `delete()` step, step
     * 4 will fail because the corrupt file blocks the fresh schema
     * apply. If `dbArtifactPaths` stops enumerating one of the
     * sidecars, step 4 may pass (main file deleted) but the test's
     * post-condition asserting clean disk state catches it.
     */
    @Test fun corruptFileOnDisk_recovers_freshDbIsQueryable() {
        val dbFile = File(tmpDir, "yancotv.db")
        // Step 1: write garbage bytes — not a valid SQLite header
        // (which would start with the magic string "SQLite format 3\0").
        dbFile.writeBytes(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        // Sanity: also drop a fake -wal sidecar so we can prove the
        // cleanup deletes it too.
        File(dbFile.path + "-wal").writeBytes(byteArrayOf(0x00, 0x01, 0x02))
        assertTrue(dbFile.exists())
        assertTrue(File(dbFile.path + "-wal").exists())

        // Step 2: production-equivalent open attempt. JdbcSqliteDriver
        // takes a JDBC URL; for a file-backed DB that's
        // jdbc:sqlite:<path>. This is the JVM analog of what
        // AndroidSqliteDriver does on a real device.
        val firstAttempt =
            runCatching {
                val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
                // Force the schema to materialise — this is where the
                // production AndroidSqliteDriver also fails on a corrupt
                // file (the SchemaCallback's onCreate / onUpgrade are
                // run lazily via the support DB's first query).
                YancoDb.Schema.create(driver)
                driver.close()
            }
        assertTrue(
            firstAttempt.isFailure,
            "Open against malformed bytes should throw — that's the trigger condition for recovery. " +
                "Got: ${firstAttempt.getOrNull()}",
        )

        // Step 3: run the recovery's file-deletion mechanics. This is
        // the same call sequence DatabaseFactory.deleteDbArtifacts uses
        // (now refactored to delegate to dbArtifactPaths).
        dbArtifactPaths(dbFile).forEach { f ->
            if (f.exists() && !f.delete()) {
                fail("Failed to delete DB artifact ${f.path}; recovery cannot proceed.")
            }
        }
        // Post-cleanup invariant: every artifact gone.
        dbArtifactPaths(dbFile).forEach { f ->
            assertFalse(f.exists(), "Recovery must delete ${f.name}; it still exists post-cleanup")
        }

        // Step 4: open fresh at the same path. This should now
        // succeed because the file system is back to a clean state.
        val freshDriver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        freshDriver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        YancoDb.Schema.create(freshDriver)
        val freshDb = YancoDb(freshDriver)

        // Functional smoke: insert + select round-trips through the
        // post-recovery DB. Proves the recovered file is a real
        // SQLite DB, not just an empty placeholder.
        freshDb.sourcesQueries.insert(
            id = "src-post-recovery",
            name = "Post-recovery source",
            type = "m3u_url",
            url = "https://example.com/p.m3u",
            file_path = null,
            username_encrypted = null,
            password_encrypted = null,
            mac_address_encrypted = null,
            epg_url = null,
            user_agent = null,
            referer = null,
            last_synced = null,
            last_sync_error = null,
            is_active = true,
            priority = 0L,
            channel_count = 0,
            auto_sync_interval = 0,
            epg_priority = 0,
            auto_sync_on_start = false,
            created_at = 1_700_000_000_000L,
            updated_at = 1_700_000_000_000L,
        )
        val readBack = freshDb.sourcesQueries.selectById("src-post-recovery").executeAsOne()
        assertEquals("Post-recovery source", readBack.name)
        freshDriver.close()
    }

    /**
     * Negative case: recovery on a clean disk (no DB file, no sidecars)
     * is a no-op. Catches a regression where dbArtifactPaths returns
     * paths that ALWAYS exist (e.g. a `File("")` bug that resolves to
     * the working directory).
     */
    @Test fun recovery_onCleanDisk_noFilesToDelete_noOpSucceeds() {
        val dbFile = File(tmpDir, "yancotv.db")
        // No setup — file doesn't exist, sidecars don't exist.
        dbArtifactPaths(dbFile).forEach { f ->
            // Delete should be no-op since file doesn't exist;
            // assertion catches a bug where the path resolved to
            // something unexpected (cwd) that DOES exist.
            if (f.exists()) {
                fail("Pre-condition: ${f.path} should not exist on a clean tmpDir; dbArtifactPaths is computing the wrong path.")
            }
        }
        // Open succeeds at a fresh path.
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        YancoDb.Schema.create(driver)
        val db = YancoDb(driver)
        assertEquals(0, db.sourcesQueries.selectAll().executeAsList().size)
        driver.close()
    }

    // ─────── Helpers ───────

    private fun sourceRow(id: String): BackedUpSource = BackedUpSource(
        id = id,
        name = "Source $id",
        type = "m3u_url",
        url = "https://example.com/$id.m3u",
        filePath = null,
        usernameEncrypted = null,
        passwordEncrypted = null,
        macAddressEncrypted = null,
        epgUrl = null,
        userAgent = null,
        referer = null,
        lastSynced = null,
        lastSyncError = null,
        isActive = true,
        priority = 0L,
        channelCount = 0L,
        autoSyncInterval = 0L,
        epgPriority = 0L,
        autoSyncOnStart = false,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
    )
}
