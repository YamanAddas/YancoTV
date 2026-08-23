package com.yancotv.shared.epg

import com.yancotv.shared.parsers.XmltvProgramme
import com.yancotv.shared.sources.testDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests for [BulkEpgWriter] — both the one-shot [replaceAll] path used by
 * the legacy in-memory refresh and the streaming [BulkEpgWriter.Session]
 * used by the Android importer.
 *
 * Coverage focuses on:
 *  - Row correctness: column binding order + natural-key id format
 *  - Batching boundaries: the "short final batch" code path (< 80 rows)
 *  - Atomicity: rollback on mid-stream failure leaves old data intact
 *  - Session lifecycle: begin/writeBatch/commit + begin/rollback + error
 *    paths that must not leave a hanging transaction
 */
class BulkEpgWriterTest {
    @Test fun replaceAll_writesAllRowsAndReportsCounts() = runTest {
        val (db, driver) = testDbPair()
        val writer = BulkEpgWriter(driver)

        val batch =
            BulkEpgWriter.ProgrammeBatch(
                sourceKey = "src-A",
                sourceIdForDb = "src-A",
                programmes =
                (0 until 210).map { i ->
                    XmltvProgramme(
                        channelId = "ch${i % 10}",
                        title = "P$i",
                        startTime = 1_000_000L + i * 1800L,
                        endTime = 1_000_000L + (i + 1) * 1800L,
                    )
                },
            )
        val result = writer.replaceAll(listOf(batch), lastRefreshedMs = 42L)

        assertEquals(210, result.rowsWritten)
        assertEquals(10, result.channels)
        assertEquals(210L, db.epgProgrammesQueries.countAll().executeAsOne())
        // Natural key format — `channelId|startTime|sourceKey`. Spot-check
        // the first row for channel 0.
        val firstChannel =
            db.epgProgrammesQueries
                .forChannelRange("ch0", 0L, 2_000_000L)
                .executeAsList()
        assertTrue(firstChannel.isNotEmpty())
        assertEquals("ch0|1000000|src-A", firstChannel.first().id)
        assertEquals("src-A", firstChannel.first().source_id)
    }

    @Test fun replaceAll_handlesShortFinalBatchSize() = runTest {
        val (db, driver) = testDbPair()
        val writer = BulkEpgWriter(driver)
        // 85 rows — 80 full + 5 trailing → exercises buildInsertSql(rows=5)
        val batch =
            BulkEpgWriter.ProgrammeBatch(
                sourceKey = "src-A",
                sourceIdForDb = null,
                programmes =
                (0 until 85).map { i ->
                    XmltvProgramme(channelId = "c1", title = "P$i", startTime = 1_000L + i, endTime = 2_000L + i)
                },
            )
        val result = writer.replaceAll(listOf(batch))
        assertEquals(85, result.rowsWritten)
        assertEquals(85L, db.epgProgrammesQueries.countAll().executeAsOne())
    }

    @Test fun replaceAll_emptyInputClearsTableAndStampsTimestamp() = runTest {
        val (db, driver) = testDbPair()
        val writer = BulkEpgWriter(driver)
        // Seed a row so we can verify the delete fires.
        writer.replaceAll(
            listOf(
                BulkEpgWriter.ProgrammeBatch(
                    "src-A",
                    null,
                    listOf(XmltvProgramme(channelId = "c1", title = "A", startTime = 1L, endTime = 2L)),
                ),
            ),
        )
        assertEquals(1L, db.epgProgrammesQueries.countAll().executeAsOne())

        val result = writer.replaceAll(emptyList(), lastRefreshedMs = 999L)
        assertEquals(0, result.rowsWritten)
        assertEquals(0L, db.epgProgrammesQueries.countAll().executeAsOne())
        assertEquals("999", db.settingsQueries.get(EpgRepository.LAST_REFRESHED_KEY).executeAsOne())
    }

    @Test fun replaceAll_nullSourceIdMeansGlobal() = runTest {
        val (db, driver) = testDbPair()
        val writer = BulkEpgWriter(driver)
        writer.replaceAll(
            listOf(
                BulkEpgWriter.ProgrammeBatch(
                    sourceKey = "global",
                    sourceIdForDb = null,
                    programmes = listOf(XmltvProgramme(channelId = "c1", title = "Show", startTime = 100L, endTime = 200L)),
                ),
            ),
        )
        val row = db.epgProgrammesQueries.forChannelRange("c1", 0L, 300L).executeAsOne()
        assertNull(row.source_id, "global EPG rows must store source_id NULL")
    }

