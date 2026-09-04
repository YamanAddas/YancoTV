package com.yancotv.android.locale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
import java.util.Locale

/**
 * MK.38.5 — one place that turns a number into text.
 *
 * ### The bug this closes
 *
 * The app had no numeral rule. Which digits an Arabic reader saw depended
 * entirely on which API the code happened to reach for:
 *
 * | How the number was rendered | Arabic sees | Sites |
 * |---|---|---|
 * | `stringResource(id, count)` / plurals — `String.format` under the hood | `٨:٠٣` | 129 |
 * | `"${value}"` / `Int.toString()` — locale-independent, always | `100%` | the rest |
 *
 * So one settings screen showed `بقي ٢ د` above `100%`. Not a decision anyone
 * made — `String.format` consults the locale and a Kotlin string template
 * cannot, and nothing in the codebase said which one to use.
 *
 * ### The rule
 *
 * **A quantity goes through here. An identifier does not.**
 *
 * A quantity is something the reader reads as an amount: a count, a duration, a
 * percentage. An identifier is a number that is part of a name — a channel
 * number, a port, `M3U`, `1080p`, `Apache 2.0`. Transliterating an identifier
 * is worse than leaving it: `M٣U` is not a thing, and a channel numbered `001`
 * is a label printed beside a name, not a total of anything.
 *
 * That distinction is why this is a small helper and not a lint rule banning
 * `toString`. `ChannelNumberFormat.format` deliberately uses `toString()` and
 * should keep doing so.
 *
 * ### Why the locale comes from the Configuration
 *
 * [LocaleController] applies the in-app language by wrapping the Activity's
 * base `Context`, so the `Configuration` is what resolved every other string on
 * screen. `Locale.getDefault()` is a separate process-wide value that usually
 * agrees — and "usually agrees" is how a localisation bug survives its test.
 */
object Numerals {

    /** An integer quantity in [locale]'s digits. */
    fun format(value: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

    /**
     * A whole percentage in [locale]'s digits and its own percent sign.
     *
     * [percent] is the number a person would say — 100 means 100%, not 1.0 —
     * because every call site holds it that way and converting at each of them
     * is one more place to divide by the wrong thing.
     *
     * Delegating to `getPercentInstance` rather than appending `"%"` also picks
     * up conventions we would otherwise have to know: Arabic's `٪`, and the
     * space French puts before the sign.
     */
    fun percent(percent: Int, locale: Locale): String = NumberFormat.getPercentInstance(locale).format(percent / 100.0)
}

/** [Numerals.format] against the locale that resolved this composition. */
@Composable
fun localeNumber(value: Long): String {
    val locales = LocalConfiguration.current.locales
    return remember(value, locales) { Numerals.format(value, locales[0]) }
}

@Composable
fun localeNumber(value: Int): String = localeNumber(value.toLong())

/** [Numerals.percent] against the locale that resolved this composition. */
@Composable
fun localePercent(percent: Int): String {
    val locales = LocalConfiguration.current.locales
    return remember(percent, locales) { Numerals.percent(percent, locales[0]) }
}
