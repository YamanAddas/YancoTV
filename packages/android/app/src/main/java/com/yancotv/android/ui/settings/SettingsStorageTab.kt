package com.yancotv.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yancotv.android.R

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
        kicker = stringResource(R.string.ph_later_milestone),
        title = "Storage",
        body =
        stringResource(R.string.ph_storage_body),
    )
}
