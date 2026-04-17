import fs from 'fs/promises';
import https from 'https';
import http from 'http';
import type { IncomingMessage } from 'http';
import { BrowserWindow } from 'electron';
import log from 'electron-log/main';
import { parseM3u } from './m3u-parser';
import { storeM3uEntries, storeXtreamContent, storeStalkerContent } from './content-store';
import type { SyncProgress } from './content-store';
import {
  getSourceById,
  getAllSources,
  updateSourceSyncTime,
  getSourceCredentials,
  getSourceMacAddress,
  updateSourceEpgUrl,
  updateSourceHealth,
} from './source-manager';
import { XtreamClient } from './xtream-client';
import { StalkerClient } from './stalker-client';
import { getSetting } from './settings-service';
import { IpcChannels } from '../../shared/ipc-channels';
import type { Result } from '../../shared/types/result';

// --- Constants ---

const MAX_M3U_SIZE = 200 * 1024 * 1024; // 200 MB — reject files larger than this
const FETCH_TIMEOUT = 90_000; // 90 seconds for large M3U downloads
const XTREAM_STREAM_DELAY = 300; // ms between sequential Xtream stream requests
const STALKER_REQUEST_DELAY = 500; // ms between sequential Stalker requests
const AUTO_SYNC_CHECK_INTERVAL = 5 * 60_000; // Check every 5 minutes

// --- Active sync tracking (prevents concurrent syncs on the same source) ---

const activeSyncs = new Set<string>();

export function isSyncing(sourceId: string): boolean {
  return activeSyncs.has(sourceId);
}

// --- Progress broadcasting ---

function broadcastProgress(sourceId: string, progress: SyncProgress): void {
  for (const win of BrowserWindow.getAllWindows()) {
    try {
      win.webContents.send(IpcChannels.SOURCES_SYNC_PROGRESS, sourceId, progress);
    } catch {
      // Window may have been closed during sync
    }
  }
}

// --- Main sync entry point ---

export async function syncSource(sourceId: string): Promise<Result<number>> {
  if (activeSyncs.has(sourceId)) {
    return { ok: false, error: new Error('Sync already in progress for this source') };
  }

  const source = getSourceById(sourceId);
  if (!source) {
    return { ok: false, error: new Error('Source not found') };
  }

  activeSyncs.add(sourceId);
  const onProgress = (p: SyncProgress) => broadcastProgress(sourceId, p);

  try {
    let count: number;

    if (source.type === 'm3u_url') {
      if (!source.url) {
        return { ok: false, error: new Error('Source has no URL') };
      }
      log.info(`Fetching M3U from URL: ${source.url}`);
      const m3uContent = await fetchUrl(source.url);
      const { entries, epgUrl } = parseM3u(m3uContent);
      count = await storeM3uEntries(sourceId, entries, onProgress);

      // Auto-detect EPG URL from M3U header (url-tvg attribute)
      if (epgUrl) {
        log.info(`Auto-detected EPG URL from M3U: ${epgUrl}`);
        updateSourceEpgUrl(sourceId, epgUrl);
      }
    } else if (source.type === 'm3u_file') {
      if (!source.filePath) {
        return { ok: false, error: new Error('Source has no file path') };
      }
      log.info(`Reading M3U from file: ${source.filePath}`);

      // Check file size before reading
      const stat = await fs.stat(source.filePath);
      if (stat.size > MAX_M3U_SIZE) {
        return {
          ok: false,
          error: new Error(`File too large (${Math.round(stat.size / 1024 / 1024)}MB). Maximum is ${MAX_M3U_SIZE / 1024 / 1024}MB.`),
        };
      }

      const m3uContent = await fs.readFile(source.filePath, 'utf-8');
      const { entries, epgUrl } = parseM3u(m3uContent);
      count = await storeM3uEntries(sourceId, entries, onProgress);

      // Auto-detect EPG URL from M3U header (url-tvg attribute)
      if (epgUrl) {
        log.info(`Auto-detected EPG URL from M3U file: ${epgUrl}`);
        updateSourceEpgUrl(sourceId, epgUrl);
      }
    } else if (source.type === 'xtream') {
      count = await syncXtreamSource(sourceId, onProgress);
    } else if (source.type === 'stalker') {
      count = await syncStalkerSource(sourceId, onProgress);
    } else {
      return { ok: false, error: new Error(`Unknown source type: ${source.type}`) };
    }

    try {
      updateSourceSyncTime(sourceId);
      updateSourceHealth(sourceId, count);
    } catch (err) {
      // Sync data was already written; only the source row metadata failed.
      // Log loudly (not warn) and broadcast so the UI can show the stale
      // health indicator instead of silently misleading the user.
      const msg = err instanceof Error ? err.message : String(err);
      log.error(`Sync of ${source.name} succeeded but health metadata update failed: ${msg}`);
      broadcastProgress(sourceId, {
        phase: 'error',
        current: count,
        total: count,
        message: `Synced ${count} entries but failed to update sync metadata: ${msg}`,
      });
    }

    log.info(`Synced source ${source.name}: ${count} entries`);
    return { ok: true, value: count };
  } catch (error) {
    const errorMsg = error instanceof Error ? error.message : String(error);
    log.error(`Failed to sync source ${sourceId}:`, error);

    try {
      updateSourceHealth(sourceId, 0, errorMsg);
    } catch (healthErr) {
      log.error(
        `Failed to record sync error in source health (will not surface to UI): ${
          healthErr instanceof Error ? healthErr.message : String(healthErr)
        }`,
      );
    }

    return { ok: false, error: error instanceof Error ? error : new Error(String(error)) };
  } finally {
    activeSyncs.delete(sourceId);
  }
}

