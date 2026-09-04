import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { translate, LOCALES, isLocaleCode } from '../../src/renderer/i18n';
import { en } from '../../src/renderer/i18n/locales/en';
import { ar } from '../../src/renderer/i18n/locales/ar';

describe('i18n', () => {
  describe('plural selection', () => {
    /**
     * The reason this layer exists at all.
     *
     * Arabic has six CLDR plural categories and `Intl.PluralRules` picks
     * between them by real rules: 0 → zero, 1 → one, 2 → two, 3–10 → few,
     * 11–99 → many, 100+ → other. A `count === 1 ? singular : plural` shortcut
     * is wrong for every number above two — the sibling Android app shipped
     * exactly that across its whole UI until MB-339.
     */
    it('picks the right Arabic form for each category', () => {
      const forms = [0, 1, 2, 3, 10, 11, 99, 100].map((n) =>
        translate('ar', 'count.channels', { count: n }),
      );

      expect(forms[0]).toBe('لا توجد قنوات'); // zero
      expect(forms[1]).toBe('قناة واحدة'); // one
      expect(forms[2]).toBe('قناتان'); // two
      expect(forms[3]).toBe('3 قنوات'); // few
      expect(forms[4]).toBe('10 قنوات'); // few (upper bound)
      expect(forms[5]).toBe('11 قناة'); // many (lower bound)
      expect(forms[6]).toBe('99 قناة'); // many (upper bound)
      expect(forms[7]).toBe('100 قناة'); // other
    });

    it('the Arabic forms for 1, 3 and 11 are genuinely different strings', () => {
      // Guards the guard: if the locale file collapsed these onto one value,
      // every assertion above would still pass while the output was wrong.
      const one = translate('ar', 'count.channels', { count: 1 });
      const few = translate('ar', 'count.channels', { count: 3 });
      const many = translate('ar', 'count.channels', { count: 11 });
      expect(new Set([one, few, many]).size).toBe(3);
    });

    it('uses one/other for English', () => {
      expect(translate('en', 'count.results', { count: 1 })).toBe('1 result');
      expect(translate('en', 'count.results', { count: 2 })).toBe('2 results');
      expect(translate('en', 'count.results', { count: 0 })).toBe('0 results');
    });

    it('prefers an explicit zero form over the count', () => {
      // English has no CLDR `zero` category, but "No channels are hidden" reads
      // better than "0 channels are hidden", so an explicit zero form wins.
      expect(translate('en', 'count.hiddenChannels', { count: 0 })).toBe(
        'No channels are hidden',
      );
      expect(translate('en', 'count.hiddenChannels', { count: 1 })).toBe(
        '1 channel is hidden',
      );
    });

    it('treats a missing count as zero rather than throwing', () => {
      expect(() => translate('en', 'count.results')).not.toThrow();
    });
  });

  describe('interpolation', () => {
    it('substitutes named placeholders', () => {
      expect(translate('en', 'parental.unlockTitle', { title: 'BBC One' })).toBe(
        'Unlock "BBC One"',
      );
    });

    it('leaves an unknown placeholder as written rather than printing undefined', () => {
      expect(translate('en', 'parental.unlockTitle', { wrong: 'x' })).toContain('{title}');
    });

    it('handles a value containing braces without recursing', () => {
      expect(translate('en', 'parental.unlockTitle', { title: '{title}' })).toBe(
        'Unlock "{title}"',
      );
    });
  });

  describe('fallback', () => {
    it('falls back to English per KEY, not per file', () => {
      // 'settings.startup' is translated; if a key were missing from ar it must
      // still render English rather than blank. Pick one that IS translated to
      // prove the Arabic path works...
      expect(translate('ar', 'nav.home')).toBe('الرئيسية');
      // ...and assert the fallback mechanism directly on a key ar omits.
      const missing = Object.keys(en).filter(
        (k) => !(k in ar),
      ) as (keyof typeof en)[];
      for (const key of missing) {
        const value = en[key];
        if (typeof value === 'string') {
          expect(translate('ar', key)).toBe(value);
        }
      }
    });

    it('never returns an empty string for any key in any locale', () => {
      for (const locale of Object.keys(LOCALES) as (keyof typeof LOCALES)[]) {
        for (const key of Object.keys(en) as (keyof typeof en)[]) {
          const out = translate(locale, key, { count: 1, title: 'x' });
          expect(out, `${locale}/${key}`).toBeTruthy();
        }
      }
    });
  });

  describe('locale registry', () => {
    it('marks Arabic as RTL and English as LTR', () => {
      expect(LOCALES.ar.dir).toBe('rtl');
      expect(LOCALES.en.dir).toBe('ltr');
    });

    it('recognises known codes and rejects others', () => {
      expect(isLocaleCode('ar')).toBe(true);
      expect(isLocaleCode('en')).toBe(true);
      expect(isLocaleCode('kl')).toBe(false);
      expect(isLocaleCode('')).toBe(false);
      // A stored setting could be anything; `toString` is on Object.prototype
      // and would pass a naive `in` check.
      expect(isLocaleCode('toString')).toBe(false);
    });
  });

  describe('translation files', () => {
    it('has no Arabic key that English does not define', () => {
      const orphans = Object.keys(ar).filter((k) => !(k in en));
      expect(orphans, `keys in ar with no English counterpart: ${orphans.join(', ')}`).toEqual(
        [],
      );
    });

    it('gives every Arabic plural entry all six CLDR categories', () => {
      // Arabic can use all six. A translated plural missing one falls back to
      // `other`, which is grammatically wrong rather than merely untranslated —
      // worth catching in the file rather than on screen.
      const required = ['zero', 'one', 'two', 'few', 'many', 'other'];
      for (const [key, value] of Object.entries(ar)) {
        if (typeof value !== 'object' || value === null) continue;
        for (const cat of required) {
          expect(cat in value, `ar/${key} is missing the '${cat}' form`).toBe(true);
        }
      }
    });
  });
});

