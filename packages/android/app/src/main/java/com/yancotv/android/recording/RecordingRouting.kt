package com.yancotv.android.recording

/**
 * MK.14.8 + MB-213 — Pure decision: should a fresh recording request
 * tap into the player's existing byte stream (live-tee), or open its
 * own HTTP connection (fresh-GET)?
 *
 * Decision rule:
 *  - [LiveTee] when there's an active playback whose `streamUrl`
 *    matches the requested URL exactly. Avoids the 1-stream IPTV cap
 *    on a foreground stream.
 *  - [FreshGet] otherwise — including the headless / standby case
 *    (`playingUrl == null`) where the player has no surface to keep
 *    bytes flowing (see MB-209).
 *
 * Extracted into a pure function so it can be pinned by JVM unit tests
 * without standing up a `PlaybackController` or `RecordingService`.
 * Real comparison is exact-string equality — we deliberately do NOT
 * normalise trailing slashes / query params, because a "different
 * URL" with identical canonical form may still be a separate stream
 * on the provider side (e.g. some Xtream providers append `?token=`
 * with rotating values).
 */
sealed interface RecordingPath {
    data object LiveTee : RecordingPath

    data object FreshGet : RecordingPath
}

object RecordingRouting {
    fun decide(playingUrl: String?, requestedUrl: String): RecordingPath = // Both sides must be non-blank AND identical. Production
        // RecordInput contract forbids blank requestedUrl already, but
        // pinning the boundary here keeps the function safe to call
        // from any context (UI debug, future caller, or a fuzz input).
        if (!playingUrl.isNullOrBlank() && requestedUrl.isNotBlank() && playingUrl == requestedUrl) {
            RecordingPath.LiveTee
        } else {
            RecordingPath.FreshGet
        }
}
