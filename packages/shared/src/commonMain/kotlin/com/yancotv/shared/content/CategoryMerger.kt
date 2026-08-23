package com.yancotv.shared.content

/**
 * MB-382 — a provider category with its country/language prefix stripped,
 * merged with every other provider category that strips to the same base name.
 *
 * Providers routinely split one logical collection across per-language
 * categories: `JAMES BOND 007` (1 English film) and `DE - JAMES BOND 007`
 * (26 German films) are the SAME collection to a viewer, but different rows in
 * `content.group_name`. Browsing the English one showed a single movie while
 * the 26 sat under a separate chip — which reads as "missing content" next to
 * apps (TiviMate) that strip the prefix and show them together.
 *
 * [displayName] is the cleaned, prefix-stripped name shown in the rail.
 * [rawGroupNames] are the original `group_name` values that fold into it — the
 * content query loads `WHERE group_name IN (rawGroupNames)`.
 */
data class MergedCategory(val displayName: String, val rawGroupNames: List<String>)

/**
 * Folds a provider-ordered flat category list into prefix-stripped, merged
 * categories (see [MergedCategory]). Pure — iOS shares it.
 *
 * Rules:
 *  - The prefix is stripped only when [PrefixParser] RESOLVES it to a known
 *    country/language ([PrefixParser.parse] → `resolved != null`). An
 *    unrecognised leading token is left intact, so a real title that merely
 *    starts with two letters is never mangled.
 *  - Merge key is the stripped base, compared case-insensitively, so
 *    `JAMES BOND 007` and `DE - JAMES BOND 007` fold together. A category with
 *    no sibling still becomes a one-entry [MergedCategory] (its name cleaned).
 *  - Provider order is preserved: each merged category sits at the position of
 *    the FIRST raw category that contributed to it (the input is already in
 *    provider order via [ContentRepository.groups] → `MIN(sort_order)`).
 *  - The display name is the first-seen stripped base for that key, so the
 *    casing/spelling the provider used survives.
 *
 * NOTE (accepted, see MB-382): this merges by base name across languages, so
 * `AR - News` + `US - News` also fold into one `News`. That is the requested
 * TiviMate-style behaviour; the smart-grouping toggle (bucket-by-language) is
 * the alternative for users who want the split kept.
 */
object CategoryMerger {
    fun merge(rawGroups: List<String>): List<MergedCategory> {
        val order = ArrayList<String>()
        val byKey = LinkedHashMap<String, MutableList<String>>()
        val displayByKey = HashMap<String, String>()
        for (raw in rawGroups) {
            val parsed = PrefixParser.parse(raw)
            val base = (if (parsed.resolved != null) parsed.remainder else raw).trim()
            if (base.isEmpty()) continue
            val key = base.lowercase()
            val list = byKey.getOrPut(key) {
                order.add(key)
                displayByKey[key] = base
                mutableListOf()
            }
            list.add(raw)
        }
        return order.map { key -> MergedCategory(displayByKey.getValue(key), byKey.getValue(key)) }
    }
}
