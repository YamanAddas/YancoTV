package com.yancotv.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Stage 1.5 — DB migration test harness.
 *
 * Tests verify that real upgrade paths from earlier schema versions to the
 * current latest preserve user data and apply structural changes correctly.
 * Pinning these means a future schema bump can't silently break a user
 * coming from an older app build (the cohort that matters most for an
 * IPTV app — sources are slow to set up; losing them on update is
 * unacceptable).
 *
 * Each test:
 *   1. Constructs a driver at an older version using a hand-crafted SQL
 *      fixture (we don't ship `.db` snapshots — Windows JBR + sqlite-jdbc
 *      compatibility issues block the SQLDelight `verifyMigrations` build
 *      task that would generate them).
 *   2. Seeds realistic data via raw SQL — the SQLDelight-generated query
 *      API expects the latest schema, so we can't use it to seed older
 *      versions.
 *   3. Calls [YancoDb.Schema.migrate] for the version range under test.
 *   4. Wraps the now-current driver in [YancoDb] and asserts the seeded
 *      data survives + new schema features behave correctly.
 *
 * Note on coverage: `1.sqm` is "drop FTS triggers" — testing it requires
 * recreating the v1 trigger set, which is straightforward but the
 * post-condition is "triggers absent" which is harder to assert
 * meaningfully than a column existence check. v2 → v3 (the column-add
 * migration) is the higher-value test and the one this file covers.
 * v0 → v1 isn't a thing (v1 is the genesis schema).
 */
