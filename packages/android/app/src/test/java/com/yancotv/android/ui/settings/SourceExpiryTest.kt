package com.yancotv.android.ui.settings

import java.util.Locale
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MK.30.3 — [formatSourceExpiry] contract.
 *
 * Pinned to UTC and [Locale.UK] so the formatted dates are deterministic;
 * production passes the user's locale, which is correct for a date a human
 * reads.
 */
class SourceExpiryTest {
    private val day = 24L * 60 * 60 * 1000
    private val expiry = 1_798_675_200_000L // 2026-12-31T00:00:00Z
    private val uk = Locale.UK
    private var saved: TimeZone? = null

    @BeforeTest fun pinZone() {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @AfterTest fun restoreZone() {
        saved?.let { TimeZone.setDefault(it) }
    }

    /**
     * MK.31.24 — the English wording, mirroring `values/strings.xml`.
     *
     * The formatter now takes its copy as a parameter block instead of building
     * English inline, so it can be localized without giving up the plain JVM
     * test. Restating the English here is the point: these assertions pin the
     * SHAPE of each case (which date, which count, no zero-day count), and they
     * would still catch a logic regression if the copy were reworded.
     */
    private val en = ExpiryStrings(
        expiredCompact = "expired",
        expiredFull = { d -> "Expired $d" },
        todayCompact = "expires today",
        todayFull = { d -> "$d · less than a day left" },
        soonCompact = { n -> "expires in ${n}d" },
        soonFullOne = { d -> "$d · 1 day left" },
        soonFullMany = { d, n -> "$d · $n days left" },
        laterCompact = { d -> "expires $d" },
    )

    private fun at(nowMs: Long) = formatSourceExpiry(expiry, nowMs, en, uk)

    @Test
    fun `no recorded expiry renders nothing at all`() {
        // Every m3u source, and any xtream source not yet re-synced. Must be
        // omitted rather than shown as "Unknown", which would read as a fault
        // on the majority of rows.
        assertNull(formatSourceExpiry(null, expiry, en, uk))
    }

    @Test
    fun `a distant expiry shows the date and reads as non-urgent`() {
        val info = at(expiry - 200 * day)!!
        assertEquals(ExpiryUrgency.Later, info.urgency)
        assertEquals("31 Dec 2026", info.full)
        assertEquals("expires 31 Dec", info.compact)
        assertEquals(200L, info.daysRemaining)
    }

    @Test
    fun `an expiry inside the soon window is flagged with days left`() {
        val info = at(expiry - 5 * day)!!
        assertEquals(ExpiryUrgency.Soon, info.urgency)
        assertEquals("31 Dec 2026 · 5 days left", info.full)
        assertEquals("expires in 5d", info.compact)
    }

    @Test
    fun `one day left is singular`() {
        val info = at(expiry - 1 * day)!!
        assertEquals("31 Dec 2026 · 1 day left", info.full)
        assertEquals("expires in 1d", info.compact)
    }

    @Test
    fun `the soon window boundary is inclusive and one day past it is not`() {
        assertEquals(ExpiryUrgency.Soon, at(expiry - EXPIRY_SOON_DAYS * day)!!.urgency)
        assertEquals(ExpiryUrgency.Later, at(expiry - (EXPIRY_SOON_DAYS + 1) * day)!!.urgency)
    }

    @Test
    fun `under a day left avoids saying zero days`() {
        // "0 days left" is true and useless.
        val info = at(expiry - 6 * 60 * 60 * 1000)!!
        assertEquals(ExpiryUrgency.Soon, info.urgency)
        assertEquals("expires today", info.compact)
        assertEquals("31 Dec 2026 · less than a day left", info.full)
        assertTrue(!info.full.contains("0 day"), "must not render a zero-day count: ${info.full}")
    }

    @Test
    fun `the exact expiry instant counts as expired`() {
        val info = at(expiry)!!
        assertEquals(ExpiryUrgency.Expired, info.urgency)
        assertEquals("Expired 31 Dec 2026", info.full)
        assertEquals("expired", info.compact)
    }

    @Test
    fun `a past expiry never reports negative days`() {
        // The row survives in the DB after the account lapses, so this is the
        // steady state for an abandoned source — it must not render "-90 days".
        val info = at(expiry + 90 * day)!!
        assertEquals(ExpiryUrgency.Expired, info.urgency)
        assertEquals(0L, info.daysRemaining)
        assertTrue(!info.compact.contains("-"), "no negative day count: ${info.compact}")
        assertTrue(!info.full.contains("-"), "no negative day count: ${info.full}")
    }

    @Test
    fun `an implausibly distant expiry still formats`() {
        // parseXtreamExpiry caps input at year 2100, but a backup restore or a
        // hand-edited DB could carry anything; this must not overflow or throw.
        val far = formatSourceExpiry(4_102_444_800_000L, 0L, en, uk)!!
        assertEquals(ExpiryUrgency.Later, far.urgency)
        assertTrue(far.daysRemaining > 0, "expected a positive day count, got ${far.daysRemaining}")
    }
}
