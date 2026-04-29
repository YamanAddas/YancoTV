package com.yancotv.shared.content

/**
 * MK.20.2 — Hierarchical category list returned by
 * [ContentRepository.groupsHierarchical].
 *
 * The tree is one level deep: top-level entries are either an unprefixed
 * [Leaf] (e.g. `"Sports"`) or a [Parent] bucketing N children with the
 * same resolved language/region (e.g. `"Arabic" → ["AR | Sports", "AR | Movies"]`).
 *
 * Single-child parents collapse to a [Leaf] in the builder so the UI
 * doesn't render useless dropdowns.
 *
 * Provider order is preserved at every level: top-level ordering follows
 * the minimum sort_order of each bucket's first contributing row;
 * children within a bucket follow the order they appeared in the input
 * (which is already provider order via [ContentRepository.groups]).
 */
sealed interface CategoryNode {
    /** The original `group_name` string used for the row's filter key. */
    val groupName: String

    data class Leaf(override val groupName: String) : CategoryNode

    data class Parent(val label: String, val kind: PrefixCatalog.Kind, val prefixCode: String, val children: List<Leaf>) : CategoryNode {
        // Parents are addressed by the parent label, not a single group_name.
        // Consumers that need a key for a Parent should use [label] (or the
        // resolved kind+code pair). `groupName` is exposed for symmetry and
        // returns the label so the field is never empty.
        override val groupName: String get() = label
    }
}

object CategoryTreeBuilder {
    private data class Bucket(val label: String, val kind: PrefixCatalog.Kind, val code: String, val children: MutableList<CategoryNode.Leaf>)

    /**
     * Build a one-level tree out of the provider-ordered flat list returned
     * by [ContentRepository.groups]. Inputs are already in provider order;
     * the builder never re-sorts — it only buckets.
     *
     * Single-child parents collapse to a [CategoryNode.Leaf] (the child's
     * original group_name), so a feed with a single `AR | Sports` row
     * doesn't surface an `Arabic` dropdown of one. Multi-child parents
     * render with the resolved [PrefixCatalog.Entry.displayName] as label.
     *
     * **Pin-a-bucket (MK.20 polish-sweep, 2026-04-28).** [pinnedParentCodes]
     * is the user's per-content-type ordered list of parent codes (lowercase,
     * e.g. `"ar"`, `"us"`, `"saudi arabia"`). When non-empty, parents whose
     * `prefixCode` (lowercase compare, falling back to displayName lowercase
     * for multi-word matches) appears in the list float to the top of the
     * root-level output IN PIN-LIST ORDER. The remaining root entries
     * (unpinned parents + collapsed-leaves + unprefixed leaves) keep their
     * provider-order position. Pinned codes that don't resolve to a parent
     * in the current tree (e.g. user pinned "ar" but the catalog has no
     * Arabic content this session, OR Arabic collapsed to a single-child
     * leaf) are silently ignored.
     *
     * Comparison is on the catalog's lowercased prefixCode + displayName so
     * the user can pin via either the 2-letter code (`"ar"`, `"sa"`) or the
     * displayName (`"arabic"`, `"saudi arabia"`) and the implementation
     * handles both. The Settings UI typically writes the code; the catalog
     * displayName form is supported for backup-restore symmetry across
     * future code-renames.
     */
    fun build(flatGroups: List<String>, pinnedParentCodes: List<String> = emptyList()): List<CategoryNode> {
        if (flatGroups.isEmpty()) return emptyList()

        // Two-pass: first pass collects buckets in first-seen order and
        // emits a slot index where each bucket's parent (or collapsed leaf)
        // will live in the final output. Second pass materialises the slots.
        // Avoids leaking a "placeholder" CategoryNode subtype through the
        // public sealed type.
        val buckets = LinkedHashMap<String, Bucket>()
        // slots: each entry is either a CategoryNode.Leaf (unprefixed group)
        // or a bucket key (String) to be resolved in pass 2.
        val slots = mutableListOf<Any>()

        for (group in flatGroups) {
            val parsed = PrefixParser.parse(group)
            val resolved = parsed.resolved
            if (resolved == null) {
                slots.add(CategoryNode.Leaf(group))
                continue
            }
            val key = resolved.displayName
            val bucket =
                buckets.getOrPut(key) {
                    slots.add(key) // reserve slot the first time we see this bucket
                    Bucket(
                        label = resolved.displayName,
                        kind = resolved.kind,
                        code = parsed.prefix ?: resolved.displayName,
                        children = mutableListOf(),
                    )
                }
            bucket.children.add(CategoryNode.Leaf(group))
        }

        val materialised =
            slots.map { slot ->
                when (slot) {
                    is CategoryNode.Leaf -> slot
                    is String -> {
                        val bucket = buckets.getValue(slot)
                        if (bucket.children.size == 1) {
                            bucket.children.single()
                        } else {
                            CategoryNode.Parent(
                                label = bucket.label,
                                kind = bucket.kind,
                                prefixCode = bucket.code,
                                children = bucket.children.toList(),
                            )
                        }
                    }
                    else -> error("unreachable")
                }
            }
        if (pinnedParentCodes.isEmpty()) return materialised

        // Re-order: pinned parents in pin-list order at the top, everything
        // else preserves its existing position. Single-child collapsed
        // leaves and unprefixed leaves are NOT pinnable as parents — only
        // multi-child Parents survive the lookup. This matches the UX
        // expectation: a "pin Arabic" with only one Arabic channel is a
        // no-op (the bucket disappeared into a leaf).
        val pinSet = pinnedParentCodes.map { it.lowercase() }.toSet()
        val pinnedParents = mutableListOf<CategoryNode>()
        val unpinned = mutableListOf<CategoryNode>()
        for (node in materialised) {
            if (node is CategoryNode.Parent &&
                (node.prefixCode.lowercase() in pinSet || node.label.lowercase() in pinSet)
            ) {
                pinnedParents += node
            } else {
                unpinned += node
            }
        }
        // Re-order pinnedParents to match the pin-list ordering. A parent
        // not in pinSet via prefixCode but matched via label still goes
        // into the slot keyed by whichever form appears in the user's list.
        val ordered =
            pinnedParentCodes
                .map { it.lowercase() }
                .mapNotNull { code ->
                    pinnedParents.firstOrNull {
                        it is CategoryNode.Parent &&
                            (it.prefixCode.lowercase() == code || it.label.lowercase() == code)
                    }
                }
        return ordered + unpinned
    }
}
