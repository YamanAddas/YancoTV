package com.yancotv.shared.parsers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmltvParserTest {
    // --- parseXmltvTimestamp ---

    @Test
    fun parsesTimestampWithoutOffsetUtc() {
        // 2026-04-15T12:00:00Z — precomputed:
        // 2026-01-01T00:00:00Z = 1767225600; +104d +12h = 1776254400
        val ts = parseXmltvTimestamp("20260415120000")
        assertEquals(1776254400L, ts)
    }

    @Test
    fun parsesTimestampWithPositiveOffset() {
        // 2026-04-15T12:00:00+02:00 = 2026-04-15T10:00:00Z = 1776254400 - 2h
        val ts = parseXmltvTimestamp("20260415120000 +0200")
        assertEquals(1776254400L - 2 * 3600L, ts)
    }

    @Test
    fun parsesTimestampWithNegativeOffset() {
        // 2026-04-15T12:00:00-05:00 = 2026-04-15T17:00:00Z = 1776254400 + 5h
        val ts = parseXmltvTimestamp("20260415120000 -0500")
        assertEquals(1776254400L + 5 * 3600L, ts)
    }

    @Test
    fun handlesTimestampWithNoSpacesBeforeOffset() {
        // 2026-01-01T00:00:00Z = 1767225600
        val ts = parseXmltvTimestamp("20260101000000+0000")
        assertEquals(1767225600L, ts)
    }

    @Test
    fun returnsZeroForEmptyString() {
        assertEquals(0L, parseXmltvTimestamp(""))
    }

    @Test
    fun returnsZeroForInvalidFormat() {
        assertEquals(0L, parseXmltvTimestamp("not-a-timestamp"))
        assertEquals(0L, parseXmltvTimestamp("2026"))
        assertEquals(0L, parseXmltvTimestamp("20261301000000")) // month 13
    }

    @Test
    fun trimsWhitespace() {
        val ts = parseXmltvTimestamp("  20260415120000 +0000  ")
        assertEquals(1776254400L, ts)
    }

    // --- parseXmltv ---

    @Test
    fun parsesChannelsFromXmltv() {
        val xml = """<?xml version="1.0"?>
<tv>
  <channel id="bbc1">
    <display-name>BBC One</display-name>
    <icon src="http://logos.com/bbc1.png" />
  </channel>
  <channel id="itv1">
    <display-name>ITV</display-name>
  </channel>
</tv>"""

        val result = parseXmltv(xml)
        assertEquals(2, result.channels.size)
        assertEquals(
            XmltvChannel(id = "bbc1", displayName = "BBC One", iconUrl = "http://logos.com/bbc1.png"),
            result.channels[0],
        )
        assertEquals(
            XmltvChannel(id = "itv1", displayName = "ITV", iconUrl = null),
            result.channels[1],
        )
    }

    @Test
    fun parsesProgrammesFromXmltv() {
        val xml = """<?xml version="1.0"?>
<tv>
  <programme start="20260415180000 +0000" stop="20260415190000 +0000" channel="bbc1">
    <title>Evening News</title>
    <desc>The latest headlines.</desc>
    <category>News</category>
    <icon src="http://img.com/news.png" />
  </programme>
  <programme start="20260415190000 +0000" stop="20260415200000 +0000" channel="bbc1">
    <title>Drama Show</title>
  </programme>
</tv>"""

        val result = parseXmltv(xml)
        assertEquals(2, result.programmes.size)

        val p1 = result.programmes[0]
        assertEquals("bbc1", p1.channelId)
        assertEquals("Evening News", p1.title)
        assertEquals("The latest headlines.", p1.description)
        assertEquals("News", p1.category)
        assertEquals("http://img.com/news.png", p1.iconUrl)
        assertEquals(parseXmltvTimestamp("20260415180000 +0000"), p1.startTime)
        assertEquals(parseXmltvTimestamp("20260415190000 +0000"), p1.endTime)

        val p2 = result.programmes[1]
        assertEquals("Drama Show", p2.title)
        assertNull(p2.description)
        assertNull(p2.category)
    }

    @Test
    fun skipsProgrammesMissingRequiredAttributes() {
        val xml = """<tv>
  <programme start="20260415180000 +0000" channel="bbc1">
    <title>Missing stop attr</title>
  </programme>
  <programme start="20260415180000 +0000" stop="20260415190000 +0000" channel="bbc1">
    <title>Valid</title>
  </programme>
</tv>"""

        val result = parseXmltv(xml)
        assertEquals(1, result.programmes.size)
        assertEquals("Valid", result.programmes[0].title)
    }

    @Test
    fun skipsProgrammesWithNoTitle() {
        val xml = """<tv>
  <programme start="20260415180000 +0000" stop="20260415190000 +0000" channel="bbc1">
    <desc>No title element</desc>
  </programme>
</tv>"""

        val result = parseXmltv(xml)
        assertEquals(0, result.programmes.size)
    }

    @Test
    fun decodesXmlEntitiesInText() {
        val xml = """<tv>
  <programme start="20260415180000 +0000" stop="20260415190000 +0000" channel="ch1">
    <title>Tom &amp; Jerry</title>
    <desc>It&apos;s a &quot;classic&quot;</desc>
  </programme>
</tv>"""

        val result = parseXmltv(xml)
        assertEquals("Tom & Jerry", result.programmes[0].title)
        assertEquals("It's a \"classic\"", result.programmes[0].description)
    }

    @Test
    fun handlesEmptyXmlGracefully() {
        val result = parseXmltv("<tv></tv>")
        assertTrue(result.channels.isEmpty())
        assertTrue(result.programmes.isEmpty())
    }

    @Test
    fun handlesPlainUtf8BufferInput() {
        val xml = """<tv>
  <channel id="ch1"><display-name>Test</display-name></channel>
</tv>"""
        val buf = xml.encodeToByteArray()
        val result = parseXmltv(buf)
        assertEquals(1, result.channels.size)
        assertEquals("ch1", result.channels[0].id)
    }

    @Test
    fun handlesLocalizedTitleElementsWithLangAttribute() {
        val xml = """<tv>
  <programme start="20260415180000 +0000" stop="20260415190000 +0000" channel="ch1">
    <title lang="en">Hello World</title>
  </programme>
</tv>"""

        val result = parseXmltv(xml)
        assertEquals("Hello World", result.programmes[0].title)
    }

    @Test
    fun handlesNumericCharacterReferences() {
        val xml = """<tv>
  <programme start="20260415180000 +0000" stop="20260415190000 +0000" channel="ch1">
    <title>&#72;ello</title>
  </programme>
</tv>"""

        val result = parseXmltv(xml)
        assertEquals("Hello", result.programmes[0].title)
    }
}
