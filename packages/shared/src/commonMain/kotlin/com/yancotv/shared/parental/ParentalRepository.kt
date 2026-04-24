package com.yancotv.shared.parental

import com.yancotv.shared.db.YancoDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Parental-controls facade. Mirrors desktop `parental-service.ts` behavior:
 * PIN-hashed access, brute-force lockout with exponential cooldown, toggle
 * settings (hide adult / require PIN for Settings), and per-channel
 * lock + hide registries.
 *
 * PIN hashing is delegated to [PinHasher] so the shared module stays
 * platform-agnostic (Android uses PBKDF2; iOS will wire CommonCrypto in
 * MK.iOS.*).
 *
 * Clock is injected so tests can fast-forward the lockout window without
 * sleeping 30 s.
 */
class ParentalRepository(
    private val db: YancoDb,
    private val hasher: PinHasher,
    private val clock: () -> Long,
) {
    // In-memory lockout state. Matches desktop — survives across verify
    // calls within one process but resets on app restart (acceptable: an
    // attacker with app kill access has bigger problems). Protected by a
    // mutex because verify() is suspend + concurrent callers are plausible
    // (Settings screen + PlaybackController gate can both trigger it).
    private val lockoutMutex = Mutex()
    private var failedAttempts = 0
    private var lockoutUntilMs = 0L

    private val _settings = MutableStateFlow(readSettings())

    /** Observable settings flow — Settings screen `collectAsState` on this. */
    val settings: StateFlow<ParentalSettings> = _settings.asStateFlow()

    private val _lockedIds = MutableStateFlow(loadLockedIds())
    val lockedIds: StateFlow<Set<String>> = _lockedIds.asStateFlow()

    private val _hiddenIds = MutableStateFlow(loadHiddenIds())
    val hiddenIds: StateFlow<Set<String>> = _hiddenIds.asStateFlow()

    // ───── PIN ─────

    /** Returns ms remaining on the current brute-force cooldown, or 0 if not locked. */
    suspend fun lockoutRemainingMs(): Long =
        lockoutMutex.withLock {
            (lockoutUntilMs - clock()).coerceAtLeast(0L)
        }

    suspend fun setPin(pin: String) {
        val encoded = hasher.hash(pin)
        db.settingsQueries.upsert(KEY_PIN_HASH, encoded)
        db.settingsQueries.upsert(KEY_PIN_ENABLED, "1")
        lockoutMutex.withLock {
            failedAttempts = 0
            lockoutUntilMs = 0L
        }
        _settings.value = readSettings()
    }

    suspend fun removePin() {
        db.settingsQueries.delete(KEY_PIN_HASH)
        db.settingsQueries.upsert(KEY_PIN_ENABLED, "0")
        lockoutMutex.withLock {
            failedAttempts = 0
            lockoutUntilMs = 0L
        }
        _settings.value = readSettings()
    }

    /**
     * Verify [pin] against the stored hash.
     *
     * Returns false during an active lockout regardless of correctness so
     * a correct-PIN-after-wrong-bursts attacker still pays their cooldown.
     *
     * Successful verify resets the failure counter and cooldown. Failed
     * verify increments the counter; once it crosses [FAIL_THRESHOLD],
     * the lockout window doubles per further miss up to [LOCKOUT_MAX_MS].
     */
    suspend fun verifyPin(pin: String): Boolean {
        lockoutMutex.withLock {
            if ((lockoutUntilMs - clock()) > 0L) return false
        }
        val stored = db.settingsQueries.get(KEY_PIN_HASH).executeAsOneOrNull() ?: return false
        val result = hasher.verify(pin, stored)
        lockoutMutex.withLock {
            if (result.ok) {
                failedAttempts = 0
                lockoutUntilMs = 0L
            } else {
                failedAttempts += 1
                if (failedAttempts >= FAIL_THRESHOLD) {
                    val over = failedAttempts - FAIL_THRESHOLD
                    val cooldown = (LOCKOUT_BASE_MS * (1L shl over.coerceAtMost(10))).coerceAtMost(LOCKOUT_MAX_MS)
                    lockoutUntilMs = clock() + cooldown
                }
            }
        }
        return result.ok
    }

    /** Explicit reset — used by Settings after a successful manual override. */
    suspend fun resetAttempts() {
        lockoutMutex.withLock {
            failedAttempts = 0
            lockoutUntilMs = 0L
        }
    }

    // ───── Settings toggles ─────

    fun setHideAdultContent(enabled: Boolean) {
        db.settingsQueries.upsert(KEY_HIDE_ADULT, if (enabled) "1" else "0")
        _settings.value = readSettings()
    }

    fun setRequirePinForSettings(enabled: Boolean) {
        db.settingsQueries.upsert(KEY_REQUIRE_PIN_SETTINGS, if (enabled) "1" else "0")
        _settings.value = readSettings()
    }

    // ───── Channel lock ─────

    fun lockChannel(contentId: String) {
        db.parentalQueries.lockChannel(contentId, clock())
        _lockedIds.value = _lockedIds.value + contentId
    }

    fun unlockChannel(contentId: String) {
        db.parentalQueries.unlockChannel(contentId)
        _lockedIds.value = _lockedIds.value - contentId
    }

    fun isChannelLocked(contentId: String): Boolean = db.parentalQueries.isLocked(contentId).executeAsOne()

    // ───── Channel hide ─────

    fun hideChannel(contentId: String) {
        db.parentalQueries.hideChannel(contentId, clock())
        _hiddenIds.value = _hiddenIds.value + contentId
    }

    fun unhideChannel(contentId: String) {
        db.parentalQueries.unhideChannel(contentId)
        _hiddenIds.value = _hiddenIds.value - contentId
    }

    // ───── internals ─────

    private fun readSettings(): ParentalSettings =
        ParentalSettings(
            pinSet = db.settingsQueries.get(KEY_PIN_HASH).executeAsOneOrNull() != null,
            pinEnabled = db.settingsQueries.get(KEY_PIN_ENABLED).executeAsOneOrNull() == "1",
            hideAdultContent = db.settingsQueries.get(KEY_HIDE_ADULT).executeAsOneOrNull() == "1",
            requirePinForSettings = db.settingsQueries.get(KEY_REQUIRE_PIN_SETTINGS).executeAsOneOrNull() == "1",
        )

    private fun loadLockedIds(): Set<String> =
        db.parentalQueries
            .selectLocked()
            .executeAsList()
            .toHashSet()

    private fun loadHiddenIds(): Set<String> =
        db.parentalQueries
            .selectHidden()
            .executeAsList()
            .toHashSet()

    companion object {
        const val KEY_PIN_HASH = "parental_pin_hash"
        const val KEY_PIN_ENABLED = "parental_pin_enabled"
        const val KEY_HIDE_ADULT = "parental_hide_adult"
        const val KEY_REQUIRE_PIN_SETTINGS = "parental_require_pin_settings"

        // Brute-force limit — matches desktop. Five free misses, then a
        // 30 s lock that doubles with each further miss, capped at 5 min.
        const val FAIL_THRESHOLD = 5
        const val LOCKOUT_BASE_MS = 30_000L
        const val LOCKOUT_MAX_MS = 5L * 60_000L
    }
}

data class ParentalSettings(
    val pinSet: Boolean,
    val pinEnabled: Boolean,
    val hideAdultContent: Boolean,
    val requirePinForSettings: Boolean,
)
