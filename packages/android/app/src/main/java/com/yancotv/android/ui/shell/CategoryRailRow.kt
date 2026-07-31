package com.yancotv.android.ui.shell

import com.yancotv.shared.content.CategoryNode
import com.yancotv.shared.content.SourceScopedGroup

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

    data class Leaf(override val key: String, override val label: String, val groupName: String, val indented: Boolean = false) : CategoryRailRow

    data class Parent(override val key: String, override val label: String, val expanded: Boolean, val childCount: Int) : CategoryRailRow
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
    /**
     * MK.33.1 — label for the leading "everything in this playlist" child of
     * each [CategoryNode.SourceParent]. Null omits it.
     *
     * Passed in rather than resolved here because this is a plain function
     * called from inside a `remember {}` block — neither is composable scope.
     */
    wholeSourceLabel: String? = null,
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
                // MK.33.1 — expansion is keyed on the ROW KEY, not the label.
                // Two playlists can carry the same name, and keying on the
                // visible label made expanding one expand the other.
                val key = "__p__${node.label}"
                val isExpanded = key in expandedParents
                out.add(
                    CategoryRailRow.Parent(
                        key = key,
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
            is CategoryNode.SourceParent -> {
                // MK.33.1 — a playlist bucketing its own groups. Keyed on the
                // source id, which is unique even when two playlists share a
                // display name.
                val key = "__sp__${node.sourceId}"
                val isExpanded = key in expandedParents
                out.add(
                    CategoryRailRow.Parent(
                        key = key,
                        label = node.label,
                        expanded = isExpanded,
                        childCount = node.children.size,
                    ),
                )
                if (isExpanded) {
                    // Leading "All" — everything this playlist contributes to the
                    // current type, with no group filter. The equivalent of
                    // picking the playlist itself.
                    if (wholeSourceLabel != null) {
                        out.add(
                            CategoryRailRow.Leaf(
                                key = "__sall__${node.sourceId}",
                                label = wholeSourceLabel,
                                groupName = SourceScopedGroup.encodeWholeSource(node.sourceId),
                                indented = true,
                            ),
                        )
                    }
                    for (child in node.children) {
                        out.add(
                            CategoryRailRow.Leaf(
                                key = "__sc__${node.sourceId}__${child.groupName}",
                                // child.groupName is a SCOPED SELECTION KEY, not
                                // display text — decode it for the label, or the
                                // rail would render "__src__<uuid>…Sports".
                                label = SourceScopedGroup.decode(child.groupName)?.groupName
                                    ?: child.groupName,
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
fun applySmartGroupingHidden(flatGroups: List<String>, hidden: Set<String>): List<String> =
    if (hidden.isEmpty()) flatGroups else flatGroups.filter { it !in hidden }
