package com.yancotv.android.recommendations

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.yancotv.android.MainActivity
import com.yancotv.android.R
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.types.ContentType

/**
 * MK.10.1 — populates the Android TV launcher Recommendations channel.
 *
 * One channel per app (named after the app), refreshed periodically by
 * [RecommendationsWorker]. Cards come from the user's recent watch
 * history — both VOD continue-watching and recently-watched live
 * channels — capped at [MAX_PROGRAMS] so the launcher row stays tidy.
 *
 * **Fire TV note.** This is an Android-TV-only surface. Fire TV's
 * launcher uses Amazon's homescreen integration program (separate
 * SDK + cert) and ignores `TvContractCompat` data. The code is
 * harmless on Fire TV — `TvContractCompat` writes return without
 * error even when no launcher consumes the rows. Coverage activates
 * automatically on Google TV / Android TV.
 *
 * **Idempotency.** Channel insertion checks the existing channel list
 * by `INTERNAL_PROVIDER_ID`; programs are nuked + reinserted on every
 * sync so removed history items disappear from the launcher row
 * within one period (default 6 hours).
 */
class RecommendationsSync(private val context: Context, private val history: WatchHistoryRepository) {
    // Lint `RestrictedApi` fires here and it is structural to
    // androidx.tvprovider, not a misuse: `PreviewProgram.Builder` is
    // public API, but the setters it inherits from
    // `BasePreviewProgram.Builder` and the `PreviewProgramColumns.TYPE_*`
    // constants behind `TvContractCompat.PreviewPrograms` are annotated
    // `@RestrictTo(LIBRARY_GROUP)`. There is no public alternative --
    // building a preview program the documented way produces these
    // errors, and Google's own samples do too. Suppressed rather than
    // baselined so the reason travels with the code. (MB-357 sweep,
    // 2026-08-22.)
    @SuppressLint("RestrictedApi")
    fun sync(): SyncResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // TvContractCompat preview-program APIs require API 26+.
            // Lower devices silently skip.
            return SyncResult(channelId = -1L, programs = 0, skipped = true)
        }
        val channelId = ensureChannel()
        if (channelId < 0L) {
            return SyncResult(channelId = -1L, programs = 0, skipped = true)
        }

        // Wipe any stale programs for this channel — the contract is
        // "this snapshot replaces the prior one." Cheap on the launcher
        // side; user sees fresh content next focus pass.
        context.contentResolver.delete(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            "${TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID} = ?",
            arrayOf(channelId.toString()),
        )

        val entries =
            runCatching { history.recent(limit = MAX_PROGRAMS.toLong()) }
                .onFailure { Log.w(TAG, "recent() failed", it) }
                .getOrDefault(emptyList())

        var inserted = 0
        for (entry in entries) {
            val item = entry.content
            val intentUri = deepLinkFor(item.id)
            val program =
                PreviewProgram
                    .Builder()
                    .setChannelId(channelId)
                    .setType(typeFor(item.type))
                    .setTitle(item.cleanTitle ?: item.title)
                    .also { b -> item.groupName?.takeIf { it.isNotBlank() }?.let(b::setDescription) }
                    .also { b -> item.logoUrl?.takeIf { it.isNotBlank() }?.let { b.setPosterArtUri(Uri.parse(it)) } }
                    .setIntentUri(intentUri)
                    .setInternalProviderId(item.id)
                    .build()
            val uri =
                runCatching {
                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        program.toContentValues(),
                    )
                }.onFailure { Log.w(TAG, "insert program failed for ${item.id}", it) }
                    .getOrNull()
            if (uri != null) inserted++
        }
        Log.i(TAG, "synced $inserted preview programs to channel $channelId")
        return SyncResult(channelId = channelId, programs = inserted, skipped = false)
    }

    /** Returns the channel id, creating the channel if needed. -1 on
     *  unrecoverable failure (e.g. content provider not present). */
    private fun ensureChannel(): Long {
        // Look for an existing row keyed by our internal provider id.
        val existing =
            runCatching {
                context.contentResolver.query(
                    TvContractCompat.Channels.CONTENT_URI,
                    CHANNEL_PROJECTION,
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    var found = -1L
                    while (cursor.moveToNext()) {
                        val internal = cursor.getString(2)
                        if (internal == INTERNAL_PROVIDER_ID) {
                            found = cursor.getLong(0)
                            break
                        }
                    }
                    found
                } ?: -1L
            }.onFailure { Log.w(TAG, "channel query failed", it) }
                .getOrDefault(-1L)
        if (existing >= 0L) return existing

        val channel =
            Channel
                .Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(context.getString(CHANNEL_DISPLAY_NAME_RES))
                .setAppLinkIntentUri(deepLinkFor(itemId = null))
                .setInternalProviderId(INTERNAL_PROVIDER_ID)
                .build()
        val inserted =
            runCatching {
                context.contentResolver.insert(
                    TvContractCompat.Channels.CONTENT_URI,
                    channel.toContentValues(),
                )
            }.onFailure { Log.w(TAG, "channel insert failed — launcher likely doesn't support TvContract (Fire TV?)", it) }
                .getOrNull()
        val newId =
            inserted?.lastPathSegment?.toLongOrNull() ?: return -1L

        // Mark the channel BROWSABLE so the launcher actually surfaces it
        // without the user having to opt in via a system permission flow.
        // No-op on launchers that ignore the column.
        runCatching {
            val cv = ContentValues().apply { put(TvContractCompat.Channels.COLUMN_BROWSABLE, 1) }
            context.contentResolver.update(
                TvContractCompat.buildChannelUri(newId),
                cv,
                null,
                null,
            )
        }
        return newId
    }

    // MB-357 — `@OptIn`, not `@UnstableApi`. This references a class we
    // annotated `@UnstableApi`, which propagates media3's opt-in to every
    // caller. Consuming it here is the correct half of that pair; the wider
    // swap of the other 64 `@UnstableApi` declarations is tracked as MB-357.
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun deepLinkFor(itemId: String?): Uri {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (itemId != null) putExtra(EXTRA_DEEP_LINK_ID, itemId)
            }
        // toUri(0) is fine here — the launcher resolves an Intent URI,
        // and we don't need any of the URI flags for our case.
        return Uri.parse(intent.toUri(0))
    }

    @SuppressLint("RestrictedApi")
    private fun typeFor(t: ContentType): Int = when (t) {
        ContentType.LIVE -> TvContractCompat.PreviewPrograms.TYPE_CHANNEL
        ContentType.MOVIE -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
        ContentType.SERIES -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
    }

    data class SyncResult(val channelId: Long, val programs: Int, val skipped: Boolean)

    companion object {
        private const val TAG = "YancoRecs"

        /** Stable id distinguishing our channel from any other publisher's. */
        private const val INTERNAL_PROVIDER_ID = "yancotv.recommendations.v1"

        /**
         * Display name shown on the launcher row header. A resource id rather
         * than a string: this is a companion const, which cannot reach a
         * Context. Resolved in [ensureChannel].
         */
        private val CHANNEL_DISPLAY_NAME_RES = R.string.rc_recently_watched

        /** Cards per refresh. 10 keeps the row scannable; user can launch
         *  the app for the full library. */
        private const val MAX_PROGRAMS = 10

        /** Intent extra carrying the deep-link target id (currently unused
         *  on the receiving side — MainActivity routes to home regardless;
         *  wire actual deep-link handling alongside MK.10.4 zap polish). */
        const val EXTRA_DEEP_LINK_ID = "yanco.deep_link.content_id"

        private val CHANNEL_PROJECTION =
            arrayOf(
                TvContractCompat.Channels._ID,
                TvContractCompat.Channels.COLUMN_DISPLAY_NAME,
                TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID,
            )
    }
}
