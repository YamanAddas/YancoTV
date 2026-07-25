package com.yancotv.shared.backup

import com.yancotv.shared.crypto.Pbkdf2Sha256
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual class BackupCipher {
    private val random = SecureRandom()

    actual fun deriveKey(password: String, saltHex: String, iterations: Int): ByteArray {
        val salt = decodeHex(saltHex)
        val passwordChars = password.toCharArray()
        return try {
            Pbkdf2Sha256.derive(passwordChars, salt, iterations, 256)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    actual fun encryptHex(plaintext: ByteArray, key: ByteArray): String {
        // AES-256-GCM with a fresh 96-bit random IV per call (NIST SP
        // 800-38D §8.2.1) and the standard 128-bit auth tag. IV is
        // prepended to the ciphertext on the wire so decryptBytes() can
        // split them back out. Semgrep's gcm-detection rule fires on
        // every AES/GCM usage as a review tripwire — the IV-handling
        // invariant is met here.
        val iv = ByteArray(12).also { random.nextBytes(it) }
        // nosemgrep: kotlin.lang.security.gcm-detection.gcm-detection
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // nosemgrep: kotlin.lang.security.gcm-detection.gcm-detection
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext)
        return encodeHex(iv) + encodeHex(ct)
    }

    actual fun decryptBytes(hex: String, key: ByteArray): ByteArray {
        val all = decodeHex(hex)
        require(all.size > 12) { "ciphertext shorter than IV" }
        val iv = all.copyOfRange(0, 12)
        val ct = all.copyOfRange(12, all.size)
        // nosemgrep: kotlin.lang.security.gcm-detection.gcm-detection
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // nosemgrep: kotlin.lang.security.gcm-detection.gcm-detection
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    actual fun randomSaltHex(): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        return encodeHex(salt)
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
    }
}
