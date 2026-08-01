package com.yancotv.android.ui.settings

import com.yancotv.android.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * MK.30.3 — presentation of `Source.expiresAt`.
 *
 * How urgent the account expiry is. Drives colour in the Sources list and
 * detail screen: an account lapsing this week should not look like one
 * lapsing next year.
 */
internal enum class ExpiryUrgency { Expired, Soon, Later }

/**
 * Rendered forms of one source's expiry.
 *
 * @param compact for the Sources list sub-line, which is a single ellipsized
 *   line already carrying type, item count and refresh timing. Kept to a few
 *   characters so it doesn't push the rest out.
 * @param full for the detail screen, where there is room for the actual date.
 */
internal data class SourceExpiryInfo(val compact: String, val full: String, val urgency: ExpiryUrgency, val daysRemaining: Long)

/** Inside this window an expiry is [ExpiryUrgency.Soon] — worth flagging. */
internal const val EXPIRY_SOON_DAYS = 14L

/**
 * Formats [expiresAt] (ms since epoch, or null) for display.
 *
 * Returns null when there is nothing to show — no expiry recorded, which
 * covers m3u sources (no account metadata exists), xtream sources that
 * haven't synced since the column was added, and providers reporting a
 * non-expiring account. Callers omit the field entirely rather than printing
 * "unknown", which would read as a fault on the majority of sources.
 *
 * [locale] is explicit rather than implicit so the choice is visible at the
 * call site: this is a date a human reads, so it SHOULD follow the user's
 * locale (unlike the time codes in MB-268, where the default locale rendered
 * Arabic-Indic digits into a machine-ish field). It is also what makes these
 * tests deterministic.
 *
 * Day counts are calendar-agnostic on purpose — whole 24h periods from now,
 * not midnight boundaries. "3 days left" derived from elapsed time can't
 * disagree with itself across a DST shift the way a midnight-diff can.
 */
/**
 * MK.31.24 — the wording, supplied by the caller.
 *
 * [formatSourceExpiry] stays a pure function with a plain JVM unit test, which
 * is worth keeping: the interesting behaviour is the day-count and urgency
 * classification, not the copy. Production passes Context-backed lambdas; the
 * test passes English ones and keeps asserting exact output.
 */
internal data class ExpiryStrings(
    val expiredCompact: String,
    val expiredFull: (String) -> String,
    val todayCompact: String,
    val todayFull: (String) -> String,
    val soonCompact: (Long) -> String,
    val soonFullOne: (String) -> String,
    val soonFullMany: (String, Long) -> String,
    val laterCompact: (String) -> String,
)

/** The production wording, read from resources. */
internal fun expiryStrings(ctx: android.content.Context) = ExpiryStrings(
    expiredCompact = ctx.getString(R.string.se_expired_compact),
    expiredFull = { d -> ctx.getString(R.string.se_expired_full, d) },
    todayCompact = ctx.getString(R.string.se_today_compact),
    todayFull = { d -> ctx.getString(R.string.se_today_full, d) },
    soonCompact = { n -> ctx.getString(R.string.se_soon_compact, n) },
    soonFullOne = { d -> ctx.getString(R.string.se_soon_full_one, d) },
    // MB-339 — only this arm becomes a plural. `formatSourceExpiry` already
    // branches at `days == 1L`, which is exactly CLDR `one` in all four
    // locales, so soonFullOne stays a plain string and this arm is reached
    // with days >= 2 — precisely the range where Arabic needed the dual and
    // the broken plural instead of the singular it was rendering.
    soonFullMany = { d, n ->
        ctx.resources.getQuantityString(R.plurals.se_soon_full_many, n.toInt(), d, n)
    },
    laterCompact = { d -> ctx.getString(R.string.se_later_compact, d) },
)

internal fun formatSourceExpiry(expiresAt: Long?, nowMs: Long, strings: ExpiryStrings, locale: Locale = Locale.getDefault()): SourceExpiryInfo? {
    if (expiresAt == null) return null
    val remainingMs = expiresAt - nowMs
    val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
    val date = SimpleDateFormat("d MMM yyyy", locale).format(Date(expiresAt))
    val shortDate = SimpleDateFormat("d MMM", locale).format(Date(expiresAt))

    return when {
        remainingMs <= 0L ->
            SourceExpiryInfo(
                compact = strings.expiredCompact,
                full = strings.expiredFull(date),
                urgency = ExpiryUrgency.Expired,
                daysRemaining = 0L,
            )
        // Under 24h left. Reporting "0 days left" is technically true and
        // completely useless, so this case gets its own wording.
        days < 1L ->
            SourceExpiryInfo(
                compact = strings.todayCompact,
                full = strings.todayFull(date),
                urgency = ExpiryUrgency.Soon,
                daysRemaining = 0L,
            )
        days <= EXPIRY_SOON_DAYS ->
            SourceExpiryInfo(
                compact = strings.soonCompact(days),
                full = if (days == 1L) {
                    strings.soonFullOne(date)
                } else {
                    strings.soonFullMany(date, days)
                },
                urgency = ExpiryUrgency.Soon,
                daysRemaining = days,
            )
        else ->
            SourceExpiryInfo(
                compact = strings.laterCompact(shortDate),
                full = date,
                urgency = ExpiryUrgency.Later,
                daysRemaining = days,
            )
    }
}
