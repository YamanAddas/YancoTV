package com.yancotv.shared.xtream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MK.30.3 — [parseXtreamExpiry] contract.
 *
 * `user_info.exp_date` is provider-supplied, so the important cases here are
 * the malformed ones: a wrong reading surfaces to the user as a confident but
 * incorrect expiry date in Settings → Sources, which is worse than showing
 * nothing.
 */
class XtreamExpiryTest {
    // 2026-12-31T00:00:00Z
    private val secs = 1_798_675_200L
    private val ms = secs * 1000L

    @Test
    fun `unix seconds become milliseconds`() {
        assertEquals(ms, parseXtreamExpiry(secs.toString()))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(ms, parseXtreamExpiry("  $secs \n"))
    }

    @Test
    fun `a value already in milliseconds is detected by magnitude`() {
        // Some panel forks return ms despite the documented unit. Read as
        // seconds this would imply the year ~58000, so magnitude decides.
        assertEquals(ms, parseXtreamExpiry(ms.toString()))
    }

    @Test
    fun `absent and empty mean no expiry to show`() {
        assertNull(parseXtreamExpiry(null))
        assertNull(parseXtreamExpiry(""))
        assertNull(parseXtreamExpiry("   "))
    }

    @Test
    fun `zero and negative mean no expiry to show`() {
        // "0" is how several panels spell an unlimited account.
        assertNull(parseXtreamExpiry("0"))
        assertNull(parseXtreamExpiry("-1"))
        assertNull(parseXtreamExpiry("-1798675200"))
    }

    @Test
    fun `non-numeric values are not guessed at`() {
        assertNull(parseXtreamExpiry("Unlimited"))
        assertNull(parseXtreamExpiry("2026-12-31"))
        assertNull(parseXtreamExpiry("2026-12-31T00:00:00Z"))
        assertNull(parseXtreamExpiry("null"))
        assertNull(parseXtreamExpiry("N/A"))
        assertNull(parseXtreamExpiry("1798675200.5"))
    }

    @Test
    fun `implausible magnitudes are rejected rather than rendered`() {
        assertNull(parseXtreamExpiry("1"), "1970 — not a real account expiry")
        assertNull(parseXtreamExpiry("999999999999999999"), "overflow-scale garbage")
        assertNull(parseXtreamExpiry(Long.MAX_VALUE.toString()))
    }

    @Test
    fun `the plausible window covers the dates a real account can carry`() {
        // Boundaries of the accepted range, read as seconds.
        assertEquals(946_684_800_000L, parseXtreamExpiry("946684800")) // 2000-01-01
        assertEquals(4_102_444_800_000L, parseXtreamExpiry("4102444800")) // 2100-01-01
        assertNull(parseXtreamExpiry("946684799"), "just before the window")
        assertNull(parseXtreamExpiry("4102444801"), "just after the window")
    }
}
