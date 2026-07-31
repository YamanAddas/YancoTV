package com.yancotv.android.ui.shell

import com.yancotv.shared.content.CategoryNode
import com.yancotv.shared.content.CategoryTreeBuilder
import com.yancotv.shared.content.PrefixCatalog
import com.yancotv.shared.content.SourceScopedGroup
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * MK.20.3.5 — Pure-function tests for the rail flatten helpers. Pinned by
 * JVM unit tests so refactors of the expand/collapse / hide rules can't
 * regress the visible-row contract.
 */
class CategoryRailRowTest {
    private fun parent(label: String, kind: PrefixCatalog.Kind, vararg children: String) =
        CategoryNode.Parent(label, kind, label.take(2).uppercase(), children.map { CategoryNode.Leaf(it) })

    @Test fun emptyTreeReturnsEmpty() {
        assertEquals(emptyList(), flattenCategoryTree(emptyList(), emptySet()))
    }

    @Test fun allCollapsed_onlyParentsAndUnprefixedLeavesShow() {
        val tree =
            listOf(
                CategoryNode.Leaf("Sports"),
                parent("Arabic", PrefixCatalog.Kind.Language, "AR | News", "AR | Movies"),
                parent("English", PrefixCatalog.Kind.Language, "EN | News", "EN | Drama"),
            )
        val rows = flattenCategoryTree(tree, expandedParents = emptySet())
        assertEquals(3, rows.size)
        assertEquals("Sports", (rows[0] as CategoryRailRow.Leaf).groupName)
        val arabic = rows[1] as CategoryRailRow.Parent
        assertEquals("Arabic", arabic.label)
        assertEquals(2, arabic.childCount)
        assertEquals(false, arabic.expanded)
    }

    @Test fun oneExpanded_onlyThatBucketsChildrenShow() {
        val tree =
            listOf(
                parent("Arabic", PrefixCatalog.Kind.Language, "AR | News", "AR | Movies"),
                parent("English", PrefixCatalog.Kind.Language, "EN | News", "EN | Drama"),
            )
        // MK.33.1 — expansion is keyed on the ROW KEY, not the bare label, so
        // prefix parents and playlist parents share one convention. The saved
        // expand-state values changed format with that; a stale set simply
        // matches nothing and the rail opens collapsed.
        val rows = flattenCategoryTree(tree, expandedParents = setOf("__p__Arabic"))
        assertEquals(4, rows.size) // Parent(Arabic), 2 children, Parent(English) collapsed
        assertEquals(true, (rows[0] as CategoryRailRow.Parent).expanded)
        assertEquals("AR | News", (rows[1] as CategoryRailRow.Leaf).groupName)
        assertEquals(true, (rows[1] as CategoryRailRow.Leaf).indented)
        assertEquals("AR | Movies", (rows[2] as CategoryRailRow.Leaf).groupName)
        assertEquals("English", (rows[3] as CategoryRailRow.Parent).label)
        assertEquals(false, (rows[3] as CategoryRailRow.Parent).expanded)
    }

    @Test fun applySmartGroupingHidden_filtersBeforeBucketing() {
        // All children of a bucket hidden → bucket vanishes via empty input.
        val flat =
            listOf(
                "Sports",
                "AR | News",
                "AR | Movies",
                "EN | News",
            )
        val hidden = setOf("AR | News", "AR | Movies")
        val filtered = applySmartGroupingHidden(flat, hidden)
        assertEquals(listOf("Sports", "EN | News"), filtered)
        // Run through the actual builder to confirm the no-empty-bucket rule.
        val tree = CategoryTreeBuilder.build(filtered)
        // EN | News collapses to a Leaf because it's the only English child.
        assertEquals(2, tree.size)
        assertEquals("Sports", (tree[0] as CategoryNode.Leaf).groupName)
        assertEquals("EN | News", (tree[1] as CategoryNode.Leaf).groupName)
    }

    @Test fun keysAreStableAcrossCollapse_andDistinctBetweenParentsAndChildren() {
        val tree = listOf(parent("Arabic", PrefixCatalog.Kind.Language, "AR | News", "AR | Movies"))
        val collapsed = flattenCategoryTree(tree, emptySet())
        val expanded = flattenCategoryTree(tree, setOf("Arabic"))
        // The Parent's key is identical between the two states (so LazyColumn
        // can survive the toggle without rebuilding the parent's slot).
        assertEquals(collapsed[0].key, expanded[0].key)
        // Children keys never collide with the parent key.
        assertEquals(true, expanded.map { it.key }.distinct().size == expanded.size)
    }

