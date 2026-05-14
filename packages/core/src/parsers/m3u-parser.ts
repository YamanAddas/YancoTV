import { NOOP_LOGGER, type Logger } from '../logger.js';

export interface M3uEntry {
  duration: number;
  title: string;
  groupTitle: string;
  tvgId: string;
  tvgName: string;
  tvgLogo: string;
  streamUrl: string;
  rawAttributes: string;
  /** Catch-up type: "default", "flussonic", "xc", "shift", "append" */
  catchupType?: string;
  /** URL template for catch-up playback */
  catchupSource?: string;
  /** Catch-up archive window in hours */
  catchupDays?: number;
}

export interface M3uParseResult {
  entries: M3uEntry[];
  /** EPG URL extracted from the #EXTM3U url-tvg header attribute */
  epgUrl?: string;
}

/**
 * Streaming M3U parser. Processes line-by-line to handle large playlists
 * without loading the entire file into memory for parsing.
 *
 * Handles: BOM markers, Windows/Unix line endings, empty lines,
 * malformed entries, and common provider quirks.
 */
export function parseM3u(content: string, logger: Logger = NOOP_LOGGER): M3uParseResult {
  const entries: M3uEntry[] = [];
  // Track URLs we've already added so duplicates from the same playlist
  // don't get classified twice. Titled entries take precedence over bare
  // URL entries (a titled entry inserted later wins by replacing the bare).
  const urlIndex = new Map<string, number>(); // streamUrl -> index in entries
  let duplicates = 0;
  let epgUrl: string | undefined;

  // Strip BOM marker if present
  const cleaned = content.charCodeAt(0) === 0xfeff ? content.slice(1) : content;

  // Normalize line endings and split
  const lines = cleaned.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');

  let currentEntry: Partial<M3uEntry> | null = null;

  const addOrReplace = (entry: M3uEntry, hasTitle: boolean) => {
    const existingIdx = urlIndex.get(entry.streamUrl);
    if (existingIdx === undefined) {
      urlIndex.set(entry.streamUrl, entries.length);
      entries.push(entry);
      return;
    }
    duplicates++;
    const existing = entries[existingIdx];
    // If the existing entry is a bare URL (no title) and the new one has
    // a real title, upgrade. Otherwise drop the duplicate.
    if (hasTitle && !existing.title) {
      entries[existingIdx] = entry;
    }
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();

    // Skip empty lines
    if (!line) continue;

    // Parse #EXTM3U header — extract url-tvg for EPG auto-detection
    if (line.startsWith('#EXTM3U')) {
      epgUrl = extractAttribute(line, 'url-tvg') || undefined;
      // Also check x-tvg-url (alternative attribute some providers use)
      if (!epgUrl) {
        epgUrl = extractAttribute(line, 'x-tvg-url') || undefined;
      }
      if (epgUrl) {
        logger.info(`M3U header contains EPG URL: ${epgUrl}`);
      }
      continue;
    }

    // Parse #EXTINF line
    if (line.startsWith('#EXTINF:')) {
      currentEntry = parseExtinfLine(line);
      continue;
    }

    // Skip other directives
    if (line.startsWith('#')) continue;

    // This is a URL line — pair it with the current EXTINF entry
    if (currentEntry) {
      addOrReplace(
        {
          duration: currentEntry.duration ?? -1,
          title: currentEntry.title ?? '',
          groupTitle: currentEntry.groupTitle ?? '',
          tvgId: currentEntry.tvgId ?? '',
          tvgName: currentEntry.tvgName ?? '',
          tvgLogo: currentEntry.tvgLogo ?? '',
          streamUrl: line,
          rawAttributes: currentEntry.rawAttributes ?? '',
          catchupType: currentEntry.catchupType,
          catchupSource: currentEntry.catchupSource,
          catchupDays: currentEntry.catchupDays,
        },
        Boolean(currentEntry.title),
      );
      currentEntry = null;
    } else {
      // URL without preceding EXTINF — create a bare entry
      addOrReplace(
        {
          duration: -1,
          title: extractTitleFromUrl(line),
          groupTitle: '',
          tvgId: '',
          tvgName: '',
          tvgLogo: '',
          streamUrl: line,
          rawAttributes: '',
        },
        false,
      );
    }
  }

  if (duplicates > 0) {
    logger.warn(`Parsed M3U: ${entries.length} unique entries (${duplicates} duplicate URLs collapsed)`);
  } else {
    logger.info(`Parsed ${entries.length} entries from M3U`);
  }
  return { entries, epgUrl };
}

