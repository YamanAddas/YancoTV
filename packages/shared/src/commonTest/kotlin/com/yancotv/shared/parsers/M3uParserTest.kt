package com.yancotv.shared.parsers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors `tests/unit/m3u-parser.test.ts`. When the TS test file changes
 * ports of added cases land here in the same commit.
 */
class M3uParserTest {
    @Test
    fun parsesBasicPlaylist() {
        val content = """#EXTM3U
#EXTINF:-1 tvg-id="ch1" tvg-name="Channel 1" tvg-logo="http://logo.com/1.png" group-title="News",Channel One HD
http://stream.example.com/ch1
#EXTINF:-1 tvg-id="ch2" tvg-name="Channel 2" tvg-logo="http://logo.com/2.png" group-title="Sports",ESPN Live
http://stream.example.com/ch2"""

        val (entries, _) = parseM3u(content)
        assertEquals(2, entries.size)

        assertEquals("Channel One HD", entries[0].title)
        assertEquals("ch1", entries[0].tvgId)
        assertEquals("Channel 1", entries[0].tvgName)
        assertEquals("http://logo.com/1.png", entries[0].tvgLogo)
        assertEquals("News", entries[0].groupTitle)
        assertEquals("http://stream.example.com/ch1", entries[0].streamUrl)
        assertEquals(-1.0, entries[0].duration)

        assertEquals("ESPN Live", entries[1].title)
        assertEquals("Sports", entries[1].groupTitle)
    }

    @Test
    fun handlesBomMarker() {
        val content = "\uFEFF#EXTM3U\n#EXTINF:-1,Test Channel\nhttp://example.com/stream"
        val (entries, _) = parseM3u(content)
        assertEquals(1, entries.size)
        assertEquals("Test Channel", entries[0].title)
    }

    @Test
    fun handlesCrlfLineEndings() {
        val content = "#EXTM3U\r\n#EXTINF:-1,Channel A\r\nhttp://a.com/1\r\n#EXTINF:-1,Channel B\r\nhttp://b.com/2\r\n"
        val (entries, _) = parseM3u(content)
        assertEquals(2, entries.size)
        assertEquals("Channel A", entries[0].title)
        assertEquals("Channel B", entries[1].title)
    }

    @Test
    fun handlesCrOnlyLineEndings() {
        val content = "#EXTM3U\r#EXTINF:-1,Channel X\rhttp://x.com/stream"
        val (entries, _) = parseM3u(content)
        assertEquals(1, entries.size)
        assertEquals("Channel X", entries[0].title)
    }

    @Test
    fun skipsEmptyLines() {
        val content = """#EXTM3U

#EXTINF:-1,Channel 1

http://example.com/1

#EXTINF:-1,Channel 2

http://example.com/2
"""
        val (entries, _) = parseM3u(content)
        assertEquals(2, entries.size)
    }

    @Test
    fun handlesUrlWithoutExtinf() {
        val content = """#EXTM3U
http://example.com/stream1
#EXTINF:-1,Proper Channel
http://example.com/stream2"""

        val (entries, _) = parseM3u(content)
        assertEquals(2, entries.size)
        assertEquals("http://example.com/stream1", entries[0].streamUrl)
        assertEquals("Proper Channel", entries[1].title)
    }

    @Test
    fun extractsDuration() {
        val content = """#EXTM3U
#EXTINF:3600,Movie Title
http://example.com/movie
#EXTINF:-1,Live Channel
http://example.com/live
#EXTINF:0,Unknown Duration
http://example.com/unknown"""

        val (entries, _) = parseM3u(content)
        assertEquals(3600.0, entries[0].duration)
        assertEquals(-1.0, entries[1].duration)
        assertEquals(0.0, entries[2].duration)
    }

    @Test
    fun handlesExtinfNoAttributes() {
        val content = """#EXTM3U
#EXTINF:-1,Simple Channel
http://example.com/simple"""

        val (entries, _) = parseM3u(content)
        assertEquals(1, entries.size)
        assertEquals("Simple Channel", entries[0].title)
        assertEquals("", entries[0].groupTitle)
        assertEquals("", entries[0].tvgId)
    }

