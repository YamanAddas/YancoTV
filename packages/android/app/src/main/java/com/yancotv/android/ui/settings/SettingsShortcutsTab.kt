package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.focus.dpadVerticalScroll
import com.yancotv.android.ui.theme.LocalYancoPalette

/**
 * Read-only key reference. Each [SettingsSection] groups shortcuts by
 * surface; each row uses the [SettingsRow] frame with the keys rendered
 * as an accent-tinted "kbd"-style chip on the right.
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
                .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 32.dp),
    ) {
        SettingsSection(
            title = "Remote &amp; keyboard",
            sub = "Quick reference for the TV-remote and keyboard bindings YancoTV listens for. Shortcuts aren't user-configurable yet — let us know in the issues if any clash with your remote.",
        ) {}

        SettingsSection(title = "Shell") {
            ShortcutRow("Open search", "Ctrl + K · Remote SEARCH")
            SettingsRowSpacer()
            ShortcutRow("Sidebar up / down", "D-pad ↑ / ↓")
            SettingsRowSpacer()
            ShortcutRow("Enter a section", "D-pad → · Enter")
            SettingsRowSpacer()
            ShortcutRow("Back out", "Back · Esc")
        }

        SettingsSection(title = "Playback") {
            ShortcutRow("Play / Pause", "Remote PLAY · Space")
            SettingsRowSpacer()
            ShortcutRow("Next / Previous channel", "Channel ± · D-pad ↑ / ↓ in player")
            SettingsRowSpacer()
            ShortcutRow("Show controller", "Remote MENU")
            SettingsRowSpacer()
            ShortcutRow("Stream info overlay", "Remote INFO · GUIDE")
            SettingsRowSpacer()
            ShortcutRow("Jump to live edge", "Remote STOP (live streams only)")
            SettingsRowSpacer()
            ShortcutRow("Exit fullscreen", "Back · Esc")
        }

        SettingsSection(title = "Guide + channel lists") {
            ShortcutRow("Open channel actions", "Long-press a channel row or cell")
            SettingsRowSpacer()
            ShortcutRow("Lock / Hide a channel", "Long-press → Lock or Hide")
            SettingsRowSpacer()
            ShortcutRow("Open programme details", "Tap a programme block in Guide")
            SettingsRowSpacer()
            ShortcutRow("Set reminder", "Programme dialog → Set reminder")
            SettingsRowSpacer()
            ShortcutRow("Play catch-up", "Programme dialog → Play catch-up")
        }

        SettingsSection(title = "Parental gate") {
            ShortcutRow("Unlock a locked channel", "Tap to play → enter PIN")
            SettingsRowSpacer()
            ShortcutRow("Dismiss PIN prompt", "Back · Cancel")
        }
    }
}

@Composable
private fun ShortcutRow(
    label: String,
    keys: String,
) {
    SettingsRow(
        label = label,
        right = { KeyChip(keys) },
    )
}

@Composable
private fun KeyChip(keys: String) {
    val palette = LocalYancoPalette.current
    Box(
        modifier =
            Modifier
                .widthIn(min = 100.dp)
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
        )
    }
}
