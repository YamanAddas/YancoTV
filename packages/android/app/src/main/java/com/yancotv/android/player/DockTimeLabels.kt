package com.yancotv.android.player

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * MB-340 (plan W2 / MK.25.B.1) — the three time facts the VOD dock shows.
 *
 * The dock printed position and duration only. The two things a viewer actually
 * wants at three metres are **how much is left** and **when this finishes**, and
 * every competitor shows both.
 *
 * Pure and clock-injected so the whole table — including the cases that are
 * annoying to reach on a device (a stream with no known duration, a position past
 * the end, an ends-at that crosses midnight or a DST boundary) — is unit-testable
 * without a player. `nowMs` and `zone` are parameters for exactly that reason;
 * reading the system clock inside would make the DST tests unwritable.
 */
data class DockTimeLabels(
    /** Elapsed, always present. */
    val elapsed: String,
    /**
     * Remaining, as a negative-signed duration (`-32:26`). Null when the duration
     * is unknown — live, or not yet prepared — because "remaining" is
     * meaningless there and a fabricated `00:00` would be a lie.
     */
    val remaining: String?,
    /**
     * Wall-clock time this finishes, formatted `HH:mm` in the device's zone.
     * Null under the same conditions as [remaining], and additionally when the
     * caller asks to suppress it.
     */
    val endsAt: String?,
)

object DockTimeFormatter {
    /**
     * Build the label set.
     *
     * @param playedMs current position.
     * @param durationMs total duration, or <= 0 / [UNKNOWN_DURATION] when
     *   unknown. Media3 reports `C.TIME_UNSET` (a large negative) for live and
     *   for a player that has not prepared yet, so any non-positive value is
     *   treated as unknown rather than as "zero length".
     * @param isLive live streams get elapsed only — a live channel has no end.
     * @param nowMs wall clock, injected.
     * @param zone the device zone, injected.
     */
    fun labels(playedMs: Long, durationMs: Long, isLive: Boolean, nowMs: Long, zone: ZoneId): DockTimeLabels {
        val elapsed = formatClock(playedMs.coerceAtLeast(0L))
        if (isLive || durationMs <= 0L) {
            return DockTimeLabels(elapsed = elapsed, remaining = null, endsAt = null)
        }
        // A position past the duration is not a bug to crash on: ExoPlayer can
        // report position slightly beyond duration at the very end of an item,
        // and a stale tick can arrive after a seek. Clamp to zero remaining
        // rather than rendering a negative countdown.
        val remainingMs = (durationMs - playedMs).coerceAtLeast(0L)
        return DockTimeLabels(
            elapsed = elapsed,
            remaining = "-" + formatClock(remainingMs),
            endsAt = formatWallClock(nowMs + remainingMs, zone),
        )
    }

    /**
     * `H:MM:SS` past an hour, `MM:SS` below it.
     *
     * Deliberately NOT the old `formatMillis`'s fixed `%02d:%02d:%02d`: a
     * 22-minute episode rendered `00:22:14`, which spends two of its eight
     * glyphs on a leading zero pair that is never useful, and the dock is read at
     * three metres where every glyph costs. Hours appear only when there are
     * hours.
     */
    fun formatClock(ms: Long): String {
        val totalSec = (ms / 1_000L).coerceAtLeast(0L)
        val h = totalSec / 3_600L
        val m = (totalSec % 3_600L) / 60L
        val s = totalSec % 60L
        // Locale-aware on purpose (MK.31.3): an Arabic UI renders Arabic-Indic
        // digits everywhere else, and a time code that stayed Latin would be the
        // only Western-digit field on the screen. This is the opposite of
        // MB-268's filename case, where Locale.US was correct precisely because
        // the output was machine-facing.
        return if (h > 0L) {
            String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.getDefault(), "%d:%02d", m, s)
        }
    }

    /**
     * 24-hour `HH:mm` in [zone].
     *
     * `ZonedDateTime` rather than arithmetic on the epoch: a DST transition
     * between now and the end of a film shifts the wall clock by an hour, and
     * `(nowMs + remainingMs) / 3_600_000 % 24` would silently be wrong for
     * everyone in a DST zone twice a year. The zone does the work.
     */
    fun formatWallClock(epochMs: Long, zone: ZoneId): String {
        val t: ZonedDateTime = Instant.ofEpochMilli(epochMs).atZone(zone)
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", t.hour, t.minute)
    }

    /** Sentinel for callers that want to be explicit rather than passing 0. */
    const val UNKNOWN_DURATION: Long = -1L
}
