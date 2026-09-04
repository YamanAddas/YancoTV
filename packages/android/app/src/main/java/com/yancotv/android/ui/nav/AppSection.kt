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
    ;

    companion object {
        /**
         * The five destinations the compact bottom bar carries.
         *
         * MK.37.B — nine will not fit a phone's width at a legible size, so the
         * bar takes the five that are browsing (where a viewer spends their
         * time) and [compactOverflow] keeps the rest one tap away. Nothing is
         * removed; it is re-homed. The rail on TV and tablet still shows all
         * nine, which is why this list lives here rather than in the bar — the
         * split is a fact about the destinations, not about one widget.
         */
        val compactPrimary: List<AppSection> = listOf(Home, LiveTv, Movies, Series, Favorites)

        /** The four that move into the More sheet on a phone. */
        val compactOverflow: List<AppSection> = listOf(Guide, Recordings, Search, Settings)
    }
}
