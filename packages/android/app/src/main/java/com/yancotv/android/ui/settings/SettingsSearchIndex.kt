package com.yancotv.android.ui.settings

import androidx.annotation.StringRes
import com.yancotv.android.R

/**
 * MK.30 — Static search index for Settings. Lists the user-searchable
 * surfaces of every tab so the sidebar's search field can answer
 * "where do I find X?" in one step.
 *
 * Why static: building a live index from composition would require every
 * tab to register its widgets at compose-time. With 12 tabs and ~50
 * settings the cost-of-index-staleness is one line per new setting,
 * which is cheaper than threading a registry through every tab's
 * `SettingsRow`. Keep this file in sync with the tab bodies — if you
 * add a new toggle / row / chip in a tab body, append an entry here.
 *
 * Matching is case-insensitive substring against label + hint + the
 * tab's own name. Sources tab content (provider list) is not indexed —
 * the search box is for *settings*, not for user-supplied data.
 */

/** A single searchable settings surface. */
internal data class SettingsSearchEntry(
    val tab: SettingsTab,
    /**
     * Row label as rendered in the tab. MK.31.8 — a resource id, and
     * deliberately the SAME id the tab body uses, so renaming a setting
     * keeps its search entry in step instead of silently drifting.
     */
    @StringRes val labelRes: Int,
    /** Short hint that helps disambiguate; also matched by query. */
    @StringRes val hintRes: Int,
)

/**
 * Hand-curated index of the most searchable settings. The order doesn't
 * matter — results are filtered by query then grouped by tab in the UI.
 *
 * Coverage tiers:
 *  - Every TOGGLE row → indexed (these are the most frequently
 *    searched-for kind: "turn off X").
 *  - Every named picker (Open app on, Number format, Resize mode, etc.)
 *    → indexed.
 *  - Diagnostics / read-only rows → SKIPPED (a user searching for
 *    "build" wants to *find* it via the About tab; we don't need to
 *    index the static value).
 */
