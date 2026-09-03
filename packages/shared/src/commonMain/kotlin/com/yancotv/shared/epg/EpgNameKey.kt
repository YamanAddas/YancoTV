package com.yancotv.shared.epg

import com.yancotv.shared.content.UnicodeStyleFold

/**
 * Reduces a channel name to a key two sources can be compared on.
 *
 * ### Why this is needed at all
 *
 * A programme is matched to a channel by `tvg_id`, and most channels have
 * none. Measured on a real account: **7,841 of 53,207 live rows carry one**,
 * and 6,333 of those find a guide — so about **12% of the channel list can
 * show a programme**, and the other 46,000 rows are unreachable no matter
 * how good the guide is.
 *
 * The names, though, are right there on both sides. The provider calls a
 * channel `TNT SPORTS ᵁᴴᴰ ³⁸⁴⁰ᴾ`; the XMLTV file calls the same channel
 * `TNT Sports` under the id `TNTSports.uk`. Nothing can match those as
 * they stand — the ids are opaque and the titles are decorated — but their
 * *names* agree once the decoration is taken off.
 *
 * ### Why it is deliberately strict
 *
 * A wrong match is worse than no match. A blank programme line is honest;
 * "now: Match of the Day" on a channel showing something else is the app
 * lying, and the viewer has no way to tell. So this normalises hard and
 * then demands **exact equality** — no edit distance, no prefix matching,
 * no "closest wins". `keyFor` returning null means "do not attempt a
 * match", and the caller must treat an ambiguous key (two different guide
 * channels reducing to the same one) as no match at all.
 *
 * What gets removed, and why each is safe:
 *
 * - **Decorative letterforms.** `ᵁᴴᴰ` is not the letters U, H, D — they are
 *   separate code points, so every ASCII matcher misses them.
 *   [UnicodeStyleFold] already handles this for search and quality badges.
 * - **Latin diacritics.** A real XMLTV file carries `13ème Rue` where the
 *   playlist has `13eme Rue`.
 * - **A country/provider prefix** — `AR| `, `TR - `, `US: `. These identify
 *   the *playlist section*, not the channel, and the guide never carries
 *   them.
 * - **Quality and format tokens** — `UHD`, `FHD`, `HD`, `SD`, `4K`, `8K`,
 *   `1080P`, `HEVC`, `RAW`, `60FPS`. The same channel is listed once per
 *   quality, and all of those rows are the same channel as far as a
 *   programme is concerned.
 * - **Everything that is not a letter or a digit.** Emoji, pipes, dashes,
 *   plus signs, spaces. Providers decorate heavily and inconsistently;
 *   `beIN SPORTS 1` and `beIN-Sports1 ⚽` are the same name.
 *
 * What is **kept**: digits. `BEIN SPORTS 1` and `BEIN SPORTS 2` must not
 * collapse into each other, and dropping trailing numbers is the single
 * easiest way to put the wrong match on a sports channel.
 */
object EpgNameKey {

    /** `AR| `, `TR - `, `US: ` and friends at the very start of a name. */
    private val COUNTRY_PREFIX = Regex("""^\s*[A-Za-z]{2,3}\s*[|:\-]\s*""")

    /**
     * Quality, codec and frame-rate tokens, as whole words.
     *
     * `\b` matters: without it `SD` would eat the "SD" out of a name that
     * merely contains those letters, and `4K` would strike inside a
     * channel numbered 4000.
     */
    private val QUALITY_TOKENS =
        Regex(
            """\b(?:4K|8K|UHD|FHD|QHD|HD|SD|HQ|LQ|H\.?26[45]|HEVC|HDR10|HDR|RAW|MULTI|VIP|\d{3,4}[PI]|\d{2,3}FPS)\b""",
            RegexOption.IGNORE_CASE,
        )

    // Anything that is not a letter or a digit is dropped by a `filter`
    // rather than a regex.
    //
    // `Regex("[^\\p{L}\\p{N}]+")` was the obvious spelling and it works
    // on the JVM — which is where the tests run. **Kotlin/Native's regex
    // does not support Unicode property classes**, so on the device the
    // object's initializer threw and the whole EPG refresh came back
    // "There was an error during file or class initialization" with zero
    // programmes. `Char.isLetterOrDigit()` is common Kotlin, script-aware,
    // and cheaper than a regex pass.

