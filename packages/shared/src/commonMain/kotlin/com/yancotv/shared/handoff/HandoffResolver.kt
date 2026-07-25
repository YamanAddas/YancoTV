package com.yancotv.shared.handoff

import com.yancotv.shared.playback.Playable
import com.yancotv.shared.types.ContentItem

/** Why a [HandoffPlayCommand] was refused by [resolveHandoffCommand]. */
enum class HandoffReject {
    /** [HandoffPlayCommand.pairingToken] didn't match the receiver's token. */
    UNAUTHORIZED,

    /** The sender speaks a newer, incompatible envelope version. */
    UNSUPPORTED_SCHEMA,

    /** The item couldn't be turned into something playable (blank URL, etc.). */
    INVALID_ITEM,
}

/**
 * The receiver's decision for a [HandoffPlayCommand], computed by the pure
 * [resolveHandoffCommand]. The Android receiver service pattern-matches this
 * and dispatches to `PlaybackController` on the main thread:
 *  - [PlayContent] -> `play(listOf(item), 0, fromStart)`
 *  - [PlayEpisode] -> `play(episode, fromStart)`
 *  - [Rejected]    -> an HTTP error back to the sender.
 *
 * [HandoffOutcome.PlayContent.resumePositionSeconds] is SECONDS (x1000 for
 * ExoPlayer's seek); honoring it precisely is MK.26.A.3 — A.1 routes the call.
 */
sealed interface HandoffOutcome {
    data class PlayContent(
        val item: ContentItem,
        val resumePositionSeconds: Long,
        val fromStart: Boolean,
        val userAgent: String? = null,
        val referer: String? = null,
    ) : HandoffOutcome

    data class PlayEpisode(
        val episode: Playable.Episode,
        val resumePositionSeconds: Long,
        val fromStart: Boolean,
        val userAgent: String? = null,
        val referer: String? = null,
    ) : HandoffOutcome

    data class Rejected(val reason: HandoffReject) : HandoffOutcome
}

/**
 * Pure decision: validate a [HandoffPlayCommand] and resolve what the receiver
 * should do with it. No I/O, no Android — the service is thin glue over this,
 * which keeps the security-relevant checks unit-testable and iOS-portable.
 *
 * Validation order is deliberate: authorize first (don't leak that a token was
 * accepted by failing later on item shape), then schema, then item validity. A
 * negative [HandoffPlayCommand.resumePositionSeconds] from a buggy/hostile
 * sender is coerced to 0 rather than seeking backwards.
 *
 * @param expectedToken the receiver's current pairing token, or null to skip
 *   the check (A.1 stub; MK.26.A.4 supplies a real per-pairing token).
 */
fun resolveHandoffCommand(command: HandoffPlayCommand, expectedToken: String?): HandoffOutcome {
    // Audit catch — constant-time compare. The pairing code is the sole
    // auth secret on the LAN-exposed /handoff/play endpoint, and a
    // sniffable Wi-Fi could in theory let an attacker time
    // String.equals's first-mismatch short-circuit to recover the
    // token byte by byte. Real attack is fringe on a 6-char / 30-bit
    // token with Wi-Fi jitter, but the project already uses
    // timing-safe compares for PIN verification, so the inconsistency
    // was itself the smell.
    if (expectedToken != null && !timingSafeEquals(command.pairingToken, expectedToken)) {
        return HandoffOutcome.Rejected(HandoffReject.UNAUTHORIZED)
    }
    if (command.schemaVersion > HandoffPlayCommand.SCHEMA_VERSION) {
        return HandoffOutcome.Rejected(HandoffReject.UNSUPPORTED_SCHEMA)
    }
    val position = command.resumePositionSeconds.coerceAtLeast(0L)
    val ua = command.item.userAgent?.takeIf { it.isNotBlank() }
    val referer = command.item.referer?.takeIf { it.isNotBlank() }
    return when (command.item.kind) {
        HandoffKind.EPISODE -> {
            val episode =
                command.item.toEpisodePlayable()
                    ?: return HandoffOutcome.Rejected(HandoffReject.INVALID_ITEM)
            HandoffOutcome.PlayEpisode(episode, position, command.fromStart, ua, referer)
        }

        HandoffKind.CHANNEL, HandoffKind.MOVIE -> {
            val item =
                command.item.toContentItem()
                    ?: return HandoffOutcome.Rejected(HandoffReject.INVALID_ITEM)
            HandoffOutcome.PlayContent(item, position, command.fromStart, ua, referer)
        }
    }
}

/**
 * Constant-time string compare. Mirrors AndroidPinHasher.timingSafeEqual.
 * Returns false fast on length mismatch (length is not the secret —
 * the pairing token is a fixed 6 chars). Otherwise XORs every char
 * and ORs the diffs so an attacker can't time the first mismatch.
 */
private fun timingSafeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) {
        diff = diff or (a[i].code xor b[i].code)
    }
    return diff == 0
}
