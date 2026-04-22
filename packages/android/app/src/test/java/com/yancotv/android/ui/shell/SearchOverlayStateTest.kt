package com.yancotv.android.ui.shell

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the global search overlay flag. This is a singleton object, so
 * reset the state at the end of every test to keep them isolated.
 */
class SearchOverlayStateTest {

    @AfterTest fun reset() = SearchOverlayState.hide()

    @Test fun startsHidden() {
        SearchOverlayState.hide()
        assertFalse(SearchOverlayState.visible.value)
    }

    @Test fun showSetsVisible() {
        SearchOverlayState.hide()
        SearchOverlayState.show()
        assertTrue(SearchOverlayState.visible.value)
    }

    @Test fun toggleFlipsState() {
        SearchOverlayState.hide()
        SearchOverlayState.toggle()
        assertTrue(SearchOverlayState.visible.value)
        SearchOverlayState.toggle()
        assertFalse(SearchOverlayState.visible.value)
    }

    @Test fun showWhileAlreadyVisibleIsIdempotent() {
        SearchOverlayState.show()
        SearchOverlayState.show()
        assertTrue(SearchOverlayState.visible.value)
    }
}
