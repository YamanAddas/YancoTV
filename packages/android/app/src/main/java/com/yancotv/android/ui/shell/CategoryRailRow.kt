package com.yancotv.android.ui.shell

import com.yancotv.shared.content.CategoryNode

/**
 * MK.20.3 — Visible-row representation for the category rail. Independent
 * from the SQLDelight-bound [CategoryNode] tree because the UI also needs
 * to track collapse state, indent state, and child counts that don't
 * belong on the data layer.
 *
 * Two row kinds:
 *   - [Leaf]: a real `group_name` filter the user can commit. Used for
 *     unprefixed categories ("Sports") and (when its parent is expanded)
 *     for prefixed children ("AR | Movies").
 *   - [Parent]: a synthetic visual row that toggles expand/collapse for
 *     N children sharing a resolved language/region. Committing a Parent
 *     does NOT change the active filter — only its children do.
 *
 * The flatten helpers below are pure / testable. Hide-set filtering and
 * single-child collapse happen in the helpers, not in the rail composable
 * — keeps the rail dumb and the rules pinned by JVM tests.
 */
sealed interface CategoryRailRow {
    val key: String
    val label: String

    data class Leaf(
        override val key: String,
        override val label: String,
        val groupName: String,
        val indented: Boolean = false,
    ) : CategoryRailRow

    data class Parent(
        override val key: String,
        override val label: String,
        val expanded: Boolean,
        val childCount: Int,
    ) : CategoryRailRow
}

/**
 * Flatten a [CategoryNode] tree into a list of visible rows, given the
 * current set of [expandedParents] (tracked by the rail composable).
 *
 * Rules:
 *   - [CategoryNode.Leaf]s render flat as [CategoryRailRow.Leaf].
 *   - [CategoryNode.Parent]s render as [CategoryRailRow.Parent]; their
 *     children only appear (as indented [CategoryRailRow.Leaf]s) when
 *     the parent's label is in [expandedParents].
 *
 * No re-sorting — input order (provider order from MK.20.1, bucketed in
 * MK.20.2) is preserved at every level.
 */
fun flattenCategoryTree(
    tree: List<CategoryNode>,
    expandedParents: Set<String>,
): List<CategoryRailRow> {
    if (tree.isEmpty()) return emptyList()
    val out = mutableListOf<CategoryRailRow>()
    for (node in tree) {
        when (node) {
            is CategoryNode.Leaf -> {
                out.add(
                    CategoryRailRow.Leaf(
                        key = node.groupName,
                        label = node.groupName,
                        groupName = node.groupName,
                    ),
                )
            }
            is CategoryNode.Parent -> {
                val isExpanded = node.label in expandedParents
                out.add(
                    CategoryRailRow.Parent(
                        key = "__p__${node.label}",
                        label = node.label,
                        expanded = isExpanded,
                        childCount = node.children.size,
                    ),
                )
                if (isExpanded) {
                    for (child in node.children) {
                        out.add(
                            CategoryRailRow.Leaf(
                                key = "__c__${node.label}__${child.groupName}",
                                label = child.groupName,
                                groupName = child.groupName,
                                indented = true,
                            ),
                        )
                    }
                }
            }
        }
    }
    return out
}

/**
 * Filter the flat group list against the user's hidden-groups set BEFORE
 * the prefix bucketer runs. Centralised here so MK.20.3.4's rule —
 * a parent whose children are all hidden disappears entirely — is just
 * a side effect of upstream filtering: an empty bucket isn't created in
 * the first place. Pinning (MK.20 follow-up) plugs in here when wired.
 */
fun applySmartGroupingHidden(
    flatGroups: List<String>,
    hidden: Set<String>,
): List<String> = if (hidden.isEmpty()) flatGroups else flatGroups.filter { it !in hidden }
