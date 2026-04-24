package com.yancotv.shared.sources

/**
 * Encrypts and decrypts short secrets (Xtream passwords, Stalker MAC addresses)
 * before they are written to the `sources` BLOB columns.
 *
 * Platform implementations:
 *  - androidMain: [com.yancotv.shared.sources.AndroidKeystoreCredentialStore]
 *    — AES/GCM via Android Keystore. Key never leaves the TEE on devices that
 *    support StrongBox.
 *  - iosMain: Keychain-backed implementation (lands with MK.iOS.*).
 *  - Tests: [PlaintextCredentialStore] — pass-through, so in-memory DB tests
 *    don't need Keystore.
 *
 * The ciphertext format is opaque to the caller — stores may prepend an IV or
 * version byte. Callers must round-trip bytes byte-for-byte through the DB.
 */
interface CredentialStore {
    fun encrypt(plaintext: String): ByteArray

    fun decrypt(ciphertext: ByteArray): String
}

/**
 * No-encryption fallback. Used by unit tests and (optionally) as an explicit
 * opt-in when the host platform's keystore is unavailable. On a real device
 * we reject construction at the DI boundary — see the Android actual.
 */
class PlaintextCredentialStore : CredentialStore {
    override fun encrypt(plaintext: String): ByteArray = plaintext.encodeToByteArray()

    override fun decrypt(ciphertext: ByteArray): String = ciphertext.decodeToString()
}
