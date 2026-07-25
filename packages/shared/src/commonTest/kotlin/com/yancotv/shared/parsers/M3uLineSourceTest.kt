package com.yancotv.shared.parsers

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MB-230 regression cover.
 *
 * The fix replaced a whole-playlist `replace().replace().split()` chain with
 * two lazy line sequences — one over a `CharSequence`, one over a byte
 * [Source]. `M3uParserTest` already pins the parse semantics through the
 * String path; this file pins the two things that file cannot see:
 *
 *  1. the [Source] path produces **identical** results to the String path, so
 *     the streaming sync used in production behaves exactly like the corpus
 *     the TypeScript mirror is checked against, and
 *  2. the [Source] really is consumed incrementally rather than slurped.
 */
class M3uLineSourceTest {
    private fun sourceOf(text: String): Source = Buffer().apply { writeString(text) }

    /**
     * Every line-ending shape the parser promises to handle, plus the awkward
     * combinations that a whole-string `replace` used to smooth over.
     */
    private val corpus =
        mapOf(
            "unix" to "#EXTM3U\n#EXTINF:-1,One\nhttp://a\n#EXTINF:-1,Two\nhttp://b\n",
            "crlf" to "#EXTM3U\r\n#EXTINF:-1,One\r\nhttp://a\r\n",
            "cr only" to "#EXTM3U\r#EXTINF:-1,One\rhttp://a\r",
            "mixed endings" to "#EXTM3U\r\n#EXTINF:-1,One\nhttp://a\r#EXTINF:-1,Two\r\nhttp://b",
            "bom" to "﻿#EXTM3U\n#EXTINF:-1,One\nhttp://a\n",
            "bom + crlf" to "﻿#EXTM3U\r\n#EXTINF:-1,One\r\nhttp://a\r\n",
            "no trailing newline" to "#EXTM3U\n#EXTINF:-1,One\nhttp://a",
            "blank lines" to "#EXTM3U\n\n\n#EXTINF:-1,One\n\nhttp://a\n\n",
            "header only" to "#EXTM3U\n",
            "empty" to "",
            "url with no extinf" to "http://a/stream.ts\nhttp://b/other.ts\n",
            "epg url header" to "#EXTM3U url-tvg=\"http://epg/x.xml\"\n#EXTINF:-1,One\nhttp://a\n",
            "comment lines" to "#EXTM3U\n#comment\n#EXTINF:-1,One\nhttp://a\n",
            "duplicate urls" to "#EXTM3U\nhttp://a\n#EXTINF:-1,Titled\nhttp://a\n",
        )

    @Test
    fun sourcePathMatchesStringPathAcrossLineEndings() {
        for ((name, text) in corpus) {
            val viaString = parseM3u(text)
            val viaSource = parseM3uLines(sourceOf(text).m3uLineSequence())

            assertEquals(
                viaString.entries.size,
                viaSource.entries.size,
                "$name: entry count diverged between the String and Source paths",
            )
            assertEquals(viaString.epgUrl, viaSource.epgUrl, "$name: epgUrl diverged")
            // Compare the whole entry list, not just the count — a line-split
            // bug can preserve arity while corrupting titles or URLs.
            assertEquals(viaString.entries, viaSource.entries, "$name: entries diverged")
        }
    }

    @Test
    fun stripsBomOnlyFromTheFirstLine() {
        // A U+FEFF anywhere but position 0 is legitimate content and must
        // survive — the old `content[0]` check had exactly this property.
        val text = "#EXTM3U\n#EXTINF:-1,Ti﻿tle\nhttp://a\n"
        val viaSource = parseM3uLines(sourceOf(text).m3uLineSequence())
        assertEquals("Ti﻿tle", viaSource.entries.single().title)
    }

    @Test
    fun parsesALargePlaylistThroughTheStreamingPath() {
        // Not a memory assertion (unit-test heap measurement is too flaky to
        // gate a build on) — this proves the streaming path stays correct at a
        // size where the old copy-chain was the problem.
        val entryCount = 50_000
        val text =
            buildString {
                append("#EXTM3U\n")
                for (i in 0 until entryCount) {
                    append("#EXTINF:-1 tvg-id=\"c$i\" group-title=\"G${i % 50}\",Channel $i\n")
                    append("http://example.test/stream/$i.ts\n")
                }
            }

        val result = parseM3uLines(sourceOf(text).m3uLineSequence())

        assertEquals(entryCount, result.entries.size)
        assertEquals("Channel 0", result.entries.first().title)
        assertEquals("Channel ${entryCount - 1}", result.entries.last().title)
        assertEquals("c${entryCount - 1}", result.entries.last().tvgId)
        assertEquals("http://example.test/stream/${entryCount - 1}.ts", result.entries.last().streamUrl)
    }

    /**
     * The load-bearing claim of the MB-230 fix: the line sequence is LAZY.
     *
     * Counting read *calls* proves nothing — `buffered()` fetches in segments
     * either way. The real test is how much of the playlist has been pulled
     * off the wire by the time the first line is available: a lazy sequence
     * needs one segment, an eager one needs all of it.
     */
    @Test
    fun yieldsTheFirstLineWithoutReadingTheWholePlaylist() {
        val text =
            buildString {
                append("#EXTM3U\n")
                for (i in 0 until 20_000) {
                    append("#EXTINF:-1,Channel $i\n")
                    append("http://example.test/$i\n")
                }
            }
        val counting = CountingRawSource(Buffer().apply { writeString(text) })
        val iterator = counting.buffered().m3uLineSequence().iterator()

        val firstLine = iterator.next()

        assertEquals("#EXTM3U", firstLine)
        assertTrue(
            counting.bytesRead < text.length / 10,
            "first line cost ${counting.bytesRead} of ${text.length} bytes — the sequence " +
                "materialised the playlist instead of streaming it (the MB-230 regression)",
        )
    }

    /** Records how many bytes the sequence has actually pulled so far. */
    private class CountingRawSource(private val delegate: RawSource) : RawSource {
        var bytesRead = 0L
            private set

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            val n = delegate.readAtMostTo(sink, byteCount)
            if (n > 0) bytesRead += n
            return n
        }

        override fun close() = delegate.close()
    }
}
