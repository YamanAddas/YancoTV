package com.yancotv.android.ui.nav

import com.yancotv.shared.types.ContentType

/**
 * Global nav destinations shown in [com.yancotv.android.ui.shell.AppSidebar].
 * [contentType] is set on the three type-filtered destinations so the shell
 * can route them into the shared ContentPanel; everything else has its own
 * dedicated screen.
 *
 * Recordings / Downloads were dropped — desktop-only features that never
 * landed on Android and aren't on the roadmap.
 */
enum class AppSection(
    val label: String,
    val contentType: ContentType? = null,
) {
    Home("Home"),
    LiveTv("Live TV", ContentType.LIVE),
    Guide("Guide"),
    Movies("Movies", ContentType.MOVIE),
    Series("Series", ContentType.SERIES),
    Favorites("Favorites"),
    Search("Search"),
    Settings("Settings"),
}
