@file:OptIn(ExperimentalForeignApi::class)

package com.yancotv.shared.backup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.posix.size_tVar

/**
 * AES-256-GCM assembled from CommonCrypto's *public* AES primitive.
 *
 * ### Why this exists
 *
 * MK.iOS.0-pre.2 wrote [BackupCipher]'s iOS actual against
 * `CCCryptorGCMOneshotEncrypt` / `…Decrypt` and flagged "is that pair
 * bound in Kotlin/Native's `platform.CoreCrypto` at all?" as its top
 * unverified risk. The first real run of the ios-compile gate
 * (2026-08-19) answered: it is not. Every CommonCrypto GCM entry point
 * — the oneshot pair and the legacy `CCCryptorGCM*` streaming set —
 * lives in `CommonCryptoSPI.h`, a private header Kotlin/Native's
 * platform klibs are not generated from. A custom cinterop over that
 * header would link, but shipping private SPI is an App Store
 * rejection vector, so it is not an option either. CryptoKit has the
 * right API but is Swift-only — unreachable from Kotlin/Native, which
 * imports Objective-C/C.
 *
 * What IS public and bound: one-shot AES via [CCCrypt]. GCM is a thin
 * mode over the raw block cipher, so this object implements the
 * NIST SP 800-38D construction on top of it:
 *
 *  - `H = E(K, 0^128)` — the GHASH subkey
 *  - `J0 = IV || 0x00000001` (96-bit-IV path only; ours is fixed at 12 bytes)
 *  - ciphertext = CTR keystream from `inc32(J0)`, i.e. counters 2, 3, …
 *  - `tag = E(K, J0) XOR GHASH_H(C || pad || len(AAD)=0^64 || bitlen(C))`
 *
 * The backup format never passes AAD, so the AAD terms are the empty
 * string and a zero length word.
 *
 * ### Correctness pinning
 *
 * GHASH is the only hand-implemented arithmetic (bit-serial GF(2^128)
 * multiply). [BackupCipherParityTest] pins the whole construction three
 * ways: the published McGrew/Viega AES-256-GCM vectors (third-party
 * ground truth, run on every target including the JVM/JCE side), an
 * Android-produced interop blob (the cross-platform wire contract),
 * and encrypt→decrypt round-trips. A GHASH slip fails all of them.
 *
 * ### Security notes
 *
 * - [open] recomputes and compares the tag in constant time BEFORE any
 *   plaintext is returned, and throws on mismatch — the expect
 *   contract androidMain meets via `AEADBadTagException`.
 * - The GF multiply is branch-free (mask arithmetic, no data-dependent
 *   branches or table lookups). The realistic threat model is offline
 *   anyway — whoever has the backup file has unlimited time — but
 *   there is no reason to be sloppy.
 * - Backups are small (KBs–MBs), so the one-shot keystream buffer and
 *   per-block ULong math are comfortably fast enough.
 */
internal object AesGcm {

    /** Encrypts; returns `ciphertext || tag` — the same single-blob shape JCE's `doFinal` hands androidMain. */
    fun seal(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val j0 = counterZero(iv)
        val cipherText = ctrCrypt(key, j0, plaintext)
        return cipherText + tag(key, j0, cipherText)
    }

    /** Verifies the tag, then decrypts. Throws [IllegalStateException] on mismatch, before touching plaintext. */
    fun open(key: ByteArray, iv: ByteArray, cipherText: ByteArray, tag: ByteArray): ByteArray {
        require(tag.size == TAG_BYTES) { "AES-GCM tag must be $TAG_BYTES bytes" }
        val j0 = counterZero(iv)
        val expected = tag(key, j0, cipherText)
        // Constant-time comparison — accumulate the XOR of every byte pair
        // and test once, so a mismatch position leaks nothing via timing.
        var diff = 0
        for (i in 0 until TAG_BYTES) {
            diff = diff or ((expected[i].toInt() xor tag[i].toInt()) and 0xFF)
        }
        check(diff == 0) { "AES-GCM decrypt failed — tag mismatch or corrupt ciphertext" }
        return ctrCrypt(key, j0, cipherText)
    }

    private const val BLOCK = 16
    private const val TAG_BYTES = 16
    private const val IV_BYTES = 12

    /** `kCCSuccess` — same local pinning as BackupCipher.ios.kt. */
    private const val CC_SUCCESS = 0

    /** `R` from SP 800-38D §6.3 — the reduction polynomial's top 64 bits (`11100001 || 0^120`). */
    private const val R_HI = 0xE100000000000000uL

    /** `J0 = IV || 0x00000001` — valid only for the 96-bit IV path, the only one the backup format uses. */
    private fun counterZero(iv: ByteArray): ByteArray {
        require(iv.size == IV_BYTES) { "AES-GCM IV must be $IV_BYTES bytes" }
        return iv + byteArrayOf(0, 0, 0, 1)
    }

    /** `tag = E(K, J0) XOR GHASH_H(C || pad || lengths)` (no AAD anywhere in the backup format). */
    private fun tag(key: ByteArray, j0: ByteArray, cipherText: ByteArray): ByteArray {
        val subkey = ecbEncrypt(key, ByteArray(BLOCK))
        val s = ghash(subkey, cipherText)
        val ekj0 = ecbEncrypt(key, j0)
        return ByteArray(BLOCK) { i -> (s[i].toInt() xor ekj0[i].toInt()).toByte() }
    }

