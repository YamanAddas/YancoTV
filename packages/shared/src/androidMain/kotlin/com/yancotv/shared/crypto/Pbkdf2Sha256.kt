package com.yancotv.shared.crypto

import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA256 with an API 24/25 compatibility path.
 *
 * Android's named PBKDF2WithHmacSHA256 SecretKeyFactory was added in API 26,
 * while HmacSHA256 itself is available on every supported Android version.
 * Prefer the platform KDF when a device provides it (including vendor
 * backports), and otherwise build PBKDF2 from the platform HMAC primitive.
 */
internal object Pbkdf2Sha256 {
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val MAX_ITERATIONS = 1_000_000

    fun derive(password: CharArray, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
        validate(salt, iterations, keyBits)
        return try {
            deriveWithPlatform(password, salt, iterations, keyBits)
        } catch (_: NoSuchAlgorithmException) {
            deriveCompat(password, salt, iterations, keyBits)
        }
    }

    internal fun deriveWithPlatform(password: CharArray, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
        validate(salt, iterations, keyBits)
        val spec = PBEKeySpec(password, salt, iterations, keyBits)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    internal fun deriveCompat(password: CharArray, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
        validate(salt, iterations, keyBits)
        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        return try {
            deriveCompatBytes(passwordBytes, salt, iterations, keyBits / Byte.SIZE_BITS)
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun deriveCompatBytes(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(password, HMAC_ALGORITHM))

        val output = ByteArray(keyBytes)
        val blockIndex = ByteArray(4)
        val blockCount = (keyBytes + mac.macLength - 1) / mac.macLength
        var outputOffset = 0

        for (block in 1..blockCount) {
            blockIndex[0] = (block ushr 24).toByte()
            blockIndex[1] = (block ushr 16).toByte()
            blockIndex[2] = (block ushr 8).toByte()
            blockIndex[3] = block.toByte()

            mac.update(salt)
            var u = mac.doFinal(blockIndex)
            val resultBlock = u.copyOf()
            try {
                repeat(iterations - 1) {
                    val next = mac.doFinal(u)
                    u.fill(0)
                    u = next
                    for (index in resultBlock.indices) {
                        resultBlock[index] =
                            (resultBlock[index].toInt() xor u[index].toInt()).toByte()
                    }
                }

                val copyLength = minOf(resultBlock.size, keyBytes - outputOffset)
                resultBlock.copyInto(output, outputOffset, 0, copyLength)
                outputOffset += copyLength
            } finally {
                u.fill(0)
                resultBlock.fill(0)
            }
        }

        return output
    }

    private fun validate(salt: ByteArray, iterations: Int, keyBits: Int) {
        require(salt.isNotEmpty()) { "PBKDF2 salt must not be empty" }
        require(iterations in 1..MAX_ITERATIONS) { "PBKDF2 iterations out of range" }
        require(keyBits > 0 && keyBits % Byte.SIZE_BITS == 0) {
            "PBKDF2 key length must be a positive whole number of bytes"
        }
    }
}
