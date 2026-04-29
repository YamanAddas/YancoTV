package com.yancotv.android.player.subtitles

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pin the OpenSubtitles moviehash algorithm. The HTTP plumbing in
 * [MoviehashCalculator.compute] is a thin wrapper around OkHttp range
 * requests — we trust OkHttp. The thing worth pinning is the byte-level
 * sum: a future refactor that swaps signed for unsigned wrong, picks the
 * wrong endianness, or breaks the size-included term, would all be silent
 * (the API just returns "no subtitles found" for the now-wrong hash).
 *
 * Hashes here aren't from external fixtures — they're derived from the
 * algorithm itself so the test pins what the code does today. If the
 * algorithm ever needs to change, the expected values get recomputed
 * against the new spec, not arbitrarily edited.
 */
class MoviehashCalculatorTest {
    private val chunkSize = 65_536

    @Test
    fun `all-zero chunks plus minimum file size returns size as hash`() {
        // size = 2 * 65536 = 131072 = 0x20000
        // first chunk = 0..., last chunk = 0... → both LE-u64 sums = 0
        // Total = 131072 → hex with 16-pad = "0000000000020000"
        val first = ByteArray(chunkSize)
        val last = ByteArray(chunkSize)
        val size = (chunkSize * 2).toLong()
        assertEquals("0000000000020000", MoviehashCalculator.computeHash(size, first, last))
    }

    @Test
    fun `first byte set adds one in little-endian`() {
        // first[0] = 1, rest 0 → first 8 bytes interpreted as LE u64 = 0x01.
        // sum = size + 1 + 0 = 131073 = 0x20001
        val first = ByteArray(chunkSize).also { it[0] = 1 }
        val last = ByteArray(chunkSize)
        val size = (chunkSize * 2).toLong()
        assertEquals("0000000000020001", MoviehashCalculator.computeHash(size, first, last))
    }

    @Test
    fun `byte 7 set adds 0x0100000000000000 in little-endian`() {
        // first[7] = 1 → first 8 bytes as LE u64 = 0x0100_0000_0000_0000
        // sum = size + 0x0100000000000000 = 0x0100000000020000
        val first = ByteArray(chunkSize).also { it[7] = 1 }
        val last = ByteArray(chunkSize)
        val size = (chunkSize * 2).toLong()
        assertEquals("0100000000020000", MoviehashCalculator.computeHash(size, first, last))
    }

    @Test
    fun `byte order is little-endian not big-endian`() {
        // first 8 bytes = 01 02 03 04 05 06 07 08 (in array order)
        // LE interpretation: 0x0807060504030201
        // BE would be:        0x0102030405060708
        // sum = size + 0x0807060504030201 + 0 = 0x0807060504030201 + 0x20000 = 0x0807060504050201
        val first =
            ByteArray(chunkSize).also { arr ->
                for (i in 0..7) arr[i] = (i + 1).toByte()
            }
        val last = ByteArray(chunkSize)
        val size = (chunkSize * 2).toLong()
        // 0x0807060504030201 + 0x0000000000020000 = 0x0807060504050201
        assertEquals("0807060504050201", MoviehashCalculator.computeHash(size, first, last))
    }

    @Test
    fun `overflow wraps modulo 2^64 not panics`() {
        // first 8 bytes = all 0xFF → LE u64 = 0xFFFFFFFFFFFFFFFF (= -1 as Long, ULong.MAX_VALUE)
        // last  8 bytes = 0x01 in byte 0 → LE u64 = 0x01
        // sum = size + ULong.MAX_VALUE + 1 = size (wraps: MAX+1 = 0)
        // → hex of size (131072) = "0000000000020000"
        val first =
            ByteArray(chunkSize).also { arr ->
                for (i in 0..7) arr[i] = 0xFF.toByte()
            }
        val last = ByteArray(chunkSize).also { it[0] = 1 }
        val size = (chunkSize * 2).toLong()
        assertEquals("0000000000020000", MoviehashCalculator.computeHash(size, first, last))
    }

    @Test
    fun `size component contributes to hash`() {
        // Different sizes with same chunks produce different hashes.
        val first = ByteArray(chunkSize)
        val last = ByteArray(chunkSize)
        val h1 = MoviehashCalculator.computeHash(chunkSize * 2L, first, last)
        val h2 = MoviehashCalculator.computeHash(chunkSize * 2L + 1, first, last)
        assert(h1 != h2) { "expected different hashes for different sizes; both were $h1" }
    }

    @Test
    fun `last chunk contributes to hash separately from first`() {
        // Same byte at offset 0 in either chunk should produce identical
        // contributions (algorithm is symmetric over the two chunk sums).
        val a = ByteArray(chunkSize).also { it[0] = 1 }
        val b = ByteArray(chunkSize)
        val size = (chunkSize * 2).toLong()
        val firstHasOne = MoviehashCalculator.computeHash(size, a, b)
        val lastHasOne = MoviehashCalculator.computeHash(size, b, a)
        assertEquals(firstHasOne, lastHasOne)
    }

    @Test
    fun `output is always 16 lowercase hex chars`() {
        val first = ByteArray(chunkSize).also { it[0] = 1 }
        val last = ByteArray(chunkSize)
        val hash = MoviehashCalculator.computeHash((chunkSize * 2).toLong(), first, last)
        assertEquals(16, hash.length)
        assert(hash.all { it.isDigit() || it in 'a'..'f' }) { "non-hex char in $hash" }
    }
}
