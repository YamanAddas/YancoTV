package com.yancotv.android.ui.shell

/**
 * Provider category buckets. Takes the raw flat list of group names the
 * provider pushes (often 400+ strings like "US | SPORTS HD", "UK - NEWS",
 * "Sports: NFL", "KIDS: Disney") and maps them to meaningful parent
 * buckets the user can scan at a glance.
 *
 * The bucket order here is the render order in the sidebar — most-used
 * first (Sports + Movies + Series top, Adult last). Groups that don't
 * match any bucket fall into [GroupBucket.Other].
 *
 * Matching is keyword-based; false positives are cheaper than false
 * negatives here — a sports channel wrongly sorted into "News" is easier
 * to find than one hidden in a generic Other bucket.
 */
enum class GroupBucket(
    val display: String,
    val icon: String,
    private val keywords: List<String>,
) {
    SPORTS("Sports", "⚽", listOf(
        "sport", "football", "soccer", "basketball", "tennis", "golf",
        "boxing", "mma", "ufc", "formula", " f1", "nfl", "nba", "mlb",
        "nhl", "rugby", "cricket", "wrestle", "espn", "bein ", "dazn",
        "fox sport", "sky sport", "nbc sport", "eurosport",
    )),
    MOVIES("Movies", "🎬", listOf(
        "movie", "film", "cinema", "hollywood", "bollywood", "vod",
    )),
    SERIES("Series & Shows", "📺", listOf(
        "series", "show", "tv show", "drama", "sitcom",
    )),
    NEWS("News", "📰", listOf(
        "news", "information", "cnbc", "cnn", "bbc news", "fox news",
        "skynews", "sky news", "al jazeera", "aljazeera", "euronews",
    )),
    KIDS("Kids", "🧸", listOf(
        "kid", "child", "cartoon", "anime", "disney", "nick", "cbeebies",
        "boomerang", "baby",
    )),
    MUSIC("Music", "🎵", listOf(
        "music", "mtv", "vh1", "concert", "radio",
    )),
    DOCUMENTARY("Documentary", "🎞️", listOf(
        "document", "history", "discovery", "natgeo", "nat geo",
        "animal planet", "science", "travel",
    )),
    ENTERTAINMENT("Entertainment", "🎭", listOf(
        "entertain", "general", "e!", "lifestyle", "comedy",
    )),
    RELIGIOUS("Religious", "🕌", listOf(
        "religi", "islamic", "christian", "quran", "gospel", "church",
        "mecca",
    )),
    ADULT("Adult", "🔞", listOf(
        "adult", "xxx", "erotic", "playboy", "hustler", "hot",
        "vixen", "brazzers",
    )),
    OTHER("Other", "•", emptyList()),
    ;

    fun matches(group: String): Boolean {
        if (keywords.isEmpty()) return false
        val lower = group.lowercase()
        return keywords.any { lower.contains(it) }
    }
}

/**
 * Assign each input group to the first bucket that matches — order in
 * the enum decides priority when a group would match multiple buckets.
 * Groups that match none land in [GroupBucket.OTHER].
 */
fun bucketize(groups: List<String>): Map<GroupBucket, List<String>> {
    val result = linkedMapOf<GroupBucket, MutableList<String>>()
    for (group in groups) {
        val bucket = GroupBucket.entries.firstOrNull { it.matches(group) } ?: GroupBucket.OTHER
        result.getOrPut(bucket) { mutableListOf() }.add(group)
    }
    // Keep the bucket order stable (enum order) so the sidebar doesn't
    // shuffle between syncs. Sort each bucket's groups alphabetically —
    // within Sports the user shouldn't have to read "BT Sport 3" before
    // "BT Sport 1".
    return GroupBucket.entries
        .mapNotNull { bucket -> result[bucket]?.let { bucket to it.sorted() } }
        .toMap()
}
