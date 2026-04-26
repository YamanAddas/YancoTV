package com.yancotv.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Stage 1.5 — tests for [SourcesBackup], the proactive sources backup
 * that supports DB-corruption recovery in [DatabaseFactory].
 *
 * Covers:
 *   - read returns null when the backup file doesn't exist (first launch)
 *   - read returns null when the file is corrupt (better to lose sources
 *     than to crash on launch)
 *   - writeFromDb → read round-trips all source columns including
 *     encrypted blobs (Keystore-wrapped credentials must survive intact)
 *   - restoreInto re-creates rows on a fresh DB so a recovered backup
 *     yields functioning sources
 *   - schema-version field is encoded so a future schema bump can detect
 *     incompatible backups
 */
class SourcesBackupTest {
    private val tmpDir: File = createTempDirectory("sources-backup-test").toFile()
    private val backupFile: File = File(tmpDir, "sources-backup.json")

    @AfterTest fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test fun readReturnsNullWhenBackupFileDoesNotExist() {
        val backup = SourcesBackup(backupFile)
        assertNull(backup.read())
    }

    @Test fun readReturnsNullWhenBackupFileIsCorrupt() {
        backupFile.writeText("this is not json {{{")
        val backup = SourcesBackup(backupFile)
        assertNull(backup.read())
    }

    @Test fun writeFromDbThenReadRoundTripsAllColumns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        YancoDb.Schema.create(driver)
        val db = YancoDb(driver)
        val now = 1_700_000_000_000L

        val cleartextSourceId = "src-clear-1"
        val xtreamSourceId = "src-xtream-1"
        val encryptedUsername = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val encryptedPassword = byteArrayOf(0x05, 0x06, 0x07, 0x08, 0x09)

        // Cleartext-ish source (M3U URL — no credentials).
        db.sourcesQueries.insert(
            id = cleartextSourceId,
            name = "Public Playlist",
            type = "m3u_url",
            url = "https://example.com/playlist.m3u",
            file_path = null,
            username_encrypted = null,
            password_encrypted = null,
            mac_address_encrypted = null,
            epg_url = "https://example.com/guide.xml",
            user_agent = "VLC/3.0",
            referer = null,
            last_synced = now - 60_000L,
            last_sync_error = null,
            is_active = true,
            priority = 0,
            channel_count = 1234,
            auto_sync_interval = 86400,
            epg_priority = 0,
            created_at = now,
            updated_at = now,
        )
        // Xtream source with encrypted blobs — exercises the credentials
        // round-trip path.
        db.sourcesQueries.insert(
            id = xtreamSourceId,
            name = "Premium Xtream",
            type = "xtream",
            url = "https://provider.example.com:8080",
            file_path = null,
            username_encrypted = encryptedUsername,
            password_encrypted = encryptedPassword,
            mac_address_encrypted = null,
            epg_url = null,
            user_agent = null,
            referer = null,
            last_synced = null,
            last_sync_error = "401 Unauthorized",
            is_active = false,
            priority = 1,
            channel_count = 0,
            auto_sync_interval = 0,
            epg_priority = 0,
            created_at = now,
            updated_at = now,
        )

        val backup = SourcesBackup(backupFile)
        backup.writeFromDb(db)

        val read = backup.read()
        assertNotNull(read)
        assertEquals(YancoDb.Schema.version.toInt(), read.schemaVersion)
        assertEquals(2, read.sources.size)

        val cleartext = read.sources.first { it.id == cleartextSourceId }
        assertEquals("Public Playlist", cleartext.name)
        assertEquals("m3u_url", cleartext.type)
        assertEquals("https://example.com/playlist.m3u", cleartext.url)
        assertNull(cleartext.usernameEncrypted)
        assertNull(cleartext.passwordEncrypted)
        assertEquals("https://example.com/guide.xml", cleartext.epgUrl)
        assertEquals(true, cleartext.isActive)
        assertEquals(1234L, cleartext.channelCount)

        val xtream = read.sources.first { it.id == xtreamSourceId }
        assertEquals("Premium Xtream", xtream.name)
        assertEquals("xtream", xtream.type)
        assertContentEquals(encryptedUsername, xtream.usernameEncrypted)
        assertContentEquals(encryptedPassword, xtream.passwordEncrypted)
        assertEquals("401 Unauthorized", xtream.lastSyncError)
        assertEquals(false, xtream.isActive)
    }

    @Test fun restoreIntoRecreatesSourcesOnFreshDb() {
        // Set up: original DB with 2 sources, dump backup.
        val originalDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        originalDriver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        YancoDb.Schema.create(originalDriver)
        val originalDb = YancoDb(originalDriver)
        val now = 1_700_000_000_000L
        originalDb.sourcesQueries.insert(
            id = "src-1", name = "S1", type = "m3u_url", url = "u1", file_path = null,
            username_encrypted = null, password_encrypted = null, mac_address_encrypted = null,
            epg_url = null, user_agent = null, referer = null, last_synced = null, last_sync_error = null,
            is_active = true, priority = 0, channel_count = 0, auto_sync_interval = 0,
            epg_priority = 0,
            created_at = now, updated_at = now,
        )
        originalDb.sourcesQueries.insert(
            id = "src-2", name = "S2", type = "stalker", url = "u2", file_path = null,
            username_encrypted = null, password_encrypted = null,
            mac_address_encrypted = byteArrayOf(0xAB.toByte(), 0xCD.toByte()),
            epg_url = null, user_agent = null, referer = null, last_synced = null, last_sync_error = null,
            is_active = false, priority = 1, channel_count = 0, auto_sync_interval = 0,
            epg_priority = 0,
            created_at = now, updated_at = now,
        )

        val backup = SourcesBackup(backupFile)
        backup.writeFromDb(originalDb)

        // Simulate corruption recovery: brand-new empty DB, restore from
        // backup.
        val freshDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        freshDriver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        YancoDb.Schema.create(freshDriver)
        val freshDb = YancoDb(freshDriver)
        assertEquals(0, freshDb.sourcesQueries.selectAll().executeAsList().size)

        val read = backup.read()
        assertNotNull(read)
        backup.restoreInto(freshDb, read.sources)

        val restoredRows = freshDb.sourcesQueries.selectAll().executeAsList()
        assertEquals(2, restoredRows.size)
        val s2 = restoredRows.first { it.id == "src-2" }
        assertEquals("stalker", s2.type)
        assertContentEquals(
            byteArrayOf(0xAB.toByte(), 0xCD.toByte()),
            s2.mac_address_encrypted,
        )
    }

    @Test fun writeAndRestoreSurviveAtomicReplaceOfExistingBackup() {
        // Two consecutive backups — second should replace first cleanly.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        YancoDb.Schema.create(driver)
        val db = YancoDb(driver)
        val now = 1_700_000_000_000L

        val backup = SourcesBackup(backupFile)

        // First write: empty DB.
        backup.writeFromDb(db)
        assertEquals(0, backup.read()!!.sources.size)

        // Add a source, write again — backup file should now reflect 1 row.
        db.sourcesQueries.insert(
            id = "src-1", name = "S1", type = "m3u_url", url = "u1", file_path = null,
            username_encrypted = null, password_encrypted = null, mac_address_encrypted = null,
            epg_url = null, user_agent = null, referer = null, last_synced = null, last_sync_error = null,
            is_active = true, priority = 0, channel_count = 0, auto_sync_interval = 0,
            epg_priority = 0,
            created_at = now, updated_at = now,
        )
        backup.writeFromDb(db)
        val second = backup.read()!!
        assertEquals(1, second.sources.size)
        assertEquals("S1", second.sources.first().name)
    }
}
