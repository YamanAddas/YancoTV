package com.yancotv.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
     * MK.23.D.6 — v9 → v10 dedicated migration test.
     *
     * `9.sqm` adds `auto_sync_on_start INTEGER NOT NULL DEFAULT 0` to
     * `sources` (produces schema v10). The MK.21 active-work-queue
     * feature has MainActivity reading this on launch to kick the
     * user-opted sources through SourceSyncCoordinator. A wrong-type
     * or missing column would crash `db.sourcesQueries.selectAll()`
     * with an SQLite column-not-found error at app launch.
     *
     * The full v3 → current path is covered by `Stage2MigrationTest`,
     * but v10 rides along with no dedicated assertion. This test
     * isolates the v9 → v10 hop so a regression in 9.sqm is caught
     * even when Stage2's bundled assertions still pass.
     */
    @Test fun migrationV9ToV10AddsAutoSyncOnStartColumnDefaultingToFalse() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)

        // Build a minimal v9 `sources` table — every column the schema
        // had at v9, NOT auto_sync_on_start. Mirrors the production
        // schema before 9.sqm landed.
        v9SourcesSchema().forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA user_version = 9;", 0)

        // Seed a v9 row so the migration's ALTER TABLE has data to
        // backfill. The DEFAULT 0 should land for this row.
        driver.execute(null, SEED_SOURCE_V9, 0)

        // Apply 9.sqm (v9 → v10). Only touches sources, no need to
        // pre-create recording_schedules / etc. tables here.
        YancoDb.Schema.migrate(driver, oldVersion = 9, newVersion = 10)

        // Wrap and read via the v10 query API. The SQLDelight-generated
        // `Source` row exposes `auto_sync_on_start` as a Boolean; if
        // the migration didn't add the column, the SELECT would throw
        // here.
        val db = YancoDb(driver)
        val row = db.sourcesQueries.selectById("src-v9").executeAsOne()
        assertEquals(false, row.auto_sync_on_start, "existing v9 rows must default to auto_sync_on_start = false (DEFAULT 0)")

        // Insert a fresh v10 row with auto_sync_on_start = true and
        // confirm round-trip — proves the column is functional, not
        // just declared.
        db.sourcesQueries.insert(
            id = "src-v10",
            name = "Auto-sync source",
            type = "m3u_url",
            url = "http://x",
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
            auto_sync_on_start = true,
            created_at = 0L,
            updated_at = 0L,
        )
        val auto = db.sourcesQueries.selectById("src-v10").executeAsOne()
        assertEquals(true, auto.auto_sync_on_start)
    }

    // ───── MK.24.G.1 / MB-227 — per-hop isolation for 3.sqm … 7.sqm ─────
    //
    // `Stage2MigrationTest` exercises v3 → current as a single bundle.
    // Each test below isolates one hop so a regression in (say) 5.sqm is
    // caught even when 6.sqm happens to leave the bundled assertions
    // green. Fixtures are intentionally lean — only tables the migration
    // touches + FK targets — so each test is independently maintainable.

    /**
     * v3 → v4 (`3.sqm`, Stage 2.1, MK.14.3): scheduled-recording layer.
     *   * Creates `recording_schedules` table (with FKs to content,
     *     epg_programmes, recordings — all SET NULL on delete).
     *   * Adds `recordings.format` TEXT (NULL for legacy rows).
     */
    @Test fun migrationV3ToV4AddsRecordingSchedulesAndRecordingsFormat() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)

        // v3 fixture: minimal tables 3.sqm references — content + sources
        // (FK target chain), epg_programmes (FK target), recordings (gets
        // ALTERed). favorites etc. omitted: 3.sqm doesn't touch them.
        v3SchemaForRecordingsHop().forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA user_version = 3;", 0)

        // Seed: a v3 recording row to confirm format defaults to NULL
        // and other columns survive intact.
        driver.execute(null, SEED_RECORDING_V3, 0)

        YancoDb.Schema.migrate(driver, oldVersion = 3, newVersion = 4)

        val db = YancoDb(driver)

        // recordings.format added — null on the legacy row.
        val rec = db.recordingsQueries.selectById("rec-v3").executeAsOne()
        assertEquals("rec-v3", rec.id)
        assertEquals("Pre-3.sqm recording", rec.title)
        assertNull(rec.format, "v3 recording row must have format NULL after 3.sqm ALTER")

        // recording_schedules table created + queryable. Use direct
        // driver read because db.recordingSchedulesQueries.selectAll's
        // generated query expects `series_key` (added in 8.sqm) — we're
        // at v4 here, that column doesn't exist yet.
        val initialCount =
            driver
                .executeQuery(null, "SELECT COUNT(*) FROM recording_schedules", { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0))
                }, 0)
                .value
        assertEquals(0L, initialCount, "recording_schedules table must exist + be empty post-3.sqm")

        // Insert + read-back round-trips through the v4 schema. Direct
        // SQL because the v10 query API expects series_key.
        driver.execute(
            null,
            """
            INSERT INTO recording_schedules
                (id, content_id, programme_id, title, stream_url,
                 scheduled_start, scheduled_end, state, recording_id, error,
                 created_at, updated_at)
            VALUES
                ('sch-1', NULL, NULL, 'Football match', 'https://example.com/stream.m3u8',
                 1700000000000, 1700007200000, 'scheduled', NULL, NULL,
                 1700000000000, 1700000000000);
            """.trimIndent(),
            0,
        )
        val schedCount =
            driver
                .executeQuery(null, "SELECT COUNT(*) FROM recording_schedules", { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0))
                }, 0)
                .value
        assertEquals(1L, schedCount, "recording_schedules row must be insertable post-3.sqm")
    }

    /**
     * v4 → v5 (`4.sqm`, Stage 2.2, MK.13.4): multi-list favorites.
     *   * Creates `favorite_lists` + INSERT OR IGNORE seeds 'default'.
     *   * Adds `favorites.list_id` (FK to favorite_lists).
     *   * Backfills every legacy favorite row's list_id to 'default'.
     *
     * The load-bearing assertion is the backfill — a regression that
     * skipped the UPDATE would leave legacy favorites with NULL list_id
     * and orphan them from list-scoped queries.
     */
    @Test fun migrationV4ToV5SeedsDefaultListAndBackfillsLegacyFavoritesListId() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)

        // v4 fixture: sources + content (FK target chain) + favorites
        // (gets ALTERed). 4.sqm only adds favorite_lists and modifies
        // favorites; everything else stays untouched.
        v4SchemaForFavoritesHop().forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA user_version = 4;", 0)

        // Seed: source + content + 2 legacy favorites (no list_id, since
        // 4.sqm hasn't run yet). Two so we can confirm the UPDATE
        // backfills BOTH, not just the first one it sees.
        driver.execute(null, SEED_SOURCE_V3_4, 0)
        driver.execute(null, SEED_CONTENT_V3_4, 0)
        driver.execute(null, SEED_CONTENT_V3_4_SECOND, 0)
        driver.execute(null, SEED_FAVORITE_V4, 0)
        driver.execute(null, SEED_FAVORITE_V4_SECOND, 0)

        YancoDb.Schema.migrate(driver, oldVersion = 4, newVersion = 5)

        val db = YancoDb(driver)

        // favorite_lists table seeded with the default row.
        val defaultList = db.favoriteListsQueries.selectDefault().executeAsOneOrNull()
        assertNotNull(defaultList, "4.sqm must seed the 'default' favorite_lists row")
        assertEquals("default", defaultList.id)
        assertEquals(1L, defaultList.is_default)

        // Both legacy favorites backfilled to list_id = 'default'.
        val favs = db.favoritesQueries.selectAll().executeAsList()
        assertEquals(2, favs.size, "Both pre-migration favorites must survive")
        favs.forEach { row ->
            assertEquals(
                "default",
                row.list_id,
                "Legacy favorite ${row.favorite_id} must be backfilled to default list — UPDATE missed it otherwise",
            )
        }

        // List-scoped query also returns both.
        val defaultListFavs = db.favoritesQueries.selectByList("default").executeAsList()
        assertEquals(2, defaultListFavs.size)
    }

    /**
     * v5 → v6 (`5.sqm`, Stage 2.3, MK.17.5): per-source `referer` for
     * providers that gate playback on the HTTP `Referer` header.
     * Existing rows pick up NULL; new rows can set the header explicitly.
     */
    @Test fun migrationV5ToV6AddsSourcesRefererAsNullForExistingRows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)

        // v5 fixture: sources at the v5 column set (no referer, no
        // epg_priority, no auto_sync_on_start). Just the source table —
        // 5.sqm doesn't touch anything else.
        v5SourcesSchema().forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA user_version = 5;", 0)

        driver.execute(null, SEED_SOURCE_V5, 0)

        YancoDb.Schema.migrate(driver, oldVersion = 5, newVersion = 6)

        // We can't use db.sourcesQueries.selectById here — the generated
        // query expects v10-shape (with auto_sync_on_start). Read directly
        // via the driver instead.
        val refererForExistingRow =
            driver
                .executeQuery(null, "SELECT referer FROM sources WHERE id = 'src-v5'", { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0))
                }, 0)
                .value
        assertNull(refererForExistingRow, "Pre-5.sqm source row should pick up NULL referer (no override)")

        // Insert a new row with a non-null referer — proves the column
        // is functional, not just declared.
        driver.execute(
            null,
            """
            INSERT INTO sources (
                id, name, type, url, file_path,
                username_encrypted, password_encrypted, mac_address_encrypted,
                epg_url, user_agent, referer, last_synced, last_sync_error,
                is_active, priority, channel_count, auto_sync_interval,
                created_at, updated_at
            ) VALUES (
                'src-v6', 'New v6 source', 'm3u_url', 'https://example.com/p.m3u', NULL,
                NULL, NULL, NULL,
                NULL, NULL, 'https://my.example/referer', NULL, NULL,
                1, 1, 0, 0,
                1700000000000, 1700000000000
            );
            """.trimIndent(),
            0,
        )
        val refererForNewRow =
            driver
                .executeQuery(null, "SELECT referer FROM sources WHERE id = 'src-v6'", { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0))
                }, 0)
                .value
        assertEquals("https://my.example/referer", refererForNewRow)
    }

    /**
     * v6 → v7 (`6.sqm`, Stage 2.4, MK.15.7): per-source EPG merge priority.
     * Existing rows pick up 0 via the `DEFAULT 0` clause; new rows can set
     * any value.
     */
    @Test fun migrationV6ToV7AddsSourcesEpgPriorityDefaultingToZero() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)

        // v6 fixture: sources at v6 (with referer from 5.sqm but without
        // epg_priority).
        v6SourcesSchema().forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA user_version = 6;", 0)

        driver.execute(null, SEED_SOURCE_V6, 0)

        YancoDb.Schema.migrate(driver, oldVersion = 6, newVersion = 7)

        val priorityForExistingRow =
            driver
                .executeQuery(null, "SELECT epg_priority FROM sources WHERE id = 'src-v6'", { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0))
                }, 0)
                .value
        assertEquals(0L, priorityForExistingRow, "Pre-6.sqm source row should pick up epg_priority=0 (DEFAULT 0)")

        // UPDATE the existing row's priority — round-trip proves the
        // column is mutable, not a stuck default.
        driver.execute(null, "UPDATE sources SET epg_priority = 5 WHERE id = 'src-v6'", 0)
        val priorityAfterUpdate =
            driver
                .executeQuery(null, "SELECT epg_priority FROM sources WHERE id = 'src-v6'", { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0))
                }, 0)
                .value
        assertEquals(5L, priorityAfterUpdate)
    }

    /**
     * v7 → v8 (`7.sqm`, Stage 2.5): backup_metadata table for the user-
     * initiated full-app backup history (separate from the silent
     * `sources-backup.json` corruption-recovery artifact).
     *
     * Pure-additive: a new table + index. No existing rows to worry
     * about.
     */
    @Test fun migrationV7ToV8CreatesBackupMetadataTable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)

        // v7 fixture: nothing strictly required because 7.sqm only
        // creates a new table. Use minimal sources table just so the
        // user_version pragma sits on a real DB.
        v6SourcesSchema().forEach { driver.execute(null, it, 0) }
        driver.execute(null, "ALTER TABLE sources ADD COLUMN epg_priority INTEGER NOT NULL DEFAULT 0;", 0)
        driver.execute(null, "PRAGMA user_version = 7;", 0)

        YancoDb.Schema.migrate(driver, oldVersion = 7, newVersion = 8)

        val db = YancoDb(driver)

        // Table queryable + empty.
        assertEquals(0, db.backupMetadataQueries.selectAll().executeAsList().size)

        // Insert + read-back round-trips a checksum string + record_counts
        // JSON intact. Picks the smallest meaningful payload.
        db.backupMetadataQueries.insert(
            id = "bk-v8-1",
            file_uri = "content://com.android.providers.documents/document/primary%3ABackup%2Fyanco-backup.json",
            label = "Backup post-7.sqm",
            schema_version = 8L,
            checksum = "abc123def456",
            size_bytes = 12_345L,
            record_counts = """{"sources":1,"favorites":0}""",
            notes = null,
            created_at = 1_700_000_000_000L,
        )
        val latest = db.backupMetadataQueries.selectLatest().executeAsOneOrNull()
        assertNotNull(latest)
        assertEquals("abc123def456", latest.checksum)
        assertEquals(8L, latest.schema_version)
        assertEquals("""{"sources":1,"favorites":0}""", latest.record_counts)
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
            epg_priority = 0,
            auto_sync_on_start = false,
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
        fun v2Schema(): List<String> = listOf(
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

        /**
         * Hand-crafted v9 `sources` schema — every column added through
         * 8.sqm but NOT `auto_sync_on_start` (which lands in 9.sqm,
         * producing v10). Cumulative columns by migration that touch
         * sources:
         *   - genesis: id, name, type, url, file_path, username_encrypted,
         *     password_encrypted, mac_address_encrypted, epg_url,
         *     user_agent, last_synced, last_sync_error, is_active,
         *     priority, channel_count, auto_sync_interval, created_at,
         *     updated_at
         *   - 5.sqm: + referer
         *   - 6.sqm: + epg_priority
         *   - 7.sqm: no sources column change (BackupMetadata table only)
         *   - 8.sqm: no sources column change (recording_schedules.series_key)
         *   - 9.sqm: + auto_sync_on_start  ← what this test verifies
         */
        fun v9SourcesSchema(): List<String> = listOf(
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
                    referer TEXT,
                    last_synced INTEGER,
                    last_sync_error TEXT,
                    is_active INTEGER NOT NULL DEFAULT 1,
                    priority INTEGER NOT NULL DEFAULT 0,
                    channel_count INTEGER NOT NULL DEFAULT 0,
                    auto_sync_interval INTEGER NOT NULL DEFAULT 0,
                    epg_priority INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                );
            """.trimIndent(),
        )

        const val SEED_SOURCE_V9 = """
            INSERT INTO sources (
                id, name, type, url, file_path,
                username_encrypted, password_encrypted, mac_address_encrypted,
                epg_url, user_agent, referer, last_synced, last_sync_error,
                is_active, priority, channel_count, auto_sync_interval,
                epg_priority, created_at, updated_at
            ) VALUES (
                'src-v9', 'Pre-9.sqm source', 'm3u_url', 'https://example.com/p.m3u', NULL,
                NULL, NULL, NULL,
                NULL, NULL, NULL, NULL, NULL,
                1, 0, 0, 0,
                0, 1700000000000, 1700000000000
            );
        """

        // ───── MK.24.G.1 / MB-227 — fixtures for per-hop tests ─────

        /**
         * v3 schema fixture for the v3→v4 (3.sqm) test. Includes the four
         * tables 3.sqm references via FK (`content`, `epg_programmes`,
         * `recordings`, `sources`) but excludes everything 3.sqm doesn't
         * touch (`favorites` etc). Override columns added in 2.sqm are
         * present here because we're starting at v3.
         */
        fun v3SchemaForRecordingsHop(): List<String> = listOf(
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
        )

        const val SEED_RECORDING_V3 = """
            INSERT INTO recordings (
                id, content_id, title, stream_url, file_path, status,
                started_at, ended_at, duration_seconds, file_size_bytes, error
            ) VALUES (
                'rec-v3', NULL, 'Pre-3.sqm recording',
                'https://example.com/stream.ts',
                '/storage/emulated/0/yanco/rec-v3.ts',
                'completed',
                1700000000000, 1700003600000, 3600, 524288000, NULL
            );
        """

        /**
         * v4 schema fixture for the v4→v5 (4.sqm) test. Includes
         * `favorites` (gets ALTERed) plus FK-target chain (`content`
         * which references `sources`). Doesn't include `recordings` or
         * `recording_schedules` — 4.sqm doesn't touch them.
         *
         * `favorites` here has the v3 shape (no list_id) — that's the
         * column 4.sqm ALTERs onto it.
         */
        fun v4SchemaForFavoritesHop(): List<String> = listOf(
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
                CREATE TABLE favorites (
                    id TEXT PRIMARY KEY NOT NULL,
                    content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
                    added_at INTEGER NOT NULL
                );
            """.trimIndent(),
        )

        const val SEED_SOURCE_V3_4 = """
            INSERT INTO sources (
                id, name, type, url, file_path,
                username_encrypted, password_encrypted, mac_address_encrypted,
                epg_url, user_agent, last_synced, last_sync_error,
                is_active, priority, channel_count, auto_sync_interval,
                created_at, updated_at
            ) VALUES (
                'src-v4', 'Pre-4.sqm source', 'm3u_url', 'https://example.com/p.m3u', NULL,
                NULL, NULL, NULL,
                NULL, NULL, NULL, NULL,
                1, 0, 0, 0,
                1700000000000, 1700000000000
            );
        """

        const val SEED_CONTENT_V3_4 = """
            INSERT INTO content (
                id, source_id, type, title, clean_title, group_name,
                stream_url, logo_url, tvg_id, metadata_json,
                sort_order, created_at, name_override, logo_override
            ) VALUES (
                'ch-v4-1', 'src-v4', 'live', 'Channel A', 'channel a', 'News',
                'https://example.com/a.ts', NULL, 'a.tvg', NULL,
                0, 1700000000000, NULL, NULL
            );
        """

        const val SEED_CONTENT_V3_4_SECOND = """
            INSERT INTO content (
                id, source_id, type, title, clean_title, group_name,
                stream_url, logo_url, tvg_id, metadata_json,
                sort_order, created_at, name_override, logo_override
            ) VALUES (
                'ch-v4-2', 'src-v4', 'live', 'Channel B', 'channel b', 'News',
                'https://example.com/b.ts', NULL, 'b.tvg', NULL,
                1, 1700000000001, NULL, NULL
            );
        """

        const val SEED_FAVORITE_V4 = """
            INSERT INTO favorites (id, content_id, added_at)
            VALUES ('fav-v4-1', 'ch-v4-1', 1700000000010);
        """

        const val SEED_FAVORITE_V4_SECOND = """
            INSERT INTO favorites (id, content_id, added_at)
            VALUES ('fav-v4-2', 'ch-v4-2', 1700000000011);
        """

        /**
         * v5 schema fixture for the v5→v6 (5.sqm) test. Just `sources`
         * — the only table 5.sqm touches.
         *
         * v5 sources columns: genesis (18 columns) — 5.sqm adds `referer`
         * (the 19th).
         */
        fun v5SourcesSchema(): List<String> = listOf(
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
        )

        const val SEED_SOURCE_V5 = """
            INSERT INTO sources (
                id, name, type, url, file_path,
                username_encrypted, password_encrypted, mac_address_encrypted,
                epg_url, user_agent, last_synced, last_sync_error,
                is_active, priority, channel_count, auto_sync_interval,
                created_at, updated_at
            ) VALUES (
                'src-v5', 'Pre-5.sqm source', 'm3u_url', 'https://example.com/p.m3u', NULL,
                NULL, NULL, NULL,
                NULL, NULL, NULL, NULL,
                1, 0, 0, 0,
                1700000000000, 1700000000000
            );
        """

        /**
         * v6 schema fixture for the v6→v7 (6.sqm) test. Sources at v6 —
         * has `referer` (added in 5.sqm) but not yet `epg_priority`
         * (added in 6.sqm).
         */
        fun v6SourcesSchema(): List<String> = listOf(
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
                    referer TEXT,
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
        )

        const val SEED_SOURCE_V6 = """
            INSERT INTO sources (
                id, name, type, url, file_path,
                username_encrypted, password_encrypted, mac_address_encrypted,
                epg_url, user_agent, referer, last_synced, last_sync_error,
                is_active, priority, channel_count, auto_sync_interval,
                created_at, updated_at
            ) VALUES (
                'src-v6', 'Pre-6.sqm source', 'm3u_url', 'https://example.com/p.m3u', NULL,
                NULL, NULL, NULL,
                NULL, NULL, NULL, NULL, NULL,
                1, 0, 0, 0,
                1700000000000, 1700000000000
            );
        """
    }
}
