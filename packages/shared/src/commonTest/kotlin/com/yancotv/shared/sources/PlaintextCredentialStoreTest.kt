package com.yancotv.shared.sources

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PlaintextCredentialStoreTest {
    @Test
    fun `encrypt is reversible`() {
        val store = PlaintextCredentialStore()
        val secret = "p@ssw0rd"
        val round = store.decrypt(store.encrypt(secret))
        assertEquals(secret, round)
    }

    @Test
    fun `encrypt empty string round-trips`() {
        val store = PlaintextCredentialStore()
        assertEquals("", store.decrypt(store.encrypt("")))
    }

    @Test
    fun `encrypt preserves non-ASCII`() {
        val store = PlaintextCredentialStore()
        val secret = "Здра́вствуйте-مرحبا-🔑"
        assertEquals(secret, store.decrypt(store.encrypt(secret)))
    }

    @Test
    fun `ciphertext is byte-equal across calls for plaintext store`() {
        val store = PlaintextCredentialStore()
        val a = store.encrypt("abc")
        val b = store.encrypt("abc")
        assertContentEquals(a, b)
    }
}
