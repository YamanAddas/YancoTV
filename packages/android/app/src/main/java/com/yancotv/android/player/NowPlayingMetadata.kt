package com.yancotv.android.player

/**
 * MK.34.1 — split a provider title into the three lines the Now Playing block
 * actually renders.
 *
 * The dock used to print the provider's raw string straight into a 34sp Black
 * title. For a Turkish series episode that string is
 *
 *     Tozkoparan İskender — TR - Tozkoparan İskender (2021) (TR) - S01E03 - 3. Bölüm
 *
 * which says the show's name twice, the country twice, and the episode number
 * twice, across two wrapped lines of very large type. The information the
 * viewer wants — what is this, which episode — is in there, just buried.
 *
 * So this pulls it apart into a title plus ordered metadata segments:
 *
 *     Tozkoparan İskender
 *     2021 · S01 E03 · 3. Bölüm
 *
 * **Structured input wins over parsing.** [season] and [episode] come from
 * `Playable.Episode`, which got them from the Xtream API as integers; the
 * regex fallback exists only for items that never went through that path. A
 * parser that ignores fields it already has is a parser that invents bugs.
 *
 * **No channel segment.** The reference design shows "TRT 1" leading this line,
 * but no such field exists for VOD — the app holds only the Xtream category
 * (here "TURKISH YERLI DIZILER"), and the TRT 1 mark in the reference is burned
 * into the video, not data. [channel] is therefore accepted and rendered when a
 * caller genuinely knows it (LIVE, where the channel IS the item) and omitted
 * otherwise. Guessing it from the category would put a shouty provider string
 * where a broadcaster name belongs.
 *
 * Pure and Compose-free, mirroring [episodeKicker] and [UpNextDecision], so the
 * rules are unit-testable without a player.
 */
internal data class NowPlaying(
    /** One line, already de-duplicated. Never blank — falls back to the input. */
    val title: String,
    /** Ordered, already cleaned. Render joined with " · ". May be empty. */
    val segments: List<String>,
) {
    /** Convenience for rendering and for tests to assert one string. */
    val metadataLine: String get() = segments.joinToString(" · ")
}

/** `(2021)` anywhere in the string. Bounded to a plausible release range. */
private val YEAR = Regex("""\((19\d{2}|20\d{2})\)""")

/** `S01E03`, `S1 E3`, `s01e03` — the space is optional, the padding is not. */
private val SEASON_EPISODE = Regex("""\bS(\d{1,2})\s*E(\d{1,3})\b""", RegexOption.IGNORE_CASE)

/** A bare `(TR)` / `(AR)` country or language tag. */
private val COUNTRY_TAG = Regex("""\(([A-Za-z]{2,3})\)""")

/** A leading `TR - ` / `AR| ` provider prefix on a segment. */
private val LEADING_CODE = Regex("""^[A-Za-z]{2,3}\s*[-|:]\s*""")

/**
 * The separator `EpisodeInfo.toPlayable` uses to join series and episode. Same
 * literal and the same coupling as [episodeKicker] and
 * `subtitles.buildSubtitleQuery`; if it changes, all three change together.
 */
private const val SERIES_EPISODE_JOIN = " — "

/**
 * Build the Now Playing lines for [rawTitle].
 *
 * @param rawTitle the provider string, exactly as stored.
 * @param season structured season number, or null. Preferred over parsing.
 * @param episode structured episode number, or null. Preferred over parsing.
 * @param channel a genuinely known channel name (LIVE), or null to omit the
 *   segment entirely. Never pass a category here.
 */
