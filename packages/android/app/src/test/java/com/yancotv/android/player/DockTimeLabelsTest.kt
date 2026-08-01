package com.yancotv.android.player

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MB-340 (plan W2) — [DockTimeFormatter] contract.
 *
 * Clock and zone are injected, which is the point: the interesting cases are a
 * DST crossing and a midnight rollover, and neither is reachable from a test that
 * reads the system clock.
 *
 * Locale is pinned to ROOT for the duration of each test. The production
 * formatter deliberately uses `Locale.getDefault()` so an Arabic UI gets
 * Arabic-Indic digits (MK.31.3), but a test asserting exact glyphs must not
 * depend on the machine it runs on.
 *
 * Negative control (run, per docs/FABLE5_SESSION_PLAN.md §3): replacing
 * [DockTimeFormatter.formatWallClock]'s ZonedDateTime with epoch arithmetic
 * (`(epochMs / 3_600_000) % 24`) turns `ends-at survives a spring-forward DST
 * transition` red. Reverting [DockTimeFormatter.labels] to return a zero
 * remaining instead of null for an unknown duration turns
 * `an unknown duration yields no remaining and no ends-at` red.
 */
class DockTimeLabelsTest {
    private val utc: ZoneId = ZoneId.of("UTC")
    private var saved: Locale? = null

