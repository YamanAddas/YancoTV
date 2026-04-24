package com.yancotv.shared.parental

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Android implementation of [PinHasher] using PBKDF2-HMAC-SHA256, available
 * in every JDK + Android API ≥ 24 without additional deps.
 *
 * Parameters:
 *  - 100 000 iterations — tuned for ~50 ms on a 2-core Fire TV Stick 4K.
 *    Keeps 4-digit PIN brute-force at ~2 h on-device (plenty, combined with
 *    the repository's 5-miss lockout); faster hashes like a single SHA-256
 *    would finish the entire keyspace in seconds.
 *  - 16 random salt bytes — `SecureRandom` is the platform CSPRNG.
 *  - 256-bit derived key length — one PBKDF2 call outputs SHA-256-sized
 *    material; larger wastes CPU for no security gain at this token.
 *
 * Encoded as `pbkdf2:{iters}:{saltHex}:{hashHex}` — self-describing so
 * we can bump the iteration count later without breaking existing hashes
 * (verify reads the stored iters).
 */
class AndroidPinHasher : PinHasher {
    private val rng = SecureRandom()

    override suspend fun hash(pin: String): String =
        withContext(Dispatchers.Default) {
            val salt = ByteArray(SALT_BYTES).also(rng::nextBytes)
            val derived = derive(pin, salt, ITERATIONS)
            "pbkdf2:$ITERATIONS:${salt.toHex()}:${derived.toHex()}"
        }

    override suspend fun verify(
        pin: String,
        encoded: String,
    ): PinHasher.VerifyResult =
        withContext(Dispatchers.Default) {
            val trimmed = encoded.trim()
            if (!trimmed.startsWith("pbkdf2:")) return@withContext PinHasher.VerifyResult(ok = false)
            val parts = trimmed.split(':')
            if (parts.size != 4) return@withContext PinHasher.VerifyResult(ok = false)
            val iters = parts[1].toIntOrNull() ?: return@withContext PinHasher.VerifyResult(ok = false)
            val salt = parts[2].fromHex() ?: return@withContext PinHasher.VerifyResult(ok = false)
            val expected = parts[3].fromHex() ?: return@withContext PinHasher.VerifyResult(ok = false)
            if (iters < 1 || iters > MAX_ITERATIONS) return@withContext PinHasher.VerifyResult(ok = false)
            val actual = derive(pin, salt, iters)
            PinHasher.VerifyResult(ok = timingSafeEqual(actual, expected))
        }

    private fun derive(
        pin: String,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        // PBEKeySpec wipes its internal char[] when `clearPassword()` is
        // called; we do that in the finally so the PIN can't linger as
        // plaintext in heap waiting for GC.
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return FACTORY.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val ITERATIONS = 100_000

        // Sanity ceiling — prevents a corrupted stored blob from triggering
        // a multi-minute hang at verify.
        const val MAX_ITERATIONS = 1_000_000
        const val SALT_BYTES = 16
        const val KEY_BITS = 256

        val FACTORY: SecretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")

        fun ByteArray.toHex(): String {
            val chars = CharArray(size * 2)
            for (i in indices) {
                val b = this[i].toInt() and 0xFF
                chars[i * 2] = Character.forDigit(b ushr 4, 16)
                chars[i * 2 + 1] = Character.forDigit(b and 0x0F, 16)
            }
            return String(chars)
        }

        fun String.fromHex(): ByteArray? {
            if (length % 2 != 0) return null
            val out = ByteArray(length / 2)
            for (i in out.indices) {
                val hi = Character.digit(this[i * 2], 16)
                val lo = Character.digit(this[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        fun timingSafeEqual(
            a: ByteArray,
            b: ByteArray,
        ): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }
    }
}
