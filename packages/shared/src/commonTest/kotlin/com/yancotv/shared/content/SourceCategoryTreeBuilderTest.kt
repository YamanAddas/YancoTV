package com.yancotv.shared.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MK.33.1 — [SourceCategoryTreeBuilder] contract.
 */
class SourceCategoryTreeBuilderTest {
    private fun src(id: String, name: String, vararg groups: String) = SourceCategoryTreeBuilder.SourceGroups(id, name, groups.toList())

    @Test
    fun `each playlist becomes a parent labelled with its name`() {
        val tree = SourceCategoryTreeBuilder.build(
            listOf(
                src("a", "Cedric", "Sports", "Movies"),
                src("b", "Backup List", "News"),
            ),
        )
        assertEquals(2, tree.size)
        val first = tree[0] as CategoryNode.SourceParent
        val second = tree[1] as CategoryNode.SourceParent
        // The NAME, never the id — a source_id is a UUID.
        assertEquals("Cedric", first.label)
        assertEquals("Backup List", second.label)
        assertEquals("a", first.sourceId)
    }

    @Test
    fun `caller order is preserved, not source id order`() {
        // The caller passes the user's `sources` row order so the rail matches
        // the Sources screen. Ids here sort the opposite way on purpose.
        val tree = SourceCategoryTreeBuilder.build(
            listOf(src("zzz", "First", "G"), src("aaa", "Second", "G")),
        )
        assertEquals(
            listOf("First", "Second"),
            tree.map { (it as CategoryNode.SourceParent).label },
        )
    }

    @Test
    fun `children carry scoped keys and unscoped labels`() {
        val tree = SourceCategoryTreeBuilder.build(listOf(src("a", "Cedric", "Sports")))
        val leaf = (tree.single() as CategoryNode.SourceParent).children.single()
        // groupName IS the selection key, and must be scoped.
        val decoded = SourceScopedGroup.decode(leaf.groupName)
        assertEquals("a", decoded?.sourceId)
        assertEquals("Sports", decoded?.groupName)
    }

    @Test
    fun `the same group under two playlists gets two distinct keys`() {
        // The bug this whole slice fixes: previously both collapsed into one
        // rail row holding both providers' channels.
        val tree = SourceCategoryTreeBuilder.build(
            listOf(src("a", "One", "Sports"), src("b", "Two", "Sports")),
        )
        val keys = tree.flatMap { (it as CategoryNode.SourceParent).children }.map { it.groupName }
        assertEquals(2, keys.toSet().size, "expected two distinct keys, got $keys")
    }

    @Test
    fun `a playlist contributing no groups is dropped`() {
        // An Xtream account with no series must not put an empty dropdown in
        // the rail.
        val tree = SourceCategoryTreeBuilder.build(
            listOf(src("a", "Has content", "Sports"), src("b", "Nothing here")),
        )
        assertEquals(1, tree.size)
        assertEquals("Has content", (tree.single() as CategoryNode.SourceParent).label)
    }

    @Test
    fun `a single-group playlist still renders as a parent`() {
        // Deliberately unlike CategoryTreeBuilder, which collapses a
        // single-child PREFIX bucket to a leaf. A prefix bucket of one is
        // noise; a playlist of one group still tells the user which provider
        // it came from, which is the point of this mode.
        val tree = SourceCategoryTreeBuilder.build(listOf(src("a", "Cedric", "Sports")))
        assertTrue(
            tree.single() is CategoryNode.SourceParent,
            "single-group playlist must not collapse to a leaf",
        )
    }

    @Test
    fun `empty input yields an empty tree`() {
        assertEquals(emptyList(), SourceCategoryTreeBuilder.build(emptyList()))
        assertEquals(emptyList(), SourceCategoryTreeBuilder.build(listOf(src("a", "Empty"))))
    }
}
