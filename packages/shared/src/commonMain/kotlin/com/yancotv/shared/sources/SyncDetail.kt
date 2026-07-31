package com.yancotv.shared.sources

/**
 * MK.31.18 — what a [SyncProgress] is currently doing, as data rather than prose.
 *
 * ### Why this type exists
 *
 * `SyncProgress` used to carry a free-text `message: String?` that the sync
 * banner rendered verbatim: "Authenticating", "Fetching categories",
 * "Live 1240". That made the banner permanently English, because
 * `commonMain` cannot reach Android string resources — hard rule #1 in
 * AGENTS.md, and not a rule worth bending: `androidx` in `commonMain` would
 * break the iOS target outright.
 *
 * So the shared module reports *what happened* and the platform decides *how to
 * say it*. Android maps each case to a `@StringRes` in
 * `com.yancotv.android.sources.SyncDetailText`; iOS will map the same cases to
 * its own `Localizable.strings` when that target lands, and gets working
 * localization for free rather than inheriting a bag of English literals.
 *
 * ### Why [Failure] still carries a String
 *
 * Everything else here is a closed set the code chooses. [Failure] is not: its
 * text comes from a provider's HTTP body, an SSL handshake error, or an
 * exception message. There is no resource for "unexpected end of stream", and
 * inventing an enum of every possible provider error would be a lie. It stays a
 * String, and the localized frame around it ("Sync failed for X: …") is what
 * Android supplies.
 *
 * Callers must keep passing that text through
 * [com.yancotv.shared.http.redactCredentials] before display — Xtream failures
 * echo request URLs, and those carry the username and password as path
 * segments (MB-292).
 */
sealed interface SyncDetail {
    /**
     * Queued, before the sync coroutine has done anything. Emitted by the
     * Android coordinator rather than by the repository, so the banner has
     * something to say during the gap between the user's tap and the first
     * real progress event.
     */
    data object Starting : SyncDetail

    /** Opening the connection, before any credentials are exchanged. */
    data object Connecting : SyncDetail

    /** Handshaking with the provider's auth endpoint. */
    data object Authenticating : SyncDetail

    data object FetchingCategories : SyncDetail

    data object FetchingCatalog : SyncDetail

    /** Post-write bookkeeping: index rebuild, orphan sweep, counters. */
    data object Finalizing : SyncDetail

    /** [written] rows committed so far for this content type. */
    data class WritingLive(val written: Int) : SyncDetail

    data class WritingMovies(val written: Int) : SyncDetail

    data class WritingSeries(val written: Int) : SyncDetail

    /** The requested source row was gone by the time the sync started. */
    data class SourceNotFound(val id: String) : SyncDetail

    /**
     * Provider- or transport-supplied error text. Inherently dynamic — see the
     * class doc for why this one is not an enum. MUST be redacted before it
     * reaches a Toast, a log, or the screen.
     */
    data class Failure(val text: String) : SyncDetail
}