// --- Xtream sync ---

async function syncXtreamSource(
  sourceId: string,
  onProgress: (p: SyncProgress) => void,
): Promise<number> {
  const source = getSourceById(sourceId);
  if (!source?.url) {
    throw new Error('Xtream source has no URL');
  }

  const credentials = getSourceCredentials(sourceId);
  if (!credentials) {
    throw new Error('Xtream source has no credentials');
  }

  const client = new XtreamClient(source.url, credentials.username, credentials.password);

  // Authenticate first
  log.info(`Authenticating with Xtream API: ${source.url}`);
  const authResult = await client.authenticate();
  if (!authResult.ok) {
    throw authResult.error;
  }
  log.info(
    `Xtream auth OK — user: ${authResult.value.userInfo.username}, status: ${authResult.value.userInfo.status}`,
  );

  // Auto-detect EPG URL from Xtream API
  const xtreamEpgUrl = client.buildEpgUrl();
  log.info(`Auto-detected Xtream EPG URL: ${xtreamEpgUrl}`);
  updateSourceEpgUrl(sourceId, xtreamEpgUrl);

  // Fetch categories in parallel (small responses, safe to parallelize)
  log.info('Fetching Xtream categories...');
  const [liveCats, vodCats, seriesCats] = await Promise.all([
    client.getLiveCategories(),
    client.getVodCategories(),
    client.getSeriesCategories(),
  ]);

  // Fetch streams sequentially to avoid overwhelming the provider
  log.info('Fetching live streams...');
  const liveStreams = await client.getLiveStreams();
  await sleep(XTREAM_STREAM_DELAY);

  log.info('Fetching VOD streams...');
  const vodStreams = await client.getVodStreams();
  await sleep(XTREAM_STREAM_DELAY);

  log.info('Fetching series list...');
  const seriesList = await client.getSeriesList();

  // Build category ID -> name maps
  const liveCategoryMap = buildCategoryMap(liveCats.ok ? liveCats.value : []);
  const vodCategoryMap = buildCategoryMap(vodCats.ok ? vodCats.value : []);
  const seriesCategoryMap = buildCategoryMap(seriesCats.ok ? seriesCats.value : []);

  const count = await storeXtreamContent(
    sourceId,
    client,
    { streams: liveStreams.ok ? liveStreams.value : [], categories: liveCategoryMap },
    { streams: vodStreams.ok ? vodStreams.value : [], categories: vodCategoryMap },
    { series: seriesList.ok ? seriesList.value : [], categories: seriesCategoryMap },
    onProgress,
  );

  return count;
}

// --- Stalker sync ---

