package com.yancotv.shared.content

/**
 * MK.33.1 — build the category tree bucketed by playlist instead of by
 * language/region prefix.
 *
 * ### The problem this solves
 *
 * `distinctGroupsForType` groups by `group_name` across the whole catalogue, so
 * two playlists that both ship a "Sports" group collapse into one rail row
 * holding both providers' channels. That is the behaviour TiviMate calls
 * *merge*, and it is what YancoTV did unconditionally. With more than one
 * playlist loaded, a user thinks provider-first: "the channels from my second
 * list", not "every Sports channel I own".
 *
 * ### Shape
 *
 * One level deep, matching [CategoryTreeBuilder]: each playlist is a
 * [CategoryNode.SourceParent] whose children are its own groups, in provider
 * order. Prefix bucketing is NOT also applied inside a playlist — that would
 * make the rail three deep (playlist → Arabic → Sports), and a three-deep tree
 * on a 380dp rail driven by a D-pad at 3m is not navigable. Prefix bucketing
 * still applies when only one playlist is loaded, which is the common case.
 *
 * Single-child playlists do NOT collapse to a leaf, unlike prefix buckets. A
 * prefix bucket of one is noise — an "Arabic" dropdown holding one Arabic group
 * tells the user nothing. A playlist of one group is still a meaningful
 * boundary: it says which provider the group came from, which is the entire
 * point of this mode.
 */
object SourceCategoryTreeBuilder {
    /**
     * @param groupsBySource one entry per playlist, in the order the playlists
     *   should appear — the caller supplies the user's `sources` order rather
     *   than letting SQL decide, so the rail matches the Sources screen.
     *   Playlists that contribute no groups for this content type must be
     *   omitted by the caller; an empty child list would render an empty
     *   dropdown.
     */
    fun build(groupsBySource: List<SourceGroups>): List<CategoryNode> = groupsBySource
        .filter { it.groups.isNotEmpty() }
        .map { entry ->
            CategoryNode.SourceParent(
                sourceId = entry.sourceId,
                label = entry.sourceName,
                children = entry.groups.map { group ->
                    // The child's key is scoped; its label stays the bare
                    // group name, because the playlist name is already the
                    // row above it. See CategoryNode.SourceParent.
                    CategoryNode.Leaf(SourceScopedGroup.encode(entry.sourceId, group))
                },
            )
        }

    /**
     * One playlist's groups for a single content type.
     *
     * [sourceName] is the display name from the `sources` table — never the id.
     * Rendering a `source_id` as user-visible text is a standing rule in this
     * codebase (it is a UUID).
     */
    data class SourceGroups(val sourceId: String, val sourceName: String, val groups: List<String>)
}
