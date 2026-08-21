package com.yancotv.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yancotv.shared.sources.BulkContentWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The v1.4.0 -> v1.5.0 upgrade, end to end.
 *
 * v1.4.0 shipped with migrations through `10.sqm`, so every device in the field
 * is at **schema v11**. v1.5.0 adds three hops — `11.sqm` (sources.expires_at),
 * `12.sqm` (content_first_seen) and `13.sqm` (recent_channels) — which
 * SQLDelight applies back-to-back the first time the upgraded binary opens the
 * database.
 *
 * Why this class exists when `MigrationTest` already covers each hop
 * individually and `Stage2MigrationTest` already walks v3 -> current:
 *
 *  1. **The per-hop tests each start from a fresh fixture at version N.** They
 *     prove `12.sqm` works on a database containing nothing else. They do not
 *     prove the three hops compose, and they say nothing about what the user
 *     sees afterwards.
 *
 *  2. **The v3 chain's fixture is explicitly lossy** — its own comment says
 *     "Indexes / triggers omitted". Fine for asserting column adds and
 *     backfills; the wrong instrument for asking whether an upgraded database
 *     is structurally the same object as a fresh one.
 *
 *  3. **Nothing tested the upgrade's user-visible contract.** The MK.35 rails
 *     are the point of this release, and their behaviour on upgrade is not
 *     obvious: `content_first_seen` arrives EMPTY, so "Recently added" has to
 *     stay empty rather than either breaking or — much worse — announcing that
 *     all 272,419 existing titles are new.
 *
 * The fixture is not hand-written. It is the actual set of CREATE statements
 * from the `.sq` files at commit `f7f38a7` (the 1.4.0 version bump), extracted
 * mechanically, with SQLDelight's `AS kotlin.X` column annotations stripped
 * because raw SQLite does not accept them. Indexes, the FTS4 virtual table and
 * the `content_ai` trigger are all included — that faithfulness is what makes
 * [migratedFrom140IsStructurallyIdenticalToAFreshInstall] mean anything.
 *
 * That test is the important one. It stands in for SQLDelight's
 * `verifyCommonMainYancoDbMigration` Gradle task, which is disabled on Windows
 * (sqlite-jdbc / JBR native-link failure) and has therefore never run against
 * `11.sqm`, `12.sqm` or `13.sqm` on the machine this release was built on. It
 * catches the drift that task exists to catch: a `.sq` CREATE edited without a
 * matching `.sqm`, leaving upgraders on a schema that differs from what fresh
 * installs get — invisible in development, where every database is a fresh
 * install.
 */
class UpgradeFrom140Test {

    // ---- the upgrade itself ---------------------------------------------

    /**
     * A database upgraded from 1.4.0 must be indistinguishable from one a fresh
     * 1.5.0 install creates.
     *
     * Compares every entry in `sqlite_master` — tables, indexes, triggers,
     * views, FTS shadow tables and SQLite's own autoindexes. SQL text is
     * normalised (comments stripped, whitespace collapsed) so the comparison is
     * about structure, not about how a statement happens to be formatted.
     *
     * A failure here means upgraders and fresh installers run different
     * schemas, and every downstream assertion about behaviour is then only true
     * for whichever of the two this file happened to build.
     */
    @Test fun migratedFrom140IsStructurallyIdenticalToAFreshInstall() {
        val migrated = schemaObjects(upgradedFrom140(seeded = false))

        val freshDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        freshDriver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        YancoDb.Schema.create(freshDriver)
        val fresh = schemaObjects(freshDriver)

        val missing = fresh.keys - migrated.keys
        assertTrue(
            missing.isEmpty(),
            "A fresh 1.5.0 install has these schema objects, an upgrade from 1.4.0 does not: " +
                "$missing. A .sq CREATE was added without a matching .sqm.",
        )

        val extra = migrated.keys - fresh.keys
        assertTrue(
            extra.isEmpty(),
            "An upgrade from 1.4.0 leaves these schema objects behind, a fresh install has no such thing: " +
                "$extra. A migration created something the .sq files no longer declare.",
        )

        for ((key, freshSql) in fresh) {
            val migratedSql = migrated.getValue(key)
            if (key.startsWith("table:")) {
                // Column ORDER is exempt; the column SET is not. See
                // [columnsOf] for why, and for what had to be true for that
                // exemption to be safe.
                assertEquals(
                    columnsOf(freshSql),
                    columnsOf(migratedSql),
                    "Columns of `$key` differ between a fresh install and an upgrade from 1.4.0",
                )
                assertEquals(
                    freshSql.substringBefore('('),
                    migratedSql.substringBefore('('),
                    "Table declaration of `$key` differs between a fresh install and an upgrade from 1.4.0",
                )
            } else {
                assertEquals(
                    freshSql,
                    migratedSql,
                    "Schema object `$key` differs between a fresh install and an upgrade from 1.4.0",
                )
            }
        }
    }