internal val SettingsSearchIndex: List<SettingsSearchEntry> = listOf(
    SettingsSearchEntry(SettingsTab.General, R.string.gen_open_on, R.string.sih_open_on),
    SettingsSearchEntry(SettingsTab.General, R.string.gen_show_channel_numbers, R.string.sih_channel_numbers),
    SettingsSearchEntry(SettingsTab.General, R.string.gen_number_format, R.string.sih_number_format),
    SettingsSearchEntry(SettingsTab.General, R.string.gen_smart_grouping, R.string.sih_smart_grouping),
    SettingsSearchEntry(SettingsTab.Appearance, R.string.app_sec_theme, R.string.sih_theme),
    SettingsSearchEntry(SettingsTab.Appearance, R.string.app_accent_colour, R.string.sih_accent),
    SettingsSearchEntry(SettingsTab.Appearance, R.string.app_font_scale, R.string.sih_font_scale),
    SettingsSearchEntry(SettingsTab.Playback, R.string.pb_resize_mode, R.string.sih_resize),
    SettingsSearchEntry(SettingsTab.Playback, R.string.pb_buffer_profile, R.string.sih_buffer),
    SettingsSearchEntry(SettingsTab.Playback, R.string.pb_decoder_fallback, R.string.sih_decoder_fallback),
    SettingsSearchEntry(SettingsTab.Playback, R.string.pb_autoplay_next, R.string.sih_autoplay),
    SettingsSearchEntry(SettingsTab.Playback, R.string.pb_audio_language, R.string.sih_audio_lang),
    SettingsSearchEntry(SettingsTab.Playback, R.string.pb_subtitle_language, R.string.sih_subtitle_lang),
    SettingsSearchEntry(SettingsTab.Playback, R.string.pb_sec_default_player, R.string.sih_default_player),
    SettingsSearchEntry(SettingsTab.Parental, R.string.si_pin, R.string.sih_pin),
    SettingsSearchEntry(SettingsTab.Parental, R.string.par_hide_adult, R.string.sih_hide_adult),
    SettingsSearchEntry(SettingsTab.Parental, R.string.par_require_pin, R.string.sih_require_pin),
    SettingsSearchEntry(SettingsTab.Parental, R.string.par_hidden_channels, R.string.sih_hidden_channels),
    SettingsSearchEntry(SettingsTab.Parental, R.string.si_lock_channel, R.string.sih_lock_channel),
    SettingsSearchEntry(SettingsTab.Recordings, R.string.si_storage_folder, R.string.sih_storage_folder),
    SettingsSearchEntry(SettingsTab.Recordings, R.string.rec_public_folder, R.string.sih_public_folder),
    SettingsSearchEntry(SettingsTab.Recordings, R.string.rec_custom_folder, R.string.sih_custom_folder),
    SettingsSearchEntry(SettingsTab.Recordings, R.string.si_storage_cap, R.string.sih_storage_cap),
    SettingsSearchEntry(SettingsTab.Network, R.string.net_sec_ua, R.string.sih_ua),
    SettingsSearchEntry(SettingsTab.Network, R.string.net_connect_timeout, R.string.sih_connect_timeout),
    SettingsSearchEntry(SettingsTab.Network, R.string.net_read_timeout, R.string.sih_read_timeout),
    SettingsSearchEntry(SettingsTab.Network, R.string.si_paired_tv, R.string.sih_paired_tv),
    SettingsSearchEntry(SettingsTab.Network, R.string.net_pairing_code, R.string.sih_pairing_code),
    SettingsSearchEntry(SettingsTab.Groups, R.string.grp_sec_pinned, R.string.sih_pinned),
    SettingsSearchEntry(SettingsTab.Groups, R.string.si_hide_group, R.string.sih_hide_group),
    SettingsSearchEntry(SettingsTab.Epg, R.string.epg_sec_override, R.string.sih_override_epg),
    SettingsSearchEntry(SettingsTab.Epg, R.string.epg_days_back, R.string.sih_days_back),
    SettingsSearchEntry(SettingsTab.Epg, R.string.epg_days_forward, R.string.sih_days_forward),
    SettingsSearchEntry(SettingsTab.Epg, R.string.epg_visible_window, R.string.sih_visible_window),
    SettingsSearchEntry(SettingsTab.Epg, R.string.epg_sec_priority, R.string.sih_source_priority),
    SettingsSearchEntry(SettingsTab.Epg, R.string.si_refresh_epg, R.string.sih_refresh_epg),
    SettingsSearchEntry(SettingsTab.Backup, R.string.si_export_backup, R.string.sih_export_backup),
    SettingsSearchEntry(SettingsTab.Backup, R.string.si_import_backup, R.string.sih_import_backup),
    SettingsSearchEntry(SettingsTab.Backup, R.string.bk_encrypt, R.string.sih_encrypt),
    SettingsSearchEntry(SettingsTab.Shortcuts, R.string.sc_sec_remote, R.string.sih_shortcuts),
    SettingsSearchEntry(SettingsTab.About, R.string.ab_auto_check, R.string.sih_auto_check),
    SettingsSearchEntry(SettingsTab.About, R.string.ab_crash_reports, R.string.sih_crash_reports),
    SettingsSearchEntry(SettingsTab.About, R.string.ab_privacy_policy, R.string.sih_privacy_policy),
    SettingsSearchEntry(SettingsTab.About, R.string.ab_terms, R.string.sih_terms),
    SettingsSearchEntry(SettingsTab.About, R.string.ab_build, R.string.sih_build),
    SettingsSearchEntry(SettingsTab.About, R.string.ab_device, R.string.sih_device),
    SettingsSearchEntry(SettingsTab.Sources, R.string.add_title, R.string.sih_add_source),
)

/**
 * Filter the index against [query]. Case-insensitive substring match
 * against label, hint, and the tab name. Empty query returns empty —
 * the UI uses that signal to fall back to the normal tab list.
 */
internal fun searchSettings(query: String, resolve: (Int) -> String): List<SettingsSearchEntry> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val needle = q.lowercase()
    return SettingsSearchIndex.filter { entry ->
        resolve(entry.labelRes).lowercase().contains(needle) ||
            resolve(entry.hintRes).lowercase().contains(needle) ||
            resolve(entry.tab.labelRes).lowercase().contains(needle)
    }
}

/**
 * Return the [SettingsTab]s whose name itself matches [query]. Used to
 * filter the sidebar's tab list so a "play" search collapses the rail
 * down to Playback. Empty query → all tabs in their natural order.
 */
internal fun searchTabs(query: String, resolve: (Int) -> String): List<SettingsTab> {
    val q = query.trim()
    if (q.isEmpty()) return SettingsTab.entries
    val needle = q.lowercase()
    return SettingsTab.entries.filter { resolve(it.labelRes).lowercase().contains(needle) }
}
