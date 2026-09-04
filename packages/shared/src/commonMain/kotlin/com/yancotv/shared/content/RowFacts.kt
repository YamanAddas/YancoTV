package com.yancotv.shared.content

import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentMetadata
import kotlinx.serialization.json.Json

/**
 * The few facts a browse row can show **without** fetching anything.
 *
 * ### Why this exists
 *
 * `ContentDetailService` fetches the full record — plot, cast, trailer — on
 * demand, per title, which is right for a detail page and impossible for a
 * list: a browse page is 100 rows and a real catalogue is 273,869 items.
 * So the browse surfaces have been rendering year and rating as `nil`.
 *
 * They did not have to. Both are already on the row:
 *
 * - **Rating** rides in `metadata_json`, which the sync writes for every
 *   VOD item. Measured on a real account: **164,224 of 175,064 movies —
 *   94% — carry a non-zero one**, and none of them reached the screen.
 * - **Genre and release date**, for series. The Xtream series *list*
 *   endpoint returns plot, cast, director, genre and release date for every
 *   title, and `ContentWriter` has been storing all of it. Measured on the
 *   live account, of 45,598 series: **genre on 43,351 (95%), release date
 *   on 43,227 (95%), rating on all of them** — and the browse rows showed
 *   none of it.
 * - **Year** is in the title itself, and [extractYear] already knows how to
 *   read it. Measured: **135,471 of 175,064 movies — 77% — carry a
 *   `(YYYY)`**. It has to be read off the *raw* title; see the note in
 *   [rowFacts].
 *
 * This is deliberately *only* the cheap fields. Anything needing a network
 * call or a second query stays in `ContentDetailService` where the cost is
 * paid once, by a screen that is showing one title.
 */
data class RowFacts(
    val id: String,
    /**
     * Release year. Read from the provider's `releaseDate` when there is
     * one — series carry it on 95% of rows — and from the title otherwise,
     * which is where movies keep it.
     */
    val year: Int?,
    /** Provider genre, e.g. `Dram / Aile`. Null when absent. */
    val genre: String?,
    /**
     * Provider rating, 0-10. Null when absent, unparseable, **or zero** —
     * providers write `"0"` for "not rated", and a zero-star rating on
     * screen is a claim the provider is not making.
     */
    val rating: Double?,
    /**
     * Provider synopsis, when the row already carries one.
     *
     * **Series get this free.** The Xtream series *list* endpoint returns a
     * plot for every title and `ContentWriter` has been storing it, so on
     * the live account it is present on essentially all 45,598 of them —
     * while the preview pane's synopsis block rendered empty on every one,
     * because the iOS row model hardcoded the plot to an empty string.
     *
     * Movies carry one only once a detail page has enriched them, which is
     * correct: a plot is not on the movie *list* endpoint, and fetching
     * 175,064 of them to fill a browse row is the trade `ContentDetailService`
     * exists to refuse. So this is populated for what has been opened, and
     * absent otherwise — the block is hidden rather than blank.
     *
     * `description` is the fallback because providers use both keys for the
     * same field, which is why `ContentDetailService` reads both.
     */
    val plot: String?,
)

private val factsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Reads [RowFacts] for a page of rows.
 *
 * Batched because the caller is a browse page: one crossing of the
 * Kotlin/Swift bridge and one pass, rather than 100 of each.
 */
fun rowFacts(items: List<ContentItem>): List<RowFacts> = items.map { item ->
    val meta = item.metadataJson?.let(::parseRowMetadata)
    RowFacts(
        id = item.id,
        // Provider first, then the title.
        //
        // **The raw title, not `displayTitle`.** `cleanTitle` strips
        // bracketed content — that is its job, it is how `[HD]` and
        // `(MULTI)` disappear — and a release year is bracketed too, so
        // `(2019)` is gone from the display string before anything can read
        // it. Measured on the live account: 135,471 of 175,064 movies carry
        // a `(YYYY)`, and reading `displayTitle` found one per hundred.
        year = meta?.releaseDate?.let(::yearFromDate) ?: extractYear(item.title),
        genre = meta?.genre?.trim()?.takeIf { it.isNotEmpty() },
        rating = meta?.rating?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 },
        plot = (meta?.plot ?: meta?.description)?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/**
 * Year out of a provider `releaseDate`.
 *
 * The format is not guaranteed — real rows carry `2023-04-01`, `2023`, and
 * `01/04/2023`. Rather than parse a date, take the first four-digit run
 * that could be a year, which is right for all three and refuses the rest.
 */
private fun yearFromDate(raw: String): Int? = Regex("""(?:^|\D)((?:19|20)\d{2})(?:\D|$)""")
    .find(raw)
    ?.groupValues
    ?.get(1)
    ?.toIntOrNull()

private fun parseRowMetadata(raw: String): ContentMetadata? = runCatching {
    factsJson.decodeFromString<ContentMetadata>(raw)
}.getOrNull()
