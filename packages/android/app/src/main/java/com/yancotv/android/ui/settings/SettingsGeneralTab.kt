package com.yancotv.android.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yancotv.android.locale.AppLanguage
import com.yancotv.android.locale.LocaleController
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.ChannelNumberFormat
import com.yancotv.android.prefs.OpenOn
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * General tab — startup destination + channel-number formatting + smart
 * grouping. Uses the SettingsSection / SettingsRow / SettingsToggleRow
 * primitives so the row chrome and font scale match every other tab in
 * Settings.
 *
 * Outer padding is calibrated against the breadcrumb (start/end 40dp +
 * top 24 + bottom 20). The tab body indents 32dp horizontally so the
 * row cards align with the breadcrumb's optical edge while leaving a
 * touch of breathing room. Top 8dp keeps the first section title from
 * crowding the breadcrumb's bottom hairline; bottom 32dp gives the
 * last row room to scroll past the panel edge without feeling clipped.
 */
@Composable
fun SettingsGeneralTab(modifier: Modifier = Modifier, prefs: AppPreferences = koinInject()) {
    val scope = rememberCoroutineScope()
    val state by prefs.generalFlow.collectAsState()
    // MK.31.1 — language lives in LocaleController's own SharedPreferences,
    // not AppPreferences: it must be readable from attachBaseContext, which
    // runs before the Koin/SQLDelight graph is guaranteed ready.
    val language by LocaleController.language.collectAsState()
    val ctx = LocalContext.current

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp),
    ) {
        SettingsSection(
            title = "Language",
            sub = "The language YancoTV's own screens use. Provider channel and category names always come from your source as-is.",
        ) {
            SettingsRow(
                label = "App language",
                hint = "Changing this reloads the app so every screen picks up the new language.",
                content = {
                    SettingsChipRow(
                        options = AppLanguage.selectable,
                        selected = language,
                        // Endonyms — "العربية", not "Arabic". This is the one
                        // screen a user may reach while the app is in a
                        // language they can't read, so each option has to be
                        // legible to the person who wants it.
                        label = { it.endonym },
                        onSelect = { choice ->
                            if (choice != language) {
                                LocaleController.setLanguage(ctx, choice)
                                // A running Activity has already resolved its
                                // resources, so nothing on screen changes until
                                // it is rebuilt. recreate() is the whole visible
                                // effect of the switch.
                                (ctx as? Activity)?.recreate()
                            }
                        },
                    )
                },
            )
        }

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
                description =
                "Bucket categories by detected language / region prefix into collapsible Arabic / English / USA parents. " +
                    "Off shows the provider's flat list as-is.",
                checked = state.smartGrouping,
                onCheckedChange = { scope.launch { prefs.setSmartGrouping(it) } },
            )
        }
    }
}