    /**
     * CTR over the payload. Counter blocks are `IV || (1 + i) mod 2^32`
     * for payload block index i starting at 1 — J0 itself (counter 1)
     * is reserved for the tag. All counter blocks are encrypted in ONE
     * [CCCrypt] call and the keystream XORed in; CTR encrypt and
     * decrypt are the same operation.
     */
    private fun ctrCrypt(key: ByteArray, j0: ByteArray, input: ByteArray): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val blocks = (input.size + BLOCK - 1) / BLOCK
        val counters = ByteArray(blocks * BLOCK)
        for (b in 0 until blocks) {
            j0.copyInto(counters, b * BLOCK, 0, IV_BYTES)
            // inc32 semantics: only the low 32 bits count up (mod 2^32). The
            // wrap is unreachable at backup sizes (2^32 blocks = 64 GiB) but
            // costs nothing to honour.
            val counter = (2L + b).toUInt()
            counters[b * BLOCK + 12] = (counter shr 24).toByte()
            counters[b * BLOCK + 13] = (counter shr 16).toByte()
            counters[b * BLOCK + 14] = (counter shr 8).toByte()
            counters[b * BLOCK + 15] = counter.toByte()
        }
        val keystream = ecbEncrypt(key, counters)
        return ByteArray(input.size) { i -> (input[i].toInt() xor keystream[i].toInt()).toByte() }
    }

    /** GHASH over `data || zero-pad || len(AAD)=0^64 || bitlen(data)` per SP 800-38D §6.4. */
    private fun ghash(subkey: ByteArray, data: ByteArray): ByteArray {
        val hHi = readBe(subkey, 0)
        val hLo = readBe(subkey, 8)
        val acc = ulongArrayOf(0uL, 0uL)
        var off = 0
        while (off < data.size) {
            val n = minOf(BLOCK, data.size - off)
            acc[0] = acc[0] xor readBePadded(data, off, n, 0)
            acc[1] = acc[1] xor readBePadded(data, off, n, 8)
            gmul(acc, hHi, hLo)
            off += BLOCK
        }
        // Length block: 64 zero bits (no AAD), then the data bit length.
        acc[1] = acc[1] xor (data.size.toULong() * 8uL)
        gmul(acc, hHi, hLo)
        val out = ByteArray(BLOCK)
        writeBe(out, 0, acc[0])
        writeBe(out, 8, acc[1])
        return out
    }

    /**
     * `acc = acc • H` in GF(2^128), bit-serial right-shift form
     * (SP 800-38D algorithm 1). Bit selection and the reduction are
     * applied through all-ones/all-zero masks — no data-dependent
     * branches, no tables.
     */
    private fun gmul(acc: ULongArray, hHi: ULong, hLo: ULong) {
        val xHi = acc[0]
        val xLo = acc[1]
        var zHi = 0uL
        var zLo = 0uL
        var vHi = hHi
        var vLo = hLo
        for (i in 0 until 128) {
            val bit = if (i < 64) (xHi shr (63 - i)) and 1uL else (xLo shr (127 - i)) and 1uL
            val useV = 0uL - bit
            zHi = zHi xor (vHi and useV)
            zLo = zLo xor (vLo and useV)
            val reduce = 0uL - (vLo and 1uL)
            vLo = (vLo shr 1) or (vHi shl 63)
            vHi = vHi shr 1
            vHi = vHi xor (R_HI and reduce)
        }
        acc[0] = zHi
        acc[1] = zLo
    }

    private fun readBe(bytes: ByteArray, offset: Int): ULong {
        var v = 0uL
        for (i in 0 until 8) {
            v = (v shl 8) or (bytes[offset + i].toULong() and 0xFFuL)
        }
        return v
    }

    /** One 8-byte lane of a zero-padded 16-byte block view over `data[blockOff, blockOff + n)`. */
    private fun readBePadded(data: ByteArray, blockOff: Int, n: Int, lane: Int): ULong {
        var v = 0uL
        for (i in 0 until 8) {
            val idx = lane + i
            val byte = if (idx < n) data[blockOff + idx].toULong() and 0xFFuL else 0uL
            v = (v shl 8) or byte
        }
        return v
    }

    private fun writeBe(out: ByteArray, offset: Int, v: ULong) {
        for (i in 0 until 8) {
            out[offset + i] = (v shr (56 - 8 * i)).toByte()
        }
    }

    /**
     * One-shot AES-ECB encrypt of `input` (a multiple of 16 bytes,
     * never empty here) — the raw block-cipher call everything above
     * builds on. ECB leaks structure when misused on data; here it only
     * ever encrypts distinct counter blocks and single GCM setup
     * blocks, which is exactly the job CTR/GCM hand the block cipher.
     */
    private fun ecbEncrypt(key: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        val status = memScoped {
            val moved = alloc<size_tVar>()
            key.usePinned { keyPinned ->
                input.usePinned { inPinned ->
                    out.usePinned { outPinned ->
                        CCCrypt(
                            kCCEncrypt,
                            kCCAlgorithmAES,
                            kCCOptionECBMode,
                            keyPinned.addressOf(0),
                            key.size.convert(),
                            null,
                            inPinned.addressOf(0),
                            input.size.convert(),
                            outPinned.addressOf(0),
                            out.size.convert(),
                            moved.ptr,
                        )
                    }
                }
            }
        }
        check(status == CC_SUCCESS) { "AES-ECB block encrypt failed (CCCryptorStatus $status)" }
        return out
    }
}
