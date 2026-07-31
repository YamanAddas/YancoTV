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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yancotv.android.R
import com.yancotv.android.locale.AppLanguage
import com.yancotv.android.locale.LocaleController
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.ChannelNumberFormat
import com.yancotv.android.prefs.OpenOn
import com.yancotv.android.ui.focus.snapToTopNearStart
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

    // MK.30.6 — hoisted so snapToTopNearStart can see the same state.
    val tabScroll = rememberScrollState()

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(tabScroll)
            .snapToTopNearStart(tabScroll)
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp),
    ) {
        val systemLangLabel = stringResource(R.string.lang_system)
        // The locale the UI is actually resolved in — read from the configuration
        // rather than from the stored AppLanguage, because `System` defers to the
        // platform and has no locale of its own.
        val uiLocale = androidx.core.os.ConfigurationCompat
            .getLocales(LocalConfiguration.current)[0]
            ?: java.util.Locale.getDefault()
        SettingsSection(
            title = stringResource(R.string.gen_sec_language),
            sub = stringResource(R.string.gen_sec_language_sub),
        ) {
            SettingsRow(
                label = stringResource(R.string.gen_app_language),
                hint = stringResource(R.string.gen_app_language_hint),
                content = {
                    SettingsChipRow(
                        options = AppLanguage.selectable,
                        selected = language,
                        // Endonyms — "العربية", not "Arabic". This is the one
                        // screen a user may reach while the app is in a
                        // language they can't read, so each option has to be
                        // legible to the person who wants it.
                        // MK.31.24 — endonyms are never translated; that is the
                        // point of an endonym. Only the "System" row, which is
                        // not a language, gets a localized label.
                        label = {
                            if (it == AppLanguage.System) systemLangLabel else it.endonym
                        },
                        // MK.31.26 — TalkBack gets the language's name in the
                        // CURRENT UI language ("Arabic" / "arabe" / "árabe"),
                        // because the visible endonym is by definition in a
                        // language the active TTS voice cannot pronounce.
                        contentDescription = {
                            it.accessibleName(uiLocale) ?: systemLangLabel
                        },
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
            title = stringResource(R.string.gen_sec_startup),
            sub = stringResource(R.string.gen_sec_startup_sub),
        ) {
            SettingsRow(
                label = stringResource(R.string.gen_open_on),
                hint = stringResource(R.string.gen_open_on_hint),
                content = {
                    SettingsChipRow(
                        options = OpenOn.values().toList(),
                        selected = state.openOn,
                        label = { stringResource(it.labelRes).uppercase() },
                        onSelect = { section -> scope.launch { prefs.setOpenOn(section) } },
                    )
                },
            )
        }

        SettingsSection(
            title = stringResource(R.string.gen_sec_channels),
            sub = stringResource(R.string.gen_sec_channels_sub),
        ) {
            SettingsToggleRow(
                label = stringResource(R.string.gen_show_channel_numbers),
                description = stringResource(R.string.gen_show_channel_numbers_desc),
                checked = state.showChannelNumbers,
                onCheckedChange = { scope.launch { prefs.setShowChannelNumbers(it) } },
            )
            if (state.showChannelNumbers) {
                SettingsRowSpacer()
                SettingsRow(
                    label = stringResource(R.string.gen_number_format),
                    hint = stringResource(R.string.gen_number_format_hint),
                    content = {
                        SettingsChipRow(
                            options = ChannelNumberFormat.values().toList(),
                            selected = state.channelNumberFormat,
                            label = { stringResource(it.labelRes) },
                            onSelect = { format -> scope.launch { prefs.setChannelNumberFormat(format) } },
                        )
                    },
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.gen_sec_categories),
            sub = stringResource(R.string.gen_sec_categories_sub),
        ) {
            SettingsToggleRow(
                label = stringResource(R.string.gen_smart_grouping),
                description = stringResource(R.string.gen_smart_grouping_desc),
                checked = state.smartGrouping,
                onCheckedChange = { scope.launch { prefs.setSmartGrouping(it) } },
            )
        }
    }
}
