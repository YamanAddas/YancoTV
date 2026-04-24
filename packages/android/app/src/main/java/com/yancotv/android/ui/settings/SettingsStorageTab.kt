package com.yancotv.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Storage placeholder. Image/EPG cache sizes, a "clear cache" action,
 * and the SQLite database footprint — all surfaced from
 * [com.yancotv.android.cache.CacheInspector] and the shared repos once
 * we wire up a size-aggregator.
 */
@Composable
fun SettingsStorageTab(modifier: Modifier = Modifier) {
    SettingsPlaceholder(
        modifier = modifier,
        kicker = "Later milestone",
        title = "Storage",
        body =
            "Image cache size, EPG cache size, SQLite DB footprint, and one-tap " +
                "clear actions. Waiting on a size-aggregator in the shared cache layer " +
                "so the read is cheap enough to refresh on tab entry.",
    )
}
