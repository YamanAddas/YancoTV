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
    ) : HandoffOutcome

    data class PlayEpisode(
        val episode: Playable.Episode,
        val resumePositionSeconds: Long,
        val fromStart: Boolean,
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
fun resolveHandoffCommand(
    command: HandoffPlayCommand,
    expectedToken: String?,
): HandoffOutcome {
    if (expectedToken != null && command.pairingToken != expectedToken) {
        return HandoffOutcome.Rejected(HandoffReject.UNAUTHORIZED)
    }
    if (command.schemaVersion > HandoffPlayCommand.SCHEMA_VERSION) {
        return HandoffOutcome.Rejected(HandoffReject.UNSUPPORTED_SCHEMA)
    }
    val position = command.resumePositionSeconds.coerceAtLeast(0L)
    return when (command.item.kind) {
        HandoffKind.EPISODE -> {
            val episode =
                command.item.toEpisodePlayable()
                    ?: return HandoffOutcome.Rejected(HandoffReject.INVALID_ITEM)
            HandoffOutcome.PlayEpisode(episode, position, command.fromStart)
        }

        HandoffKind.CHANNEL, HandoffKind.MOVIE -> {
            val item =
                command.item.toContentItem()
                    ?: return HandoffOutcome.Rejected(HandoffReject.INVALID_ITEM)
            HandoffOutcome.PlayContent(item, position, command.fromStart)
        }
    }
}
