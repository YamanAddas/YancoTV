package com.yancotv.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 2.6 — single migration test pass for all v3 → current Stage 2
 * migrations (3.sqm through 7.sqm). Standing up a v3 fixture with
 * realistic data, running the full migrate sequence, and asserting:
 *
 *   * Stage 2.1 (3.sqm) — `recordings.format` is NULL on the legacy row;
 *     `recording_schedules` table is queryable.
 *   * Stage 2.2 (4.sqm) — `favorite_lists` has the seeded 'default' row;
 *     pre-existing favorites are backfilled to `list_id = 'default'`.
 *   * Stage 2.3 (5.sqm) — `sources.referer` is NULL on the legacy row.
 *   * Stage 2.4 (6.sqm) — `sources.epg_priority` is 0 on the legacy row.
 *   * Stage 2.5 (7.sqm) — `backup_metadata` table is queryable; insert
 *     + read round-trips a checksum string intact.
 *
 * Goal: a v3-shipped device that pulls v8 binary preserves every user
 * record AND can immediately use every Stage 2 feature surface that
 * reads the new columns. No second pass needed for any data-class.
 *
 * The `MigrationTest` class above this one tests v2 → v3 (the 2.sqm
 * column-add migration) in isolation; this class picks up at v3 and
 * walks through Stage 2's bundle.
 */
