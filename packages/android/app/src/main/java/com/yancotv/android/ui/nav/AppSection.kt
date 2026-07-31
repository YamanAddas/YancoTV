package com.yancotv.android.ui.nav

import androidx.annotation.StringRes
import com.yancotv.android.R
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
enum class AppSection(@StringRes val labelRes: Int, val contentType: ContentType? = null) {
    Home(R.string.section_home),
    LiveTv(R.string.section_live_tv, ContentType.LIVE),
    Guide(R.string.section_guide),
    Movies(R.string.section_movies, ContentType.MOVIE),
    Series(R.string.section_series, ContentType.SERIES),
    Favorites(R.string.section_favorites),
    Recordings(R.string.section_recordings),
    Search(R.string.section_search),
    Settings(R.string.section_settings),
}
