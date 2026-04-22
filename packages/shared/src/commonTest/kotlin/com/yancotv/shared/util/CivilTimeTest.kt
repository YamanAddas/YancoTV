package com.yancotv.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [civilFromEpochSeconds] — the Hinnant civil_from_days algorithm
 * used by catch-up URL builders and title cleaner to match TS `Date.getUTCxxx`.
 *
 * Hits the corner cases that bite naive epoch-decoding implementations:
 *  - Unix epoch itself (1970-01-01 00:00:00)
 *  - Leap years (2000 was a leap year, 1900 was not, 2024 is a leap year)
 *  - Pre-epoch (negative) seconds, including near-midnight boundaries
 *  - End-of-day rollovers
 */
class CivilTimeTest {

    @Test fun epochIsJan1_1970() {
        val c = civilFromEpochSeconds(0)
        assertEquals(1970, c.year)
        assertEquals(1, c.month)
        assertEquals(1, c.day)
        assertEquals(0, c.hour)
        assertEquals(0, c.minute)
        assertEquals(0, c.second)
    }

    @Test fun y2kBoundary() {
        // 2000-01-01 00:00:00 UTC == 946684800
        val c = civilFromEpochSeconds(946684800L)
        assertEquals(2000, c.year)
        assertEquals(1, c.month)
        assertEquals(1, c.day)
    }

    @Test fun leapDay2024() {
        // 2024-02-29 12:34:56 UTC == 1709210096
        val c = civilFromEpochSeconds(1709210096L)
        assertEquals(2024, c.year)
        assertEquals(2, c.month)
        assertEquals(29, c.day)
        assertEquals(12, c.hour)
        assertEquals(34, c.minute)
        assertEquals(56, c.second)
    }

    @Test fun dayRollover() {
        // 1970-01-02 00:00:00 UTC == 86400
        val c = civilFromEpochSeconds(86400L)
        assertEquals(1970, c.year)
        assertEquals(1, c.month)
        assertEquals(2, c.day)
        assertEquals(0, c.hour)
    }

    @Test fun lastSecondOfDay() {
        // 1970-01-01 23:59:59 UTC == 86399
        val c = civilFromEpochSeconds(86399L)
        assertEquals(1970, c.year)
        assertEquals(1, c.month)
        assertEquals(1, c.day)
        assertEquals(23, c.hour)
        assertEquals(59, c.minute)
        assertEquals(59, c.second)
    }

    @Test fun preEpochNegativeSeconds() {
        // 1969-12-31 23:59:59 UTC == -1
        val c = civilFromEpochSeconds(-1L)
        assertEquals(1969, c.year)
        assertEquals(12, c.month)
        assertEquals(31, c.day)
        assertEquals(23, c.hour)
        assertEquals(59, c.minute)
        assertEquals(59, c.second)
    }

    @Test fun preEpochDeeplyNegative() {
        // 1900-01-01 00:00:00 UTC == -2208988800
        val c = civilFromEpochSeconds(-2208988800L)
        assertEquals(1900, c.year)
        assertEquals(1, c.month)
        assertEquals(1, c.day)
    }

    @Test fun utcYearFromEpochSecondsMatches() {
        assertEquals(1970, utcYearFromEpochSeconds(0))
        assertEquals(2024, utcYearFromEpochSeconds(1709209096L))
        assertEquals(1969, utcYearFromEpochSeconds(-1L))
    }

    @Test fun nonLeap1900() {
        // 1900 was NOT a leap year (Gregorian: divisible by 100 but not 400).
        // 1900-02-28 23:59:59 UTC == -2203891201
        // 1900-03-01 00:00:00 UTC == -2203891200
        val before = civilFromEpochSeconds(-2203891201L)
        assertEquals(1900, before.year); assertEquals(2, before.month); assertEquals(28, before.day)
        val after = civilFromEpochSeconds(-2203891200L)
        assertEquals(1900, after.year); assertEquals(3, after.month); assertEquals(1, after.day)
    }

    @Test fun leap2000() {
        // 2000 WAS a leap year (divisible by 400).
        // 2000-02-29 00:00:00 UTC == 951782400
        val c = civilFromEpochSeconds(951782400L)
        assertEquals(2000, c.year); assertEquals(2, c.month); assertEquals(29, c.day)
    }
}
