package com.yancotv.shared.content

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistDividersTest {

    @Test
    fun `real banners from the owner's account are dividers`() {
        // Every one of these is a row the provider ships as a heading, taken
        // verbatim from the live catalogue.
        val banners = listOf(
            "##### beIN SP⚽RTS ᴴᴰ #####",
            "###### RELAX ᵁᴴᴰ 3840P ######",
            "### ARABIC 24/7 4K UHD 3840P ###",
            "####### VIAPLAY PPV #######",
            "##### CY| ΚΥΠΡΙΑΚΑ/CYPRUS VIP #####",
            "#### DAZN EXCLUSIVE ᴴᴰ/ᴿᴬᵂ #####",
            "##### 24/7 SINGER #####",
            "##### ᵁᴴᴰ ³⁸⁴⁰ᴾ #####",
        )
        for (title in banners) assertTrue(isPlaylistDivider(title), title)
    }

    @Test
    fun `other separator characters count too`() {
        assertTrue(isPlaylistDivider("=== SPORTS ==="))
        assertTrue(isPlaylistDivider("***  MOVIES  ***"))
        assertTrue(isPlaylistDivider("▬▬▬ KIDS ▬▬▬"))
        assertTrue(isPlaylistDivider("   ### PADDED ###   "))
    }

    @Test
    fun `a row of nothing but the marker is a divider`() {
        assertTrue(isPlaylistDivider("##########"))
        assertTrue(isPlaylistDivider("------"))
    }

    @Test
    fun `channels people actually watch are not dividers`() {
        val real = listOf(
            "beIN SPORTS AFC 5",
            "V SPORT ᵁᴴᴰ ³⁸⁴⁰ᴾ",
            "Ping-Pong -- Live",
            "24/7 SINGER",
            "TRT 1 HD",
            "- Sky Sports",
            "E! Entertainment",
            "",
            "  ",
        )
        for (title in real) assertFalse(isPlaylistDivider(title), title)
    }

    @Test
    fun `both ends must have a run and of the same character`() {
        // A banner opened but not closed is a title with decoration, not a
        // heading — and mixing characters is not a convention any playlist
        // uses.
        assertFalse(isPlaylistDivider("### SPORTS"))
        assertFalse(isPlaylistDivider("SPORTS ###"))
        assertFalse(isPlaylistDivider("### SPORTS ==="))
        assertFalse(isPlaylistDivider("## SPORTS ##"))
    }

    @Test
    fun `a short run of the marker alone is not a divider`() {
        // "##" is two, under the floor, and "#" is a title someone could
        // plausibly have.
        assertFalse(isPlaylistDivider("##"))
        assertFalse(isPlaylistDivider("#"))
    }
}
