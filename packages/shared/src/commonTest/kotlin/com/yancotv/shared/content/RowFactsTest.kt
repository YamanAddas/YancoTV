package com.yancotv.shared.content

import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RowFactsTest {

    private fun row(
        id: String = "1",
        title: String,
        metadata: String? = null,
    ) = ContentItem(
        id = id,
        sourceId = "s",
        type = ContentType.MOVIE,
        title = title,
        cleanTitle = cleanTitle(title),
        groupName = "TR - FILM",
        streamUrl = "http://example/1.mkv",
        metadataJson = metadata,
        sortOrder = 0,
        createdAt = 0,
    )

    /**
     * The bug this was written for: `cleanTitle` strips bracketed content,
     * which is how `[HD]` and `(MULTI)` are removed — and takes `(2019)`
     * with it. Reading the year off `displayTitle` found it on one movie
     * per hundred; off the raw title, a hundred per hundred.
     */
    @Test
    fun `year comes from the raw title which the cleaner has stripped`() {
        val item = row(title = "Yaz Işığı (2024)")
        // Precondition: the cleaner really does remove it.
        assertEquals("Yaz Işığı", item.displayTitle)
        assertEquals(2024, rowFacts(listOf(item)).single().year)
    }

    @Test
    fun `no year in the title yields null rather than a guess`() {
        assertNull(rowFacts(listOf(row(title = "Veda Mektubu"))).single().year)
    }

    /**
     * MK.36.4 — the reason the caller cannot just do `releaseDate.take(4)`.
     *
     * Providers send all three of these for the same field. `take(4)` is
     * right for one of them and prints "01/0" for another, which is what the
     * Android preview pane was rendering before it moved onto `rowFacts`.
     */
    @Test
    fun `year is read from every release-date shape providers send`() {
        val shapes = mapOf(
            "2023-04-01" to 2023,
            "2023" to 2023,
            "01/04/2023" to 2023,
        )
        for ((raw, expected) in shapes) {
            val item = row(title = "No Year In Title", metadata = """{"releaseDate":"$raw"}""")
            assertEquals(expected, rowFacts(listOf(item)).single().year, "releaseDate=$raw")
        }
    }

    /** A date that carries no plausible year yields null, not a fragment. */
    @Test
    fun `an unparseable release date yields no year`() {
        val item = row(title = "No Year In Title", metadata = """{"releaseDate":"n/a"}""")
        assertNull(rowFacts(listOf(item)).single().year)
    }

    @Test
    fun `rating is read from the provider metadata`() {
        val facts = rowFacts(
            listOf(row(title = "A", metadata = """{"rating":"7.5","streamId":21}""")),
        ).single()
        assertEquals(7.5, facts.rating)
    }

    /**
     * Providers write `"0"` for "we have no rating". Printing that as a
     * zero-star score states something the provider did not — on this
     * catalogue it would mislabel 10,840 movies.
     */
    @Test
    fun `a zero rating is absent rather than zero`() {
        val zero = row(title = "A", metadata = """{"rating":"0","streamId":21}""")
        assertNull(rowFacts(listOf(zero)).single().rating)
        val blank = row(title = "A", metadata = """{"rating":"","streamId":21}""")
        assertNull(rowFacts(listOf(blank)).single().rating)
    }

    @Test
    fun `malformed or absent metadata does not throw`() {
        assertNull(rowFacts(listOf(row(title = "A", metadata = "not json"))).single().rating)
        assertNull(rowFacts(listOf(row(title = "A", metadata = null))).single().rating)
    }

    @Test
    fun `facts keep the row id so the caller can match them back`() {
        val facts = rowFacts(
            listOf(
                row(id = "a", title = "One (1999)"),
                row(id = "b", title = "Two (2001)"),
            ),
        )
        assertEquals(listOf("a", "b"), facts.map { it.id })
        assertEquals(listOf(1999, 2001), facts.map { it.year })
    }

    /**
     * The preview pane draws a synopsis block and it was empty on every
     * row, because the iOS model hardcoded the plot to `""` rather than
     * because the provider sends nothing: series carry one on the list
     * endpoint and the sync has been storing it all along.
     */
    @Test
    fun `plot is read from the stored metadata`() {
        val item = row(
            title = "Kara Sevda",
            metadata = """{"plot":"  Kemal ve Nihan.  "}""",
        )
        assertEquals("Kemal ve Nihan.", rowFacts(listOf(item)).single().plot)
    }

    /**
     * Providers write the same field under either key — the detail service
     * reads both and so must this or a row looks emptier than the page
     * that opens from it.
     */
    @Test
    fun `description stands in when plot is absent`() {
        val item = row(title = "Bir Film", metadata = """{"description":"Bir özet."}""")
        assertEquals("Bir özet.", rowFacts(listOf(item)).single().plot)
    }

    /** An empty string is not a synopsis; the block must hide rather than
     * reserve space for nothing. */
    @Test
    fun `blank plot reads as absent`() {
        val item = row(title = "Bir Film", metadata = """{"plot":"   "}""")
        assertNull(rowFacts(listOf(item)).single().plot)
    }
}
