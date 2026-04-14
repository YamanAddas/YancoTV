/**
 * Content classifier for IPTV entries.
 *
 * Classifies M3U entries into live/movie/series using multiple heuristics:
 * - Group/category name patterns
 * - Title patterns (S01E02, year in parens, etc.)
 * - URL patterns (.mp4, .mkv, /live/, /movie/, /series/)
 * - Duration (live = -1, VOD = positive)
 */

import type { ContentType } from '../../shared/types';
import type { M3uEntry } from './m3u-parser';

/** Classify an M3U entry into live, movie, or series */
export function classifyEntry(entry: M3uEntry): ContentType {
  const group = entry.groupTitle.toLowerCase();
  const title = entry.title;
  const url = entry.streamUrl.toLowerCase();
  const duration = entry.duration;

  // --- Series indicators (check first — more specific) ---

  // Title has S01E02 / Season X Episode Y pattern
  if (/S\d{1,2}\s*E\d{1,3}/i.test(title) || /Season\s+\d/i.test(title)) {
    return 'series';
  }

  // Group explicitly says series/episode
  if (
    group.includes('series') ||
    group.includes('episode') ||
    group.includes('tv show')
  ) {
    return 'series';
  }

  // URL has /series/ path
  if (url.includes('/series/')) {
    return 'series';
  }

  // --- Movie/VOD indicators ---

  // Group explicitly says movie/vod/film
  if (
    group.includes('movie') ||
    group.includes('vod') ||
    group.includes('film') ||
    group.includes('cinema')
  ) {
    return 'movie';
  }

  // URL has /movie/ path
  if (url.includes('/movie/')) {
    return 'movie';
  }

  // Video file extensions with positive duration (VOD content)
  if (
    url.endsWith('.mp4') ||
    url.endsWith('.mkv') ||
    url.endsWith('.avi') ||
    url.endsWith('.mov')
  ) {
    // Check if title has series pattern — some series come as video files
    if (/S\d{1,2}\s*E\d{1,3}/i.test(title)) return 'series';
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