async function syncStalkerSource(
  sourceId: string,
  onProgress: (p: SyncProgress) => void,
): Promise<number> {
  const source = getSourceById(sourceId);
  if (!source?.url) {
    throw new Error('Stalker source has no portal URL');
  }

  const macAddress = getSourceMacAddress(sourceId);
  if (!macAddress) {
    throw new Error('Stalker source has no MAC address');
  }

  const client = new StalkerClient(source.url, macAddress);

  // Authenticate
  log.info(`Authenticating with Stalker Portal: ${source.url}`);
  const authResult = await client.authenticate();
  if (!authResult.ok) {
    throw authResult.error;
  }
  log.info(`Stalker auth OK — portal: ${authResult.value.portalUrl}`);

  // Fetch categories
  log.info('Fetching Stalker categories...');
  const [liveCats, vodCats, seriesCats] = await Promise.all([
    client.getLiveCategories(),
    client.getVodCategories(),
    client.getSeriesCategories(),
  ]);

  // Fetch content sequentially to avoid overwhelming the portal
  log.info('Fetching Stalker live channels...');
  const liveChannels = await client.getLiveChannels();
  await sleep(STALKER_REQUEST_DELAY);

  log.info('Fetching Stalker VOD items...');
  const vodItems = await client.getVodItems();
  await sleep(STALKER_REQUEST_DELAY);

  log.info('Fetching Stalker series...');
  const seriesList = await client.getSeriesList();

  // Build category ID -> name maps
  const liveCategoryMap = buildStalkerCategoryMap(liveCats.ok ? liveCats.value : []);
  const vodCategoryMap = buildStalkerCategoryMap(vodCats.ok ? vodCats.value : []);
  const seriesCategoryMap = buildStalkerCategoryMap(seriesCats.ok ? seriesCats.value : []);

  const count = await storeStalkerContent(
    sourceId,
    client,
    { channels: liveChannels.ok ? liveChannels.value : [], categories: liveCategoryMap },
    { items: vodItems.ok ? vodItems.value : [], categories: vodCategoryMap },
    { series: seriesList.ok ? seriesList.value : [], categories: seriesCategoryMap },
    onProgress,
  );

  return count;
}

// --- Auto-sync ---

let autoSyncTimer: ReturnType<typeof setInterval> | null = null;

export function startAutoSync(): void {
  if (autoSyncTimer) return;

  autoSyncTimer = setInterval(async () => {
    try {
      const sources = getAllSources();
      const globalInterval = parseInt(getSetting('playlist_auto_sync_interval') ?? '0', 10);

      for (const source of sources) {
        if (activeSyncs.has(source.id)) continue;

        const interval = source.autoSyncInterval > 0
          ? source.autoSyncInterval
          : globalInterval;

        if (interval <= 0) continue;

        const intervalMs = interval * 3600_000; // hours to ms
        const lastSynced = source.lastSynced ?? 0;

        if (Date.now() - lastSynced >= intervalMs) {
          log.info(`Auto-syncing source: ${source.name} (interval: ${interval}h)`);
          await syncSource(source.id);
        }
      }
    } catch (err) {
      log.error('Auto-sync check failed:', err);
    }
  }, AUTO_SYNC_CHECK_INTERVAL);

  log.info('Auto-sync started (checking every 5 minutes)');
}

export function stopAutoSync(): void {
  if (autoSyncTimer) {
    clearInterval(autoSyncTimer);
    autoSyncTimer = null;
    log.info('Auto-sync stopped');
  }
}

// --- Helpers ---

function buildCategoryMap(
  categories: Array<{ categoryId: string; categoryName: string }>,
): Map<string, string> {
  const map = new Map<string, string>();
  for (const cat of categories) {
    map.set(cat.categoryId, cat.categoryName);
  }
  return map;
}

function buildStalkerCategoryMap(
  categories: Array<{ id: string; title: string }>,
): Map<string, string> {
  const map = new Map<string, string>();
  for (const cat of categories) {
    map.set(cat.id, cat.title);
  }
  return map;
}

function fetchUrl(url: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const callback = (res: IncomingMessage) => {
      // Follow redirects
      if (res.statusCode && [301, 302, 307, 308].includes(res.statusCode) && res.headers.location) {
        fetchUrl(res.headers.location).then(resolve, reject);
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
        if (receivedBytes > MAX_M3U_SIZE) {
          res.destroy();
          reject(new Error(`Response exceeded ${MAX_M3U_SIZE / 1024 / 1024}MB limit`));
          return;
        }
        chunks.push(chunk);
      });

      res.on('end', () => {
        const buffer = Buffer.concat(chunks);
        resolve(buffer.toString('utf-8'));
      });

      res.on('error', reject);
    };

    const request = url.startsWith('https')
      ? https.get(url, { timeout: FETCH_TIMEOUT }, callback)
      : http.get(url, { timeout: FETCH_TIMEOUT }, callback);

    request.on('error', reject);
    request.on('timeout', () => {
      request.destroy();
      reject(new Error('Download timed out'));
    });
  });
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