    // ───── MK.33.1 — per-playlist buckets ─────

    private fun sourceParent(id: String, name: String, vararg groups: String) = CategoryNode.SourceParent(
        sourceId = id,
        label = name,
        children = groups.map { CategoryNode.Leaf(SourceScopedGroup.encode(id, it)) },
    )

    @Test fun sourceParent_collapsed_showsOnlyThePlaylistRow() {
        val rows = flattenCategoryTree(
            listOf(sourceParent("a", "Cedric", "Sports", "News")),
            expandedParents = emptySet(),
        )
        assertEquals(1, rows.size)
        assertEquals("Cedric", rows.single().label)
        assertEquals(2, (rows.single() as CategoryRailRow.Parent).childCount)
    }

    @Test fun sourceParent_expanded_childLabelsAreGroupNamesNotEncodedKeys() {
        // The regression this guards: a child's `groupName` is a SCOPED KEY, so
        // using it as the label too would render "__src__<uuid>Sports" on
        // screen. Label and key must diverge here, unlike the prefix path.
        val rows = flattenCategoryTree(
            listOf(sourceParent("a", "Cedric", "Sports")),
            expandedParents = setOf("__sp__a"),
        )
        val leaf = rows.filterIsInstance<CategoryRailRow.Leaf>().single { it.label == "Sports" }
        assertTrue(leaf.indented, "playlist children must be indented")
        assertTrue(
            leaf.groupName.startsWith(SourceScopedGroup.PREFIX),
            "selection key must stay scoped, got ${leaf.groupName}",
        )
        val decoded = SourceScopedGroup.decode(leaf.groupName)
        assertNotNull(decoded)
        assertEquals("a", decoded.sourceId)
        assertEquals("Sports", decoded.groupName)
    }

    @Test fun sourceParent_expansionIsKeyedOnSourceIdNotLabel() {
        // Two playlists the user happened to name the same must expand
        // independently. Keying on the visible label made them move together.
        val tree = listOf(sourceParent("a", "Same", "G1"), sourceParent("b", "Same", "G2"))
        val rows = flattenCategoryTree(tree, expandedParents = setOf("__sp__a"))
        val leaves = rows.filterIsInstance<CategoryRailRow.Leaf>()
        assertEquals(1, leaves.size, "only playlist a should be expanded, got ${leaves.map { it.label }}")
        assertEquals("G1", leaves.single().label)
    }

    @Test fun sourceParent_expanded_leadsWithAWholePlaylistEntryWhenLabelled() {
        val rows = flattenCategoryTree(
            listOf(sourceParent("a", "Cedric", "Sports")),
            expandedParents = setOf("__sp__a"),
            wholeSourceLabel = "All",
        )
        val leaves = rows.filterIsInstance<CategoryRailRow.Leaf>()
        assertEquals(listOf("All", "Sports"), leaves.map { it.label }, "All must come first")
        // The "All" entry filters by playlist with NO group filter.
        val decoded = SourceScopedGroup.decode(leaves.first().groupName)
        assertNotNull(decoded)
        assertEquals("a", decoded.sourceId)
        assertEquals(null, decoded.groupName, "whole-playlist entry must not carry a group")
    }

    @Test fun sourceParent_expanded_omitsWholePlaylistEntryWhenUnlabelled() {
        val rows = flattenCategoryTree(
            listOf(sourceParent("a", "Cedric", "Sports")),
            expandedParents = setOf("__sp__a"),
        )
        assertEquals(
            listOf("Sports"),
            rows.filterIsInstance<CategoryRailRow.Leaf>().map { it.label },
        )
    }

    @Test fun rowKeysAreUniqueAcrossPlaylistsSharingGroupNames() {
        // The rail uses `key` as its LazyColumn item key; duplicates throw.
        val tree = listOf(sourceParent("a", "One", "Sports"), sourceParent("b", "Two", "Sports"))
        val rows = flattenCategoryTree(
            tree,
            expandedParents = setOf("__sp__a", "__sp__b"),
            wholeSourceLabel = "All",
        )
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate row keys: $keys")
    }
}
