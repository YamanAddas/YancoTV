package com.yancotv.android.ui.settings

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
    /** Human-readable row label as rendered in the tab. */
    val label: String,
    /** Short hint that helps disambiguate; also matched by query. */
    val hint: String,
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
    // ───── General ─────
    SettingsSearchEntry(SettingsTab.General, "Open app on", "Startup destination — Home, Live TV, or Last used"),
    SettingsSearchEntry(SettingsTab.General, "Show channel numbers", "Channel position number before title"),
    SettingsSearchEntry(SettingsTab.General, "Number format", "Padding for channel numbers (1 vs 001)"),
    SettingsSearchEntry(SettingsTab.General, "Smart category grouping", "Bucket categories by language / region prefix"),

    // ───── Appearance ─────
    SettingsSearchEntry(SettingsTab.Appearance, "Theme", "Dark / light palette"),
    SettingsSearchEntry(SettingsTab.Appearance, "Accent colour", "Highlight tint across the app"),
    SettingsSearchEntry(SettingsTab.Appearance, "Font scale", "Text size multiplier"),

    // ───── Playback ─────
    SettingsSearchEntry(SettingsTab.Playback, "Resize mode", "How video fits the screen — fit / fill / zoom"),
    SettingsSearchEntry(SettingsTab.Playback, "Buffer profile", "Network buffering aggressiveness"),
    SettingsSearchEntry(SettingsTab.Playback, "Enable decoder fallback", "Drop to software decoder on hardware failure"),
    SettingsSearchEntry(SettingsTab.Playback, "Auto-play next episode", "Continue to the next episode automatically"),
    SettingsSearchEntry(SettingsTab.Playback, "Preferred audio language", "Default audio track when stream offers multiple"),
    SettingsSearchEntry(SettingsTab.Playback, "Preferred subtitle language", "Default subtitle track"),
    SettingsSearchEntry(SettingsTab.Playback, "Default player", "External player override (VLC, MX Player)"),

    // ───── Parental ─────
    SettingsSearchEntry(SettingsTab.Parental, "PIN", "Set or change the parental PIN"),
    SettingsSearchEntry(SettingsTab.Parental, "Hide adult-tagged content", "Filter adult-flagged channels / movies / series"),
    SettingsSearchEntry(SettingsTab.Parental, "Require PIN to open Settings", "Gate Settings behind the PIN"),
    SettingsSearchEntry(SettingsTab.Parental, "Hidden channels", "Manage hidden / unlocked channels"),
    SettingsSearchEntry(SettingsTab.Parental, "Lock channel", "Channel-level lock"),

    // ───── Recordings ─────
    SettingsSearchEntry(SettingsTab.Recordings, "Storage folder", "Where recordings are saved"),
    SettingsSearchEntry(SettingsTab.Recordings, "Public folder", "Save to the device's public Movies / Videos folder"),
    SettingsSearchEntry(SettingsTab.Recordings, "Custom folder", "Pick a specific folder for recordings"),
    SettingsSearchEntry(SettingsTab.Recordings, "Storage cap", "Maximum disk space for recordings"),

    // ───── Network ─────
    SettingsSearchEntry(SettingsTab.Network, "User-Agent", "HTTP User-Agent header — System / VLC / custom"),
    SettingsSearchEntry(SettingsTab.Network, "Connect timeout", "Maximum seconds to wait for TCP connect"),
    SettingsSearchEntry(SettingsTab.Network, "Read timeout", "Maximum seconds between bytes during playback"),
    SettingsSearchEntry(SettingsTab.Network, "Paired TV", "LAN handoff — send playback to another YancoTV"),
    SettingsSearchEntry(SettingsTab.Network, "Pairing code", "6-digit pairing code for the LAN handoff endpoint"),

    // ───── Groups ─────
    SettingsSearchEntry(SettingsTab.Groups, "Pinned categories", "Reorder and pin provider categories"),
    SettingsSearchEntry(SettingsTab.Groups, "Hide group", "Hide a provider category from rails"),

    // ───── EPG ─────
    SettingsSearchEntry(SettingsTab.Epg, "Override EPG URL", "Use a custom XMLTV URL instead of the provider's"),
    SettingsSearchEntry(SettingsTab.Epg, "Days back", "How many days of past EPG to keep"),
    SettingsSearchEntry(SettingsTab.Epg, "Days forward", "How many days of future EPG to keep"),
    SettingsSearchEntry(SettingsTab.Epg, "Visible window", "Hours shown in the guide grid"),
    SettingsSearchEntry(SettingsTab.Epg, "Source priority", "Order EPG sources contend for the same channel"),
    SettingsSearchEntry(SettingsTab.Epg, "Refresh EPG", "Re-fetch the EPG now"),

    // ───── Backup ─────
    SettingsSearchEntry(SettingsTab.Backup, "Export backup", "Save settings, sources, favorites, history to a file"),
    SettingsSearchEntry(SettingsTab.Backup, "Import backup", "Restore from a backup file"),
    SettingsSearchEntry(SettingsTab.Backup, "Encrypt with password", "AES-encrypt the backup with a password"),

    // ───── Shortcuts ─────
    SettingsSearchEntry(SettingsTab.Shortcuts, "Remote & keyboard", "D-pad and remote key reference"),

    // ───── About ─────
    SettingsSearchEntry(SettingsTab.About, "Check for updates automatically", "Background update check"),
    SettingsSearchEntry(SettingsTab.About, "Send crash reports", "Sentry crash reporting opt-in"),
    SettingsSearchEntry(SettingsTab.About, "Privacy policy", "Open the privacy policy"),
    SettingsSearchEntry(SettingsTab.About, "Terms of service", "Open the terms of service"),
    SettingsSearchEntry(SettingsTab.About, "Build", "App version and build number"),
    SettingsSearchEntry(SettingsTab.About, "Device", "Hardware diagnostic information"),

    // ───── Sources (only the actions — provider list itself isn't indexed) ─────
    SettingsSearchEntry(SettingsTab.Sources, "Add source", "Add a new IPTV provider (M3U / Xtream / Stalker)"),
)

/**
 * Filter the index against [query]. Case-insensitive substring match
 * against label, hint, and the tab name. Empty query returns empty —
 * the UI uses that signal to fall back to the normal tab list.
 */
internal fun searchSettings(query: String): List<SettingsSearchEntry> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val needle = q.lowercase()
    return SettingsSearchIndex.filter { entry ->
        entry.label.lowercase().contains(needle) ||
            entry.hint.lowercase().contains(needle) ||
            entry.tab.label.lowercase().contains(needle)
    }
}

/**
 * Return the [SettingsTab]s whose name itself matches [query]. Used to
 * filter the sidebar's tab list so a "play" search collapses the rail
 * down to Playback. Empty query → all tabs in their natural order.
 */
internal fun searchTabs(query: String): List<SettingsTab> {
    val q = query.trim()
    if (q.isEmpty()) return SettingsTab.entries
    val needle = q.lowercase()
    return SettingsTab.entries.filter { it.label.lowercase().contains(needle) }
}
