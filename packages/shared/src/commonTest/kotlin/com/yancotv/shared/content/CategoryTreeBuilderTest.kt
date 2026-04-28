package com.yancotv.shared.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MK.20 polish-sweep — unit tests for [CategoryTreeBuilder.build] focused
 * on the pin-a-bucket reordering rules. The bucket-collapsing /
 * provider-order-preservation rules are covered by `ContentRepositoryTest.
 * groupsHierarchical_*` integration tests; this file pins the pure
 * tree-builder contract.
 */
class CategoryTreeBuilderTest {
    /**
     * No pinned codes → output identical to the no-arg build. Sanity
     * baseline so subsequent tests' diffs read as "what pinning changed".
     */
    @Test fun build_noPins_returnsProviderOrder() {
        val groups =
            listOf(
                "AR | Sports",
                "AR | Movies",
                "US | News",
                "Sports", // unprefixed
                "US | Drama",
            )
        val tree = CategoryTreeBuilder.build(groups, pinnedParentCodes = emptyList())
        // Arabic (2 children) → US (2 children) → Sports leaf.
        // Note: unprefixed "Sports" appears AFTER the Arabic bucket because
        // the Arabic slot was reserved on its first child's iteration.
        assertEquals(3, tree.size)
        assertTrue(tree[0] is CategoryNode.Parent)
        assertEquals("Arabic", (tree[0] as CategoryNode.Parent).label)
        assertTrue(tree[1] is CategoryNode.Parent)
        assertEquals("USA", (tree[1] as CategoryNode.Parent).label)
        assertTrue(tree[2] is CategoryNode.Leaf)
        assertEquals("Sports", (tree[2] as CategoryNode.Leaf).groupName)
    }

    /**
     * Single pinned code by lowercase prefix → that parent floats to the
     * top, everything else keeps its existing order. The load-bearing
     * test: confirms the basic pin-to-top semantics.
     */
    @Test fun build_pinByLowercaseCode_floatsParentToTop() {
        val groups =
            listOf(
                "AR | Sports", // first → Arabic slot 0
                "US | News",   // second → USA slot 1
                "AR | Movies", // appends to Arabic
                "US | Drama",  // appends to USA
            )
        val tree = CategoryTreeBuilder.build(groups, pinnedParentCodes = listOf("us"))
        assertEquals(2, tree.size)
        // USA pinned to position 0 even though it appeared second in
        // provider order.
        assertEquals("USA", (tree[0] as CategoryNode.Parent).label)
        assertEquals("Arabic", (tree[1] as CategoryNode.Parent).label)
    }

    /**
     * Pin order in the input list is preserved in the output. Multiple
     * pinned codes float in the user's chosen sequence, NOT in provider
     * order.
     */
    @Test fun build_multiplePins_orderFollowsInputList() {
        val groups =
            listOf(
                "AR | Sports", "AR | Movies", // Arabic
                "US | News", "US | Drama",    // USA
                "FR | Sport", "FR | Drame",   // France
                "Sports",                      // unprefixed leaf
            )
        // User pinned in (FR, AR) order — France should be FIRST, then Arabic,
        // then USA (unpinned, kept in provider order), then leaf.
        val tree = CategoryTreeBuilder.build(groups, pinnedParentCodes = listOf("fr", "ar"))
        assertEquals(4, tree.size)
        assertEquals("France", (tree[0] as CategoryNode.Parent).label)
        assertEquals("Arabic", (tree[1] as CategoryNode.Parent).label)
        assertEquals("USA", (tree[2] as CategoryNode.Parent).label)
        assertEquals("Sports", (tree[3] as CategoryNode.Leaf).groupName)
    }

    /**
     * Pin by displayName (e.g. user pinned "Saudi Arabia" not "sa") —
     * also lifts to top. Required for multi-word names where the user
     * recognises the displayName but not the code.
     */
    @Test fun build_pinByDisplayName_alsoFloats() {
        val groups =
            listOf(
                "AR | Sports", "AR | Movies",
                "Saudi Arabia | beIN", "Saudi Arabia | KSA1",
            )
        val tree =
            CategoryTreeBuilder.build(
                groups,
                pinnedParentCodes = listOf("saudi arabia"),
            )
        assertEquals(2, tree.size)
        assertEquals("Saudi Arabia", (tree[0] as CategoryNode.Parent).label)
        assertEquals("Arabic", (tree[1] as CategoryNode.Parent).label)
    }

    /**
     * Pinning a code that doesn't resolve to a parent (no Arabic content
     * at all in this session) is silently ignored. UX: user keeps the
     * pin so when content reappears it floats again.
     */
    @Test fun build_pinForAbsentParent_silentlyIgnored() {
        val groups = listOf("US | News", "US | Drama")
        val tree = CategoryTreeBuilder.build(groups, pinnedParentCodes = listOf("ar"))
        // Only USA exists. AR pin is a no-op.
        assertEquals(1, tree.size)
        assertEquals("USA", (tree[0] as CategoryNode.Parent).label)
    }

    /**
     * Pinning a code whose bucket collapsed to a single-child leaf is
     * ignored — single-child collapse means the bucket isn't a Parent
     * anymore, so it can't be pinned-as-parent. The leaf stays in its
     * provider-order position.
     */
    @Test fun build_pinForSingleChildCollapsedBucket_ignored() {
        val groups =
            listOf(
                "AR | Sports", // single Arabic → collapses to leaf
                "US | News", "US | Drama", // USA stays as parent
            )
        val tree = CategoryTreeBuilder.build(groups, pinnedParentCodes = listOf("ar"))
        assertEquals(2, tree.size)
        // First slot is the collapsed Arabic leaf in its original position.
        assertTrue(tree[0] is CategoryNode.Leaf)
        assertEquals("AR | Sports", (tree[0] as CategoryNode.Leaf).groupName)
        assertEquals("USA", (tree[1] as CategoryNode.Parent).label)
    }

    /**
     * Empty input → empty output regardless of pin list. Catches a bug
     * where the early-return doesn't fire when pinnedParentCodes is set.
     */
    @Test fun build_emptyInput_emptyOutput() {
        assertEquals(emptyList(), CategoryTreeBuilder.build(emptyList(), listOf("ar", "us")))
    }

    /**
     * Pin order is case-insensitive on the input. User typing "AR"
     * (uppercase, matching the catalog's prefix display) and "ar"
     * (lowercase, matching the storage convention) both resolve.
     */
    @Test fun build_pinIsCaseInsensitive() {
        val groups = listOf("AR | Sports", "AR | Movies", "US | News", "US | Drama")
        val tree = CategoryTreeBuilder.build(groups, pinnedParentCodes = listOf("AR"))
        assertEquals("Arabic", (tree[0] as CategoryNode.Parent).label)
    }

    /**
     * Children of a pinned parent retain their internal order. The pin
     * affects root-level position only; the bucket's contents are
     * unchanged.
     */
    @Test fun build_pinDoesNotReorderChildren() {
        val groups =
            listOf(
                "AR | Sports",
                "US | News",
                "AR | Movies",
                "AR | Drama",
            )
        val tree = CategoryTreeBuilder.build(groups, pinnedParentCodes = listOf("ar"))
        val arabic = tree[0] as CategoryNode.Parent
        assertEquals(
            listOf("AR | Sports", "AR | Movies", "AR | Drama"),
            arabic.children.map { it.groupName },
            "Pinning a parent must not reorder its children",
        )
    }
}