class Stage2MigrationTest {
    @Test fun migrationV3ToCurrentBackfillsAndCreatesEverything() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)

        // Apply v3 schema (genesis + 1.sqm trigger drops + 2.sqm
        // override columns). Indexes / triggers omitted — the
        // migrations don't depend on them, and a leaner fixture is
        // easier to keep in sync with reality.
        v3Schema().forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA user_version = 3;", 0)

        // Seed: one realistic row per table the Stage 2 migrations touch.
        v3SeedRows().forEach { driver.execute(null, it, 0) }

        // Run the full Stage 2 migration sequence. SQLDelight applies
        // 3.sqm → 4.sqm → 5.sqm → 6.sqm → 7.sqm in order and stops at
        // the current schema version.
        YancoDb.Schema.migrate(driver, oldVersion = 3, newVersion = YancoDb.Schema.version)

        val db = YancoDb(driver)

        // ── Stage 2.1 — recordings.format + recording_schedules ────
        val recording = db.recordingsQueries.selectById("rec-v3").executeAsOne()
        assertEquals("rec-v3", recording.id)
        assertEquals("Pre-migration recording", recording.title)
        assertNull(recording.format, "v3 recording row should have format NULL after Stage 2.1 ALTER")
        // recording_schedules table should be queryable + empty.
        val schedules = db.recordingSchedulesQueries.selectAll().executeAsList()
        assertEquals(0, schedules.size)
        // Insert + read-back round-trip proves the schema bound cleanly.
        db.recordingSchedulesQueries.insert(
            id = "sch-1",
            content_id = "ch-1",
            programme_id = null,
            title = "Football match",
            stream_url = "https://example.com/stream.m3u8",
            scheduled_start = 1_700_000_000_000L,
            scheduled_end = 1_700_007_200_000L,
            state = "scheduled",
            recording_id = null,
            error = null,
            created_at = 1_700_000_000_000L,
            updated_at = 1_700_000_000_000L,
            series_key = null,
        )
        val sched = db.recordingSchedulesQueries.selectById("sch-1").executeAsOne()
        assertEquals("Football match", sched.title)
        assertEquals("scheduled", sched.state)

        // ── Stage 2.2 — favorite_lists seeded + favorites.list_id backfilled ──
        val defaultList = db.favoriteListsQueries.selectDefault().executeAsOneOrNull()
        assertNotNull(defaultList, "v4 → v5 migration should seed a default favorites list")
        assertEquals("default", defaultList.id)
        assertEquals(1L, defaultList.is_default)

        val favs = db.favoritesQueries.selectAll().executeAsList()
        assertEquals(1, favs.size, "Pre-migration favorite row should survive")
        assertEquals("fav-v3", favs.first().favorite_id)
        assertEquals(
            "default",
            favs.first().list_id,
            "Pre-migration favorites should be backfilled to the default list",
        )

        // List-scoped query also returns the backfilled row.
        val defaultListFavs = db.favoritesQueries.selectByList("default").executeAsList()
        assertEquals(1, defaultListFavs.size)

        // ── Stage 2.3 — sources.referer added as NULL ──────────────
        val source = db.sourcesQueries.selectById("src-v3").executeAsOne()
        assertEquals("Pre-migration source", source.name)
        assertEquals("VLC/3.0", source.user_agent)
        assertNull(source.referer, "Existing sources should pick up NULL referer (no override)")

        // ── Stage 2.4 — sources.epg_priority added with default 0 ──
        assertEquals(
            0L,
            source.epg_priority,
            "Existing sources should pick up epg_priority=0 (no preference)",
        )

        // ── Stage 2.5 — backup_metadata queryable ──────────────────
        assertEquals(0, db.backupMetadataQueries.selectAll().executeAsList().size)
        db.backupMetadataQueries.insert(
            id = "bk-1",
            file_uri = "content://com.android.providers.documents/document/primary%3ABackup%2Fyanco-backup.json",
            label = "Backup 2026-04-26 14:30",
            schema_version = YancoDb.Schema.version.toLong(),
            checksum = "abc123def456",
            size_bytes = 12_345L,
            record_counts = """{"sources":1,"favorites":1}""",
            notes = null,
            created_at = 1_700_000_000_000L,
        )
        val latest = db.backupMetadataQueries.selectLatest().executeAsOneOrNull()
        assertNotNull(latest)
        assertEquals("abc123def456", latest.checksum)
        assertEquals(YancoDb.Schema.version.toLong(), latest.schema_version)

        // ── Cross-cutting — schema version is now current ──────────
        // Functional smoke: a fresh write through the post-migration API
        // works (proves the cascade of migrations didn't leave any
        // table in a half-migrated shape).
        db.sourcesQueries.setEpgPriority(
            epg_priority = 5L,
            updated_at = 1_700_000_001_000L,
            id = "src-v3",
        )
        val updated = db.sourcesQueries.selectById("src-v3").executeAsOne()
        assertEquals(5L, updated.epg_priority)
    }

    @Test fun migrationV3ToCurrentOnEmptyDbCompletesCleanly() {
        // Sanity: empty v3 fixture migrates without fixture-specific seed
        // data. Catches "migration assumes at least one row of X" bugs.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        v3Schema().forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA user_version = 3;", 0)

        YancoDb.Schema.migrate(driver, oldVersion = 3, newVersion = YancoDb.Schema.version)

        val db = YancoDb(driver)
        // Every Stage 2 table is queryable on the now-empty migrated DB.
        assertEquals(0, db.recordingSchedulesQueries.selectAll().executeAsList().size)
        assertEquals(0, db.recordingsQueries.selectAll().executeAsList().size)
        assertEquals(0, db.backupMetadataQueries.selectAll().executeAsList().size)
        // The default favorites list is still seeded even on an empty
        // pre-migration DB — that's the migration's INSERT OR IGNORE.
        val defaultList = db.favoriteListsQueries.selectDefault().executeAsOneOrNull()
        assertNotNull(defaultList)
        assertEquals("default", defaultList.id)
    }

    @Test fun freshSchemaSeedsDefaultFavoriteList() {
        // Mirror of the migration assertion but against a fresh-create
        // path: FavoriteLists.sq's INSERT OR IGNORE should land the same
        // 'default' row at Schema.create time, so a brand-new install
        // doesn't differ in shape from a migrated one.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        YancoDb.Schema.create(driver)

        val db = YancoDb(driver)
        val defaultList = db.favoriteListsQueries.selectDefault().executeAsOneOrNull()
        assertNotNull(defaultList, "Fresh install should have the 'default' favorites list seeded")
        assertEquals("default", defaultList.id)
        assertTrue(defaultList.is_default == 1L)
    }

    private companion object {
        /**
         * Hand-crafted v3 schema. Includes every table that a Stage 2
         * migration ALTERs, CREATEs, or backfills against. Tables that
         * Stage 2 doesn't touch (epg_programmes children, episodes,
         * downloads, etc.) aren't included unless they're FK targets.
         */
        fun v3Schema(): List<String> =
            listOf(
                """
                CREATE TABLE sources (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL CHECK(type IN ('m3u_url', 'm3u_file', 'xtream', 'stalker')),
                    url TEXT,
                    file_path TEXT,
                    username_encrypted BLOB,
                    password_encrypted BLOB,
                    mac_address_encrypted BLOB,
                    epg_url TEXT,
                    user_agent TEXT,
                    last_synced INTEGER,
                    last_sync_error TEXT,
                    is_active INTEGER NOT NULL DEFAULT 1,
                    priority INTEGER NOT NULL DEFAULT 0,
                    channel_count INTEGER NOT NULL DEFAULT 0,
                    auto_sync_interval INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                );
                """.trimIndent(),
                """
                CREATE TABLE content (
                    id TEXT PRIMARY KEY NOT NULL,
                    source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
                    type TEXT NOT NULL CHECK(type IN ('live', 'movie', 'series')),
                    title TEXT NOT NULL,
                    clean_title TEXT,
                    group_name TEXT,
                    stream_url TEXT NOT NULL,
                    logo_url TEXT,
                    tvg_id TEXT,
                    metadata_json TEXT,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    name_override TEXT,
                    logo_override TEXT
                );
                """.trimIndent(),
                """
                CREATE TABLE epg_programmes (
                    id TEXT PRIMARY KEY NOT NULL,
                    source_id TEXT REFERENCES sources(id) ON DELETE CASCADE,
                    channel_tvg_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT,
                    start_time INTEGER NOT NULL,
                    end_time INTEGER NOT NULL,
                    category TEXT,
                    icon_url TEXT
                );
                """.trimIndent(),
                """
                CREATE TABLE recordings (
                    id TEXT PRIMARY KEY NOT NULL,
                    content_id TEXT,
                    title TEXT NOT NULL,
                    stream_url TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('recording', 'completed', 'failed', 'cancelled')),
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER,
                    duration_seconds INTEGER,
                    file_size_bytes INTEGER,
                    error TEXT
                );
                """.trimIndent(),
                """
                CREATE TABLE favorites (
                    id TEXT PRIMARY KEY NOT NULL,
                    content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
                    added_at INTEGER NOT NULL
                );
                """.trimIndent(),
            )

        fun v3SeedRows(): List<String> =
            listOf(
                """
                INSERT INTO sources (
                    id, name, type, url, file_path,
                    username_encrypted, password_encrypted, mac_address_encrypted,
                    epg_url, user_agent, last_synced, last_sync_error,
                    is_active, priority, channel_count, auto_sync_interval,
                    created_at, updated_at
                ) VALUES (
                    'src-v3', 'Pre-migration source', 'm3u_url', 'https://example.com/p.m3u', NULL,
                    NULL, NULL, NULL,
                    'https://example.com/guide.xml', 'VLC/3.0', NULL, NULL,
                    1, 0, 1, 0,
                    1700000000000, 1700000000000
                );
                """.trimIndent(),
                """
                INSERT INTO content (
                    id, source_id, type, title, clean_title, group_name,
                    stream_url, logo_url, tvg_id, metadata_json,
                    sort_order, created_at, name_override, logo_override
                ) VALUES (
                    'ch-1', 'src-v3', 'live', 'Test Channel', 'test channel', 'News',
                    'https://example.com/stream.ts', NULL, 'test.tvg', NULL,
                    0, 1700000000000, NULL, NULL
                );
                """.trimIndent(),
                """
                INSERT INTO favorites (id, content_id, added_at)
                VALUES ('fav-v3', 'ch-1', 1700000000000);
                """.trimIndent(),
                """
                INSERT INTO recordings (
                    id, content_id, title, stream_url, file_path, status,
                    started_at, ended_at, duration_seconds, file_size_bytes, error
                ) VALUES (
                    'rec-v3', 'ch-1', 'Pre-migration recording',
                    'https://example.com/stream.ts',
                    '/storage/emulated/0/yanco/rec-v3.ts',
                    'completed',
                    1700000000000, 1700003600000, 3600, 524288000, NULL
                );
                """.trimIndent(),
            )
    }
}
