import log from 'electron-log/main';
import { buildXtreamTimeshiftUrl, buildM3uCatchupUrl } from '@yancotv/core';
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

      // Check programme age doesn't exceed archive window.
      // MB-389 — tv_archive_duration is in DAYS, not hours. Comparing the age in
      // HOURS to it meant a 3-day archive hid catch-up for anything older than
      // 3 HOURS. The native CatchupService compares in days (* 86400); match it.
      const nowSecs = Math.floor(Date.now() / 1000);
      const ageDays = (nowSecs - programmeStart) / 86400;
      if (ageDays > tvArchiveDuration) {
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

// Pure URL builders moved to @yancotv/core/catchup. Re-exported here so tests
// that import them from this module continue to work without churn.
export { buildXtreamTimeshiftUrl, buildM3uCatchupUrl };