    @Test
    fun handlesSingleQuotedAttributes() {
        val content = """#EXTM3U
#EXTINF:-1 tvg-id='abc' group-title='Entertainment',Show Name
http://example.com/show"""

        val (entries, _) = parseM3u(content)
        assertEquals("abc", entries[0].tvgId)
        assertEquals("Entertainment", entries[0].groupTitle)
    }

    @Test
    fun handlesRealWorldTitlesWithNoise() {
        val content = """#EXTM3U
#EXTINF:-1 tvg-id="us.cnn" tvg-logo="http://cdn.logo/cnn.png" group-title="US | News",US: CNN HD [MULTI]
http://provider.com/live/us/cnn
#EXTINF:-1 tvg-id="" tvg-logo="" group-title="VOD | Movies",The Matrix (1999) [4K]
http://provider.com/movie/12345.mp4
#EXTINF:-1 tvg-id="" group-title="Series | Drama",Breaking Bad S01E01
http://provider.com/series/bb/s01e01.mp4"""

        val (entries, _) = parseM3u(content)
        assertEquals(3, entries.size)
        assertEquals("US: CNN HD [MULTI]", entries[0].title)
        assertEquals("US | News", entries[0].groupTitle)
        assertEquals("The Matrix (1999) [4K]", entries[1].title)
        assertEquals("Breaking Bad S01E01", entries[2].title)
    }

    @Test
    fun handlesEmptyPlaylist() {
        val content = "#EXTM3U\n"
        val (entries, _) = parseM3u(content)
        assertEquals(0, entries.size)
    }

    @Test
    fun handlesHeaderOnly() {
        val content = "#EXTM3U"
        val (entries, _) = parseM3u(content)
        assertEquals(0, entries.size)
    }

    @Test
    fun skipsOtherDirectives() {
        val content = """#EXTM3U
#EXTINF:-1,Channel 1
#EXTVLCOPT:http-user-agent=Mozilla
#EXTVLCOPT:http-referrer=http://ref.com
http://example.com/ch1"""

        val (entries, _) = parseM3u(content)
        assertEquals(1, entries.size)
        assertEquals("Channel 1", entries[0].title)
        assertEquals("http://example.com/ch1", entries[0].streamUrl)
    }

    @Test
    fun handlesSpecialCharsInGroupTitle() {
        val content = """#EXTM3U
#EXTINF:-1 group-title="US | News & Politics (HD)",Channel
http://example.com/ch"""

        val (entries, _) = parseM3u(content)
        assertEquals("US | News & Politics (HD)", entries[0].groupTitle)
    }

    @Test
    fun extractsUrlTvgEpgUrl() {
        val content = """#EXTM3U url-tvg="http://epg.example.com/guide.xml.gz"
#EXTINF:-1 tvg-id="ch1",Channel 1
http://stream.example.com/ch1"""

        val result = parseM3u(content)
        assertEquals("http://epg.example.com/guide.xml.gz", result.epgUrl)
        assertEquals(1, result.entries.size)
    }

    @Test
    fun extractsXTvgUrlEpgUrl() {
        val content = """#EXTM3U x-tvg-url="http://epg.example.com/alt.xml"
#EXTINF:-1,Channel 1
http://stream.example.com/ch1"""

        val result = parseM3u(content)
        assertEquals("http://epg.example.com/alt.xml", result.epgUrl)
    }

    @Test
    fun returnsNullEpgUrlWhenAbsent() {
        val content = """#EXTM3U
#EXTINF:-1,Channel 1
http://stream.example.com/ch1"""

        val result = parseM3u(content)
        assertNull(result.epgUrl)
    }

    @Test
    fun handlesLargePlaylist() {
        val lines = StringBuilder("#EXTM3U")
        for (i in 0 until 10000) {
            lines.append("\n#EXTINF:-1 group-title=\"Group ${i % 10}\",Channel $i")
            lines.append("\nhttp://example.com/ch$i")
        }

        val result = parseM3u(lines.toString())
        assertEquals(10000, result.entries.size)
        assertTrue(result.entries[0].title == "Channel 0")
        assertTrue(result.entries.last().title == "Channel 9999")
    }
}
