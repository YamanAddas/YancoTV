@file:OptIn(ExperimentalForeignApi::class)

package com.yancotv.shared.backup

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCCryptorGCMOneshotDecrypt
import platform.CoreCrypto.CCCryptorGCMOneshotEncrypt
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * iOS actual for [BackupCipher] — CommonCrypto, not CryptoKit.
 *
 * CryptoKit is the idiomatic Apple choice for AES-GCM, but it is a
 * Swift-only framework and Kotlin/Native can only import Objective-C and
 * C interfaces. CommonCrypto is the reachable primitive, and it exposes
 * everything this class needs: `CCKeyDerivationPBKDF` for the KDF and the
 * one-shot GCM pair for the cipher.
 *
 * Note the GCM entry points are `CCCryptorGCMOneshotEncrypt` /
 * `…Decrypt` (iOS 11+), **not** the older `CCCryptorGCM` /
 * `CCCryptorGCMFinal` — those are deprecated and have a documented
 * history of mishandling the auth tag.
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
        // `String(password).toByteArray(Charsets.UTF_8)`. Same bytes here.
        val passwordBytes = password.encodeToByteArray()
        val out = ByteArray(KEY_BYTES)
        val status =
            try {
                passwordBytes.withPtr { pw ->
                    salt.withPtr { saltPtr ->
                        out.withPtr { outPtr ->
                            CCKeyDerivationPBKDF(
                                kCCPBKDF2,
                                pw,
                                passwordBytes.size.toULong(),
                                saltPtr?.reinterpret<UByteVar>(),
                                salt.size.toULong(),
                                kCCPRFHmacAlgSHA256,
                                iterations.toUInt(),
                                outPtr?.reinterpret<UByteVar>(),
                                KEY_BYTES.toULong(),
                            )
                        }
                    }
                }
            } finally {
                // Android zeroes its CharArray in a finally; same hygiene.
                passwordBytes.fill(0)
            }
        check(status == CC_SUCCESS) { "PBKDF2 derivation failed (CCCryptorStatus $status)" }
        return out
    }

    actual fun encryptHex(plaintext: ByteArray, key: ByteArray): String {
        require(key.size == KEY_BYTES) { "AES-256-GCM requires a $KEY_BYTES-byte key" }
        // Fresh 96-bit IV per call (NIST SP 800-38D §8.2.1), same as
        // androidMain. Never reuse an IV under a derived key.
        val iv = randomBytes(IV_BYTES)
        val cipherText = ByteArray(plaintext.size)
        val tag = ByteArray(TAG_BYTES)
        val status =
            key.withPtr { keyPtr ->
                iv.withPtr { ivPtr ->
                    plaintext.withPtr { inPtr ->
                        cipherText.withPtr { outPtr ->
                            tag.withPtr { tagPtr ->
                                CCCryptorGCMOneshotEncrypt(
                                    kCCAlgorithmAES,
                                    keyPtr, KEY_BYTES.toULong(),
                                    ivPtr, IV_BYTES.toULong(),
                                    null, 0uL,
                                    inPtr, plaintext.size.toULong(),
                                    outPtr,
                                    tagPtr, TAG_BYTES.toULong(),
                                )
                            }
                        }
                    }
                }
            }
        check(status == CC_SUCCESS) { "AES-GCM encrypt failed (CCCryptorStatus $status)" }
        return encodeHex(iv) + encodeHex(cipherText) + encodeHex(tag)
    }

    actual fun decryptBytes(hex: String, key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256-GCM requires a $KEY_BYTES-byte key" }
        val all = decodeHex(hex)
        // androidMain only checks `> 12` and lets the JCE reject a missing
        // tag; being explicit fails with a readable message rather than a
        // bare CommonCrypto status code.
        require(all.size >= IV_BYTES + TAG_BYTES) { "ciphertext shorter than IV + tag" }
        val iv = all.copyOfRange(0, IV_BYTES)
        val cipherText = all.copyOfRange(IV_BYTES, all.size - TAG_BYTES)
        val tag = all.copyOfRange(all.size - TAG_BYTES, all.size)
        val out = ByteArray(cipherText.size)
        val status =
            key.withPtr { keyPtr ->
                iv.withPtr { ivPtr ->
                    cipherText.withPtr { inPtr ->
                        out.withPtr { outPtr ->
                            tag.withPtr { tagPtr ->
                                CCCryptorGCMOneshotDecrypt(
                                    kCCAlgorithmAES,
                                    keyPtr, KEY_BYTES.toULong(),
                                    ivPtr, IV_BYTES.toULong(),
                                    null, 0uL,
                                    inPtr, cipherText.size.toULong(),
                                    outPtr,
                                    tagPtr, TAG_BYTES.toULong(),
                                )
                            }
                        }
                    }
                }
            }
        // CommonCrypto does the constant-time tag comparison internally and
        // returns a non-success status on mismatch, satisfying the expect
        // declaration's "throws on tag mismatch" contract (androidMain
        // surfaces this as AEADBadTagException).
        check(status == CC_SUCCESS) { "AES-GCM decrypt failed — tag mismatch or corrupt ciphertext" }
        return out
    }

    actual fun randomSaltHex(): String = encodeHex(randomBytes(SALT_BYTES))

    private fun randomBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        val status = out.withPtr { ptr -> SecRandomCopyBytes(kSecRandomDefault, count.toULong(), ptr) }
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

/**
 * Runs [block] with a pointer to this array's first byte, or `null` when
 * the array is empty.
 *
 * `Pinned.addressOf(0)` throws on an empty ByteArray, but `(NULL, 0)` is a
 * valid argument pair for every CommonCrypto entry point used here — and
 * empty input is reachable: AES-GCM over a zero-length plaintext is legal
 * and yields a bare tag.
 */
private inline fun <R> ByteArray.withPtr(block: (CPointer<ByteVar>?) -> R): R = if (isEmpty()) block(null) else usePinned { block(it.addressOf(0)) }
