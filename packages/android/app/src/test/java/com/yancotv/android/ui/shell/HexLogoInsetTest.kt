package com.yancotv.android.ui.shell

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inset that keeps a live channel's logo clear of the hexagon's diagonal.
 *
 * The bug this replaced was a **fixed** 16 dp, which was tuned against the
 * 140 dp television orb and applied to every size after MK.37 started deriving
 * tile sizes from the lane. The cut is a fraction of the tile; a constant is not.
 */
class HexLogoInsetTest {

    @Test
    fun `inset scales with the tile`() {
        val small = hexLogoInset(80.dp)
        val phone = hexLogoInset(115.dp)
        val tv = hexLogoInset(140.dp)
        assertTrue("$small should be under $phone", small < phone)
        assertTrue("$phone should be under $tv", phone < tv)
    }

    /**
     * The regression: at the phone's tile the old constant left the logo 7 dp
     * further out than the diagonal allows, which is where the outer letters
     * were being clipped.
     */
    @Test
    fun `a phone tile needs more inset than the old fixed sixteen`() {
        assertTrue("expected more than 16.dp, got ${hexLogoInset(115.dp)}", hexLogoInset(115.dp) > 16.dp)
    }

    @Test
    fun `the inset is clamped so a huge tile does not swallow its own logo`() {
        // Cut clamps at 36 dp, so the inset clamps at 36 * 0.72.
        assertEquals(36.dp * 0.72f, hexLogoInset(4000.dp))
        assertEquals(10.dp * 0.72f, hexLogoInset(10.dp))
    }
}
