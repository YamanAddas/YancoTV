package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.focus.dpadVerticalScroll
import com.yancotv.android.ui.theme.LocalYancoPalette

/**
 * Static key reference. No wiring — each entry documents a binding
 * that lives in its consumer (PlayerActivity.onKeyDown,
 * MainActivity.onKeyDown, etc.). Updating this list when a new
 * shortcut lands is the source-of-truth discipline.
 */
@Composable
fun SettingsShortcutsTab(modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                // MB-116: focusable + D-pad scroll on the read-only body.
                .dpadVerticalScroll(scroll)
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // MB-109 fallback focusable lives at SettingsScreen.ContentPane
        // scope (one place for all 14 tabs), not here. Keeping it inline
        // pushed the heading down 16dp from the breadcrumb.
        Text(
            text = "Shortcuts",
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Quick reference for TV remote + keyboard bindings. Shortcuts are not user-configurable yet — let us know in the issues if something clashes with your remote.",
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 12.sp,
        )

        Section("Shell") {
            Shortcut("Open search", "Ctrl + K / Remote SEARCH key")
            Shortcut("Sidebar up / down", "D-pad Up / Down")
            Shortcut("Enter a section", "D-pad Right / Enter")
            Shortcut("Back out", "Back / Esc")
        }

        Section("Playback") {
            Shortcut("Play / Pause", "Remote PLAY/PAUSE / Space")
            Shortcut("Next / Previous channel", "Channel Up/Down or D-pad Up/Down in player")
            Shortcut("Show controller", "Remote MENU")
            Shortcut("Stream info overlay", "Remote INFO / GUIDE")
            Shortcut("Jump to live edge", "Remote STOP (live streams only)")
            Shortcut("Exit fullscreen", "Back / Esc")
        }

        Section("Guide + channel lists") {
            Shortcut("Open channel actions", "Long-press a channel row or cell")
            Shortcut("Lock / Hide a channel", "Long-press → Lock or Hide")
            Shortcut("Open programme details", "Tap a programme block in Guide")
            Shortcut("Set reminder", "Programme dialog → Set reminder (future programmes)")
            Shortcut("Play catch-up", "Programme dialog → Play catch-up (past programmes, where available)")
        }

        Section("Parental gate") {
            Shortcut("Unlock a locked channel", "Tap to play → enter PIN")
            Shortcut("Dismiss PIN prompt", "Back / Cancel → returns to previous screen")
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(LocalYancoPalette.current.BackgroundRaised)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = LocalYancoPalette.current.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp)
        content()
    }
}

@Composable
private fun Shortcut(
    label: String,
    keys: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = keys,
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 12.sp,
        )
    }
}
