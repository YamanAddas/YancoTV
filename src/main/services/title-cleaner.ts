/**
 * Title cleaner for IPTV content.
 *
 * IPTV providers add a lot of noise to titles: quality tags, country prefixes,
 * provider branding, numbering, etc. This module strips that noise to produce
 * a clean title suitable for display and metadata matching.
 */

/** Patterns to strip from titles, applied in order */
const STRIP_PATTERNS: RegExp[] = [
  // Bracketed tags FIRST (before quality tags strip inner content):
  // [HD], [MULTI], [4K], (HD), {HD}, etc.
  /\s*[\[({][^\])}\n]*[\])}]/g,

  // Quality/resolution tags: HD, FHD, UHD, 4K, SD, 720p, 1080p, etc.
  /\b(?:4K|8K|UHD|FHD|HD|SD|H\.?265|H\.?264|HEVC|HDR(?:10)?|MULTI)\b/gi,
  /\b(?:\d{3,4}[pi])\b/gi,

  // Country prefix patterns: "US:", "US |", "UK:" (2-3 letter codes with : or |)
  /^[A-Z]{2,3}\s*[:|]\s*/,
  // Country prefix with dash: only 2-letter codes (to avoid matching "CNN -")
  /^[A-Z]{2}\s+-\s+/,

  // Country/region with pipe: "| US", "| UK" at end
  /\s*\|\s*[A-Z]{2,3}$/,

  // Provider channel numbering: "001.", "CH 001:", etc.
  /^(?:CH\s*)?\d{1,4}[.:]\s*/i,

  // Trailing provider noise: "- Backup", "| S2", "| Server 3"
  /\s*[-|]\s*(?:backup|bk|s\d+|server\s*\d+)$/i,

  // Double/triple spaces from removals
  /\s{2,}/g,
];

/** Clean an IPTV title by removing provider noise */
export function cleanTitle(rawTitle: string): string {
  let title = rawTitle.trim();

  for (const pattern of STRIP_PATTERNS) {
    title = title.replace(pattern, ' ');
  }

  // Trim again after pattern removal
  title = title.trim();

  // If cleaning emptied the title, return the original
  if (!title) return rawTitle.trim();

  return title;
}

/** Extract year from a title like "The Matrix (1999)" or "Movie 2023" */
export function extractYear(title: string): number | null {
  // Match (YYYY) pattern first — most reliable
  const parenMatch = title.match(/\((\d{4})\)/);
  if (parenMatch) {
    const year = parseInt(parenMatch[1], 10);
    if (year >= 1900 && year <= new Date().getFullYear() + 2) return year;
  }

  // Match trailing year: "Movie Name 2023"
  const trailingMatch = title.match(/\b((?:19|20)\d{2})$/);
  if (trailingMatch) {
    const year = parseInt(trailingMatch[1], 10);
    if (year >= 1900 && year <= new Date().getFullYear() + 2) return year;
  }

  return null;
}

/** Extract season and episode numbers from a title */
export function extractSeasonEpisode(
  title: string,
): { season: number; episode: number } | null {
  // S01E02, S1E2, s01e02
  const seMatch = title.match(/S(\d{1,2})\s*E(\d{1,3})/i);
  if (seMatch) {
    return { season: parseInt(seMatch[1], 10), episode: parseInt(seMatch[2], 10) };
  }

  // Season 1 Episode 2
  const longMatch = title.match(/Season\s+(\d{1,2}).*?Episode\s+(\d{1,3})/i);
  if (longMatch) {
    return { season: parseInt(longMatch[1], 10), episode: parseInt(longMatch[2], 10) };
  }

  // E02 or Ep02 (episode only, assume season 1)
  const epOnly = title.match(/\bE(?:p(?:isode)?)?\s*(\d{1,3})\b/i);
  if (epOnly) {
    return { season: 1, episode: parseInt(epOnly[1], 10) };
  }

  return null;
}

/** Extract the show name from a series title (strip S01E02 and everything after) */
export function extractShowName(title: string): string {
  // Strip everything from SxxExx onwards
  let showName = title.replace(/\s*S\d{1,2}\s*E\d{1,3}.*/i, '');

  // Strip everything from "Season X" onwards
  showName = showName.replace(/\s*Season\s+\d+.*/i, '');

  // Clean the result
  showName = cleanTitle(showName);

  return showName || title;
}
