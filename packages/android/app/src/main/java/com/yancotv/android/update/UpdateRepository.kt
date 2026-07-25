package com.yancotv.android.update

import com.yancotv.android.prefs.AppPreferences
import com.yancotv.shared.update.UpdateCheckOutcome
import com.yancotv.shared.update.UpdateChecker
import com.yancotv.shared.update.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stage 5.2.2 — Android-side wrapper around the pure
 * [com.yancotv.shared.update.UpdateChecker]. Owns the in-memory
 * [StateFlow] of the latest known [UpdateInfo] (or null) and the
 * single-flight check trigger.
 *
 * Why a thin wrapper instead of using `UpdateChecker` directly:
 *   1. UI surfaces (Settings → About, sidebar badge in 5.2.3) need a
 *      reactive `Flow` to subscribe to. The pure checker is one-shot.
 *   2. Multiple call sites can request a check (the WorkManager worker,
 *      the manual "Check now" button, future "auto-check on boot"). A
 *      [Mutex]-guarded `triggerCheck()` makes overlapping requests
 *      coalesce — never two in flight at once.
 *   3. Persisting `lastCheckedAt` on every successful run lives here
 *      so it's done once + tested in one place rather than duplicated
 *      at every caller.
 *
 * State is in-memory only — across app restarts the UpdateInfo is null
 * until the next worker tick. This is a deliberate trade: persisting
 * the JSON would let a stale "update available" prompt linger after the
 * user has already updated. Re-fetching on next tick costs ~1 KB of
 * network and keeps the prompt source-of-truth = the live endpoint.
 */
class UpdateRepository(private val checker: UpdateChecker, private val prefs: AppPreferences, private val now: () -> Long = { System.currentTimeMillis() }) {
    private val _info = MutableStateFlow<UpdateInfo?>(null)
    val info: StateFlow<UpdateInfo?> = _info.asStateFlow()

    private val _lastCheck = MutableStateFlow<UpdateCheckOutcome?>(null)
    val lastCheck: StateFlow<UpdateCheckOutcome?> = _lastCheck.asStateFlow()

    /**
     * Mirrors [UpdateChecker.isConfigured] so UI can read it via
     * koinInject() without pulling the checker directly. Fixed at
     * construction time (BuildConfig.UPDATE_ENDPOINT is baked in at
     * build time), so a plain val is enough — no Flow needed.
     */
    val isConfigured: Boolean
        get() = checker.isConfigured

    private val checkMutex = Mutex()

    /**
     * Run the underlying [UpdateChecker.check]. Idempotent on overlap —
     * a second concurrent caller waits for the first to finish and
     * sees the same result via [info]. Updates `lastCheckedAt` only
     * when [UpdateChecker.isConfigured] is true — otherwise the call
     * is a no-op and we don't want a misleading "Just now" timestamp
     * masking the fact that the build doesn't have an update endpoint
     * wired (the original behavior, before 2026-04-28's fix, *did*
     * write the timestamp on no-ops, which made dev builds look like
     * they were checking when they weren't).
     *
     * Returns the resolved [UpdateInfo] (or null) for callers that want
     * to act immediately rather than collecting from [info].
     */
    suspend fun triggerCheck(): UpdateInfo? = when (val outcome = triggerCheckOutcome()) {
        is UpdateCheckOutcome.Available -> outcome.info
        else -> null
    }

    /**
     * Manual-check friendly variant of [triggerCheck]. It keeps the
     * reason for the outcome so Settings can say "you're on the
     * latest version" or "couldn't check" instead of silently doing
     * nothing when there is no update.
     */
    suspend fun triggerCheckOutcome(): UpdateCheckOutcome = checkMutex.withLock {
        if (!checker.isConfigured) {
            _lastCheck.value = UpdateCheckOutcome.Disabled
            _info.value = null
            return@withLock UpdateCheckOutcome.Disabled
        }
        _lastCheck.value = UpdateCheckOutcome.Checking
        val outcome = checker.checkDetailed()
        _lastCheck.value = outcome
        _info.value =
            when (outcome) {
                is UpdateCheckOutcome.Available -> outcome.info
                else -> null
            }
        prefs.setLastUpdateCheckAt(now())
        outcome
    }

    /**
     * Used by [com.yancotv.android.ui.settings.SettingsAboutTab]'s
     * "Dismiss" path (Stage 5.2.3 will add a UI dismissal). Clears the
     * in-memory state without touching the persisted lastCheckedAt —
     * next worker tick will re-populate if the update is still
     * relevant.
     */
    fun clearKnownUpdate() {
        _info.value = null
    }
}
