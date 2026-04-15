/**
 * EPG Service — fetch, store, and query Electronic Program Guide data.
 *
 * Responsibilities:
 * - Download XMLTV from source EPG URLs or a global EPG URL
 * - Parse XMLTV and store programmes in the database
 * - Query now/next for channels
 * - Query guide data for a time range
 * - Auto-refresh on a configurable interval
 */

import https from 'https';
import http from 'http';
import type { IncomingMessage } from 'http';
import { v4 as uuid } from 'uuid';
import log from 'electron-log/main';
import { getDb } from './db';
import { parseXmltv } from './xmltv-parser';
import type {
  EpgProgramme,
  NowNext,
  NowNextMap,
  EpgGuideChannel,
  EpgRefreshResult,
} from '../../shared/types/epg';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const FETCH_TIMEOUT = 120_000; // 2 minutes for large EPG files
const MAX_EPG_SIZE = 500 * 1024 * 1024; // 500 MB limit
const BATCH_SIZE = 5000;

// ---------------------------------------------------------------------------
// EPG refresh state
// ---------------------------------------------------------------------------

let refreshTimer: ReturnType<typeof setTimeout> | null = null;
let isRefreshing = false;

// ---------------------------------------------------------------------------
// Public API — Queries
// ---------------------------------------------------------------------------

/**
 * Get the currently airing programme for a channel.
 */
export function getNowProgramme(tvgId: string): EpgProgramme | null {
  const db = getDb();
  const now = Math.floor(Date.now() / 1000);

  const row = db
    .prepare(
      `SELECT id, channel_tvg_id, title, description, start_time, end_time, category, icon_url
       FROM epg_programmes
       WHERE channel_tvg_id = ? AND start_time <= ? AND end_time > ?
       ORDER BY start_time DESC
       LIMIT 1`,
    )
    .get(tvgId, now, now) as EpgProgrammeRow | undefined;

  return row ? mapRow(row) : null;
}

/**
 * Get now + next programmes for a single channel.
 */
export function getNowNext(tvgId: string): NowNext {
  const db = getDb();
  const now = Math.floor(Date.now() / 1000);

  const rows = db
    .prepare(
      `SELECT id, channel_tvg_id, title, description, start_time, end_time, category, icon_url
       FROM epg_programmes
       WHERE channel_tvg_id = ? AND end_time > ?
       ORDER BY start_time ASC
       LIMIT 2`,
    )
    .all(tvgId, now) as EpgProgrammeRow[];

  const result: NowNext = { channelTvgId: tvgId };

  if (rows.length > 0) {
    const first = rows[0];
    if (first.start_time <= now) {
      result.now = mapRow(first);
      if (rows.length > 1) {
        result.next = mapRow(rows[1]);
      }
    } else {
      // Nothing airing right now, first future programme is "next"
      result.next = mapRow(first);
    }
  }

  return result;
}

/**
 * Get now/next for multiple channels in one call.
 * Efficient batch query for the Live TV grid.
 */
export function getNowNextBatch(tvgIds: string[]): NowNextMap {
  if (tvgIds.length === 0) return {};

  const db = getDb();
  const now = Math.floor(Date.now() / 1000);
  const result: NowNextMap = {};

  // For efficiency, query all programmes that are currently airing or next
  // across all requested channels in a single query
  const placeholders = tvgIds.map(() => '?').join(',');
  const rows = db
    .prepare(
      `SELECT id, channel_tvg_id, title, description, start_time, end_time, category, icon_url
       FROM epg_programmes
       WHERE channel_tvg_id IN (${placeholders}) AND end_time > ?
       ORDER BY channel_tvg_id, start_time ASC`,
    )
    .all(...tvgIds, now) as EpgProgrammeRow[];

  // Group by channel and pick now + next
  const byChannel = new Map<string, EpgProgrammeRow[]>();
  for (const row of rows) {
    const existing = byChannel.get(row.channel_tvg_id);
    if (existing) {
      // Only keep first 2 per channel
      if (existing.length < 2) existing.push(row);
    } else {
      byChannel.set(row.channel_tvg_id, [row]);
    }
  }

  for (const tvgId of tvgIds) {
    const channelRows = byChannel.get(tvgId);
    const entry: NowNext = { channelTvgId: tvgId };

    if (channelRows && channelRows.length > 0) {
      const first = channelRows[0];
      if (first.start_time <= now) {
        entry.now = mapRow(first);
        if (channelRows.length > 1) {
          entry.next = mapRow(channelRows[1]);
        }
      } else {
        entry.next = mapRow(first);
      }
    }

    result[tvgId] = entry;
  }

  return result;
}

