package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.focus.dpadVerticalScroll
import com.yancotv.android.ui.theme.LocalYancoPalette

/**
 * Read-only key reference. Each shortcut row renders as a SettingsRow
 * with the action label on the left and one or more compact key chips
 * on the right (one chip per alternative key combo). The chips are
 * sized to their natural width and pinned to a single line, so a long
 * combo like 'Ctrl + K' or 'Channel +' never wraps to two rows the way
 * the previous one-string-many-tokens layout did.
 *
 * Verified against the actual key-event handlers (audit 2026-04-28):
 *   - MainActivity.onKeyDown / onKeyUp — global hotkeys (search,
 *     long-press menu).
 *   - PlayerActivity.onKeyDown — player-side keys (play/pause, channel,
 *     info, stop, back, options menu).
 *   - GuideScreen.combinedClickable — tap programme block, long-press
 *     channel row.
 *   - PinEntryDialog — implicit BACK / Cancel dismissal.
 *
 * Entries dropped: 'Show controller (Remote MENU)' was wrong — MENU
 * opens the OPTIONS popup, not the controller. Replaced with
 * 'Open options menu' pointing at the correct key.
 */
@Composable
fun SettingsShortcutsTab(modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .dpadVerticalScroll(scroll)
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp),
    ) {
        SettingsSection(
            title = "Remote & keyboard",
            sub =
            "Quick reference for the TV-remote and keyboard bindings YancoTV listens for. " +
                "Shortcuts aren't user-configurable yet — let us know in the issues if any clash with your remote.",
        ) {}

        SettingsSection(title = "Shell") {
            ShortcutRow("Open search", listOf("Ctrl + K", "Search"))
            SettingsRowSpacer()
            ShortcutRow("Sidebar up / down", listOf("D-pad ↑", "D-pad ↓"))
            SettingsRowSpacer()
            ShortcutRow("Enter a section", listOf("D-pad →", "Enter"))
            SettingsRowSpacer()
            ShortcutRow("Back out", listOf("Back", "Esc"))
        }

        SettingsSection(title = "Settings") {
            // MK.31 — numpad 1-9 jumps to the Nth Settings tab. Order after
            // MK.29.5: 1=Sources, 2=General, 3=Playback, 4=Parental,
            // 5=Recordings, 6=Network, 7=Groups, 8=EPG, 9=Appearance.
            // Gated on sidebar focus + empty search field, so typing
            // into the search bar isn't hijacked.
            ShortcutRow("Jump to Settings tab", listOf("1–9"))
        }

        SettingsSection(title = "Playback") {
            ShortcutRow("Play / Pause", listOf("Play", "Space"))
            SettingsRowSpacer()
            ShortcutRow("Next channel", listOf("Channel +", "D-pad ↓"))
            SettingsRowSpacer()
            ShortcutRow("Previous channel", listOf("Channel −", "D-pad ↑"))
            SettingsRowSpacer()
            ShortcutRow("Open options menu", listOf("Menu"))
            SettingsRowSpacer()
            ShortcutRow("Stream info overlay", listOf("Info", "Guide"))
            SettingsRowSpacer()
            ShortcutRow("Jump to live edge", listOf("Stop"))
            SettingsRowSpacer()
            ShortcutRow("Exit fullscreen", listOf("Back", "Esc"))
        }

        SettingsSection(title = "Guide + channel lists") {
            ShortcutRow("Open channel actions", listOf("Long-press channel"))
            SettingsRowSpacer()
            ShortcutRow("Lock or hide a channel", listOf("Long-press → menu"))
            SettingsRowSpacer()
            ShortcutRow("Open programme details", listOf("Tap programme"))
            SettingsRowSpacer()
            ShortcutRow("Set reminder", listOf("Programme dialog"))
            SettingsRowSpacer()
            ShortcutRow("Play catch-up", listOf("Programme dialog"))
        }

        SettingsSection(title = "Parental gate") {
            ShortcutRow("Unlock a locked channel", listOf("Tap → enter PIN"))
            SettingsRowSpacer()
            ShortcutRow("Dismiss PIN prompt", listOf("Back", "Cancel"))
        }
    }
}

/**
 * Render one shortcut row. The right slot lays out N key chips with
 * 6dp gaps; each chip is intrinsic-width so a 'D-pad →' chip is
 * narrow and a 'Long-press → menu' chip is wider. Both stay one line
 * because [KeyChip] sets `maxLines = 1, softWrap = false` on its Text.
 */
@Composable
private fun ShortcutRow(label: String, keys: List<String>) {
    SettingsRow(
        label = label,
        right = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keys.forEach { combo -> KeyChip(combo) }
            }
        },
    )
}

/**
 * One key combo. Sized to the text — never wraps. Accent-tinted
 * 'kbd' aesthetic so the chips read as keyboard glyphs at a glance.
 */
@Composable
private fun KeyChip(keys: String) {
    val palette = LocalYancoPalette.current
    Box(
        modifier =
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.Accent.copy(alpha = 0.12f))
            .border(
                width = 1.dp,
                color = palette.Accent.copy(alpha = 0.35f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = keys,
            color = palette.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}
