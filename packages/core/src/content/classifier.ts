/**
 * Content classifier for IPTV entries.
 *
 * Classifies M3U entries into live/movie/series using multiple heuristics:
 * - Group/category name patterns
 * - Title patterns (S01E02, year in parens, etc.)
 * - URL patterns (.mp4, .mkv, /live/, /movie/, /series/)
 * - Duration (live = -1, VOD = positive)
 */

import type { ContentType } from '../types/index.js';
import type { M3uEntry } from '../parsers/index.js';

// Series group markers — kept broad so non-English providers land correctly.
// Matches: "Series", "TV Shows", "Episodes", "Sezon", "Dizi" (TR), "Serial"
// (many Slavic locales), "Sorozat" (HU), and multi-season pack labels.
const SERIES_GROUP_PATTERNS = [
  'series',
  'serie',
  'episode',
  'tv show',
  'tvshow',
  'season',
  'sezon',
  'dizi',
  'serial',
  'sorozat',
  'show',
];

// Movie/VOD group markers — covers English + common non-English tags and the
// generic "VOD" bucket every Xtream provider ships.
const MOVIE_GROUP_PATTERNS = [
  'movie',
  'vod',
  'film',
  'cinema',
  'peliculas', // es
  'pelicula',
  'filme', // pt/de
  'kino', // ru/de
  'filmy', // pl
  'on demand',
  'ondemand',
];

function matchesAny(group: string, patterns: string[]): boolean {
  for (const p of patterns) {
    if (group.includes(p)) return true;
  }
  return false;
}

/** Classify an M3U entry into live, movie, or series */
export function classifyEntry(entry: M3uEntry): ContentType {
  const group = entry.groupTitle.toLowerCase();
  const title = entry.title;
  const url = entry.streamUrl.toLowerCase();
  const duration = entry.duration;

  // --- Series indicators (check first — more specific) ---

  // MB-387 — a series title needs an EPISODE marker (SxxExx / "Season N Episode
  // M" / NxMM), not a bare "Season N" which movies carry ("Open Season 2").
  if (/(S\d{1,2}\s*E\d{1,3})|(Season\s+\d+\s*[:\-]?\s*Episode\s+\d+)|(\d{1,2}x\d{1,3})/i.test(title)) {
    return 'series';
  }

  if (matchesAny(group, SERIES_GROUP_PATTERNS)) {
    return 'series';
  }

  // URL has /series/ path (Xtream-style m3u_plus exports)
  if (url.includes('/series/')) {
    return 'series';
  }

  // --- Movie/VOD indicators ---

  if (matchesAny(group, MOVIE_GROUP_PATTERNS)) {
    return 'movie';
  }

  // URL has /movie/ path
  if (url.includes('/movie/')) {
    return 'movie';
  }

  // Video file extensions with positive duration (VOD content). Use a
  // regex so query-strings and fragments don't block the match.
  if (/\.(mp4|mkv|avi|mov|m4v|webm|flv|wmv)(\?|#|$)/i.test(url)) {
    // Some series come as video files — S01E02 wins.
    if (/(S\d{1,2}\s*E\d{1,3})|(Season\s+\d+\s*[:\-]?\s*Episode\s+\d+)|(\d{1,2}x\d{1,3})/i.test(title)) return 'series';
    return 'movie';
  }

  // Positive duration usually means VOD
  if (duration > 0) {
    return 'movie';
  }

  // --- Live is the default ---
  return 'live';
}

// --- Category normalization ---

/** Common category normalization mappings */
const CATEGORY_NORMALIZATIONS: [RegExp, string][] = [
  // Normalize country prefixes: "US | News" → "News", "UK: Sports" → "Sports"
  [/^[A-Z]{2,3}\s*[:|]\s*/, ''],
  [/^[A-Z]{2,3}\s*[-–]\s*/, ''],

  // Normalize pipe-separated country suffixes: "News | US" → "News"
  [/\s*\|\s*[A-Z]{2,3}$/, ''],

  // Normalize common variations
  [/\bSport(?:s)?\b/gi, 'Sports'],
  [/\bEntertainm?ent\b/gi, 'Entertainment'],
  [/\bDocument(?:ary|aries)\b/gi, 'Documentary'],
  [/\bChild(?:ren'?s?|s)\b/gi, 'Kids'],
  [/\bKid(?:'?s)?\b/gi, 'Kids'],
  [/\bMusic(?:al)?\b/gi, 'Music'],
  [/\bReligio(?:us|n)\b/gi, 'Religious'],
  [/\bEduc(?:ation(?:al)?)\b/gi, 'Education'],
  [/\bCook(?:ing)?\b/gi, 'Cooking'],
  [/\bTravel\b/gi, 'Travel'],
  [/\bComedy\b/gi, 'Comedy'],
  [/\bDrama\b/gi, 'Drama'],
  [/\bAction\b/gi, 'Action'],
  [/\bHorror\b/gi, 'Horror'],
  [/\bSci[- ]?Fi\b/gi, 'Sci-Fi'],
  [/\bThriller\b/gi, 'Thriller'],
  [/\bRomance\b/gi, 'Romance'],
  [/\bWestern\b/gi, 'Western'],
  [/\bAnimat(?:ion|ed)\b/gi, 'Animation'],
  [/\bAnime\b/gi, 'Anime'],
];

/** Normalize a category/group name for consistency */
export function normalizeCategory(category: string): string {
  if (!category) return '';

  let normalized = category.trim();

  // Apply normalizations
  for (const [pattern, replacement] of CATEGORY_NORMALIZATIONS) {
    normalized = normalized.replace(pattern, replacement);
  }

  // Clean up whitespace
  normalized = normalized.replace(/\s{2,}/g, ' ').trim();

  // Title case (handle hyphenated words like Sci-Fi)
  normalized = normalized
    .split(' ')
    .map((word) => {
      if (word.length <= 2 && word === word.toUpperCase()) return word; // Keep short acronyms
      // Handle hyphenated words
      if (word.includes('-')) {
        return word
          .split('-')
          .map((part) => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase())
          .join('-');
      }
      return word.charAt(0).toUpperCase() + word.slice(1).toLowerCase();
    })
    .join(' ');

  return normalized || category.trim();
}