    @BeforeTest fun pinLocale() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.ROOT)
    }

    @AfterTest fun restoreLocale() {
        saved?.let { Locale.setDefault(it) }
    }

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId): Long = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    // ───── Elapsed formatting ─────

    @Test
    fun `sub-hour durations omit the hour field entirely`() {
        // The reason for not reusing formatMillis: "00:22:14" wasted two of eight
        // glyphs on a leading zero pair, and the dock is read at three metres.
        assertEquals("0:00", DockTimeFormatter.formatClock(0L))
        assertEquals("0:09", DockTimeFormatter.formatClock(9_000L))
        assertEquals("22:14", DockTimeFormatter.formatClock(22L * 60_000L + 14_000L))
        assertEquals("59:59", DockTimeFormatter.formatClock(59L * 60_000L + 59_000L))
    }

    @Test
    fun `the hour field appears exactly at one hour and pads minutes thereafter`() {
        assertEquals("1:00:00", DockTimeFormatter.formatClock(3_600_000L))
        assertEquals("1:05:03", DockTimeFormatter.formatClock(3_600_000L + 5L * 60_000L + 3_000L))
        assertEquals("12:00:00", DockTimeFormatter.formatClock(12L * 3_600_000L))
    }

    @Test
    fun `a negative position formats as zero rather than a negative time code`() {
        assertEquals("0:00", DockTimeFormatter.formatClock(-1L))
        assertEquals("0:00", DockTimeFormatter.formatClock(-500_000L))
    }

    // ───── The unknown-duration contract ─────

    @Test
    fun `an unknown duration yields no remaining and no ends-at`() {
        // Live, or a player that has not prepared. Media3 reports C.TIME_UNSET (a
        // large negative), so anything non-positive is unknown. A fabricated
        // "0:00 remaining" would be a lie the user would act on.
        for (dur in listOf(0L, -1L, Long.MIN_VALUE / 2, DockTimeFormatter.UNKNOWN_DURATION)) {
            val l = DockTimeFormatter.labels(60_000L, dur, isLive = false, nowMs = 0L, zone = utc)
            assertEquals("1:00", l.elapsed, "elapsed must still render for duration=$dur")
            assertNull(l.remaining, "remaining must be null for duration=$dur")
            assertNull(l.endsAt, "endsAt must be null for duration=$dur")
        }
    }

    @Test
    fun `live gets elapsed only, even when a duration happens to be reported`() {
        // A live HLS stream with a sliding window does report a duration; it is
        // the window length, not "how much is left of the programme", so showing
        // it as remaining would be actively misleading.
        val l = DockTimeFormatter.labels(90_000L, 600_000L, isLive = true, nowMs = 0L, zone = utc)
        assertEquals("1:30", l.elapsed)
        assertNull(l.remaining)
        assertNull(l.endsAt)
    }

    // ───── Remaining ─────

    @Test
    fun `remaining is signed and counts down`() {
        val l = DockTimeFormatter.labels(
            playedMs = 12L * 60_000L + 34_000L,
            durationMs = 45L * 60_000L,
            isLive = false,
            nowMs = 0L,
            zone = utc,
        )
        assertEquals("12:34", l.elapsed)
        assertEquals("-32:26", l.remaining)
    }

    @Test
    fun `a position past the duration clamps remaining to zero`() {
        // ExoPlayer can report position slightly past duration at the end of an
        // item, and a stale tick can land after a seek. Neither should produce a
        // countdown that runs upward through negative numbers.
        val l = DockTimeFormatter.labels(700_000L, 600_000L, isLive = false, nowMs = 0L, zone = utc)
        assertEquals("-0:00", l.remaining)
        assertTrue(!l.remaining!!.contains("--"), "double sign in ${l.remaining}")
    }

    // ───── Ends-at, the part epoch arithmetic gets wrong ─────

    @Test
    fun `ends-at is now plus remaining, in wall-clock time`() {
        val now = epoch(2026, 8, 1, 20, 15, utc)
        val l = DockTimeFormatter.labels(
            playedMs = 0L,
            durationMs = 92L * 60_000L,
            isLive = false,
            nowMs = now,
            zone = utc,
        )
        assertEquals("21:47", l.endsAt)
    }

    @Test
    fun `ends-at rolls over midnight without wrapping into a negative hour`() {
        val now = epoch(2026, 8, 1, 23, 30, utc)
        val l = DockTimeFormatter.labels(0L, 90L * 60_000L, isLive = false, nowMs = now, zone = utc)
        assertEquals("01:00", l.endsAt)
    }

    @Test
    fun `ends-at survives a spring-forward DST transition`() {
        // Europe/London springs forward 2026-03-29 at 01:00 -> 02:00. A film
        // starting at 00:30 with 90 minutes left ends at 03:00 wall-clock, not
        // 02:00: the hour between 01:00 and 02:00 does not exist. Epoch
        // arithmetic ((now + remaining) / 3_600_000 % 24) gets this wrong twice a
        // year for everyone in a DST zone, which is why the implementation uses
        // ZonedDateTime.
        val london = ZoneId.of("Europe/London")
        val now = epoch(2026, 3, 29, 0, 30, london)
        val l = DockTimeFormatter.labels(0L, 90L * 60_000L, isLive = false, nowMs = now, zone = london)
        assertEquals("03:00", l.endsAt)
    }

    @Test
    fun `ends-at survives a fall-back DST transition`() {
        // Europe/BERLIN, not London, and the choice is deliberate. The negative
        // control exposed that a London fall-back case passes against naive epoch
        // arithmetic too — London is UTC+0 in winter, so the naive answer
        // coincidentally agrees and the test guards nothing.
        //
        // Berlin falls back 2026-10-25 03:00 CEST -> 02:00 CET. Starting 01:30
        // CEST (= 23:30 UTC the previous day) with 90 minutes left ends at 02:00
        // CET wall-clock. Naive `(epochMs / 3_600_000) % 24` renders 01:00,
        // because it ignores the zone entirely. The two answers differ, so this
        // case actually discriminates.
        val berlin = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2026, 10, 25, 1, 30, 0, 0, berlin).toInstant().toEpochMilli()
        val l = DockTimeFormatter.labels(0L, 90L * 60_000L, isLive = false, nowMs = now, zone = berlin)
        assertEquals("02:00", l.endsAt)
    }

    @Test
    fun `ends-at is zone-relative, not UTC-relative`() {
        // The same instant renders as different wall clocks in different zones;
        // a hardcoded UTC would show the wrong finish time to most of the world.
        val now = epoch(2026, 8, 1, 12, 0, utc)
        val inUtc = DockTimeFormatter.labels(0L, 60L * 60_000L, false, now, utc).endsAt
        val inRiyadh = DockTimeFormatter.labels(0L, 60L * 60_000L, false, now, ZoneId.of("Asia/Riyadh")).endsAt
        assertEquals("13:00", inUtc)
        assertEquals("16:00", inRiyadh)
    }

    @Test
    fun `ends-at pads both fields to two digits`() {
        // "9:5" would be unreadable; the wall clock is the one field that keeps
        // fixed width, unlike the elapsed/remaining codes.
        val now = epoch(2026, 8, 1, 9, 4, utc)
        val l = DockTimeFormatter.labels(0L, 60_000L, isLive = false, nowMs = now, zone = utc)
        assertEquals("09:05", l.endsAt)
    }
}
