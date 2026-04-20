package com.yancotv.shared.sources

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM credential store backed by Android Keystore.
 *
 * One symmetric key is lazily generated under [keyAlias] and never leaves the
 * Keystore — on StrongBox-capable devices (Pixel 3+, many TV boxes) it lives
 * in the Titan TEE. We store the 12-byte IV prepended to the ciphertext so
 * the same column can round-trip without a separate IV field.
 *
 * Format: `[0..11] = IV | [12..] = AES/GCM ciphertext + auth tag`
 *
 * Version byte reserved: the first byte of the IV is effectively random so
 * we can't reuse it as a version marker. If we need to rotate the scheme
 * later, do it via a new [keyAlias] and fall back to the old alias when
 * decrypting — same pattern Android uses for its own keyset rotation.
 */
class AndroidKeystoreCredentialStore(
    private val keyAlias: String = DEFAULT_ALIAS,
) : CredentialStore {

    override fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) {
            "Keystore returned unexpected IV length ${iv.size}; expected $IV_LENGTH"
        }
        val ct = cipher.doFinal(plaintext.encodeToByteArray())
        return iv + ct
    }

    override fun decrypt(ciphertext: ByteArray): String {
        require(ciphertext.size > IV_LENGTH) {
            "Ciphertext too short: ${ciphertext.size} bytes (need > $IV_LENGTH)"
        }
        val iv = ciphertext.copyOfRange(0, IV_LENGTH)
        val ct = ciphertext.copyOfRange(IV_LENGTH, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct).decodeToString()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return kg.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        const val DEFAULT_ALIAS = "com.yancotv.credential_v1"
    }
}
