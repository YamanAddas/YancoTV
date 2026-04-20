package com.yancotv.shared.util

/**
 * UTC civil time decomposition from Unix epoch seconds. Used by catch-up URL
 * builders and title cleaner to match the behavior of TS `new Date(...).getUTCxxx()`.
 *
 * Algorithm: Howard Hinnant's civil_from_days
 * (https://howardhinnant.github.io/date_algorithms.html#civil_from_days).
 * Pure arithmetic — no platform dependency.
 */

internal data class Civil(
    val year: Int,
    /** 1..12 */
    val month: Int,
    /** 1..31 */
    val day: Int,
    /** 0..23 */
    val hour: Int,
    /** 0..59 */
    val minute: Int,
    /** 0..59 */
    val second: Int,
)

internal fun civilFromEpochSeconds(epochSecs: Long): Civil {
    val secondsPerDay = 86400L
    // Floor-divide so negatives behave correctly.
    val days = floorDivLong(epochSecs, secondsPerDay)
    val secOfDay = floorModLong(epochSecs, secondsPerDay).toInt()

    val hour = secOfDay / 3600
    val minute = (secOfDay % 3600) / 60
    val second = secOfDay % 60

    // civil_from_days: epoch days relative to 1970-01-01
    val z = days + 719468L
    val era = if (z >= 0) z / 146097L else (z - 146096L) / 146097L
    val doe = (z - era * 146097L).toInt() // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 // [0, 399]
    val y = yoe.toLong() + era * 400L
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
    val mp = (5 * doy + 2) / 153 // [0, 11]
    val d = doy - (153 * mp + 2) / 5 + 1 // [1, 31]
    val m = if (mp < 10) mp + 3 else mp - 9 // [1, 12]
    val year = (if (m <= 2) y + 1 else y).toInt()

    return Civil(year = year, month = m, day = d, hour = hour, minute = minute, second = second)
}

internal fun utcYearFromEpochSeconds(epochSecs: Long): Int = civilFromEpochSeconds(epochSecs).year

private fun floorDivLong(a: Long, b: Long): Long {
    var q = a / b
    if ((a xor b) < 0 && q * b != a) q--
    return q
}

private fun floorModLong(a: Long, b: Long): Long = a - floorDivLong(a, b) * b

