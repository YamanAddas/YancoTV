package com.yancotv.shared.xtream

/**
 * MK.30.3 — normalise the Xtream `user_info.exp_date` field into
 * "ms since epoch, or null if there is nothing to show".
 *
 * The field is nominally Unix **seconds** in a JSON string, but it is
 * provider-supplied and unreliable in practice, so every branch below is a
 * real shape seen in the wild rather than defensive padding:
 *
 *  - **absent / JSON null / empty** — the account never expires. Xtream's own
 *    panel omits the key for unlimited accounts.
 *  - **`"0"`** — same meaning, spelled differently by some panel forks.
 *  - **non-numeric** (an ISO date, `"Unlimited"`, an error string) — not
 *    parseable into an instant we'd be willing to render as a date, so treat
 *    it as unknown rather than showing the user a wrong day.
 *  - **already in milliseconds** — a handful of panels return ms. Detected by
 *    magnitude, not by trusting the documented unit: a value that only lands
 *    in [PLAUSIBLE_FROM_MS]..[PLAUSIBLE_TO_MS] when read as ms is ms.
 *  - **out of plausible range either way** — garbage (negative, or a seconds
 *    value implying the year 50000). Null, not a nonsense date.
 *
 * Returning null for all of these collapses "never expires" and "we don't
 * know" into one state. That is deliberate: the wire format genuinely cannot
 * distinguish them, and inventing a distinction would mean showing the user a
 * confident "never expires" for a source we simply failed to read.
 */
private const val PLAUSIBLE_FROM_MS = 946_684_800_000L // 2000-01-01
private const val PLAUSIBLE_TO_MS = 4_102_444_800_000L // 2100-01-01

fun parseXtreamExpiry(expDate: String?): Long? {
    val raw = expDate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val n = raw.toLongOrNull() ?: return null
    if (n <= 0L) return null

    val asMs = n * 1000L
    // Check seconds first: that's the documented unit, and a real seconds
    // value read as ms would land in 1970 and fail the plausibility test
    // anyway, so there's no ambiguous overlap to worry about.
    if (asMs in PLAUSIBLE_FROM_MS..PLAUSIBLE_TO_MS) return asMs
    if (n in PLAUSIBLE_FROM_MS..PLAUSIBLE_TO_MS) return n
    return null
}