/**
 * Locks in the surfaces that have been migrated so far.
 *
 * The desktop has roughly 386 user-visible strings across 46 files and they are
 * being converted incrementally. A test that demanded ALL of them be translated
 * would be red for as long as the migration takes, and a permanently red test
 * is one nobody reads. This asserts only that the files already converted STAY
 * converted — regression protection for finished work, and a list that grows as
 * the migration does.
 */
describe('migrated surfaces stay migrated', () => {
  const MIGRATED = [
    'src/renderer/components/Sidebar.tsx',
    'src/renderer/components/PinModal.tsx',
    'src/renderer/components/settings/GeneralSettings.tsx',
    'src/renderer/pages/SettingsPage.tsx',
    'src/renderer/pages/SearchPage.tsx',
    'src/renderer/pages/HomePage.tsx',
    'src/renderer/pages/LiveTvPage.tsx',
    'src/renderer/pages/MoviesPage.tsx',
    'src/renderer/pages/SeriesPage.tsx',
    'src/renderer/pages/FavoritesPage.tsx',
    'src/renderer/pages/RecordingsPage.tsx',
    'src/renderer/pages/DownloadsPage.tsx',
    'src/renderer/pages/ContentDetailPage.tsx',
    'src/renderer/pages/GuidePage.tsx',
    'src/renderer/components/settings/PlaylistSettings.tsx',
    'src/renderer/components/settings/ParentalSettings.tsx',
    'src/renderer/components/settings/AboutSettings.tsx',
    'src/renderer/components/settings/EpgSettings.tsx',
    'src/renderer/components/settings/SubtitlesSettings.tsx',
    'src/renderer/components/settings/PlaybackSettings.tsx',
    'src/renderer/components/settings/DownloadsSettings.tsx',
    'src/renderer/components/settings/MetadataSettings.tsx',
    'src/renderer/components/settings/NetworkSettings.tsx',
    'src/renderer/components/settings/AdvancedSettings.tsx',
    'src/renderer/components/player/settings-tabs/SubtitlesTab.tsx',
    'src/renderer/components/player/SettingsPanel.tsx',
    'src/renderer/components/settings/RecordingSettings.tsx',
    'src/renderer/components/AddSourceForm.tsx',
    'src/renderer/components/player/TheaterControls.tsx',
    'src/renderer/components/SortDropdown.tsx',
    'src/renderer/components/settings/ShortcutsSettings.tsx',
    'src/renderer/components/GroupContextMenu.tsx',
    'src/renderer/components/player/MiniPlayer.tsx',
    'src/renderer/components/SourceList.tsx',
    'src/renderer/components/CategorySidebar.tsx',
    'src/renderer/components/ContentGrid.tsx',
    'src/renderer/components/EpisodesTab.tsx',
    'src/renderer/components/player/AspectMenu.tsx',
    'src/renderer/components/DetailHero.tsx',
    'src/renderer/components/InfoTab.tsx',
    'src/renderer/components/player/PlayerContainer.tsx',
    'src/renderer/components/SourceSwitcher.tsx',
  ];

  // Deliberately narrow: JSX text nodes of two or more capitalised words, which
  // is what a hardcoded sentence looks like. Broad enough to catch a regression,
  // tight enough not to fire on class names, icon paths or single-word markup.
  const HARDCODED = />\s*([A-Z][a-z]+(?: [A-Za-z]+){1,8})\s*</g;

  it.each(MIGRATED)('%s has no hardcoded UI sentences', (rel) => {
    const body = readFileSync(join(process.cwd(), rel), 'utf-8');
    const hits = [...body.matchAll(HARDCODED)].map((m) => m[1].trim());
    expect(hits, `hardcoded strings in ${rel}: ${hits.join(' | ')}`).toEqual([]);
  });

  it('each migrated file actually uses the translator', () => {
    // Guard the guard: a file with no JSX text at all would pass the check
    // above while being entirely untranslated.
    for (const rel of MIGRATED) {
      const body = readFileSync(join(process.cwd(), rel), 'utf-8');
      expect(body, `${rel} does not import useT`).toContain('useT');
    }
  });
});