    /** Every row a 1.4.0 user had is still there afterwards. */
    @Test fun upgradeFrom140PreservesUserData() {
        val db = YancoDb(upgradedFrom140())

        val source = db.sourcesQueries.selectById("src-140").executeAsOne()
        assertEquals("Living room provider", source.name)
        assertEquals(0L, source.epg_priority)
        // 11.sqm's column arrives NULL — "no expiry known", not "expired".
        assertNull(source.expires_at, "an existing source must not acquire a bogus expiry on upgrade")

        assertEquals(5L, db.contentQueries.countBySource("src-140").executeAsOne())

        val history = db.watchHistoryQueries.selectByContent("c-movie-1").executeAsList()
        assertEquals(1, history.size, "watch history is the least replaceable thing a user owns")
        assertEquals(1_820L, history.first().position_seconds)

        assertEquals(1, db.favoritesQueries.selectAll().executeAsList().size)

        // Category settings drive Home's rails as of MK.35.2, so a lost pin or a
        // lost hide is now visible on the first screen rather than buried.
        assertTrue(
            db.groupPreferencesQueries.selectByType("series").executeAsList()
                .any { it.group_key == "TURKISH YERLI DIZILER" && it.is_pinned },
            "a pinned category must survive the upgrade — MK.35.2 puts pins on Home",
        )
        assertTrue(
            db.groupPreferencesQueries.selectByType("movie").executeAsList()
                .any { it.group_key == "ADULT" && it.is_hidden },
            "a hidden category must survive the upgrade — losing a hide surfaces content the user suppressed",
        )
    }

    /** A 1.4.0 fixture with no rows at all must upgrade without complaint. */
    @Test fun upgradeFrom140OnAnEmptyDatabaseCompletesCleanly() {
        val db = YancoDb(upgradedFrom140(seeded = false))
        assertEquals(0, db.contentQueries.recentlyAddedVod(20).executeAsList().size)
        assertEquals(0L, db.recentChannelsQueries.countAll().executeAsOne())
        assertNotNull(db.favoriteListsQueries.selectDefault().executeAsOneOrNull())
    }

    // ---- what the user actually sees -------------------------------------

    /**
     * The rail is empty on first launch after the upgrade — NOT full.
     *
     * `12.sqm` deliberately does not backfill, so no existing title has a
     * first-seen stamp and the JOIN matches nothing. The failure this pins is
     * the tempting "helpful" alternative: backfilling from `content.created_at`
     * would stamp all four VOD rows here (all 272,419 on the real device) with
     * the last sync's timestamp and present the whole catalogue as new.
     */
    @Test fun recentlyAddedIsEmptyImmediatelyAfterUpgradeRatherThanFullOfOldTitles() {
        val db = YancoDb(upgradedFrom140())
        val vodCount = db.contentQueries.selectByType("movie").executeAsList().size +
            db.contentQueries.selectByType("series").executeAsList().size
        assertEquals(3, vodCount, "the fixture has VOD to be wrong about")
        assertEquals(
            0,
            db.contentQueries.recentlyAddedVod(20).executeAsList().size,
            "pre-existing titles are not new; an empty rail is correct, a full one is the bug",
        )
    }

