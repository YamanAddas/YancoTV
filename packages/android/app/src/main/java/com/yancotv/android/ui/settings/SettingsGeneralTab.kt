package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.OpenOn
import com.yancotv.android.ui.theme.LocalYancoPalette
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * General tab — "open app on" choice + a handful of small UX toggles.
 * Patterned on TiviMate's General screen, which is the first thing a
 * user opens after installing. We keep the list small; feature creep
 * lands in its own tab.
 */
@Composable
fun SettingsGeneralTab(
    modifier: Modifier = Modifier,
    prefs: AppPreferences = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val state by prefs.generalFlow.collectAsState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "General",
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(LocalYancoPalette.current.BackgroundRaised)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Open app on",
                color = LocalYancoPalette.current.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Which section greets you when you launch YancoTV.",
                color = LocalYancoPalette.current.TextMuted,
                fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (section in OpenOn.values()) {
                    Chip(
                        label = section.displayName,
                        selected = state.openOn == section,
                        onClick = { scope.launch { prefs.setOpenOn(section) } },
                    )
                }
            }
        }

        // MB-107a: shared focus-aware row, see [SettingsToggleRow].
        SettingsToggleRow(
            label = "Show channel numbers",
            description = "Prepends the channel's position number before its title. Helpful if you remember channels by number.",
            checked = state.showChannelNumbers,
            onCheckedChange = { scope.launch { prefs.setShowChannelNumbers(it) } },
        )

        SettingsToggleRow(
            label = "Smart grouping",
            description = "Bucket the provider's 400+ category list into Sports / Movies / News / Kids / Music / Regions for easy scanning. Selecting a sub-group still filters by the exact category.",
            checked = state.smartGrouping,
            onCheckedChange = { scope.launch { prefs.setSmartGrouping(it) } },
        )
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) LocalYancoPalette.current.Accent.copy(alpha = 0.22f) else LocalYancoPalette.current.BackgroundDeep
    val fg = if (selected) LocalYancoPalette.current.TextPrimary else LocalYancoPalette.current.TextMuted
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(bg)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
