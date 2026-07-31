package com.yancotv.shared.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MK.33.1 — [SourceScopedGroup] contract.
 *
 * This encoding is the single point where a source-scoped selection can go
 * wrong, and getting it wrong is not cosmetic: a mis-decoded key filters the
 * catalogue by the wrong playlist, or by a group name that includes half a
 * UUID. That is why the shell keeps passing a plain `String` around — all the
 * risk is concentrated here, where it can be tested exhaustively.
 */
class SourceScopedGroupTest {
    private val src = "9f8b1c2d-0000-4aaa-8bbb-1234567890ab"

    @Test
    fun `a scoped key round-trips`() {
        val decoded = SourceScopedGroup.decode(SourceScopedGroup.encode(src, "Sports"))
        assertEquals(src, decoded?.sourceId)
        assertEquals("Sports", decoded?.groupName)
    }

    @Test
    fun `whole-playlist keys decode with a null group`() {
        // Selecting the playlist row itself, not one of its groups.
        val decoded = SourceScopedGroup.decode(SourceScopedGroup.encodeWholeSource(src))
        assertEquals(src, decoded?.sourceId)
        assertNull(decoded?.groupName, "whole-playlist selection must not carry a group filter")
    }

    @Test
    fun `group names containing the characters providers actually use survive`() {
        // Every one of these appears in real M3U group-titles, and each was a
        // candidate separator that had to be rejected. If any of them broke the
        // encoding, the affected groups would filter as the wrong playlist.
        val nasty = listOf(
            "AR | Sports",
            "US: Entertainment",
            "Movies/Action",
            "Kids - Cartoons",
            "24/7 | Comedy",
            "Sports__all__", // collides with the shell's own sentinel
            "__favorites__", // ditto
            "__src__not-really-a-key", // looks like our own prefix
            "  leading and trailing  ",
            "emoji 🏈 group",
            "الرياضة", // Arabic, RTL
        )
        for (group in nasty) {
            val decoded = SourceScopedGroup.decode(SourceScopedGroup.encode(src, group))
            assertEquals(src, decoded?.sourceId, "sourceId lost for group '$group'")
            assertEquals(group, decoded?.groupName, "group name mangled for '$group'")
        }
    }

    @Test
    fun `unscoped keys decode as null rather than throwing`() {
        // The shell hands this function every selection key it holds, including
        // its two synthetic sentinels and bare provider group names. Null means
        // "no source filter", which is the pre-MK.33 behaviour.
        assertNull(SourceScopedGroup.decode("__all__"))
        assertNull(SourceScopedGroup.decode("__favorites__"))
        assertNull(SourceScopedGroup.decode("Sports"))
        assertNull(SourceScopedGroup.decode(""))
    }

    @Test
    fun `malformed scoped keys decode as null rather than half-parsing`() {
        // A truncated or hand-edited key must not produce a garbage sourceId —
        // it would filter to a playlist that does not exist and show an empty
        // screen with no way to tell why.
        assertNull(SourceScopedGroup.decode(SourceScopedGroup.PREFIX), "prefix with no body")
        assertNull(SourceScopedGroup.decode(SourceScopedGroup.PREFIX + src), "no separator")
        assertNull(
            SourceScopedGroup.decode(SourceScopedGroup.PREFIX + SourceScopedGroup.SEP + "Sports"),
            "empty source id must not decode",
        )
    }

    @Test
    fun `an encoded key is distinguishable from a bare group name`() {
        // The rail compares selection keys with plain string equality, so a
        // scoped key must never equal the unscoped group it wraps.
        val encoded = SourceScopedGroup.encode(src, "Sports")
        assertTrue(encoded != "Sports")
        assertTrue(encoded.startsWith(SourceScopedGroup.PREFIX))
    }

    @Test
    fun `two playlists produce different keys for the same group name`() {
        // The entire reason this type exists.
        val other = "00000000-1111-2222-3333-444444444444"
        assertTrue(
            SourceScopedGroup.encode(src, "Sports") != SourceScopedGroup.encode(other, "Sports"),
            "same group under two playlists must not share a selection key",
        )
    }
}