    /**
     * The first sync after upgrading stamps the catalogue as an initial import.
     *
     * This is the hinge of the whole upgrade. `content_first_seen` is empty, so
     * [BulkContentWriter.finishSource] sees zero rows for the source and must
     * conclude "first import" — even though the app has been installed for
     * months and the user has watch history. Get this backwards and the first
     * post-upgrade sync announces the user's entire existing catalogue as newly
     * added.
     *
     * Drives the real `finishSource` rather than a re-implementation of its
     * rule, so an inverted flag fails here.
     */
    @Test fun theFirstSyncAfterUpgradeIsStampedAsAnInitialImport() {
        val driver = upgradedFrom140()
        val db = YancoDb(driver)

        BulkContentWriter(driver, clock = { SYNC_ONE_AT }).finishSource("src-140")

        assertEquals(
            5L,
            db.contentFirstSeenQueries.countBySource("src-140").executeAsOne(),
            "every existing row should be stamped",
        )
        assertEquals(
            0L,
            db.contentFirstSeenQueries.countRecent().executeAsOne(),
            "...and every one of them as an initial import, not as a new title",
        )
        assertEquals(0, db.contentQueries.recentlyAddedVod(20).executeAsList().size)
    }

    /**
     * The sync after that surfaces genuinely new titles, and only those.
     *
     * Proves the stamp is durable across the DELETE + re-INSERT every sync
     * performs: the pre-existing VOD rows get fresh `created_at` values and
     * must still not count as new.
     */
    @Test fun theSecondSyncAfterUpgradeSurfacesOnlyGenuinelyNewTitles() {
        val driver = upgradedFrom140()
        val db = YancoDb(driver)

        BulkContentWriter(driver, clock = { SYNC_ONE_AT }).finishSource("src-140")

        // A later sync: the provider still has everything, plus one new film.
        driver.execute(
            null,
            "INSERT INTO content (id, source_id, type, title, stream_url, group_name, sort_order, created_at) " +
                "VALUES ('c-new', 'src-140', 'movie', 'Brand New Film', 'http://x/new', 'TURKISH YERLI DIZILER', 9, ?)",
            1,
        ) { bindLong(0, SYNC_TWO_AT) }
        BulkContentWriter(driver, clock = { SYNC_TWO_AT }).finishSource("src-140")

        assertEquals(
            listOf("Brand New Film"),
            db.contentQueries.recentlyAddedVod(20).executeAsList().map { it.title },
            "only the title that actually arrived between syncs belongs on the rail",
        )
    }

    /**
     * A watched live channel survives the sync that replaces its content row.
     *
     * `recent_channels` has no foreign key precisely so a sync's DELETE cannot
     * cascade it away; the read side inner-joins instead. This drives that whole
     * loop — record, replace the content row, read back.
     */
    @Test fun aWatchedChannelSurvivesTheSyncThatReplacesItsContentRow() {
        val driver = upgradedFrom140()
        val db = YancoDb(driver)

        db.recentChannelsQueries.recordWatch("c-live-1", SYNC_ONE_AT)
        assertEquals(
            listOf("TRT 1 HD"),
            db.recentChannelsQueries.recentChannels(10).executeAsList().map { it.title },
        )

        // What a sync does to that row: delete it, then write it back with the
        // same deterministic id and a new created_at.
        driver.execute(null, "DELETE FROM content WHERE id = 'c-live-1'", 0)
        driver.execute(
            null,
            "INSERT INTO content (id, source_id, type, title, stream_url, sort_order, created_at) " +
                "VALUES ('c-live-1', 'src-140', 'live', 'TRT 1 HD', 'http://x/trt1', 0, ?)",
            1,
        ) { bindLong(0, SYNC_TWO_AT) }

        assertEquals(
            listOf("TRT 1 HD"),
            db.recentChannelsQueries.recentChannels(10).executeAsList().map { it.title },
            "a re-synced channel must keep its place in the list",
        )

        // A channel the provider drops for good disappears from the rail
        // instead of rendering a tile that plays nothing.
        driver.execute(null, "DELETE FROM content WHERE id = 'c-live-1'", 0)
        assertEquals(0, db.recentChannelsQueries.recentChannels(10).executeAsList().size)
        assertEquals(
            1L,
            db.recentChannelsQueries.countAll().executeAsOne(),
            "the row itself stays — the provider may restore the channel, and the read side already filters",
        )
    }

    // ---- harness ---------------------------------------------------------