class MigrationTest {
    /**
     * v2 → v3 (`2.sqm`): adds `name_override` and `logo_override` to
     * `content`. Existing rows should pick up NULL defaults; subsequent
     * `setOverrides` calls should work normally.
     */
    @Test fun migrationV2ToV3AddsOverrideColumnsAsNullForExistingRows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)

        // Apply v2 schema (sources + content WITHOUT override columns).
        // Indexes / triggers omitted — the migration only ALTERs columns,
        // and a leaner fixture is easier to reason about.
        v2Schema().forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA user_version = 2;", 0)

        // Seed: one source + one content row at v2.
        driver.execute(null, SEED_SOURCE_V2, 0)
        driver.execute(null, SEED_CONTENT_V2, 0)

        // Run the migration through SQLDelight's generated migrator.
        // Pinned to v3 — this test exercises 2.sqm specifically. Stage 2's
        // 3.sqm onward operate on tables (recordings, favorites, sources)
        // this minimal v2 fixture doesn't include; the full v2 → current
        // path is covered by the Stage 2.6 migration test with a complete
        // v2 fixture.
        YancoDb.Schema.migrate(driver, oldVersion = 2, newVersion = 3)

        // Wrap and read back via the v3 query API.
        val db = YancoDb(driver)
        val row = db.contentQueries.selectById("ch-1").executeAsOne()

        assertEquals("ch-1", row.id)
        assertEquals("Test Channel", row.title)
        assertNull(row.name_override, "v2 row should have name_override NULL after migration")
        assertNull(row.logo_override, "v2 row should have logo_override NULL after migration")

        // setOverrides should now work — proves the columns were added,
        // not just declared.
        db.contentQueries.setOverrides(
            nameOverride = "Renamed",
            logoOverride = "https://example.com/logo.png",
            id = "ch-1",
        )
        val updated = db.contentQueries.selectById("ch-1").executeAsOne()
        assertEquals("Renamed", updated.name_override)
        assertEquals("https://example.com/logo.png", updated.logo_override)
    }

    /**
     * Sanity: migrating a fresh empty v2 DB to v3 should not error and
     * should leave a functionally-current schema (queries still work).
     * Catches "migration crashes on empty table / missing-row regressions".
     *
     * Note on user_version: SQLDelight's `Schema.migrate` runs the
     * declared `.sqm` SQL but does NOT bump the SQLite `user_version`
     * pragma — that's the driver's responsibility (`AndroidSqliteDriver`
     * does it via its onUpgrade callback in production). Asserting on
     * pragma value here would only test driver behaviour, not migration
     * correctness. The functional assertion (insert + select) is the
     * meaningful check.
     */
    @Test fun migrationV2ToV3OnEmptyDbCompletesAndSchemaIsCurrent() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        v2Schema().forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA user_version = 2;", 0)

        // Pinned to v3 — same rationale as above; Stage 2.6 covers v2 →
        // current with a complete fixture.
        YancoDb.Schema.migrate(driver, oldVersion = 2, newVersion = 3)

        // Now usable as a v3 DB — selectById on an empty table returns null,
        // setOverrides on a non-existent row is a no-op (UPDATE ... WHERE id=
        // matches nothing). Both prove the v3-shaped query API binds cleanly
        // against the migrated schema.
        val db = YancoDb(driver)
        val empty = db.contentQueries.selectById("does-not-exist").executeAsOneOrNull()
        assertNull(empty)
        // No exception → schema accepts the v3 query.
        db.contentQueries.setOverrides(
            nameOverride = "x",
            logoOverride = null,
            id = "does-not-exist",
        )
    }

    /**
     * v3 schema fresh-create sanity: `Schema.create()` on a clean driver
     * yields a fully-functional database — write, read, FTS search round-
     * trip works. Catches schema-file syntax errors that only surface at
     * runtime.
     */
    @Test fun freshSchemaSupportsFullWriteReadCycle() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        YancoDb.Schema.create(driver)

        val db = YancoDb(driver)
        val now = 1_700_000_000_000L
        db.sourcesQueries.insert(
            id = "src-1",
            name = "Test Source",
            type = "m3u_url",
            url = "https://example.com/playlist.m3u",
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
            priority = 0,
            channel_count = 0,
            auto_sync_interval = 0,
            created_at = now,
            updated_at = now,
        )
        db.contentQueries.insert(
            id = "ch-1",
            source_id = "src-1",
            type = "live",
            title = "Test Channel",
            clean_title = "test channel",
            group_name = "News",
            stream_url = "https://example.com/stream.ts",
            logo_url = null,
            tvg_id = "test.tvg",
            metadata_json = null,
            sort_order = 0,
            created_at = now,
        )

        // FTS round-trip — "test" should match the seeded title.
        val ftsHits = db.contentQueries.searchFts(query = "test", limitCount = 10).executeAsList()
        assertEquals(1, ftsHits.size, "FTS should match seeded title via the AFTER INSERT trigger")
        assertEquals("ch-1", ftsHits.first().id)
    }

    private companion object {
        /**
         * Hand-crafted v2 schema: tables we need to exercise the migration.
         * The `content` table deliberately omits `name_override` and
         * `logo_override` columns — those land in `2.sqm`. Other tables
         * (epg_programmes, episodes, etc.) aren't included because they're
         * irrelevant to the v2→v3 migration; SQLDelight's migrator only
         * touches what `2.sqm` says, so missing tables don't fail the
         * migration step.
         */
        fun v2Schema(): List<String> =
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
                    created_at INTEGER NOT NULL
                );
                """.trimIndent(),
            )

        const val SEED_SOURCE_V2 = """
            INSERT INTO sources (
                id, name, type, url, file_path,
                username_encrypted, password_encrypted, mac_address_encrypted,
                epg_url, user_agent, last_synced, last_sync_error,
                is_active, priority, channel_count, auto_sync_interval,
                created_at, updated_at
            ) VALUES (
                'src-v2', 'Pre-migration source', 'm3u_url', 'https://example.com/p.m3u', NULL,
                NULL, NULL, NULL,
                NULL, NULL, NULL, NULL,
                1, 0, 0, 0,
                1700000000000, 1700000000000
            );
        """

        const val SEED_CONTENT_V2 = """
            INSERT INTO content (
                id, source_id, type, title, clean_title, group_name,
                stream_url, logo_url, tvg_id, metadata_json,
                sort_order, created_at
            ) VALUES (
                'ch-1', 'src-v2', 'live', 'Test Channel', 'test channel', 'News',
                'https://example.com/stream.ts', NULL, 'test.tvg', NULL,
                0, 1700000000000
            );
        """
    }
}
