package com.yancotv.shared.sources

/**
 * Progress signal emitted during [SourceRepository.syncSource]. Matches the
 * desktop `SyncProgress` shape (src/main/services/content-store.ts) so the
 * UI layer can be ported with minimal translation.
 */
data class SyncProgress(
    val phase: Phase,
    val current: Int = 0,
    val total: Int = 0,
    val message: String? = null,
) {
    enum class Phase {
        FETCHING,
        PARSING,
        CLASSIFYING,
        WRITING,
        DONE,
        ERROR,
    }
}
