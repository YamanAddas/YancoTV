#!/usr/bin/env node
/**
 * Lists user-visible strings in the renderer that are still hardcoded.
 *
 * The desktop had no i18n at all until MB-408; roughly 386 strings across 46
 * files are being converted incrementally. This is the progress tracker and the
 * worklist — run it to see what is left, and per file with `--file <path>` to
 * get the exact strings to lift into a locale key.
 *
 * Deliberately OVER-reports. A scan that misses a string leaves an untranslated
 * label in the UI with nothing to point at it; a scan that reports a false
 * positive costs a glance. Anything matched here still needs a human decision —
 * a `viewBox`, a CSS class or a provider's own data must not be translated.
 *
 * Usage:
 *   node scripts/i18n-scan.js              summary, worst files first
 *   node scripts/i18n-scan.js --file X     every candidate string in one file
 *   node scripts/i18n-scan.js --list       every file with a count
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const RENDERER = path.join(ROOT, 'src', 'renderer');

// Files already converted. Keep in step with the MIGRATED list in
// tests/unit/i18n.test.ts, which is what actually enforces they stay converted.
const MIGRATED = new Set(
  [
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
  ].map((p) => p.split('/').join(path.sep)),
);

/** JSX text between tags, e.g. `>Save changes<`. */
const JSX_TEXT = /> *([A-Z][A-Za-z0-9 ,.'\-?!()/&]{3,80}?) *</g;
/** String-valued props that reach the user. */
const PROPS =
  /(?:label|title|placeholder|description|aria-label|alt|message|hint|tooltip|emptyText|subtitle|heading)= *\{? *['"]([A-Z][^'"]{3,120})['"]/g;
/**
 * Object-literal labels, e.g. `{ value: 'dark', label: 'Dark' }` in a <Select>'s
 * options array.
 *
 * Added after the first pass missed eleven of them in GeneralSettings alone:
 * the length filter below discards a bare capitalised word as probable markup,
 * which is right for JSX text but wrong here — `label:` is only ever a string
 * shown to a person, however short. Matches are exempted from that filter.
 */
const OBJECT_LABEL = /(?:^|[^A-Za-z])label: *['"]([A-Z][^'"]{1,80})['"]/g;

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (entry.name.endsWith('.tsx')) out.push(full);
  }
  return out;
}

function candidates(file) {
  const body = fs.readFileSync(file, 'utf-8');
  const found = new Set();
  for (const re of [JSX_TEXT, PROPS]) {
    re.lastIndex = 0;
    let m;
    while ((m = re.exec(body)) !== null) {
      const text = m[1].trim();
      // A bare capitalised word is usually markup or an identifier, not a
      // sentence. Two words, or one long word, is the useful signal.
      if (!/\s/.test(text) && text.length < 8) continue;
      found.add(text);
    }
  }

  // No length filter: `label:` is always shown to a person.
  OBJECT_LABEL.lastIndex = 0;
  let lm;
  while ((lm = OBJECT_LABEL.exec(body)) !== null) found.add(lm[1].trim());
  return [...found];
}

const args = process.argv.slice(2);
const fileArg = args.indexOf('--file');

if (fileArg !== -1 && args[fileArg + 1]) {
  const target = path.resolve(ROOT, args[fileArg + 1]);
  const list = candidates(target);
  console.log(`${list.length} candidate strings in ${path.relative(ROOT, target)}:`);
  for (const s of list) console.log('  ' + s);
  process.exit(0);
}

const rows = [];
let total = 0;
let migratedFiles = 0;

for (const file of walk(RENDERER)) {
  const rel = path.relative(ROOT, file);
  if (MIGRATED.has(rel)) {
    migratedFiles++;
    continue;
  }
  const n = candidates(file).length;
  if (n > 0) {
    rows.push([rel, n]);
    total += n;
  }
}

rows.sort((a, b) => b[1] - a[1]);

if (args.includes('--list')) {
  for (const [rel, n] of rows) console.log(`${String(n).padStart(4)}  ${rel}`);
} else {
  console.log(`${total} candidate strings remain across ${rows.length} files`);
  console.log(`${migratedFiles} files already migrated`);
  console.log('');
  for (const [rel, n] of rows.slice(0, 15)) {
    console.log(`  ${String(n).padStart(4)}  ${rel}`);
  }
  if (rows.length > 15) console.log(`  ... and ${rows.length - 15} more (--list for all)`);
}
