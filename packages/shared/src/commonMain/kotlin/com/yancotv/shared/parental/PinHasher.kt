package com.yancotv.shared.parental

/**
 * Platform-agnostic PIN hasher. The shared [ParentalRepository] depends on
 * this interface; androidMain wires in a PBKDF2-HMAC-SHA256 implementation
 * (see `AndroidPinHasher`), and iosMain will supply a CommonCrypto-backed
 * actual in MK.iOS.
 *
 * Format convention (produced by [hash], accepted by [verify]):
 *     pbkdf2:{iterations}:{saltHex}:{hashHex}
 *
 * Stored strings are inert text — callers write them to the settings table
 * and read them back. The hasher owns the format end to end; nothing else
 * in the codebase should parse it.
 *
 * Desktop's scrypt-format hashes are NOT supported on Android — a fresh
 * install has no legacy to migrate and scrypt has no stdlib path on
 * Android SDK 24+. If cross-platform backup restore ever lands, we'll
 * add a legacy code path at that point.
 */
interface PinHasher {
    data class VerifyResult(
        val ok: Boolean,
    )

    /**
     * Hash [pin] with a fresh random salt at the current iteration count.
     * Returns the full self-describing encoded string.
     *
     * Suspend because PBKDF2 @100k iterations takes ~50ms on Fire TV and
     * we don't want to freeze the UI thread — callers dispatch to IO.
     */
    suspend fun hash(pin: String): String

    /**
     * Compare [pin] against a previously-stored [encoded] hash. Uses the
     * iteration count and salt encoded in [encoded], not any global value,
     * so bumping iterations later doesn't invalidate existing hashes.
     *
     * Returns [VerifyResult] with ok = false on malformed input rather
     * than throwing, so callers don't need to try/catch around every
     * verify site.
     */
    suspend fun verify(
        pin: String,
        encoded: String,
    ): VerifyResult
}