    private fun upgradedFrom140(seeded: Boolean = true): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        v11Schema().forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA user_version = 11;", 0)
        if (seeded) v11SeedRows().forEach { driver.execute(null, it, 0) }
        // Unpinned on purpose: when 14.sqm lands this test starts covering it
        // without an edit, and the structural comparison above starts asking
        // whether that hop kept upgraders and fresh installs in step.
        YancoDb.Schema.migrate(driver, oldVersion = 11, newVersion = YancoDb.Schema.version)
        return driver
    }

    /** `sqlite_master` as name -> normalised SQL, for structural comparison. */
    private fun schemaObjects(driver: JdbcSqliteDriver): Map<String, String> = driver.executeQuery(
        null,
        "SELECT type, name, COALESCE(sql, '') FROM sqlite_master ORDER BY type, name",
        { cursor ->
            val out = mutableMapOf<String, String>()
            while (cursor.next().value) {
                val type = cursor.getString(0).orEmpty()
                val name = cursor.getString(1).orEmpty()
                out["$type:$name"] = normalizeSql(cursor.getString(2).orEmpty())
            }
            QueryResult.Value(out)
        },
        0,
    ).value

    private companion object {
        const val SYNC_ONE_AT = 1_770_000_000_000L
        const val SYNC_TWO_AT = 1_770_003_600_000L

        /**
         * Comments and whitespace are not schema. `.sq` files carry inline
         * documentation that SQLite stores verbatim in `sqlite_master`, and a
         * `.sqm` writing the same table without those comments is not a
         * difference anyone can observe at runtime.
         */
        /**
         * A table's columns as a SORTED set, so ordering does not count.
         *
         * `ALTER TABLE ADD COLUMN` can only append, so a column that a `.sq`
         * file declares in the middle of a table lands at the end on every
         * upgraded device. `sources.expires_at` is the live example: `11.sqm`
         * appends it, `Sources.sq` declares it between `auto_sync_on_start`
         * and `created_at`, and no migration can reconcile the two short of
         * rebuilding the table.
         *
         * That is safe HERE, and it is worth writing down why rather than
         * waving at it, because the mapping in the generated code is
         * positional — `cursor.getLong(19)` and friends — which is exactly
         * the shape that a reordered table would corrupt:
         *
         *  * SQLDelight expands `SELECT *` into an explicit, named column list
         *    at codegen time (`SELECT sources.id, sources.name, ...`), so
         *    those ordinals index the PROJECTION, not the table. Physical
         *    order cannot reach them.
         *  * No `INSERT INTO <table> VALUES (...)` without a column list
         *    exists in this repo — that form is the other way physical order
         *    becomes load-bearing.
         *
         * Both were verified before this exemption was written. If either ever
         * stops holding, this method is the wrong tool and the assertion above
         * should go back to comparing full SQL.
         */
        fun columnsOf(createSql: String): List<String> {
            val open = createSql.indexOf('(')
            val close = createSql.lastIndexOf(')')
            if (open < 0 || close <= open) return emptyList()
            val body = createSql.substring(open + 1, close)
            val parts = mutableListOf<String>()
            val buf = StringBuilder()
            var depth = 0
            for (ch in body) {
                when {
                    ch == '(' -> {
                        depth++
                        buf.append(ch)
                    }
                    ch == ')' -> {
                        depth--
                        buf.append(ch)
                    }
                    // Only a top-level comma separates column definitions; the
                    // ones inside CHECK(type IN ('live', 'movie', 'series')) do not.
                    ch == ',' && depth == 0 -> {
                        parts.add(buf.toString().trim())
                        buf.clear()
                    }
                    else -> buf.append(ch)
                }
            }
            if (buf.isNotBlank()) parts.add(buf.toString().trim())
            return parts.sorted()
        }

        fun normalizeSql(sql: String): String = sql
            .replace(Regex("--[^\\n]*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        /**
         * The 1.4.0 schema, extracted from the `.sq` files at commit `f7f38a7`.
         * Regenerate rather than edit by hand if it ever needs to move.
         */
        fun v11Schema(): List<String> = listOf(
            // ---- BackupMetadata.sq
            """
            CREATE TABLE backup_metadata (
                id TEXT PRIMARY KEY NOT NULL,
                file_uri TEXT,
                label TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                checksum TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                record_counts TEXT,
                notes TEXT,
                created_at INTEGER NOT NULL 
            )
            """.trimIndent(),
            "CREATE INDEX idx_backup_metadata_created_at ON backup_metadata(created_at DESC)",
            // ---- Content.sq
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
            )
            """.trimIndent(),
            "CREATE INDEX idx_content_source ON content(source_id)",
            "CREATE INDEX idx_content_type ON content(type)",
            "CREATE INDEX idx_content_group ON content(group_name)",
            "CREATE INDEX idx_content_vod_created_at ON content(created_at DESC) WHERE type IN ('movie', 'series')",
            "CREATE INDEX idx_content_clean_title ON content(clean_title)",
            "CREATE INDEX idx_content_sort_order ON content(source_id, type, sort_order)",
            "CREATE INDEX idx_content_type_tvg ON content(type, tvg_id)",
            """
            CREATE VIRTUAL TABLE content_fts USING fts4(
                content_id,
                title,
                clean_title,
                group_name,
                tokenize=unicode61
            )
            """.trimIndent(),
            """
            CREATE TRIGGER content_ai AFTER INSERT ON content BEGIN
                INSERT INTO content_fts (content_id, title, clean_title, group_name)
                VALUES (new.id, new.title, new.clean_title, new.group_name);
            END
            """.trimIndent(),
            // ---- Downloads.sq
            """
            CREATE TABLE downloads (
                id TEXT PRIMARY KEY NOT NULL,
                content_id TEXT,
                episode_id TEXT,
                title TEXT NOT NULL,
                stream_url TEXT NOT NULL,
                file_path TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('queued', 'downloading', 'paused', 'completed', 'failed', 'cancelled')),
                queued_at INTEGER NOT NULL,
                started_at INTEGER,
                completed_at INTEGER,
                bytes_downloaded INTEGER NOT NULL DEFAULT 0,
                bytes_total INTEGER,
                error TEXT,
                resumable INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX idx_downloads_status ON downloads(status)",
            "CREATE INDEX idx_downloads_queued_at ON downloads(queued_at DESC)",
            // ---- EpgProgrammes.sq
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
            )
            """.trimIndent(),
            "CREATE INDEX idx_epg_channel_time ON epg_programmes(channel_tvg_id, start_time)",
            "CREATE INDEX idx_epg_channel_end_start ON epg_programmes(channel_tvg_id, end_time, start_time)",
            "CREATE INDEX idx_epg_time_window ON epg_programmes(end_time, start_time)",
            "CREATE INDEX idx_epg_source ON epg_programmes(source_id)",
            "CREATE INDEX idx_epg_end_time ON epg_programmes(end_time)",
            // ---- Episodes.sq
            """
            CREATE TABLE episodes (
                id TEXT PRIMARY KEY NOT NULL,
                content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
                season_number INTEGER,
                episode_number INTEGER,
                title TEXT,
                stream_url TEXT NOT NULL,
                duration INTEGER
            )
            """.trimIndent(),
            "CREATE INDEX idx_episodes_content ON episodes(content_id, season_number, episode_number)",
            // ---- FavoriteLists.sq
            """
            CREATE TABLE favorite_lists (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                is_default INTEGER NOT NULL DEFAULT 0 CHECK(is_default IN (0, 1)),
                created_at INTEGER NOT NULL, 
                updated_at INTEGER NOT NULL  
            )
            """.trimIndent(),
            "CREATE INDEX idx_favorite_lists_sort_order ON favorite_lists(sort_order ASC)",
            """
            INSERT OR IGNORE INTO favorite_lists
                (id, name, sort_order, is_default, created_at, updated_at)
            VALUES
                ('default', 'Favorites', 0, 1, 0, 0)
            """.trimIndent(),
            // ---- Favorites.sq
            """
            CREATE TABLE favorites (
                id TEXT PRIMARY KEY NOT NULL,
                content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
                list_id TEXT REFERENCES favorite_lists(id) ON DELETE CASCADE,
                added_at INTEGER NOT NULL 
            )
            """.trimIndent(),
            "CREATE INDEX idx_favorites_content ON favorites(content_id)",
            "CREATE INDEX idx_favorites_list_id ON favorites(list_id)",
            // ---- GroupPreferences.sq
            """
            CREATE TABLE group_preferences (
                id TEXT PRIMARY KEY NOT NULL,
                content_type TEXT NOT NULL CHECK(content_type IN ('live', 'movie', 'series')),
                group_key TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                is_hidden INTEGER NOT NULL DEFAULT 0,
                is_pinned INTEGER NOT NULL DEFAULT 0,
                custom_name TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE UNIQUE INDEX idx_group_prefs_type_key
                ON group_preferences(content_type, group_key)
            """.trimIndent(),
            // ---- Parental.sq
            """
            CREATE TABLE locked_channels (
                content_id TEXT PRIMARY KEY NOT NULL,
                locked_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE hidden_channels (
                content_id TEXT PRIMARY KEY NOT NULL,
                hidden_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE channel_overrides (
                content_id TEXT PRIMARY KEY NOT NULL,
                custom_name TEXT,
                custom_logo_url TEXT,
                custom_number INTEGER,
                custom_group TEXT,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX idx_channel_overrides_number ON channel_overrides(custom_number)",
            // ---- RecordingSchedules.sq
            """
            CREATE TABLE recording_schedules (
                id TEXT PRIMARY KEY NOT NULL,
                content_id TEXT REFERENCES content(id) ON DELETE SET NULL,
                programme_id TEXT REFERENCES epg_programmes(id) ON DELETE SET NULL,
                title TEXT NOT NULL,
                stream_url TEXT NOT NULL,
                scheduled_start INTEGER NOT NULL, 
                scheduled_end INTEGER NOT NULL,   
                state TEXT NOT NULL CHECK(state IN (
                    'scheduled', 'armed', 'firing',
                    'completed', 'failed', 'cancelled', 'missed'
                )),
                recording_id TEXT REFERENCES recordings(id) ON DELETE SET NULL,
                error TEXT,
                created_at INTEGER NOT NULL, 
                updated_at INTEGER NOT NULL, 
                series_key TEXT
            )
            """.trimIndent(),
            """
            CREATE INDEX idx_recording_schedules_scheduled_start
                ON recording_schedules(scheduled_start ASC)
            """.trimIndent(),
            """
            CREATE INDEX idx_recording_schedules_state
                ON recording_schedules(state)
            """.trimIndent(),
            """
            CREATE INDEX idx_recording_schedules_series_key
                ON recording_schedules(series_key)
            """.trimIndent(),
            // ---- Recordings.sq
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
                error TEXT,
                format TEXT
            )
            """.trimIndent(),
            "CREATE INDEX idx_recordings_status ON recordings(status)",
            "CREATE INDEX idx_recordings_started_at ON recordings(started_at DESC)",
            // ---- Reminders.sq
            """
            CREATE TABLE reminders (
                id TEXT PRIMARY KEY NOT NULL,
                programme_id TEXT NOT NULL,
                channel_tvg_id TEXT NOT NULL,
                title TEXT NOT NULL,
                start_time INTEGER NOT NULL,
                end_time INTEGER NOT NULL,
                lead_seconds INTEGER NOT NULL DEFAULT 0,
                fire_at INTEGER NOT NULL,
                fired INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE INDEX idx_reminders_fire_at_unfired
                ON reminders(fire_at) WHERE fired = 0
            """.trimIndent(),
            "CREATE INDEX idx_reminders_programme ON reminders(programme_id)",
            // ---- Settings.sq
            """
            CREATE TABLE settings (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL
            )
            """.trimIndent(),
            // ---- Sources.sq
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
                auto_sync_on_start INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX idx_sources_priority ON sources(priority ASC)",
            "CREATE INDEX idx_sources_epg_priority ON sources(epg_priority DESC)",
            // ---- SubtitleCache.sq
            """
            CREATE TABLE subtitle_cache (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                content_id TEXT NOT NULL,
                episode_id TEXT,
                language TEXT NOT NULL,
                file_path TEXT NOT NULL,
                file_id INTEGER,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE UNIQUE INDEX idx_subtitle_cache_unique
                ON subtitle_cache(content_id, COALESCE(episode_id, ''), language)
            """.trimIndent(),
            """
            CREATE INDEX idx_subtitle_cache_lookup
                ON subtitle_cache(content_id, language)
            """.trimIndent(),
            // ---- TmdbCache.sq
            """
            CREATE TABLE tmdb_cache (
                content_id TEXT PRIMARY KEY NOT NULL,
                tmdb_id INTEGER,
                tmdb_type TEXT CHECK(tmdb_type IN ('movie', 'tv')),
                payload_json TEXT,
                miss INTEGER NOT NULL DEFAULT 0,
                fetched_at INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX idx_tmdb_cache_fetched_at ON tmdb_cache(fetched_at)",
            // ---- WatchHistory.sq
            """
            CREATE TABLE watch_history (
                id TEXT PRIMARY KEY NOT NULL,
                content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
                episode_id TEXT REFERENCES episodes(id),
                position_seconds INTEGER NOT NULL DEFAULT 0, 
                duration_seconds INTEGER,                    
                watched_at INTEGER NOT NULL                  
            )
            """.trimIndent(),
            "CREATE INDEX idx_watch_history_content ON watch_history(content_id)",
            "CREATE INDEX idx_watch_history_watched_at ON watch_history(watched_at DESC)",
            "CREATE INDEX idx_watch_history_episode ON watch_history(episode_id) WHERE episode_id IS NOT NULL",
        )

        /** One realistic row per table the upgrade or the MK.35 rails touch. */
        fun v11SeedRows(): List<String> = listOf(
            "INSERT INTO sources (id, name, type, url, user_agent, is_active, priority, channel_count, created_at, updated_at) " +
                "VALUES ('src-140', 'Living room provider', 'xtream', 'http://provider.example/x', 'VLC/3.0', 1, 0, 5, 1750000000000, 1750000000000)",
            "INSERT INTO content (id, source_id, type, title, clean_title, group_name, stream_url, tvg_id, sort_order, created_at) " +
                "VALUES ('c-live-1', 'src-140', 'live', 'TRT 1 HD', 'TRT 1', 'TURKISH', 'http://x/trt1', 'trt1.tr', 0, 1750000000000)",
            "INSERT INTO content (id, source_id, type, title, clean_title, group_name, stream_url, tvg_id, sort_order, created_at) " +
                "VALUES ('c-live-2', 'src-140', 'live', 'MBC 1', 'MBC 1', 'ARABIC', 'http://x/mbc1', 'mbc1.sa', 1, 1750000000000)",
            "INSERT INTO content (id, source_id, type, title, clean_title, group_name, stream_url, sort_order, created_at) " +
                "VALUES ('c-movie-1', 'src-140', 'movie', 'Ayla', 'Ayla', 'TURKISH YERLI DIZILER', 'http://x/ayla', 2, 1750000000000)",
            "INSERT INTO content (id, source_id, type, title, clean_title, group_name, stream_url, sort_order, created_at) " +
                "VALUES ('c-movie-2', 'src-140', 'movie', 'The Old Film', 'The Old Film', 'ADULT', 'http://x/old', 3, 1750000000000)",
            "INSERT INTO content (id, source_id, type, title, clean_title, group_name, stream_url, sort_order, created_at) " +
                "VALUES ('c-series-1', 'src-140', 'series', 'Dirilis Ertugrul', 'Dirilis Ertugrul', " +
                "'TURKISH YERLI DIZILER', 'http://x/dirilis', 4, 1750000000000)",
            "INSERT INTO watch_history (id, content_id, position_seconds, duration_seconds, watched_at) " +
                "VALUES ('wh-1', 'c-movie-1', 1820, 7200, 1750000500000)",
            "INSERT INTO favorites (id, content_id, list_id, added_at) VALUES ('fav-1', 'c-live-1', 'default', 1750000600000)",
            "INSERT INTO group_preferences (id, content_type, group_key, sort_order, is_hidden, is_pinned, created_at) " +
                "VALUES ('gp-1', 'series', 'TURKISH YERLI DIZILER', 0, 0, 1, 1750000700000)",
            "INSERT INTO group_preferences (id, content_type, group_key, sort_order, is_hidden, is_pinned, created_at) " +
                "VALUES ('gp-2', 'movie', 'ADULT', 0, 1, 0, 1750000700000)",
            "INSERT INTO settings (key, value) VALUES ('theme', 'midnight')",
        )
    }
}
