package com.yancotv.shared.db

import java.io.File

/**
 * MK.24.G.2 / MB-228 — recovery-decision pure functions extracted from
 * [DatabaseFactory.recoverWithFreshDb] so the contract can be tested
 * without standing up a real Android `Context` (which the production
 * factory needs for `AndroidSqliteDriver` + `getDatabasePath`).
 *
 * The recovery flow on a corrupted DB has three branches the production
 * code cared about: no backup found at all, backup is from an OLDER or
 * CURRENT schema (safe to restore), or backup is from a NEWER schema
 * (refuse — restoring would write columns the current binary doesn't
 * understand). Capturing those as a sealed type + pure decision lets us
 * pin every branch with a unit test, and stops a future refactor from
 * silently changing the rule (e.g. dropping the schema-version guard
 * because a hurried "simplify the recovery code" pass collapses
 * `RefuseRestore` into `FreshOnly`).
 *
 * Mirrors the MK.23.C.1 `resumePointDecision` extraction pattern.
 */
internal sealed interface RecoveryAction {
    /**
     * No usable backup. The fresh DB is left empty; the user re-adds
     * sources by hand. Hits when this is the first launch (no backup
     * file ever written), the file got deleted between sessions, or
     * the file failed to parse and `SourcesBackup.read` returned null.
     */
    data object FreshOnly : RecoveryAction

    /**
     * Backup is consumable. [sources] is replayed into the fresh DB via
     * `SourcesBackup.restoreInto`.
     */
    data class Restore(val sources: List<BackedUpSource>) : RecoveryAction

    /**
     * Backup exists but its `schemaVersion` is newer than the running
     * binary. Restoring would write rows shaped for a schema this
     * binary doesn't have queries for, which is worse than starting
     * fresh. The reason string is logged at the call site so the
     * forensic trail stays in logcat / Sentry breadcrumbs.
     */
    data class RefuseRestore(val reason: String) : RecoveryAction
}

/**
 * Decide what to do with the (optional) backup that survived the
 * corrupt DB. Pure — no I/O, no Android types, no logging.
 *
 * @param saved the parsed `SourcesBackupFile`, or null when no backup
 *   exists or the file was unparseable.
 * @param currentSchemaVersion the schema version the running binary's
 *   `YancoDb.Schema.version.toInt()` reports.
 */
internal fun decideRecoveryAction(saved: SourcesBackupFile?, currentSchemaVersion: Int): RecoveryAction = when {
    saved == null -> RecoveryAction.FreshOnly
    saved.schemaVersion > currentSchemaVersion ->
        RecoveryAction.RefuseRestore(
            "Sources backup schemaVersion=${saved.schemaVersion} is newer than " +
                "current schema $currentSchemaVersion; refusing to restore.",
        )
    else -> RecoveryAction.Restore(saved.sources)
}

/**
 * Enumerate every file SQLite might leave behind for a given main DB
 * file. WAL mode (enabled in `DatabaseFactory.SchemaCallback.onConfigure`)
 * creates `-wal` and `-shm` sidecars on every open; rollback-journal
 * mode uses `-journal`. Leaving any of these on disk after deleting
 * the main file gives the next open an inconsistent state — SQLite
 * may attempt to roll forward / back from the orphan and either
 * succeed (resurrecting the corruption we tried to escape) or fail
 * with a different error.
 *
 * Pure — no I/O. The caller is responsible for filtering by
 * `f.exists()` and calling `delete()`.
 *
 * Order matters slightly for the WAL case: the sidecar files (-wal,
 * -shm) reference the main file, so deleting them first leaves a
 * brief window where the main file has no companions. We delete in
 * (main, journal, wal, shm) order to mirror the existing production
 * code's order; SQLite's own recovery is robust to either ordering.
 */
internal fun dbArtifactPaths(dbFile: File): List<File> = listOf(
    dbFile,
    File("${dbFile.path}-journal"),
    File("${dbFile.path}-wal"),
    File("${dbFile.path}-shm"),
)
