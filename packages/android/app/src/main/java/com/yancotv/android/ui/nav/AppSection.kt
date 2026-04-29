package com.yancotv.android.ui.nav

import com.yancotv.shared.types.ContentType

/**
 * Global nav destinations shown in [com.yancotv.android.ui.shell.AppSidebar].
 * [contentType] is set on the three type-filtered destinations so the shell
 * can route them into the shared ContentPanel; everything else has its own
 * dedicated screen.
 *
 * Recordings landed back in 2026-04-26 (Stage 3.1 / MK.14.5) as the catalog
 * surface for the recording engine. Downloads stays dropped — no roadmap.
 */
enum class AppSection(val label: String, val contentType: ContentType? = null) {
    Home("Home"),
    LiveTv("Live TV", ContentType.LIVE),
    Guide("Guide"),
    Movies("Movies", ContentType.MOVIE),
    Series("Series", ContentType.SERIES),
    Favorites("Favorites"),
    Recordings("Recordings"),
    Search("Search"),
    Settings("Settings"),
}
