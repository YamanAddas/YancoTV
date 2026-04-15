import log from 'electron-log/main';
import { getDb } from './db';
import { getSourceCredentials } from './source-manager';
import type { Result } from '../../shared/types/result';

// ---------------------------------------------------------------------------
// Catch-up TV Service
//
// Builds playback URLs for past EPG programmes. Works with:
//   - Xtream Codes: uses the timeshift endpoint
//   - M3U: uses catchup-source / catchup directives if present
// ---------------------------------------------------------------------------

export interface CatchupInfo {
  /** Whether catch-up is available for this channel */
  available: boolean;
  /** Max archive duration in hours (Xtream: tv_archive_duration) */
  archiveHours: number;
  /** The playback URL for the requested programme */
  streamUrl?: string;
}

interface ContentRow {
  id: string;
  source_id: string;
  type: string;
  stream_url: string;
  tvg_id: string | null;
  metadata_json: string | null;
}

interface SourceRow {
  id: string;
  type: string;
  url: string | null;
}

/**
 * Check if catch-up is available for a channel (by tvgId)
 * and optionally build a playback URL for a past programme.
 */
export function getCatchupUrl(
  tvgId: string,
  programmeStart: number,
  programmeDuration: number,
): Result<CatchupInfo> {
  try {
    const db = getDb();

    // Find the live channel matching this tvgId
    const content = db
      .prepare('SELECT id, source_id, type, stream_url, tvg_id, metadata_json FROM content WHERE tvg_id = ? AND type = ? LIMIT 1')
      .get(tvgId, 'live') as ContentRow | undefined;

    if (!content) {
      return { ok: true, value: { available: false, archiveHours: 0 } };
    }

    // Get source info
    const source = db
      .prepare('SELECT id, type, url FROM sources WHERE id = ?')
      .get(content.source_id) as SourceRow | undefined;

    if (!source) {
      return { ok: true, value: { available: false, archiveHours: 0 } };
    }

    // Parse metadata for Xtream archive info
    let metadata: Record<string, unknown> = {};
    if (content.metadata_json) {
      try {
        metadata = JSON.parse(content.metadata_json);
      } catch {
        // ignore
      }
    }

    const tvArchive = Number(metadata.tvArchive) || 0;
    const tvArchiveDuration = Number(metadata.tvArchiveDuration) || 0;

    // Check if catch-up is supported
    if (source.type === 'xtream') {
      if (tvArchive === 0 || tvArchiveDuration === 0) {
        return { ok: true, value: { available: false, archiveHours: 0 } };
      }

      // Check programme age doesn't exceed archive window
      const nowSecs = Math.floor(Date.now() / 1000);
      const ageHours = (nowSecs - programmeStart) / 3600;
      if (ageHours > tvArchiveDuration) {
        return {
          ok: true,
          value: { available: false, archiveHours: tvArchiveDuration },
        };
      }

      // Build Xtream timeshift URL
      const credentials = getSourceCredentials(content.source_id);
      if (!credentials) {
        return { ok: false, error: new Error('Source credentials not found') };
      }

      const baseUrl = (source.url ?? '').replace(/\/+$/, '');
      const streamUrl = buildXtreamTimeshiftUrl(
        baseUrl,
        credentials.username,
        credentials.password,
        content.stream_url,
        programmeStart,
        programmeDuration,
      );

      log.info(`Built catch-up URL for ${tvgId}: ${streamUrl.slice(0, 80)}...`);

      return {
        ok: true,
        value: {
          available: true,
          archiveHours: tvArchiveDuration,
          streamUrl,
        },
      };
    }

    // For M3U sources, check if stream URL contains catchup patterns
    if (source.type === 'm3u_url' || source.type === 'm3u_file') {
      const catchupUrl = buildM3uCatchupUrl(
        content.stream_url,
        metadata,
        programmeStart,
        programmeDuration,
      );

      if (catchupUrl) {
        return {
          ok: true,
          value: {
            available: true,
            archiveHours: tvArchiveDuration || 24,
            streamUrl: catchupUrl,
          },
        };
      }
    }

    return { ok: true, value: { available: false, archiveHours: 0 } };
  } catch (error) {
    log.error('Catch-up URL error:', error);
    return { ok: false, error: error instanceof Error ? error : new Error(String(error)) };
  }
}

/**
 * Check catch-up availability for a channel without building a URL.
 */
export function checkCatchupSupport(tvgId: string): CatchupInfo {
  const db = getDb();

  const content = db
    .prepare('SELECT source_id, metadata_json FROM content WHERE tvg_id = ? AND type = ? LIMIT 1')
    .get(tvgId, 'live') as { source_id: string; metadata_json: string | null } | undefined;

  if (!content) {
    return { available: false, archiveHours: 0 };
  }

  const source = db
    .prepare('SELECT type FROM sources WHERE id = ?')
    .get(content.source_id) as { type: string } | undefined;

  if (!source) {
    return { available: false, archiveHours: 0 };
  }

  let metadata: Record<string, unknown> = {};
  if (content.metadata_json) {
    try {
      metadata = JSON.parse(content.metadata_json);
    } catch {
      // ignore
    }
  }

  if (source.type === 'xtream') {
    const tvArchive = Number(metadata.tvArchive) || 0;
    const tvArchiveDuration = Number(metadata.tvArchiveDuration) || 0;
    return {
      available: tvArchive > 0 && tvArchiveDuration > 0,
      archiveHours: tvArchiveDuration,
    };
  }

  // M3U: check for catchup metadata
  if (metadata.catchupSource || metadata.catchupType) {
    return {
      available: true,
      archiveHours: Number(metadata.tvArchiveDuration) || 24,
    };
  }

  return { available: false, archiveHours: 0 };
}

