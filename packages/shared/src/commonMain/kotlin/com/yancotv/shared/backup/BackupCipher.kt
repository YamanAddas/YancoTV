package com.yancotv.shared.backup

/**
 * MK.19.8 — Password-based credential encryption for portable backups.
 *
 * When the user picks "encrypted backup" at export time we run their
 * password through PBKDF2-HMAC-SHA256 (matching the parental PIN
 * hasher's primitives — same iteration count tuned for ~50ms on a
 * 2-core Fire TV), then use the derived 256-bit key to AES/GCM the
 * three credential blobs per source. A fresh random IV is prepended to
 * each ciphertext (12 bytes), same shape as
 * [com.yancotv.shared.sources.AndroidKeystoreCredentialStore] — but
 * the KEY itself is portable (derivable on any device from the user's
 * password) instead of Keystore-bound.
 *
 * Trade-off documented in the PRODUCTION_PLAN_NATIVE.md MK.19.8 spec:
 * password mode is opt-in; default is plaintext + warning, matching the
 * desktop backup-service.ts safeStorage flow.
 */
expect class BackupCipher() {
    /** Derive a 256-bit key from the user's password. */
    fun deriveKey(password: String, saltHex: String, iterations: Int): ByteArray

    /** Encrypt arbitrary bytes; returns hex-encoded `iv || ciphertext || tag`. */
    fun encryptHex(plaintext: ByteArray, key: ByteArray): String

    /** Decrypt `iv || ciphertext || tag` hex string. Throws on tag mismatch. */
    fun decryptBytes(hex: String, key: ByteArray): ByteArray

    /** Generate a random 16-byte salt, returned as hex. */
    fun randomSaltHex(): String
}

/** Default PBKDF2 iteration count — same calibration as PIN hasher. */
const val BACKUP_PBKDF2_ITERATIONS = 100_000
