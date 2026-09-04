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