function parseExtinfLine(line: string): Partial<M3uEntry> {
  // Format: #EXTINF:duration tvg-id="..." tvg-name="..." tvg-logo="..." group-title="...",Title
  const entry: Partial<M3uEntry> = {};

  // Extract duration — everything between #EXTINF: and the first space or comma
  const afterPrefix = line.substring(8); // Remove "#EXTINF:"
  const durationMatch = afterPrefix.match(/^(-?\d+(?:\.\d+)?)/);
  entry.duration = durationMatch ? parseFloat(durationMatch[1]) : -1;

  // Extract attributes from the line
  entry.tvgId = extractAttribute(line, 'tvg-id');
  entry.tvgName = extractAttribute(line, 'tvg-name');
  entry.tvgLogo = extractAttribute(line, 'tvg-logo');
  entry.groupTitle = extractAttribute(line, 'group-title');
  entry.rawAttributes = afterPrefix;

  // Extract catch-up attributes (used by some M3U providers)
  const catchupType = extractAttribute(line, 'catchup') || extractAttribute(line, 'catchup-type');
  if (catchupType) entry.catchupType = catchupType;
  const catchupSource = extractAttribute(line, 'catchup-source');
  if (catchupSource) entry.catchupSource = catchupSource;
  const catchupDays = extractAttribute(line, 'catchup-days') || extractAttribute(line, 'tvg-rec');
  if (catchupDays) entry.catchupDays = parseInt(catchupDays, 10) || undefined;

  // Extract title — everything after the last comma
  const lastCommaIndex = line.lastIndexOf(',');
  if (lastCommaIndex !== -1) {
    entry.title = line.substring(lastCommaIndex + 1).trim();
  } else {
    entry.title = '';
  }

  return entry;
}

/**
 * Precompiled regex map for every M3U `#EXTINF` attribute the parser
 * reads. Building the patterns at module load (rather than on every
 * call via `new RegExp(`${attr}=...`)` ) closes Semgrep's
 * detect-non-literal-regexp finding and removes the per-line regex
 * construction cost — `extractAttribute` is called up to 7 times
 * per channel on multi-thousand-channel playlists.
 *
 * Each attribute has two patterns: one matching `attr="..."` (the
 * common form) and one matching `attr='...'`.
 */
const ATTRIBUTE_NAMES = [
  'tvg-id',
  'tvg-name',
  'tvg-logo',
  'group-title',
  'url-tvg',
  'x-tvg-url',
  'catchup',
  'catchup-type',
  'catchup-source',
  'catchup-days',
  'tvg-rec',
] as const;

type AttributeName = (typeof ATTRIBUTE_NAMES)[number];

const ATTRIBUTE_REGEX = new Map<AttributeName, { double: RegExp; single: RegExp }>(
  ATTRIBUTE_NAMES.map((attr) => [
    attr,
    {
      double: new RegExp(`${attr}="([^"]*)"`, 'i'),
      single: new RegExp(`${attr}='([^']*)'`, 'i'),
    },
  ]),
);

function extractAttribute(line: string, attr: AttributeName): string {
  const patterns = ATTRIBUTE_REGEX.get(attr);
  if (!patterns) return '';
  const match = line.match(patterns.double);
  if (match) return match[1];
  const matchSingle = line.match(patterns.single);
  if (matchSingle) return matchSingle[1];
  return '';
}

function extractTitleFromUrl(url: string): string {
  try {
    const pathname = new URL(url).pathname;
    const filename = pathname.split('/').pop() || '';
    // Remove extension
    return filename.replace(/\.[^.]+$/, '').replace(/[_-]/g, ' ');
  } catch {
    return url.split('/').pop() || 'Unknown';
  }
}