/**
 * Get all programmes for a channel within a time range.
 */
export function getProgrammesForChannel(
  tvgId: string,
  startTime: number,
  endTime: number,
): EpgProgramme[] {
  const db = getDb();

  const rows = db
    .prepare(
      `SELECT id, channel_tvg_id, title, description, start_time, end_time, category, icon_url
       FROM epg_programmes
       WHERE channel_tvg_id = ? AND end_time > ? AND start_time < ?
       ORDER BY start_time ASC`,
    )
    .all(tvgId, startTime, endTime) as EpgProgrammeRow[];

  return rows.map(mapRow);
}

/**
 * Get guide data for the EPG grid view.
 * Returns channels that have EPG data, with their programmes for the time range.
 */
export function getGuideData(
  startTime: number,
  endTime: number,
  sourceId?: string,
): EpgGuideChannel[] {
  const db = getDb();

  // Get all live channels that have EPG data, joined with their content info
  let channelQuery: string;
  let channelParams: unknown[];

  if (sourceId) {
    channelQuery = `
      SELECT DISTINCT c.tvg_id, c.clean_title, c.title, c.logo_url
      FROM content c
      INNER JOIN epg_programmes ep ON ep.channel_tvg_id = c.tvg_id
      WHERE c.type = 'live' AND c.tvg_id IS NOT NULL AND c.tvg_id != ''
        AND c.source_id = ?
        AND ep.end_time > ? AND ep.start_time < ?
      ORDER BY c.title ASC`;
    channelParams = [sourceId, startTime, endTime];
  } else {
    channelQuery = `
      SELECT DISTINCT c.tvg_id, c.clean_title, c.title, c.logo_url
      FROM content c
      INNER JOIN epg_programmes ep ON ep.channel_tvg_id = c.tvg_id
      WHERE c.type = 'live' AND c.tvg_id IS NOT NULL AND c.tvg_id != ''
        AND ep.end_time > ? AND ep.start_time < ?
      ORDER BY c.title ASC`;
    channelParams = [startTime, endTime];
  }

  const channelRows = db.prepare(channelQuery).all(...channelParams) as Array<{
    tvg_id: string;
    clean_title: string | null;
    title: string;
    logo_url: string | null;
  }>;

  if (channelRows.length === 0) return [];

  // Fetch programmes for all those channels
  const tvgIds = channelRows.map((r) => r.tvg_id);
  const placeholders = tvgIds.map(() => '?').join(',');

  const progRows = db
    .prepare(
      `SELECT id, channel_tvg_id, title, description, start_time, end_time, category, icon_url
       FROM epg_programmes
       WHERE channel_tvg_id IN (${placeholders}) AND end_time > ? AND start_time < ?
       ORDER BY start_time ASC`,
    )
    .all(...tvgIds, startTime, endTime) as EpgProgrammeRow[];

  // Group programmes by channel
  const progsByChannel = new Map<string, EpgProgramme[]>();
  for (const row of progRows) {
    const list = progsByChannel.get(row.channel_tvg_id) || [];
    list.push(mapRow(row));
    progsByChannel.set(row.channel_tvg_id, list);
  }

  return channelRows.map((ch) => ({
    tvgId: ch.tvg_id,
    name: ch.clean_title || ch.title,
    logoUrl: ch.logo_url || undefined,
    programmes: progsByChannel.get(ch.tvg_id) || [],
  }));
}

