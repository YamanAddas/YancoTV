@file:OptIn(ExperimentalForeignApi::class)

package com.yancotv.shared.backup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * iOS actual for [BackupCipher] — CommonCrypto for the KDF and RNG, a
 * local SP 800-38D GCM assembly ([AesGcm]) for the cipher.
 *
 * CryptoKit is the idiomatic Apple choice for AES-GCM, but it is a
 * Swift-only framework and Kotlin/Native can only import Objective-C and
 * C interfaces. MK.iOS.0-pre.2 originally wrote this against
 * CommonCrypto's `CCCryptorGCMOneshotEncrypt` / `…Decrypt` and carried
 * an explicit "unverified: is that pair bound at all?" flag. The first
 * ios-compile gate run (2026-08-19) answered no — every CommonCrypto GCM
 * entry point is private SPI, invisible to Kotlin/Native's public-header
 * klibs and an App Store liability besides. The public surface that IS
 * bound — `CCKeyDerivationPBKDF`, `SecRandomCopyBytes`, and one-shot AES
 * via `CCCrypt` — is enough: [AesGcm] builds the GCM mode on the raw AES
 * primitive and documents its own correctness pinning.
 *
 * ### Wire-format parity with androidMain
 *
 * Both platforms must produce and consume the identical on-disk blob, or
 * a password-protected backup taken on Android cannot be restored on iOS
 * (and vice versa). The contract:
 *
 *  - **KDF** — PBKDF2-HMAC-SHA256 over the UTF-8 bytes of the password,
 *    caller-supplied salt and iteration count, 256-bit output.
 *  - **Cipher** — AES-256-GCM, 96-bit IV, 128-bit tag.
 *  - **Layout** — hex of `iv(12) || ciphertext || tag(16)`, lowercase.
 *    Java's `Cipher.doFinal` returns `ciphertext||tag` as a single blob,
 *    which is why androidMain can write `hex(iv) + hex(doFinal(...))`
 *    while this side concatenates the three parts explicitly.
 *
 * [BackupCipherParityTest] pins all three against fixed vectors and runs
 * on every target.
 */
actual class BackupCipher {

    actual fun deriveKey(password: String, saltHex: String, iterations: Int): ByteArray {
        val salt = decodeHex(saltHex)
        // Mirrors androidMain's Pbkdf2Sha256.validate() so malformed input
        // is rejected identically on both platforms.
        require(salt.isNotEmpty()) { "PBKDF2 salt must not be empty" }
        require(iterations in 1..MAX_ITERATIONS) { "PBKDF2 iterations out of range" }

        // Android runs PBEKeySpec(CharArray) through PBKDF2WithHmacSHA256,
        // whose JCE implementation encodes the password as UTF-8 — and its
        // API 24/25 compat fallback spells that out with
        // `String(password).toByteArray(Charsets.UTF_8)`. The Kotlin/Native
        // binding maps the C `const char *password` parameter to a Kotlin
        // `String?` and marshals it as UTF-8 itself — same bytes — so the
        // password goes in directly; only the byte LENGTH is computed here.
        // (MK.iOS.0-pre predicted typing nudges on first Mac compile; this
        // was one — a CPointer built by hand is rejected where the binding
        // wants the String. Android's zero-the-CharArray hygiene has no
        // equivalent on this path: the transient C buffer is owned and
        // freed by the interop layer.)
        val passwordByteLen = password.encodeToByteArray().size
        val out = ByteArray(KEY_BYTES)
        val status =
            salt.usePinned { saltPinned ->
                out.usePinned { outPinned ->
                    CCKeyDerivationPBKDF(
                        kCCPBKDF2,
                        password,
                        passwordByteLen.toULong(),
                        saltPinned.addressOf(0).reinterpret<UByteVar>(),
                        salt.size.toULong(),
                        kCCPRFHmacAlgSHA256,
                        iterations.toUInt(),
                        outPinned.addressOf(0).reinterpret<UByteVar>(),
                        KEY_BYTES.toULong(),
                    )
                }
            }
        check(status == CC_SUCCESS) { "PBKDF2 derivation failed (CCCryptorStatus $status)" }
        return out
    }

    actual fun encryptHex(plaintext: ByteArray, key: ByteArray): String {
        require(key.size == KEY_BYTES) { "AES-256-GCM requires a $KEY_BYTES-byte key" }
        // Fresh 96-bit IV per call (NIST SP 800-38D §8.2.1), same as
        // androidMain. Never reuse an IV under a derived key.
        val iv = randomBytes(IV_BYTES)
        // AesGcm.seal returns ciphertext||tag as one blob — the same shape
        // JCE's doFinal hands androidMain, so both sides end up writing
        // hex(iv) + hex(blob) and the wire layout cannot drift.
        return encodeHex(iv) + encodeHex(AesGcm.seal(key, iv, plaintext))
    }

    actual fun decryptBytes(hex: String, key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256-GCM requires a $KEY_BYTES-byte key" }
        val all = decodeHex(hex)
        // androidMain only checks `> 12` and lets the JCE reject a missing
        // tag; being explicit fails with a readable message instead.
        require(all.size >= IV_BYTES + TAG_BYTES) { "ciphertext shorter than IV + tag" }
        val iv = all.copyOfRange(0, IV_BYTES)
        val cipherText = all.copyOfRange(IV_BYTES, all.size - TAG_BYTES)
        val tag = all.copyOfRange(all.size - TAG_BYTES, all.size)
        // AesGcm.open verifies the tag (constant-time) before returning any
        // plaintext and throws on mismatch — the expect declaration's
        // "throws on tag mismatch" contract, which androidMain meets via
        // AEADBadTagException.
        return AesGcm.open(key, iv, cipherText, tag)
    }

    actual fun randomSaltHex(): String = encodeHex(randomBytes(SALT_BYTES))

    private fun randomBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        val status = out.usePinned { pinned -> SecRandomCopyBytes(kSecRandomDefault, count.toULong(), pinned.addressOf(0)) }
        check(status == CC_SUCCESS) { "SecRandomCopyBytes failed (OSStatus $status)" }
        return out
    }

    private fun encodeHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private fun decodeHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string has odd length" }
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            out[i / 2] = ((hexValue(hex[i]) shl 4) or hexValue(hex[i + 1])).toByte()
            i += 2
        }
        return out
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> error("invalid hex char $c")
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()

        /** `kCCSuccess` / `errSecSuccess` — both are 0. */
        const val CC_SUCCESS = 0
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val SALT_BYTES = 16

        /** Mirrors androidMain Pbkdf2Sha256.MAX_ITERATIONS. */
        const val MAX_ITERATIONS = 1_000_000
    }
}