internal fun nowPlayingFrom(rawTitle: String, season: Int? = null, episode: Int? = null, channel: String? = null): NowPlaying {
    val raw = rawTitle.trim()
    if (raw.isEmpty()) return NowPlaying(title = rawTitle, segments = emptyList())

    // `toPlayable` pre-joins "<series> — <episode detail>". When that join is
    // present the left side is authoritative for the title, which is what makes
    // the de-duplication below reliable rather than heuristic.
    val joinAt = raw.indexOf(SERIES_EPISODE_JOIN)
    val head = if (joinAt >= 0) raw.substring(0, joinAt).trim() else raw
    val detail = if (joinAt >= 0) raw.substring(joinAt + SERIES_EPISODE_JOIN.length).trim() else ""

    val title = cleanTitle(head).ifBlank { raw }

    val segments = mutableListOf<String>()
    channel?.trim()?.takeIf { it.isNotEmpty() }?.let(segments::add)

    // Year: look across the whole string, since providers put it on either side
    // of the join. First match wins — a second one is the same year repeated.
    YEAR.find(raw)?.groupValues?.get(1)?.let(segments::add)

    // Season/episode: structured first. Padded to the S01 E03 form the reference
    // uses, which is deliberately NOT episodeKicker's unpadded S1E2 — that one
    // is a compact badge, this is a metadata line with room to be conventional.
    val se = formatSeasonEpisode(season, episode) ?: SEASON_EPISODE.find(raw)?.let { m ->
        formatSeasonEpisode(m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull())
    }
    se?.let(segments::add)

    episodeName(detail, title)?.let(segments::add)

    return NowPlaying(title = title, segments = segments)
}

private fun formatSeasonEpisode(season: Int?, episode: Int?): String? {
    if (season == null || episode == null) return null
    if (season <= 0 || episode <= 0) return null
    return "S%02d E%02d".format(season, episode)
}

/**
 * Strip provider noise from the title half: a leading country code, a bare
 * `(TR)` tag, and the year (which becomes its own segment).
 *
 * Deliberately conservative — it removes only tokens it can positively
 * identify. Anything it does not recognise stays, because a title rendered
 * with a stray tag is a cosmetic problem while a title with a real word eaten
 * out of it is a wrong one.
 */
private fun cleanTitle(value: String): String = value
    .replace(LEADING_CODE, "")
    .replace(YEAR, "")
    .replace(COUNTRY_TAG, "")
    .replace(Regex("""\s{2,}"""), " ")
    .trim()
    .trim('-', '|', ':', '·')
    .trim()

/**
 * Recover the episode's own name from the detail half — "3. Bölüm" above.
 *
 * The detail is a ` - ` separated list whose contents vary by provider, so
 * rather than assume a position this drops every token it can identify as
 * something else (a country code, the year, the season/episode marker, or the
 * series title repeated) and takes the last of whatever survives. Last, not
 * first, because providers append the episode name after the numbering.
 *
 * Returns null when nothing survives, which is the common and correct outcome
 * for a movie.
 */
private fun episodeName(detail: String, title: String): String? {
    if (detail.isBlank()) return null
    val titleKey = normalizeForCompare(title)
    val candidates = detail
        .split(" - ", " – ", "|")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.replace(COUNTRY_TAG, "").replace(YEAR, "").trim().trim('-', '|', ':').trim() }
        .filter { token ->
            token.isNotEmpty() &&
                // a bare country/language code left on its own
                !token.matches(Regex("""^[A-Za-z]{2,3}$""")) &&
                // the season/episode marker, already its own segment
                !token.matches(SEASON_EPISODE) &&
                // the series name repeated — the whole point of this exercise
                normalizeForCompare(token) != titleKey
        }
    return candidates.lastOrNull()
}

/**
 * Casefold for comparison only, never for display.
 *
 * [java.util.Locale.ROOT] is explicit for the reader's benefit rather than for
 * correctness: Kotlin's `String.lowercase()` is already locale-independent,
 * which is precisely how it differs from Java's `String.toLowerCase()`. Passing
 * ROOT is verified to be a no-op here — removing it leaves every test green
 * under a `tr-TR` default locale.
 *
 * It stays because the trap it names is real for the NEXT edit. Rewriting this
 * to `toLowerCase()` or to `lowercase(Locale.getDefault())` WOULD break it: in
 * Turkish, capital ASCII I lowercases to a dotless ı, so "DIRILIS" and
 * "Dirilis" stop comparing equal and a repeated title escapes into the metadata
 * line. `NowPlayingMetadataTest` pins that case under an actual tr-TR locale.
 *
 * Turkish and Arabic characters are preserved untouched; nothing here strips
 * non-ASCII.
 */
private fun normalizeForCompare(value: String): String = value.lowercase(java.util.Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), "")