    /**
     * Latin diacritics, folded to their base letter.
     *
     * Needed because the two sides spell the same channel differently:
     * a real XMLTV file carries `13ème Rue` while the playlist has
     * `13eme Rue`, and without this they are simply different strings. It
     * covers what European and Turkish listings actually use — Latin-1
     * Supplement and Latin Extended-A.
     *
     * Kotlin/Native has no `java.text.Normalizer`, so this is a table. It
     * is crude in places (`Æ` to `A`, `ß` to `s`) and that is fine:
     * **both sides go through the same fold**, so the only property that
     * matters is that it is deterministic. It is used for matching, never
     * for anything the user reads.
     */
    private val DIACRITICS: Map<Char, Char> =
        buildMap {
            fun fold(from: String, to: Char) = from.forEach { put(it, to) }
            fold("ÀÁÂÃÄÅĀĂĄÆ", 'A')
            fold("àáâãäåāăąæ", 'a')
            fold("ÇĆĈĊČ", 'C')
            fold("çćĉċč", 'c')
            fold("ÐĎĐ", 'D')
            fold("ðďđ", 'd')
            fold("ÈÉÊËĒĔĖĘĚ", 'E')
            fold("èéêëēĕėęě", 'e')
            fold("ĜĞĠĢ", 'G')
            fold("ĝğġģ", 'g')
            fold("ĤĦ", 'H')
            fold("ĥħ", 'h')
            fold("ÌÍÎÏĨĪĬĮİ", 'I')
            fold("ìíîïĩīĭįı", 'i')
            fold("ĴĶĹĻĽĿŁ", 'L')
            fold("ĵķĺļľŀł", 'l')
            fold("ÑŃŅŇŊ", 'N')
            fold("ñńņňŋ", 'n')
            fold("ÒÓÔÕÖØŌŎŐŒ", 'O')
            fold("òóôõöøōŏőœ", 'o')
            fold("ŔŖŘ", 'R')
            fold("ŕŗř", 'r')
            fold("ŚŜŞŠ", 'S')
            fold("śŝşšß", 's')
            fold("ŢŤŦ", 'T')
            fold("ţťŧ", 't')
            fold("ÙÚÛÜŨŪŬŮŰŲ", 'U')
            fold("ùúûüũūŭůűų", 'u')
            fold("Ŵ", 'W')
            fold("ŵ", 'w')
            fold("ÝŶŸ", 'Y')
            fold("ýÿŷ", 'y')
            fold("ŹŻŽ", 'Z')
            fold("źżž", 'z')
        }

    private fun foldDiacritics(text: String): String {
        if (text.none { it.code > 0x7F && DIACRITICS.containsKey(it) }) return text
        val out = StringBuilder(text.length)
        for (ch in text) out.append(DIACRITICS[ch] ?: ch)
        return out.toString()
    }

    /**
     * A comparable key for [name], or null when what is left is too thin to
     * match on safely.
     */
    fun keyFor(name: String?): String? {
        if (name.isNullOrBlank()) return null

        var text = foldDiacritics(UnicodeStyleFold.fold(name))
        text = COUNTRY_PREFIX.replace(text, "")
        text = QUALITY_TOKENS.replace(text, " ")

        val key = text.filter { it.isLetterOrDigit() }.lowercase()

        // Two or three characters is not a channel name, it is whatever
        // survived the strips — `#####` reduces to nothing, `4K| ᵁᴴᴰ ³⁸⁴⁰ᴾ`
        // to nothing, `TV` to two letters that hundreds of channels share.
        // Matching on those would be worse than not matching.
        if (key.length < 4) return null

        // A key that is only digits came from a name that was only a number
        // — a channel slot, not an identity.
        if (key.all { it.isDigit() }) return null

        return key
    }

    /**
     * Builds a name lookup for **one guide**.
     *
     * ### Duplicated names are the normal case, not the exception
     *
     * The first version dropped every key two channels claimed, on the
     * principle that a wrong match is worse than none. Measured against a
     * real public guide, that principle was far too expensive: of 756
     * channels only 405 names were distinct and only **133 appeared
     * exactly once**, so matching a 295-channel playlist against it went
     * from 32 possible matches down to 4. Nearly all of those duplicates
     * are the same channel listed twice — an HD and an SD feed, or a
     * regional variant — where either answer is right.
     *
     * So a contested key is **decided, not discarded**: [programmeCounts]
     * ranks the candidates and the fullest listing wins. That is the one
     * signal in an XMLTV file that says which entry the publisher actually
     * maintains; an empty duplicate is of no use to anyone even when it is
     * the "correct" channel. Ties break on the id, so the result is stable
     * across refreshes.
     *
     * Still scoped to one guide. Two *guides* naming the same channel is
     * redundancy rather than a clash, and is resolved at read time by
     * preferring the channel's own provider.
     */
    fun uniqueIndex(
        channels: List<Pair<String, String?>>,
        programmeCounts: Map<String, Int> = emptyMap(),
    ): Map<String, String> {
        val best = mutableMapOf<String, String>()
        for ((tvgId, displayName) in channels) {
            val key = keyFor(displayName) ?: continue
            val incumbent = best[key]
            if (incumbent == null) {
                best[key] = tvgId
                continue
            }
            if (incumbent == tvgId) continue
            val challengerCount = programmeCounts[tvgId] ?: 0
            val incumbentCount = programmeCounts[incumbent] ?: 0
            val wins = challengerCount > incumbentCount ||
                (challengerCount == incumbentCount && tvgId < incumbent)
            if (wins) best[key] = tvgId
        }
        return best
    }
}
