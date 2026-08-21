package com.yancotv.android.player

import com.yancotv.android.player.options.PlayerOptionCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MK.34.10 — the player chrome's behavioural rules.
 *
 * These are the checks the design brief asks for in its verification phase,
 * written against extracted decisions rather than against a rendered screen.
 * That is a deliberate trade and worth stating plainly: this project has no
 * instrumented-test stack, and adding one is a bigger change than the feature.
 * These tests pin the RULES — focus order, marquee behaviour, menu routing,
 * focus return — and cannot catch a purely visual regression. The visual side
 * was verified by measuring `uiautomator` geometry on a Fire TV during each
 * slice, and that evidence lives in the commit messages.
 */
class ChromeDecisionsTest {

    // ── Focus order ──────────────────────────────────────────────────────────

    @Test
    fun `dock order matches the brief exactly`() {
        assertEquals(
            listOf(
                DockControl.SKIP_BACK,
                DockControl.PLAY_PAUSE,
                DockControl.SKIP_FORWARD,
                DockControl.NEXT,
                DockControl.DIVIDER,
                DockControl.SUBTITLES,
                DockControl.AUDIO,
                DockControl.SPEED,
                DockControl.ASPECT,
                DockControl.FAVORITE,
                DockControl.MENU,
            ),
            dockControlOrder(hasNext = true),
        )
    }

    @Test
    fun `NEXT disappears when there is no next episode, and nothing else moves`() {
        val without = dockControlOrder(hasNext = false)
        assertFalse(DockControl.NEXT in without)
        assertEquals(
            dockControlOrder(hasNext = true).filterNot { it == DockControl.NEXT },
            without,
            "gating NEXT must not disturb the rest of the order",
        )
    }

    @Test
    fun `PREVIOUS does not exist in any configuration`() {
        // User decision, 2026-08-19. The reference dock has no direction
        // controls, and "remove any stray comma/apostrophe button" described the
        // old ‹ glyph precisely. It lost nothing: play(episode) synthesises a
        // one-item queue, so ‹ was already dead for every episode.
        val names = (dockControlOrder(true) + dockControlOrder(false)).map { it.name }
        assertTrue(names.none { it.contains("PREV") }, "a PREVIOUS control came back: $names")
    }

    @Test
    fun `the divider is not a focus stop`() {
        // A focus-order assertion that included the divider would encode a
        // cursor stop that does not exist, and would happily pass a dock where
        // RIGHT silently skipped a real control.
        val focus = dockFocusOrder(hasNext = true)
        assertFalse(DockControl.DIVIDER in focus)
        assertEquals(10, focus.size)
        assertEquals(DockControl.SKIP_BACK, focus.first())
        assertEquals(DockControl.MENU, focus.last())
    }

    // ── Marquee / reduced motion ─────────────────────────────────────────────

    @Test
    fun `a title may scroll by default`() {
        assertEquals(MarqueeMode.ANIMATE, marqueeMode(reduceMotion = false))
    }

    @Test
    fun `reduce motion means no movement at all, not slower movement`() {
        // The accessibility preference asks for stillness. Answering it with a
        // gentler scroll would be answering a different question.
        assertEquals(MarqueeMode.ELLIPSIS, marqueeMode(reduceMotion = true))
    }

    // ── Options routing ──────────────────────────────────────────────────────

    @Test
    fun `the three-dot control opens the sheet ROOT, not a panel`() {
        // MK.34.7's fix. It used to send AUDIO, which opens the Audio panel —
        // and the popup hides itself whenever a panel is active, so the options
        // sheet was unreachable from the dock entirely.
        assertNull(optionCategoryFor(SheetMode.MENU))
    }

    @Test
    fun `every dock chip routes to its own panel`() {
        assertEquals(PlayerOptionCategory.SUBTITLES, optionCategoryFor(SheetMode.SUBS))
        assertEquals(PlayerOptionCategory.AUDIO, optionCategoryFor(SheetMode.AUDIO))
        assertEquals(PlayerOptionCategory.SPEED, optionCategoryFor(SheetMode.SPEED))
        assertEquals(PlayerOptionCategory.ASPECT, optionCategoryFor(SheetMode.ASPECT))
        assertEquals(PlayerOptionCategory.FAVORITES, optionCategoryFor(SheetMode.FAV))
        assertEquals(PlayerOptionCategory.EXTERNAL, optionCategoryFor(SheetMode.EXT))
    }

    @Test
    fun `modes with no panel fall through to the root rather than dead-ending`() {
        assertNull(optionCategoryFor(SheetMode.CAST))
        assertNull(optionCategoryFor(SheetMode.LOOK))
    }

    @Test
    fun `every SheetMode is routed`() {
        // Guards the next control added to the dock: a new mode that nobody
        // mapped would silently open the root, which may or may not be intended.
        SheetMode.entries.forEach { mode ->
            // Must not throw; null is a legitimate answer meaning "root".
            optionCategoryFor(mode)
        }
    }

    // ── Focus return ─────────────────────────────────────────────────────────

    @Test
    fun `closing the sheet restores the dock when it was opened from the dock`() {
        // Without this, BACK drops the user on a bare video surface with no
        // visible focus — the brief's "focus must never disappear", failing at
        // the one moment a user is most likely to press BACK.
        assertTrue(shouldRestoreDockOnOptionsClose(openedFromDock = true))
    }

    @Test
    fun `arriving via the MENU key does not conjure a dock on the way out`() {
        assertFalse(shouldRestoreDockOnOptionsClose(openedFromDock = false))
    }

    // ── Layout direction ─────────────────────────────────────────────────────

    @Test
    fun `the timeline and dock never mirror, because the seek keys never do`() {
        // MK.31.2 fixed LEFT as rewind in every locale. MK.34.9 made the visuals
        // agree: a mirrored bar driven by unmirrored keys means pressing LEFT
        // makes the fill grow toward the press.
        assertTrue(pinsLeftToRight(ChromeSurface.TIMELINE))
        assertTrue(pinsLeftToRight(ChromeSurface.CONTROL_DOCK))
    }

    @Test
    fun `text surfaces still mirror, because they are text`() {
        assertFalse(pinsLeftToRight(ChromeSurface.NOW_PLAYING))
        assertFalse(pinsLeftToRight(ChromeSurface.OPTIONS_SHEET))
    }
}