/**
 * Get EPG statistics.
 */
export function getEpgStats(): {
  programmeCount: number;
  channelCount: number;
  lastRefreshedAt: number | null;
} {
  const db = getDb();

  const stats = db
    .prepare(
      `SELECT COUNT(*) as total, COUNT(DISTINCT channel_tvg_id) as channels
       FROM epg_programmes`,
    )
    .get() as { total: number; channels: number };

  const setting = db
    .prepare(`SELECT value FROM settings WHERE key = 'epg_last_refreshed'`)
    .get() as { value: string } | undefined;

  return {
    programmeCount: stats.total,
    channelCount: stats.channels,
    lastRefreshedAt: setting ? parseInt(setting.value, 10) : null,
  };
}

// ---------------------------------------------------------------------------
// Public API — Refresh / Import
// ---------------------------------------------------------------------------

/**
 * Refresh EPG data from all configured sources.
 * Fetches XMLTV from each source's epg_url, plus any global EPG URL.
 */
export async function refreshEpg(): Promise<EpgRefreshResult> {
  if (isRefreshing) {
    return { ok: false, error: 'EPG refresh already in progress' };
  }

  isRefreshing = true;
  log.info('Starting EPG refresh...');

  try {
    const db = getDb();
    let totalProgrammes = 0;
    const allChannelIds = new Set<string>();

    // Collect EPG URLs: per-source + global
    const epgUrls: Array<{ url: string; sourceId: string | null }> = [];

    // Per-source EPG URLs
    const sources = db
      .prepare(
        `SELECT id, epg_url FROM sources WHERE is_active = 1 AND epg_url IS NOT NULL AND epg_url != ''`,
      )
      .all() as Array<{ id: string; epg_url: string }>;

    for (const source of sources) {
      epgUrls.push({ url: source.epg_url, sourceId: source.id });
    }

    // Global EPG URL (stored in settings)
    const globalEpgSetting = db
      .prepare(`SELECT value FROM settings WHERE key = 'epg_global_url'`)
      .get() as { value: string } | undefined;

    if (globalEpgSetting?.value) {
      epgUrls.push({ url: globalEpgSetting.value, sourceId: null });
    }

    if (epgUrls.length === 0) {
      log.info('No EPG URLs configured, skipping refresh');
      return { ok: true, programmeCount: 0, channelCount: 0 };
    }

    // Clear old EPG data before importing fresh data
    db.prepare('DELETE FROM epg_programmes').run();

    // Process each EPG URL
    for (const { url, sourceId } of epgUrls) {
      try {
        log.info(`Fetching EPG from: ${url}`);
        const buffer = await fetchEpgData(url);
        const { programmes } = parseXmltv(buffer);

        if (programmes.length === 0) {
          log.warn(`No programmes found in EPG: ${url}`);
          continue;
        }

        // Store programmes in batches
        const insertStmt = db.prepare(
          `INSERT OR REPLACE INTO epg_programmes
           (id, channel_tvg_id, title, description, start_time, end_time, category, icon_url, source_id)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        );

        const insertBatch = db.transaction(
          (batch: typeof programmes) => {
            for (const prog of batch) {
              insertStmt.run(
                uuid(),
                prog.channelId,
                prog.title,
                prog.description || null,
                prog.startTime,
                prog.endTime,
                prog.category || null,
                prog.iconUrl || null,
                sourceId,
              );
              allChannelIds.add(prog.channelId);
            }
          },
        );

        // Insert in batches to avoid holding the DB lock too long
        for (let i = 0; i < programmes.length; i += BATCH_SIZE) {
          const batch = programmes.slice(i, i + BATCH_SIZE);
          insertBatch(batch);

          // Yield to event loop between batches
          if (i + BATCH_SIZE < programmes.length) {
            await new Promise((resolve) => setTimeout(resolve, 0));
          }
        }

        totalProgrammes += programmes.length;
        log.info(
          `Stored ${programmes.length} programmes from ${url} (${allChannelIds.size} channels total)`,
        );
      } catch (err) {
        log.error(`Failed to fetch/parse EPG from ${url}:`, err);
        // Continue with other URLs
      }
    }

    // Update last refresh timestamp
    ensureSettingsTable(db);
    db.prepare(
      `INSERT OR REPLACE INTO settings (key, value) VALUES ('epg_last_refreshed', ?)`,
    ).run(String(Date.now()));

    log.info(
      `EPG refresh complete: ${totalProgrammes} programmes, ${allChannelIds.size} channels`,
    );

    return {
      ok: true,
      programmeCount: totalProgrammes,
      channelCount: allChannelIds.size,
    };
  } catch (err) {
    log.error('EPG refresh failed:', err);
    return {
      ok: false,
      error: err instanceof Error ? err.message : String(err),
    };
  } finally {
    isRefreshing = false;
  }
}

/**
 * Start the auto-refresh timer for EPG data.
 */
export function startAutoRefresh(intervalHours: number = 12): void {
  stopAutoRefresh();

  const intervalMs = intervalHours * 60 * 60 * 1000;
  log.info(`EPG auto-refresh scheduled every ${intervalHours}h`);

  refreshTimer = setInterval(() => {
    refreshEpg().catch((err) => log.error('Auto EPG refresh error:', err));
  }, intervalMs);
}

/**
 * Stop the auto-refresh timer.
 */
export function stopAutoRefresh(): void {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

/**
 * Check if EPG refresh is currently running.
 */
export function isEpgRefreshing(): boolean {
  return isRefreshing;
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

function ensureSettingsTable(db: ReturnType<typeof getDb>): void {
  db.exec(`
    CREATE TABLE IF NOT EXISTS settings (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    )
  `);
}

interface EpgProgrammeRow {
  id: string;
  channel_tvg_id: string;
  title: string;
  description: string | null;
  start_time: number;
  end_time: number;
  category: string | null;
  icon_url: string | null;
}

function mapRow(row: EpgProgrammeRow): EpgProgramme {
  return {
    id: row.id,
    channelTvgId: row.channel_tvg_id,
    title: row.title,
    description: row.description || undefined,
    startTime: row.start_time,
    endTime: row.end_time,
    category: row.category || undefined,
    iconUrl: row.icon_url || undefined,
  };
}

/**
 * Fetch EPG data as a Buffer (handles gzip and plain text).
 */
function fetchEpgData(url: string): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const callback = (res: IncomingMessage) => {
      // Follow redirects
      if (
        res.statusCode &&
        [301, 302, 307, 308].includes(res.statusCode) &&
        res.headers.location
      ) {
        fetchEpgData(res.headers.location).then(resolve, reject);
        return;
      }

      if (res.statusCode && (res.statusCode < 200 || res.statusCode >= 300)) {
        reject(new Error(`HTTP ${res.statusCode}: ${res.statusMessage}`));
        return;
      }

      let receivedBytes = 0;
      const chunks: Buffer[] = [];

      res.on('data', (chunk: Buffer) => {
        receivedBytes += chunk.length;
        if (receivedBytes > MAX_EPG_SIZE) {
          res.destroy();
          reject(
            new Error(
              `EPG response exceeded ${MAX_EPG_SIZE / 1024 / 1024}MB limit`,
            ),
          );
          return;
        }
        chunks.push(chunk);
      });

      res.on('end', () => {
        resolve(Buffer.concat(chunks));
      });

      res.on('error', reject);
    };

    const options = {
      timeout: FETCH_TIMEOUT,
      headers: {
        'Accept-Encoding': 'gzip',
        'User-Agent': 'YancoTV/0.1.0',
      },
    };

    const request = url.startsWith('https')
      ? https.get(url, options, callback)
      : http.get(url, options, callback);

    request.on('error', reject);
    request.on('timeout', () => {
      request.destroy();
      reject(new Error('EPG download timed out'));
    });
  });
}
