package com.yancotv.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.ChannelNumberFormat
import com.yancotv.android.prefs.OpenOn
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * General tab — startup destination + channel-number formatting + smart
 * grouping. Re-skinned onto the [SettingsSection] / [SettingsRow]
 * primitives so it shares the same vertical rhythm and focus chrome
 * as every other tab. Contents and persistence behaviour are unchanged.
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
                .verticalScroll(rememberScrollState()),
    ) {
        SettingsSection(
            title = "Startup",
            sub = "Which screen YancoTV opens first when you launch the app.",
        ) {
            SettingsRow(
                label = "Open app on",
                hint = "Skip the launcher and jump straight into your most-used surface.",
                content = {
                    SettingsChipRow(
                        options = OpenOn.values().toList(),
                        selected = state.openOn,
                        label = { it.displayName.uppercase() },
                        onSelect = { section -> scope.launch { prefs.setOpenOn(section) } },
                    )
                },
            )
        }

        SettingsSection(
            title = "Channels",
            sub = "How channel rows render across the guide and Live TV.",
        ) {
            SettingsToggleRow(
                label = "Show channel numbers",
                description = "Prepends the channel's position number before its title — helpful if you remember channels by number.",
                checked = state.showChannelNumbers,
                onCheckedChange = { scope.launch { prefs.setShowChannelNumbers(it) } },
            )
            if (state.showChannelNumbers) {
                SettingsRowSpacer()
                SettingsRow(
                    label = "Number format",
                    hint = "Padding applied to short channel numbers (1 → 001).",
                    content = {
                        SettingsChipRow(
                            options = ChannelNumberFormat.values().toList(),
                            selected = state.channelNumberFormat,
                            label = { it.displayName },
                            onSelect = { format -> scope.launch { prefs.setChannelNumberFormat(format) } },
                        )
                    },
                )
            }
        }

        SettingsSection(
            title = "Categories",
            sub = "How the provider's category list is grouped on the home and browse rails.",
        ) {
            SettingsToggleRow(
                label = "Smart category grouping",
                description = "Bucket categories by detected language / region prefix into collapsible Arabic / English / USA parents. Off shows the provider's flat list as-is.",
                checked = state.smartGrouping,
                onCheckedChange = { scope.launch { prefs.setSmartGrouping(it) } },
            )
        }
    }
}
