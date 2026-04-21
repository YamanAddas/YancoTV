package com.yancotv.android.ui.nav

import com.yancotv.shared.types.ContentType

/**
 * Global nav destinations shown in [com.yancotv.android.ui.shell.AppSidebar].
 * MK.4 is scaffolding — only Home/LiveTV/Movies/Series are wired to the
 * content panel via [contentType]; the rest render placeholders until their
 * milestones (Guide = MK.7, Favorites/Recordings/Downloads/Settings = MK.8).
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
    Recordings("Recordings"),
    Downloads("Downloads"),
    Settings("Settings"),
}
