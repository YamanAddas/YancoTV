/**
 * Best-effort parsing of a noisy IPTV title into a clean
 * search query for subtitle providers.
 *
 * Mirrors the heuristics in src/main/services/title-cleaner.ts but runs in the
 * renderer so the UI can show an instant cleaned preview without a round-trip.
 */

export interface GuessedTitle {
  /** Cleaned movie/show name, with no year/episode/release noise. */
  title: string;
  /** Release year if detected (1900–current+2). */
  year?: number;
  /** Season number if the title looks like a series episode. */
  season?: number;
  /** Episode number if the title looks like a series episode. */
  episode?: number;
  /** True if the cleaned title differs meaningfully from the raw input. */
  changed: boolean;
  /** Original untouched input. */
  raw: string;
}

const RELEASE_NOISE = [
  /\b(?:4K|8K|UHD|FHD|HD|SD)\b/gi,
  /\b(?:H\.?265|H\.?264|HEVC|AVC|AAC|AC3|DTS|DDP?5\.1|DD5\.1|FLAC|OPUS|TrueHD|Atmos)\b/gi,
  /\b\d{3,4}[pi]\b/gi,
  /\b(?:BluRay|BDRip|BRRip|WEB-?DL|WEB-?Rip|HDRip|DVDRip|HDTV|PDTV|CAM|TS|TC|SCR|REMUX|PROPER|REPACK|LIMITED|INTERNAL|EXTENDED|UNRATED|DIRECTORS\.?CUT|IMAX|OPEN\.?MATTE)\b/gi,
  /\b(?:MULTI|DUAL|DUBBED|SUB|SUBS|SUBBED|HC|MSUB|ESUB)\b/gi,
  /\b(?:x264|x265|xvid|divx|10bit|8bit)\b/gi,
  /\b(?:5\.1|7\.1|2\.0)\b/g,
  /\b(?:HDR(?:10|10\+)?|DV|DolbyVision|SDR)\b/gi,
];

const PREFIX_NOISE = [
  /^\s*(?:VIP|PREMIUM|HOT|NEW|EXCLUSIVE|24\/?7)\s*[|:\-–]\s*/i,
  /^\s*[A-Z]{2,3}\s*[|:]\s*/, // "US |", "EN:"
  /^\s*[A-Z]{2}\s+-\s+/, // "US - "
  /^\s*(?:CH\s*)?\d{1,4}[.:]\s*/i, // "001.", "CH 002:"
];

const BRACKETED = /\s*[\[({][^\])}\n]*[\])}]/g;

const EPISODE_PATTERNS = [
  /\bS(\d{1,2})\s*E(\d{1,3})\b/i,
  /\b(\d{1,2})x(\d{1,3})\b/, // 1x02
  /\bSeason\s+(\d{1,2})\s+Episode\s+(\d{1,3})\b/i,
];

function detectYear(input: string): number | null {
  const now = new Date().getFullYear();
  const paren = input.match(/\((\d{4})\)/);
  if (paren) {
    const y = Number(paren[1]);
    if (y >= 1900 && y <= now + 2) return y;
  }
  // Last standalone 4-digit year anywhere
  const all = input.match(/\b(19|20)\d{2}\b/g);
  if (all) {
    for (let i = all.length - 1; i >= 0; i--) {
      const y = Number(all[i]);
      if (y >= 1900 && y <= now + 2) return y;
    }
  }
  return null;
}

function detectSeasonEpisode(input: string): { season: number; episode: number } | null {
  for (const re of EPISODE_PATTERNS) {
    const m = input.match(re);
    if (m) return { season: Number(m[1]), episode: Number(m[2]) };
  }
  return null;
}

export function guessTitle(raw: string | undefined | null): GuessedTitle {
  const input = (raw ?? '').trim();
  if (!input) return { title: '', changed: false, raw: '' };

  const year = detectYear(input) ?? undefined;
  const se = detectSeasonEpisode(input);

  let t = input;

  // Drop bracketed tags completely.
  t = t.replace(BRACKETED, ' ');

  // Drop provider prefixes (iterate — multiple can stack).
  for (let i = 0; i < 3; i++) {
    const before = t;
    for (const re of PREFIX_NOISE) t = t.replace(re, '');
    if (t === before) break;
  }

  // Drop release-quality / codec / source tags.
  for (const re of RELEASE_NOISE) t = t.replace(re, ' ');

  // Cut off at SxxExx or 1x02 — keep only the show name.
  if (se) {
    t = t
      .replace(/\s*S\d{1,2}\s*E\d{1,3}.*$/i, '')
      .replace(/\s*\d{1,2}x\d{1,3}.*$/, '')
      .replace(/\s*Season\s+\d+.*$/i, '');
  }

  // Drop the year so the title itself doesn't include it (we pass year separately).
  if (year) {
    t = t.replace(/\s*\(?\b(19|20)\d{2}\b\)?/g, ' ');
  }

  // Normalize separators: "Movie.Name.2010" → "Movie Name".
  t = t.replace(/[._]+/g, ' ');

  // Remove trailing noise like " - " or " | " and stray brackets.
  t = t.replace(/[\[\](){}]/g, ' ');
  t = t.replace(/\s*[-–|]\s*$/g, '');
  t = t.replace(/^\s*[-–|]\s*/g, '');

  // Collapse whitespace.
  t = t.replace(/\s{2,}/g, ' ').trim();

  // If over-cleaned to empty, fall back to a mild cleanup of the original.
  if (!t) {
    t = input.replace(BRACKETED, ' ').replace(/\s{2,}/g, ' ').trim();
  }

  const changed = t.toLowerCase() !== input.toLowerCase();

  return {
    title: t,
    year,
    season: se?.season,
    episode: se?.episode,
    changed,
    raw: input,
  };
}