// ---------------------------------------------------------------------------
// URL Builders
// ---------------------------------------------------------------------------

/**
 * Build Xtream Codes timeshift/catch-up URL.
 *
 * Xtream standard format:
 *   {baseUrl}/timeshift/{username}/{password}/{duration}/{start}/{streamId}.ts
 *
 * Some providers use the alternative format:
 *   {baseUrl}/streaming/timeshift.php?username={u}&password={p}&stream={id}&start={YYYY-MM-DD:HH-MM}
 *
 * We use the path-based format as it's more widely supported.
 */
/** @internal Exported for testing */
export function buildXtreamTimeshiftUrl(
  baseUrl: string,
  username: string,
  password: string,
  originalStreamUrl: string,
  programmeStart: number,
  programmeDuration: number,
): string {
  // Extract stream ID from the original URL (e.g., /live/user/pass/12345.ts → 12345)
  const streamIdMatch = originalStreamUrl.match(/\/(\d+)\.\w+$/);
  const streamId = streamIdMatch ? streamIdMatch[1] : '0';

  // Format start time as YYYY-MM-DD:HH-MM
  const startDate = new Date(programmeStart * 1000);
  const year = startDate.getUTCFullYear();
  const month = String(startDate.getUTCMonth() + 1).padStart(2, '0');
  const day = String(startDate.getUTCDate()).padStart(2, '0');
  const hours = String(startDate.getUTCHours()).padStart(2, '0');
  const minutes = String(startDate.getUTCMinutes()).padStart(2, '0');
  const startStr = `${year}-${month}-${day}:${hours}-${minutes}`;

  // Duration in minutes
  const durationMins = Math.ceil(programmeDuration / 60);

  return `${baseUrl}/timeshift/${username}/${password}/${durationMins}/${startStr}/${streamId}.ts`;
}

/**
 * Build catch-up URL for M3U sources using catchup-source patterns.
 *
 * Common M3U catchup patterns:
 *   catchup-source="http://example.com/timeshift/{stream_id}/{start}/{duration}"
 *   catchup-type="flussonic" / "xc" / "shift" / "append"
 *
 * Placeholder variables:
 *   {start}        — Unix timestamp or formatted date
 *   {end}          — End timestamp
 *   {duration}     — Duration in seconds
 *   {timestamp}    — Alias for {start}
 *   {utc}          — UTC timestamp
 *   {lutc}         — Local time UTC
 *   {stream_id}    — Extracted from original URL
 *   {Y}, {m}, {d}, {H}, {M}, {S} — Date components
 */
/** @internal Exported for testing */
export function buildM3uCatchupUrl(
  originalUrl: string,
  metadata: Record<string, unknown>,
  programmeStart: number,
  programmeDuration: number,
): string | null {
  const catchupSource = String(metadata.catchupSource ?? '');
  const catchupType = String(metadata.catchupType ?? '');

  if (!catchupSource && !catchupType) return null;

  let template = catchupSource || originalUrl;

  // If catchup type is "append", just append the start/duration to the URL
  if (catchupType === 'append' && !catchupSource) {
    return `${originalUrl}?utc=${programmeStart}&lutc=${programmeStart}&duration=${programmeDuration}`;
  }

  // If catchup type is "shift", use standard shift format
  if (catchupType === 'shift' && !catchupSource) {
    const nowSecs = Math.floor(Date.now() / 1000);
    const shift = nowSecs - programmeStart;
    return `${originalUrl}?utc=${programmeStart}&lutc=${programmeStart}&shift=${shift}`;
  }

  // Replace placeholders in the catchup-source template
  const startDate = new Date(programmeStart * 1000);

  template = template
    .replace(/\{start\}/g, String(programmeStart))
    .replace(/\{end\}/g, String(programmeStart + programmeDuration))
    .replace(/\{duration\}/g, String(programmeDuration))
    .replace(/\{timestamp\}/g, String(programmeStart))
    .replace(/\{utc\}/g, String(programmeStart))
    .replace(/\{lutc\}/g, String(programmeStart))
    .replace(/\{Y\}/g, String(startDate.getUTCFullYear()))
    .replace(/\{m\}/g, String(startDate.getUTCMonth() + 1).padStart(2, '0'))
    .replace(/\{d\}/g, String(startDate.getUTCDate()).padStart(2, '0'))
    .replace(/\{H\}/g, String(startDate.getUTCHours()).padStart(2, '0'))
    .replace(/\{M\}/g, String(startDate.getUTCMinutes()).padStart(2, '0'))
    .replace(/\{S\}/g, String(startDate.getUTCSeconds()).padStart(2, '0'));

  // Extract and replace stream_id placeholder
  const streamIdMatch = originalUrl.match(/\/(\d+)\.\w+$/);
  if (streamIdMatch) {
    template = template.replace(/\{stream_id\}/g, streamIdMatch[1]);
  }

  return template;
}
