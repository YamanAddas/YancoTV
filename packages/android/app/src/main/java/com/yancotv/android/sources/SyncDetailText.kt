package com.yancotv.android.sources

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yancotv.android.R
import com.yancotv.shared.http.redactCredentials
import com.yancotv.shared.sources.SyncDetail

/**
 * MK.31.18 — the Android half of [SyncDetail]: turns the shared module's
 * structured progress into localized text.
 *
 * This file is the whole point of the [SyncDetail] refactor. `commonMain` cannot
 * see `R.string` (AGENTS.md hard rule #1 — `androidx` there would break the iOS
 * target), so the shared code reports *what* it is doing and this maps it to
 * *how we say it*. iOS will add its own mapper over the same sealed type.
 *
 * Two entry points because there are two kinds of caller:
 *  - [syncDetailText] for Compose, where `stringResource` is available.
 *  - [syncDetailText] with a [Context] for [SourceSyncCoordinator]'s error bus,
 *    which formats a Toast from a coroutine and has no composition.
 *
 * Both funnel [SyncDetail.Failure] through [redactCredentials] rather than
 * trusting callers to remember. Provider failures echo the request URL and
 * Xtream carries credentials as path segments (MB-292), so redaction belongs at
 * the single point where that text becomes user-visible.
 */
@Composable
fun syncDetailText(detail: SyncDetail?): String? = when (detail) {
    null -> null
    SyncDetail.Starting -> stringResource(R.string.sd_starting)
    SyncDetail.Connecting -> stringResource(R.string.sd_connecting)
    SyncDetail.Authenticating -> stringResource(R.string.sd_authenticating)
    SyncDetail.FetchingCategories -> stringResource(R.string.sd_fetching_categories)
    SyncDetail.FetchingCatalog -> stringResource(R.string.sd_fetching_catalog)
    SyncDetail.Finalizing -> stringResource(R.string.sd_finalizing)
    is SyncDetail.WritingLive -> stringResource(R.string.sd_writing_live, detail.written)
    is SyncDetail.WritingMovies -> stringResource(R.string.sd_writing_movies, detail.written)
    is SyncDetail.WritingSeries -> stringResource(R.string.sd_writing_series, detail.written)
    is SyncDetail.SourceNotFound -> stringResource(R.string.sd_source_not_found, detail.id)
    is SyncDetail.Failure -> redactCredentials(detail.text)
}

/** Non-composable variant — see the class doc for why both exist. */
fun syncDetailText(context: Context, detail: SyncDetail?): String? = when (detail) {
    null -> null
    SyncDetail.Starting -> context.getString(R.string.sd_starting)
    SyncDetail.Connecting -> context.getString(R.string.sd_connecting)
    SyncDetail.Authenticating -> context.getString(R.string.sd_authenticating)
    SyncDetail.FetchingCategories -> context.getString(R.string.sd_fetching_categories)
    SyncDetail.FetchingCatalog -> context.getString(R.string.sd_fetching_catalog)
    SyncDetail.Finalizing -> context.getString(R.string.sd_finalizing)
    is SyncDetail.WritingLive -> context.getString(R.string.sd_writing_live, detail.written)
    is SyncDetail.WritingMovies -> context.getString(R.string.sd_writing_movies, detail.written)
    is SyncDetail.WritingSeries -> context.getString(R.string.sd_writing_series, detail.written)
    is SyncDetail.SourceNotFound -> context.getString(R.string.sd_source_not_found, detail.id)
    is SyncDetail.Failure -> redactCredentials(detail.text)
}
