package com.yancotv.shared.epg

import com.yancotv.shared.parsers.XmltvProgramme
import com.yancotv.shared.sources.testDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MK.39.1 — the guide's channel-name index, written with the programmes it
 * points at.
 *
 * The index is a map from a normalised channel name to a `tvg_id`, and every
 * `tvg_id` it names must exist in `epg_programmes` or a viewer gets a guide
 * for a channel that is no longer in the file. Those two tables are therefore
 * written and cleared **inside one transaction**, and that is what these tests
 * hold: not that a row can be inserted, but that a refresh cannot leave the
 * two out of step, and that a failed refresh leaves neither behind.
 */
class BulkEpgWriterNameIndexTest {

    private fun programme(channelId: String, start: Long) = XmltvProgramme(
        channelId = channelId,
        title = "Match of the Day",
        description = null,
        // XMLTV epoch SECONDS, not ms — see the schema comment on this column.
        startTime = start,
        endTime = start + 3_600L,
        category = null,
        iconUrl = null,
    )

    @Test
    fun `the index is written inside the streaming transaction`() {
        val (db, driver) = testDatabase().let { it.db to it.driver }
        val session = BulkEpgWriter(driver).openSession()

        session.begin()
        session.writeBatch("guide-a", null, listOf(programme("SkySportsF1.uk", 1_780_000_000L)))
        session.writeChannelNames("guide-a", mapOf("skysportsf1" to "SkySportsF1.uk"))
        session.commit()

        assertEquals(1L, db.epgChannelNamesQueries.count().executeAsOne())
        val resolved = db.epgChannelNamesQueries
            .resolveKeys(listOf("skysportsf1"), "guide-a")
            .executeAsList()
        assertEquals("SkySportsF1.uk", resolved.single().tvg_id)
    }

    @Test
    fun `a rolled-back refresh leaves no index behind`() {
        // The failure this guards: a refresh that dies mid-write leaving keys
        // that resolve to programmes which were rolled away. The guide would
        // then show one channel's listing under another's name, and nothing
        // on screen would say so.
        val (db, driver) = testDatabase().let { it.db to it.driver }
        val writer = BulkEpgWriter(driver)

        val ok = writer.openSession()
        ok.begin()
        ok.writeBatch("guide-a", null, listOf(programme("BBCOne.uk", 1_780_000_000L)))
        ok.writeChannelNames("guide-a", mapOf("bbcone" to "BBCOne.uk"))
        ok.commit()
        assertEquals(1L, db.epgChannelNamesQueries.count().executeAsOne())

        val doomed = writer.openSession()
        doomed.begin()
        doomed.writeChannelNames("guide-a", mapOf("itv1" to "ITV1.uk"))
        doomed.rollback()

        // Back to exactly the state before, not to empty and not to both.
        assertEquals(1L, db.epgChannelNamesQueries.count().executeAsOne())
        val resolved = db.epgChannelNamesQueries.resolveKeys(listOf("bbcone", "itv1"), "guide-a").executeAsList()
        assertEquals(listOf("BBCOne.uk"), resolved.map { it.tvg_id })
    }

    @Test
    fun `a refresh REPLACES the index rather than adding to it`() {
        // A channel dropped from the guide must lose its key, or it keeps
        // resolving forever to programmes that no longer exist.
        val (db, driver) = testDatabase().let { it.db to it.driver }
        val writer = BulkEpgWriter(driver)

        writer.openSession().apply {
            begin()
            writeChannelNames("guide-a", mapOf("bbcone" to "BBCOne.uk", "itv1" to "ITV1.uk"))
            commit()
        }
        assertEquals(2L, db.epgChannelNamesQueries.count().executeAsOne())

        writer.openSession().apply {
            begin()
            writeChannelNames("guide-a", mapOf("bbcone" to "BBCOne.uk"))
            commit()
        }
        assertEquals(1L, db.epgChannelNamesQueries.count().executeAsOne())
    }

    @Test
    fun `two guides naming the same channel is redundancy, and the caller's own guide sorts first`() {
        // Migration 18's whole point: keying the index globally meant adding a
        // second EPG source REDUCED coverage, because a name claimed twice was
        // treated as a clash. Measured then: a 756-channel guide took the
        // index from 3,893 keys to 3,869.
        val (db, driver) = testDatabase().let { it.db to it.driver }
        val session = BulkEpgWriter(driver).openSession()
        session.begin()
        session.writeChannelNames("guide-a", mapOf("bbcone" to "a.BBCOne"))
        session.writeChannelNames("guide-b", mapOf("bbcone" to "b.BBCOne"))
        session.commit()

        assertEquals(2L, db.epgChannelNamesQueries.count().executeAsOne())
        assertEquals(
            "b.BBCOne",
            db.epgChannelNamesQueries.resolveKeys(listOf("bbcone"), "guide-b").executeAsList().first().tvg_id,
        )
        assertEquals(
            "a.BBCOne",
            db.epgChannelNamesQueries.resolveKeys(listOf("bbcone"), "guide-a").executeAsList().first().tvg_id,
        )
    }

    @Test
    fun `an empty index writes nothing and does not fail the refresh`() {
        // A guide with no usable names is ordinary, not an error.
        val (db, driver) = testDatabase().let { it.db to it.driver }
        val session = BulkEpgWriter(driver).openSession()
        session.begin()
        session.writeBatch("guide-a", null, listOf(programme("X.uk", 1_780_000_000L)))
        session.writeChannelNames("guide-a", emptyMap())
        session.commit()

        assertEquals(0L, db.epgChannelNamesQueries.count().executeAsOne())
        assertTrue(db.epgProgrammesQueries.byId("X.uk|1780000000|guide-a").executeAsOneOrNull() != null)
    }
}
