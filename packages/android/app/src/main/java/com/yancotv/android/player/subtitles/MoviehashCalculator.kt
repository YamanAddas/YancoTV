package com.yancotv.android.player.subtitles

import java.nio.ByteBuffer
import java.nio.ByteOrder
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OpenSubtitles moviehash — 64-bit content-derived identifier for a video file.
 *
 * Lets OpenSubtitles match a file even when the M3U title has a noisy provider
 * prefix that bears no relation to the actual movie. The bytes are the bytes;
 * if a release is in OS's database, the hash matches regardless of how the
 * IPTV provider mangled the name.
 *
 * Algorithm (from opensubtitles.org wiki):
 *   1. Read first 64 KiB and last 64 KiB of the file.
 *   2. Sum each as little-endian unsigned 64-bit ints, 8 bytes at a time.
 *   3. Add the total file size to the running sum.
 *   4. Return as 16-char lowercase hex of the low 64 bits (overflow wraps).
 *
 * Requires the IPTV server to honor HEAD + Range. If either fails we throw
 * [MoviehashUnavailable] so the caller falls back to title-only search.
 */
object MoviehashCalculator {
    private const val CHUNK_BYTES: Long = 65_536

    data class Result(val hash: String, val byteSize: Long)

    fun compute(client: OkHttpClient, url: String): Result {
        val size =
            headContentLength(client, url)
                ?: throw MoviehashUnavailable("Content-Length unavailable")
        if (size < CHUNK_BYTES * 2) {
            throw MoviehashUnavailable("File too small for moviehash ($size bytes)")
        }
        val first = rangeBytes(client, url, 0, CHUNK_BYTES - 1)
        val last = rangeBytes(client, url, size - CHUNK_BYTES, size - 1)

        var sum: ULong = size.toULong()
        sum += sumLongsLE(first)
        sum += sumLongsLE(last)

        return Result(
            hash = sum.toString(16).padStart(16, '0'),
            byteSize = size,
        )
    }

    private fun headContentLength(client: OkHttpClient, url: String): Long? {
        val req = Request.Builder().url(url).head().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.header("Content-Length")?.toLongOrNull()
        }
    }

    private fun rangeBytes(client: OkHttpClient, url: String, start: Long, endInclusive: Long): ByteArray {
        val req =
            Request.Builder()
                .url(url)
                .header("Range", "bytes=$start-$endInclusive")
                .get()
                .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw MoviehashUnavailable("Range request failed: ${resp.code}")
            }
            val body =
                resp.body?.bytes()
                    ?: throw MoviehashUnavailable("Empty range body")
            val expected = endInclusive - start + 1
            if (body.size.toLong() != expected) {
                throw MoviehashUnavailable("Short range read: ${body.size} of $expected")
            }
            return body
        }
    }

    private fun sumLongsLE(bytes: ByteArray): ULong {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var s: ULong = 0uL
        while (buf.remaining() >= 8) {
            // Long → ULong is a bit-reinterpret, exactly the wrap-mod-2^64 semantic
            // the algorithm wants.
            s += buf.long.toULong()
        }
        return s
    }
}

class MoviehashUnavailable(message: String) : Exception(message)