    @Test fun session_beginWriteCommit_roundtrips() = runTest {
        val (db, driver) = testDbPair()
        val writer = BulkEpgWriter(driver)
        val session = writer.openSession()
        session.begin()
        val progs = (0 until 120).map { XmltvProgramme(channelId = "c$it", title = "T$it", startTime = 1L, endTime = 2L) }
        session.writeBatch("src-A", "src-A", progs)
        assertEquals(120, session.rowsWritten)
        assertEquals(120, session.channelCount)
        session.commit(lastRefreshedMs = 77L)

        assertEquals(120L, db.epgProgrammesQueries.countAll().executeAsOne())
        assertEquals("77", db.settingsQueries.get(EpgRepository.LAST_REFRESHED_KEY).executeAsOne())
    }

    @Test fun session_closedOnAnotherThreadIsReported() = runTest {
        // MB-356 regression. Android's SQLiteSession is thread-local, so a
        // transaction begun on one thread and committed on another leaks the
        // primary connection permanently and NEITHER statement errors -- the
        // app just stops persisting writes until it is restarted. The session
        // deliberately spans a streaming import whose caller suspends between
        // batches, so a thread migration is legal and silent. This asserts the
        // detector speaks up; the hazard itself is removed by running the
        // import on a single-thread dispatcher (AndroidEpgImporter).
        val (_, driver) = testDbPair()
        val logged = mutableListOf<String>()
        val capturing =
            object : com.yancotv.shared.logger.Logger {
                override fun info(msg: String) {
                    logged.add(msg)
                }
                override fun warn(msg: String) {
                    logged.add(msg)
                }
                override fun error(msg: String) {
                    logged.add(msg)
                }
            }
        val writer = BulkEpgWriter(driver, capturing)
        val session = writer.openSession()
        session.begin()
        session.writeBatch("src-A", "src-A", listOf(XmltvProgramme(channelId = "c1", title = "T", startTime = 1L, endTime = 2L)))

        // commit from a DIFFERENT thread, which is what a coroutine resume
        // onto another Dispatchers.IO worker does in production
        val other = Thread { session.commit(lastRefreshedMs = 5L) }
        other.start()
        other.join()

        assertTrue(
            logged.any { it.contains("MB-356") && it.contains("COMMIT") },
            "closing the session on another thread must be reported; logged=$logged",
        )
    }

    @Test fun session_rollbackRestoresOldData() = runTest {
        val (db, driver) = testDbPair()
        val writer = BulkEpgWriter(driver)
        // Seed existing state — a prior successful refresh.
        writer.replaceAll(
            listOf(
                BulkEpgWriter.ProgrammeBatch(
                    "src-A",
                    "src-A",
                    listOf(XmltvProgramme(channelId = "old", title = "Old show", startTime = 0L, endTime = 1L)),
                ),
            ),
        )
        assertEquals(1L, db.epgProgrammesQueries.countAll().executeAsOne())

        // Start a new session, write some rows, then rollback.
        val session = writer.openSession()
        session.begin()
        // After begin(), the table is DELETED — the whole point of the
        // all-or-nothing swap is that commit makes it atomic and rollback
        // restores pre-begin state.
        assertEquals(0L, db.epgProgrammesQueries.countAll().executeAsOne())
        session.writeBatch(
            "src-A",
            "src-A",
            listOf(XmltvProgramme(channelId = "new", title = "New show", startTime = 10L, endTime = 20L)),
        )
        session.rollback()

        // Old data must be back.
        assertEquals(1L, db.epgProgrammesQueries.countAll().executeAsOne())
        val row = db.epgProgrammesQueries.forChannelRange("old", -1L, 10L).executeAsOne()
        assertEquals("Old show", row.title)
    }

    @Test fun session_beginTwiceFails() = runTest {
        val (_, driver) = testDbPair()
        val writer = BulkEpgWriter(driver)
        val session = writer.openSession()
        session.begin()
        assertFails { session.begin() }
        session.rollback()
    }

    @Test fun session_writeBeforeBeginFails() = runTest {
        val (_, driver) = testDbPair()
        val writer = BulkEpgWriter(driver)
        val session = writer.openSession()
        assertFails {
            session.writeBatch(
                "src-A",
                null,
                listOf(XmltvProgramme(channelId = "c1", title = "X", startTime = 1L, endTime = 2L)),
            )
        }
    }

    @Test fun session_rollbackWithoutBeginIsNoOp() = runTest {
        val (_, driver) = testDbPair()
        val writer = BulkEpgWriter(driver)
        val session = writer.openSession()
        // No assertion on side effects — just must not throw.
        session.rollback()
    }

