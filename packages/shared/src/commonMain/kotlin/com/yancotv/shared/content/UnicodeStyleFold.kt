package com.yancotv.shared.content

/**
 * Folds the decorative Unicode letterforms IPTV providers dress channel
 * names in back to plain ASCII.
 *
 * Providers routinely style titles with superscript modifier letters,
 * fullwidth forms and mathematical alphanumerics so they stand out in a
 * list: `: V SPORT ᵁᴴᴰ ³⁸⁴⁰ᴾ`, `US| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ`. Those are not the
 * letters U, H, D — they are separate code points, so every ASCII matcher
 * in the codebase silently misses them.
 *
 * Measured on a real 273,869-item account: **0 of the first 1,300 live
 * channels produced a quality badge**, because the parser was looking for
 * "UHD" in text that contains `ᵁᴴᴰ`. The same gap means a search for
 * "UHD" or "4K" cannot find those channels either — FTS tokenises the
 * decorated form as its own word.
 *
 * Deliberately narrow: letters and digits only. Punctuation, emoji, flags
 * and box-drawing characters are left alone, because those are decoration
 * a user may well be reading by, and folding them would change what titles
 * look like rather than what they match as.
 */
object UnicodeStyleFold {

    /**
     * Superscript modifier letters, folded to the **same case** they are
     * styling.
     *
     * Case is preserved rather than normalised because the result is what
     * the user reads, not only what a matcher sees: `US| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ`
     * is "RAW 60fps", and folding it to "RAW 60FPS" would shout a word the
     * provider did not. Downstream matching is case-insensitive anyway.
     */
    private val SUPERSCRIPT_LETTERS =
        mapOf(
            // Uppercase forms.
            'ᴬ' to 'A', 'ᴮ' to 'B', 'ᴰ' to 'D', 'ᴱ' to 'E', 'ᴳ' to 'G',
            'ᴴ' to 'H', 'ᴵ' to 'I', 'ᴶ' to 'J', 'ᴷ' to 'K', 'ᴸ' to 'L',
            'ᴹ' to 'M', 'ᴺ' to 'N', 'ᴼ' to 'O', 'ᴾ' to 'P', 'ᴿ' to 'R',
            'ᵀ' to 'T', 'ᵁ' to 'U', 'ⱽ' to 'V', 'ᵂ' to 'W',
            // Lowercase forms.
            'ᵃ' to 'a', 'ᵇ' to 'b', 'ᶜ' to 'c', 'ᵈ' to 'd', 'ᵉ' to 'e',
            'ᶠ' to 'f', 'ᵍ' to 'g', 'ʰ' to 'h', 'ⁱ' to 'i', 'ʲ' to 'j',
            'ᵏ' to 'k', 'ˡ' to 'l', 'ᵐ' to 'm', 'ⁿ' to 'n', 'ᵒ' to 'o',
            'ᵖ' to 'p', 'ʳ' to 'r', 'ˢ' to 's', 'ᵗ' to 't', 'ᵘ' to 'u',
            'ᵛ' to 'v', 'ʷ' to 'w', 'ˣ' to 'x', 'ʸ' to 'y', 'ᶻ' to 'z',
        )

    /** Superscript digits, which are scattered rather than contiguous. */
    private val SUPERSCRIPT_DIGITS =
        mapOf(
            '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4',
            '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9',
        )

    /**
     * Returns [text] with decorative letterforms replaced by their plain
     * equivalents. Text that carries none is returned unchanged, which is
     * the common case and costs one scan.
     */
    fun fold(text: String): String {
        if (text.isEmpty()) return text
        var touched = false
        val out = StringBuilder(text.length)
        for (ch in text) {
            val plain = plainOrNull(ch)
            if (plain != null) {
                touched = true
                out.append(plain)
            } else {
                out.append(ch)
            }
        }
        return if (touched) out.toString() else text
    }

    private fun plainOrNull(ch: Char): Char? {
        SUPERSCRIPT_LETTERS[ch]?.let { return it }
        SUPERSCRIPT_DIGITS[ch]?.let { return it }
        val code = ch.code
        return when (code) {
            // Fullwidth forms: `Ａ`-`Ｚ`, `ａ`-`ｚ`, `０`-`９`.
            in 0xFF21..0xFF3A -> ('A' + (code - 0xFF21))
            in 0xFF41..0xFF5A -> ('a' + (code - 0xFF41))
            in 0xFF10..0xFF19 -> ('0' + (code - 0xFF10))
            else -> null
        }
    }
}
