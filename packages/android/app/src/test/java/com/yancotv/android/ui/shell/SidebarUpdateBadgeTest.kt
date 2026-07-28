package com.yancotv.android.ui.shell

import com.yancotv.android.ui.nav.AppSection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MK.30.4 — [badgeSection] contract.
 *
 * The badge is the only update signal that survives a swiped-away
 * notification, and on Fire TV it is effectively the *primary* one, since the
 * notification shade is not somewhere a TV user goes. So "exactly one row,
 * only when there's something to act on" is worth pinning down.
 */
class SidebarUpdateBadgeTest {
    @Test
    fun `settings is badged when an update is available`() {
        assertTrue(badgeSection(AppSection.Settings, updateAvailable = true))
    }

    @Test
    fun `no row is badged when there is no update`() {
        for (section in AppSection.entries) {
            assertFalse(
                badgeSection(section, updateAvailable = false),
                "$section must not badge with no update available",
            )
        }
    }

    @Test
    fun `only settings is badged, never a second row`() {
        val badged = AppSection.entries.filter { badgeSection(it, updateAvailable = true) }
        assertTrue(
            badged == listOf(AppSection.Settings),
            "expected exactly [Settings] to badge, got $badged",
        )
    }
}