    /**
     * MK.23.D.5 — EPG re-sync vs recording_schedules FK SET NULL.
     *
     * `recording_schedules.programme_id` declares
     * `REFERENCES epg_programmes(id) ON DELETE SET NULL` (3.sqm:30).
     * Intent: programme rows churn during EPG refresh, but a user's
     * armed schedule must survive — at worst the link goes null so the
     * schedule's title-snapshot still drives the firing decision.
     *
     * EPG re-sync calls `DELETE FROM epg_programmes` to wipe the prior
     * snapshot before re-writing. Without `ON DELETE SET NULL`, the
     * default FK behaviour (ON DELETE NO ACTION) would FAIL the DELETE
     * with a constraint violation, breaking every EPG refresh after
     * the user arms a schedule from EPG long-press.
     *
     * Pin both halves of the contract:
     *   1. The `DELETE FROM epg_programmes` succeeds (no FK throw).
     *   2. The schedule row survives.
     *   3. The schedule's `programme_id` is NULL afterwards.
     */
    @Test fun epgReSync_setsScheduleProgrammeIdNull_andSurvivesSchedule() = runTest {
        val (db, driver) = testDbPair()
        val writer = BulkEpgWriter(driver)

        // Seed programme P that the schedule will reference.
        writer.replaceAll(
            listOf(
                BulkEpgWriter.ProgrammeBatch(
                    sourceKey = "src-A",
                    sourceIdForDb = "src-A",
                    programmes =
                    listOf(
                        XmltvProgramme(
                            channelId = "ch-1",
                            title = "Live game",
                            startTime = 1_000_000L,
                            endTime = 1_010_000L,
                        ),
                    ),
                ),
            ),
        )

        // Natural key from BulkEpgWriter is `channelId|startTime|sourceKey`.
        val programmeId =
            db.epgProgrammesQueries
                .forChannelRange("ch-1", 0L, 2_000_000L)
                .executeAsList()
                .single()
                .id

        // Seed a recording_schedule referencing programme P.
        db.recordingSchedulesQueries.insert(
            id = "sched-1",
            content_id = null,
            programme_id = programmeId,
            title = "Live game",
            stream_url = "http://stream/x",
            scheduled_start = 1_000_000L,
            scheduled_end = 1_010_000L,
            state = "armed",
            error = null,
            created_at = 1L,
            updated_at = 1L,
            series_key = null,
        )
        assertEquals(
            programmeId,
            db.recordingSchedulesQueries.selectById("sched-1").executeAsOne().programme_id,
            "schedule must initially link to programme",
        )

        // Run an EPG re-sync via replaceAll with empty programme set —
        // this triggers the internal `DELETE FROM epg_programmes`
        // (mirrors a provider returning no programmes for this source).
        // Without ON DELETE SET NULL on the FK, this DELETE would
        // throw a constraint violation because the schedule references
        // the programme.
        writer.replaceAll(
            listOf(
                BulkEpgWriter.ProgrammeBatch(
                    sourceKey = "src-A",
                    sourceIdForDb = "src-A",
                    programmes = emptyList(),
                ),
            ),
        )

        // Programme is gone.
        assertEquals(0L, db.epgProgrammesQueries.countAll().executeAsOne())

        // Schedule survives.
        val schedule = db.recordingSchedulesQueries.selectById("sched-1").executeAsOneOrNull()
        assertTrue(schedule != null, "schedule row must NOT be cascade-deleted by EPG re-sync")
        // programme_id was set to NULL via ON DELETE SET NULL.
        assertNull(
            schedule!!.programme_id,
            "ON DELETE SET NULL must null out programme_id when its programme is deleted",
        )
        // Title snapshot survives — the schedule still knows what to
        // record even without the programme link.
        assertEquals("Live game", schedule.title)
    }

    private fun testDbPair(): Pair<com.yancotv.shared.db.YancoDb, app.cash.sqldelight.db.SqlDriver> {
        val pair = testDatabase()
        // Seed a sources row so epg_programmes rows with non-null source_id
        // don't fail the FK. Every test in this file uses sourceIdForDb =
        // "src-A" (or null for the global EPG case); seeding eagerly keeps
        // the tests focused on BulkEpgWriter behavior rather than fixtures.
        pair.db.sourcesQueries.insert(
            id = "src-A",
            name = "src-A",
            type = "m3u_url",
            url = "http://host/a.m3u",
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
            created_at = 0L,
            updated_at = 0L,
        )
        return pair.db to pair.driver
    }
}
