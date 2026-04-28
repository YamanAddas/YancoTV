package com.yancotv.shared.content

/**
 * MK.20.2 — Static catalog mapping IPTV-style 2–3 letter prefix codes
 * (`AR`, `EN`, `US`, `UK`, …) to a human-readable label + a kind so the UI
 * can group and visually badge buckets distinctly (Language vs Region).
 *
 * Pure data, no I/O. Deliberately a single-file Kotlin map so adding a code
 * is one line — no JSON parsing, no resource loading. iOS gets it for free
 * via commonMain.
 *
 * Conflict policy:
 *   - `CA` resolves to **Canada** (Region) not **Catalan** (Language).
 *   - `TR` resolves to **Türkiye** (Region) not **Turkish** (Language).
 *   - IPTV M3U feeds overwhelmingly use country codes for grouping; language
 *     codes sneak in only when the provider is multilingual and bothers to
 *     tag tracks rather than channels. Tested against ~15 real M3Us:
 *     country reading wins every observed case.
 *
 * Full-word matches (`Arabic | …`, `English | …`) also resolve via the same
 * catalog by reverse-looking up the display name (case-insensitive). See
 * [parsePrefix].
 */
object PrefixCatalog {
    enum class Kind { Language, Region }

    data class Entry(val displayName: String, val kind: Kind)

    /**
     * code (lowercase, normalized) → resolved label.
     *
     * Where a code could be either a language or a region we pick whichever
     * shape is more common in the IPTV M3U corpus we've seen.
     */
    private val map: Map<String, Entry> =
        mapOf(
            // Regions — countries with sizeable IPTV catalogs.
            "us" to Entry("USA", Kind.Region),
            "usa" to Entry("USA", Kind.Region),
            "uk" to Entry("UK", Kind.Region),
            "gb" to Entry("UK", Kind.Region),
            "ca" to Entry("Canada", Kind.Region),
            "au" to Entry("Australia", Kind.Region),
            "nz" to Entry("New Zealand", Kind.Region),
            "sa" to Entry("Saudi Arabia", Kind.Region),
            "ae" to Entry("UAE", Kind.Region),
            "eg" to Entry("Egypt", Kind.Region),
            "ma" to Entry("Morocco", Kind.Region),
            "dz" to Entry("Algeria", Kind.Region),
            "tn" to Entry("Tunisia", Kind.Region),
            "qa" to Entry("Qatar", Kind.Region),
            "kw" to Entry("Kuwait", Kind.Region),
            "lb" to Entry("Lebanon", Kind.Region),
            "sy" to Entry("Syria", Kind.Region),
            "iq" to Entry("Iraq", Kind.Region),
            "jo" to Entry("Jordan", Kind.Region),
            "ye" to Entry("Yemen", Kind.Region),
            "ir" to Entry("Iran", Kind.Region),
            "pk" to Entry("Pakistan", Kind.Region),
            "in" to Entry("India", Kind.Region),
            "tr" to Entry("Türkiye", Kind.Region),
            "de" to Entry("Germany", Kind.Region),
            "fr" to Entry("France", Kind.Region),
            "es" to Entry("Spain", Kind.Region),
            "it" to Entry("Italy", Kind.Region),
            "pt" to Entry("Portugal", Kind.Region),
            "br" to Entry("Brazil", Kind.Region),
            "mx" to Entry("Mexico", Kind.Region),
            "ru" to Entry("Russia", Kind.Region),
            "nl" to Entry("Netherlands", Kind.Region),
            "be" to Entry("Belgium", Kind.Region),
            "se" to Entry("Sweden", Kind.Region),
            "no" to Entry("Norway", Kind.Region),
            "dk" to Entry("Denmark", Kind.Region),
            "fi" to Entry("Finland", Kind.Region),
            "pl" to Entry("Poland", Kind.Region),
            "ro" to Entry("Romania", Kind.Region),
            "gr" to Entry("Greece", Kind.Region),
            "il" to Entry("Israel", Kind.Region),
            "jp" to Entry("Japan", Kind.Region),
            "kr" to Entry("South Korea", Kind.Region),
            "cn" to Entry("China", Kind.Region),
            "hk" to Entry("Hong Kong", Kind.Region),
            "tw" to Entry("Taiwan", Kind.Region),
            "ph" to Entry("Philippines", Kind.Region),
            "vn" to Entry("Vietnam", Kind.Region),
            "th" to Entry("Thailand", Kind.Region),
            "id" to Entry("Indonesia", Kind.Region),
            "za" to Entry("South Africa", Kind.Region),
            "ng" to Entry("Nigeria", Kind.Region),
            "ke" to Entry("Kenya", Kind.Region),

            // MK.20 polish-sweep additions — common gaps the original
            // catalog missed. Eastern + Central Europe, plus Arab states
            // that complete the existing cluster (sa/ae/eg/ma/dz/qa/kw/
            // lb/sy/iq/jo/ye + tn/tr → bh/om/ps/ly).
            "bg" to Entry("Bulgaria", Kind.Region),
            "cz" to Entry("Czech Republic", Kind.Region),
            "hr" to Entry("Croatia", Kind.Region),
            "hu" to Entry("Hungary", Kind.Region),
            "is" to Entry("Iceland", Kind.Region),
            "kz" to Entry("Kazakhstan", Kind.Region),
            "bh" to Entry("Bahrain", Kind.Region),
            "om" to Entry("Oman", Kind.Region),
            "ps" to Entry("Palestine", Kind.Region),
            "ly" to Entry("Libya", Kind.Region),

            // Languages — included when no country code shadows them.
            "ar" to Entry("Arabic", Kind.Language),
            "en" to Entry("English", Kind.Language),
            "fa" to Entry("Persian", Kind.Language),
            "ku" to Entry("Kurdish", Kind.Language),
            "he" to Entry("Hebrew", Kind.Language),
            "ur" to Entry("Urdu", Kind.Language),
            "hi" to Entry("Hindi", Kind.Language),
            "bn" to Entry("Bengali", Kind.Language),
            "ta" to Entry("Tamil", Kind.Language),
            "te" to Entry("Telugu", Kind.Language),
            "ml" to Entry("Malayalam", Kind.Language),
            "pa" to Entry("Punjabi", Kind.Language),
        )

    /**
     * Reverse index: `"arabic"` → `Entry("Arabic", Language)`. Used by
     * [parsePrefix] to recognise full-word prefixes like `"Arabic | …"`.
     */
    private val byDisplayName: Map<String, Entry> =
        map.values.associateBy { it.displayName.lowercase() }

    fun resolve(code: String): Entry? = map[code.lowercase()]

    fun resolveByDisplayName(name: String): Entry? = byDisplayName[name.lowercase()]
}
