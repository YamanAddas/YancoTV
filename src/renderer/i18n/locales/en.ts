/**
 * English strings — the source of truth for the desktop app.
 *
 * This object's TYPE defines the key set. Every other locale is a
 * `Partial<Strings>`, so a missing translation is a compile-time-safe fallback
 * to English rather than a blank label, and a key that does not exist here is a
 * compile error at the call site.
 *
 * A value is either a plain string or a plural form. Plural forms are keyed by
 * the categories `Intl.PluralRules` returns for the active locale — see
 * `../index.tsx` for why that matters and why a hand-rolled `n === 1 ? a : b`
 * is not good enough here.
 *
 * Placeholders are `{name}` and are replaced positionally by key.
 */

export interface PluralForms {
  zero?: string;
  one?: string;
  two?: string;
  few?: string;
  many?: string;
  other: string;
}

export const en = {
  // ── navigation / shell ────────────────────────────────────────────────
  'nav.home': 'Home',
  'nav.liveTv': 'Live TV',
  'nav.guide': 'TV Guide',
  'nav.movies': 'Movies',
  'nav.series': 'Series',
  'nav.favorites': 'Favorites',
  'nav.recordings': 'Recordings',
  'nav.downloads': 'Downloads',
  'nav.settings': 'Settings',
  'nav.search': 'Search',
  'nav.searchPlaceholder': 'Search channels, movies, series…',

  // ── common actions ────────────────────────────────────────────────────
  'action.play': 'Play',
  'action.cancel': 'Cancel',
  'action.save': 'Save',
  'action.close': 'Close',
  'action.remove': 'Remove',
  'action.retry': 'Retry',
  'action.refresh': 'Refresh',
  'action.back': 'Back',
  'action.unlock': 'Unlock',

  // ── parental ──────────────────────────────────────────────────────────
  'parental.enterPin': 'Enter PIN',
  'parental.unlockTitle': 'Unlock "{title}"',
  'parental.pinTooShort': 'PIN must be at least 4 digits',
  'parental.pinIncorrect': 'Incorrect PIN',
  'parental.verifyFailed': 'Verification failed',
  'parental.enterToContinue': 'Enter your PIN to continue',
  'parental.checking': 'Checking…',
  'parental.verify': 'Verify',

  // ── settings: general ─────────────────────────────────────────────────
  'settings.title': 'Settings',
  'settings.general': 'General',
  'settings.language': 'Language',
  'settings.languageDesc': 'Interface language. Takes effect immediately.',
  'settings.startPage': 'Start page',
  'settings.startPageDesc': 'Page shown when the app launches',
  'settings.theme': 'Theme',
  'settings.startup': 'Startup',
  'settings.pinRequired': 'Settings are PIN-protected',
  'settings.themeDesc': 'Application color theme',
  'settings.listStyle': 'Default list style',
  'settings.listStyleDesc': 'How channels and content are displayed',
  'settings.channelLogos': 'Show channel logos',
  'settings.channelLogosDesc': 'Display logos next to channel names',
  'settings.rememberChannel': 'Remember last channel',
  'settings.rememberChannelDesc': 'Resume the last watched channel on startup',
  'settings.confirmExit': 'Confirm on exit',
  'settings.confirmExitDesc': 'Show a confirmation dialog when closing the app',
  'settings.minimizeToTray': 'Minimize to tray',
  'settings.minimizeToTrayDesc': 'Minimize button hides the window to the system tray',
  'settings.closeToTray': 'Close to tray',
  'settings.closeToTrayDesc': 'Closing the window keeps the app running in the tray. Quit from the tray menu to fully exit.',
  'settings.launchOnStartup': 'Launch on startup',
  'settings.launchOnStartupDesc': 'Start YancoTV automatically when you sign in to Windows',
  'settings.showClock': 'Show clock in sidebar',
  'settings.showClockDesc': 'Display the current time in the navigation sidebar',
  'settings.autoTune': 'Auto-tune on reminder',
  'settings.autoTuneDesc': 'When a programme reminder fires, switch to that channel automatically',
  'theme.dark': 'Dark',
  'theme.oled': 'OLED Black',
  'theme.light': 'Light',
  'listStyle.grid': 'Grid',
  'listStyle.list': 'List',
  'listStyle.compact': 'Compact List',
  'settingsTab.playlists': 'Playlists',
  'settingsTab.epg': 'EPG',
  'settingsTab.playback': 'Playback',
  'settingsTab.subtitles': 'Subtitles',
  'settingsTab.recording': 'Recording',
  'settingsTab.parental': 'Parental Controls',
  'settingsTab.network': 'Network',
  'settingsTab.metadata': 'Metadata',
  'settingsTab.advanced': 'Advanced',
  'settingsTab.shortcuts': 'Keyboard Shortcuts',
  'settingsTab.about': 'About',
  'search.prompt': 'Type to search your content library',
  'search.filterByType': 'Filter by content type',
  'search.filterAll': 'All',
  'search.filterLive': 'Live',
  'empty.noContent': 'No content yet',
  'empty.noLiveChannels': 'No live channels',
  'empty.noMovies': 'No movies',
  'empty.noSeries': 'No series',
  'empty.noFavorites': 'No favorites yet',
  'empty.nothingWatched': 'Nothing watched yet. Start browsing!',
  'home.recentlyWatched': 'Recently Watched',
  'empty.noMoviesHint': 'Add an IPTV source in Settings to see movies.',
  'empty.noSeriesHint': 'Add an IPTV source in Settings to see series.',
  'empty.noLiveHint': 'Add an IPTV source in Settings to see live channels.',
  'empty.noFavoritesHint': 'Browse your content and tap the heart to save favorites here.',
  'empty.noRecordings': 'No recordings yet',
  'empty.noRecordingsHint': 'Right-click a live channel and choose Record to start recording.',
  'empty.noDownloads': 'No downloads yet',
  'empty.noDownloadsHint': 'Open a movie or episode and click Download to add it here.',
  'media.duration': 'Duration',
  'media.playRecording': 'Play recording',
  'media.playDownload': 'Play download',
  'media.removeKeepFile': 'Remove from list (keep file)',
  'media.deleteFile': 'Delete file and remove from list',
  'detail.notFound': 'Content not found',
  'detail.episodes': 'Episodes',
  'detail.noEpisodes': 'No episodes available',
  'detail.view': 'View',
  'guide.currentlyAiring': 'Currently airing',
  'guide.noData': 'No EPG Data',
  'action.view': 'View',
  'settings.generalDesc': 'App appearance and startup behaviour',
  'settings.desktopIntegration': 'Desktop integration',
  'action.goBack': 'Go back',
  'action.openFolder': 'Open Folder',
  'action.watchLive': 'Watch Live',
  'action.remindMe': 'Remind me when it starts',
  'action.goToSettings': 'Go to Settings',
  'action.seeAll': 'See all',
  'action.clearAll': 'Clear all',
  'home.recentlyWatchedLower': 'Recently watched',
  'search.recentSearches': 'Recent searches',
  'interval.manual': 'Manual only',
  'interval.6h': 'Every 6 hours',
  'interval.12h': 'Every 12 hours',
  'interval.24h': 'Every 24 hours',
  'interval.2d': 'Every 2 days',
  'parental.reEnter': 'Re-enter',
  'parental.currentPin': 'Current PIN',
  'parental.hideAdult': 'Hide adult content',
  'parental.hideAdultDesc': 'Filter out channels and VOD tagged as adult/18+',
  'parental.requirePin': 'Require PIN for settings',
  'parental.requirePinDesc': 'Ask for PIN before opening the Settings page',
  'stats.liveChannels': 'Live Channels',
  'stats.epgProgrammes': 'EPG Programmes',
  'stats.epgChannels': 'EPG Channels',
  'stats.programmes': 'Programmes',
  'stats.channels': 'Channels',
  'stats.lastRefresh': 'Last Refresh',
  'about.platform': 'Platform',
  'about.userAgent': 'User Agent',
  'about.desc': 'Application information and diagnostics',
  'about.tagline': 'Custom IPTV media application',
  'about.builtWith': 'Built With',
  'epg.desc': 'Electronic Programme Guide settings and data management',
  'epg.refreshDesc': 'How often EPG data is automatically updated',
  'parental.desc': 'Restrict access to content and app settings',
  'parental.removePin': 'Remove PIN',
  'parental.removePinDesc': 'Enter current PIN to remove it',
  'parental.confirmRemove': 'Confirm Remove',
  'parental.contentRestrictions': 'Content Restrictions',
  'parental.channelManagement': 'Channel Management',
  'playlists.desc': 'Manage your IPTV sources and sync settings',
  'playlists.syncOptions': 'Sync Options',
  'playlists.syncOnStartDesc': 'Automatically refresh all sources when the app starts',
  'playlists.syncIntervalDesc': 'How often to automatically refresh playlist data',

  // ── empty / error states ──────────────────────────────────────────────
  'state.loading': 'Loading…',
  'state.noResults': 'No results',
  'state.noSources': 'No sources yet',
  'state.error': 'Something went wrong',

  // ── counted things (plural forms) ─────────────────────────────────────
  //
  // English needs only `one` and `other`. Arabic needs six, and the whole
  // reason these are objects rather than `${n} channels` is that the correct
  // set is a property of the LOCALE, not of this file.
  'count.channels': {
    one: '{count} channel',
    other: '{count} channels',
  } as PluralForms,
  'count.results': {
    one: '{count} result',
    other: '{count} results',
  } as PluralForms,
  'count.recordings': {
    one: '{count} recording',
    other: '{count} recordings',
  } as PluralForms,
  'count.hiddenChannels': {
    zero: 'No channels are hidden',
    one: '{count} channel is hidden',
    other: '{count} channels are hidden',
  } as PluralForms,
} as const;

/** The key set every locale is checked against. */
export type StringKey = keyof typeof en;

/**
 * The contract a translation file conforms to.
 *
 * Values are WIDENED here. `en` is `as const`, so each of its values is a
 * literal type — without this, a translation could only ever be assigned the
 * exact English string it replaces, which is the opposite of the point. The
 * plural/plain distinction is preserved so a counted key cannot be translated
 * as a bare string and silently lose its plural forms.
 */
export type Strings = {
  [K in StringKey]: (typeof en)[K] extends PluralForms ? PluralForms : string;
};
