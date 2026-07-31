package com.yancotv.android.recording.schedule

/**
 * MB-337 — where a firing schedule should be routed, as a pure decision.
 *
 * ### Why this is extracted
 *
 * The routing lived inline in `RecordingScheduleReceiver.handlePreFire` as a
 * `when` over three collaborator reads, and it hid a fail-open defect for
 * months: the active-recording read was wrapped in
 * `runCatching { … }.getOrDefault(emptyList())`, so a DB read failure was
 * **indistinguishable from "nothing is recording"**. The receiver then switched
 * the player off the channel being recorded and opened a second connection —
 * against a 1-stream provider that means a corrupted file AND a rejected
 * connection, silently. `RecordingService` never re-checks the cap, so this
 * decision is the only concurrency guard in the system.
 *
 * Inline, that was untestable. Extracted, every branch is a table row — the same
 * playbook already used for `playLaunchDecision`, `tryStartOrFailSchedule`,
 * `shouldSkipForActiveSync` and `decideRecoveryAction`.
 */
enum class PreFireRoute {
    /**
     * The player is already on the scheduled channel: tee off the existing
     * connection. No second connection, no player switch, no interruption.
     */
    TEE_SAME_CHANNEL,

    /** Another channel's recording is in flight — 1-stream cap exhausted. */
    MISSED_CONCURRENT,

    /**
     * MB-337 — the recording table could not be read, so we cannot know whether
     * a recording is in flight. Fail CLOSED: skip rather than risk a second
     * connection.
     */
    MISSED_STATE_UNREADABLE,

    /**
     * MB-209 — nothing is playing. Let the service open its own HTTP connection
     * and write straight to disk; do not kick ExoPlayer with no Surface
     * attached (it would fill its buffer and stop, truncating the recording).
     */
    HEADLESS_FRESH_GET,

    /** Player is on a different channel: switch it, then tee. */
    SWITCH_THEN_TEE,
}

/**
 * Decide the pre-fire route.
 *
 * @param sameChannel the player's current stream URL equals the schedule's.
 * @param activeRecordingCount how many recordings are in flight, or **null when
 *   that could not be determined** — the distinction this whole function exists
 *   to preserve. `0` and `null` used to collapse into the same branch.
 * @param currentUrlPresent whether anything is playing at all.
 *
 * Order matters, and [sameChannel] is deliberately checked **before** the
 * unreadable case: teeing off a connection that is already open cannot breach
 * the 1-stream cap, so it stays safe even when the recording table is
 * unreadable. Refusing to tee there would skip a recording for no benefit.
 */
fun preFireRoute(sameChannel: Boolean, activeRecordingCount: Int?, currentUrlPresent: Boolean): PreFireRoute = when {
    sameChannel -> PreFireRoute.TEE_SAME_CHANNEL
    // MB-337 — fail closed. Ordered before the concurrent check because with a
    // null count there is no count to compare.
    activeRecordingCount == null -> PreFireRoute.MISSED_STATE_UNREADABLE
    activeRecordingCount > 0 -> PreFireRoute.MISSED_CONCURRENT
    !currentUrlPresent -> PreFireRoute.HEADLESS_FRESH_GET
    else -> PreFireRoute.SWITCH_THEN_TEE
}
