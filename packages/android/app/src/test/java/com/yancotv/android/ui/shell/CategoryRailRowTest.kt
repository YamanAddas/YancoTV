package com.yancotv.android.ui.shell

import com.yancotv.shared.content.CategoryNode
import com.yancotv.shared.content.CategoryTreeBuilder
import com.yancotv.shared.content.PrefixCatalog
import kotlin.test.assertEquals
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
        val rows = flattenCategoryTree(tree, expandedParents = setOf("Arabic"))
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
}
