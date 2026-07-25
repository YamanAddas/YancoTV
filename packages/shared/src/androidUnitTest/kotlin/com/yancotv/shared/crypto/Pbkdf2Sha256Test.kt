package com.yancotv.shared.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Pbkdf2Sha256Test {
    @Test
    fun `compat implementation matches RFC 7914 single-iteration vector`() {
        val actual =
            Pbkdf2Sha256.deriveCompat(
                password = "passwd".toCharArray(),
                salt = "salt".encodeToByteArray(),
                iterations = 1,
                keyBits = 512,
            )

        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            actual.toHex(),
        )
    }

    @Test
    fun `compat implementation matches RFC 7914 high-iteration vector`() {
        val actual =
            Pbkdf2Sha256.deriveCompat(
                password = "Password".toCharArray(),
                salt = "NaCl".encodeToByteArray(),
                iterations = 80_000,
                keyBits = 512,
            )

        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            actual.toHex(),
        )
    }

    @Test
    fun `compat implementation matches platform provider including unicode`() {
        val salt = "fixed-test-salt".encodeToByteArray()
        val passwords = listOf("1234", "correct horse battery staple", "pässwörd 🔐", "nul\u0000inside")

        for (password in passwords) {
            val expected =
                Pbkdf2Sha256.deriveWithPlatform(password.toCharArray(), salt, 1_000, 256)
            val actual =
                Pbkdf2Sha256.deriveCompat(password.toCharArray(), salt, 1_000, 256)
            assertEquals(expected.toHex(), actual.toHex(), "password=$password")
        }
    }

    @Test
    fun `rejects attacker-controlled work factors outside the safe range`() {
        assertFailsWith<IllegalArgumentException> {
            Pbkdf2Sha256.deriveCompat("pin".toCharArray(), byteArrayOf(1), 0, 256)
        }
        assertFailsWith<IllegalArgumentException> {
            Pbkdf2Sha256.deriveCompat("pin".toCharArray(), byteArrayOf(1), 1_000_001, 256)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
