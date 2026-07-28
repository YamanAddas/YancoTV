package com.yancotv.android.ui.settings

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
internal fun formatSourceExpiry(expiresAt: Long?, nowMs: Long, locale: Locale = Locale.getDefault()): SourceExpiryInfo? {
    if (expiresAt == null) return null
    val remainingMs = expiresAt - nowMs
    val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
    val date = SimpleDateFormat("d MMM yyyy", locale).format(Date(expiresAt))
    val shortDate = SimpleDateFormat("d MMM", locale).format(Date(expiresAt))

    return when {
        remainingMs <= 0L ->
            SourceExpiryInfo(
                compact = "expired",
                full = "Expired $date",
                urgency = ExpiryUrgency.Expired,
                daysRemaining = 0L,
            )
        // Under 24h left. Reporting "0 days left" is technically true and
        // completely useless, so this case gets its own wording.
        days < 1L ->
            SourceExpiryInfo(
                compact = "expires today",
                full = "$date · less than a day left",
                urgency = ExpiryUrgency.Soon,
                daysRemaining = 0L,
            )
        days <= EXPIRY_SOON_DAYS ->
            SourceExpiryInfo(
                compact = "expires in ${days}d",
                full = "$date · $days ${if (days == 1L) "day" else "days"} left",
                urgency = ExpiryUrgency.Soon,
                daysRemaining = days,
            )
        else ->
            SourceExpiryInfo(
                compact = "expires $shortDate",
                full = date,
                urgency = ExpiryUrgency.Later,
                daysRemaining = days,
            )
    }
}
