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
                    SettingsChip(
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
        if (state.showChannelNumbers) {
            // MK.16.5 — number padding format. Disabled-feel chips when
            // the toggle is off; only render the picker when on so the
            // dependency is visible.
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.Text(
                    text = "Number format",
                    color = com.yancotv.android.ui.theme.LocalYancoPalette.current.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    com.yancotv.android.prefs.ChannelNumberFormat.values().forEach { f ->
                        SettingsChip(
                            label = f.displayName,
                            selected = state.channelNumberFormat == f,
                            onClick = { scope.launch { prefs.setChannelNumberFormat(f) } },
                        )
                    }
                }
            }
        }

        SettingsToggleRow(
            label = "Smart category grouping",
            description = "Bucket categories by detected language / region prefix (AR | …, EN | …, US| …) into collapsible Arabic / English / USA / … parents. Off shows the provider's flat category list.",
            checked = state.smartGrouping,
            onCheckedChange = { scope.launch { prefs.setSmartGrouping(it) } },
        )
    }
}

