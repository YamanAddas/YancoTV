import fs from 'fs';
import https from 'https';
import http from 'http';
import type { IncomingMessage } from 'http';
import log from 'electron-log/main';
import { parseM3u } from './m3u-parser';
import { storeM3uEntries, storeXtreamContent } from './content-store';
import { getSourceById, updateSourceSyncTime, getSourceCredentials } from './source-manager';
import { XtreamClient } from './xtream-client';
import type { Result } from '../../shared/types/result';

export async function syncSource(sourceId: string): Promise<Result<number>> {
  const source = getSourceById(sourceId);
  if (!source) {
    return { ok: false, error: new Error('Source not found') };
  }

  try {
    let count: number;

    if (source.type === 'm3u_url') {
      if (!source.url) {
        return { ok: false, error: new Error('Source has no URL') };
      }
      log.info(`Fetching M3U from URL: ${source.url}`);
      const m3uContent = await fetchUrl(source.url);
      const entries = parseM3u(m3uContent);
      count = storeM3uEntries(sourceId, entries);
    } else if (source.type === 'm3u_file') {
      if (!source.filePath) {
        return { ok: false, error: new Error('Source has no file path') };
      }
      log.info(`Reading M3U from file: ${source.filePath}`);
      const m3uContent = fs.readFileSync(source.filePath, 'utf-8');
      const entries = parseM3u(m3uContent);
      count = storeM3uEntries(sourceId, entries);
    } else if (source.type === 'xtream') {
      count = await syncXtreamSource(sourceId);
    } else {
      return { ok: false, error: new Error(`Unknown source type: ${source.type}`) };
    }

    updateSourceSyncTime(sourceId);
    log.info(`Synced source ${source.name}: ${count} entries`);
    return { ok: true, value: count };
  } catch (error) {
    log.error(`Failed to sync source ${sourceId}:`, error);
    return { ok: false, error: error instanceof Error ? error : new Error(String(error)) };
  }
}

async function syncXtreamSource(sourceId: string): Promise<number> {
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

  // Fetch all categories and streams in parallel
  log.info('Fetching Xtream categories and streams...');
  const [liveCats, vodCats, seriesCats, liveStreams, vodStreams, seriesList] = await Promise.all([
    client.getLiveCategories(),
    client.getVodCategories(),
    client.getSeriesCategories(),
    client.getLiveStreams(),
    client.getVodStreams(),
    client.getSeriesList(),
  ]);

  // Build category ID → name maps
  const liveCategoryMap = buildCategoryMap(liveCats.ok ? liveCats.value : []);
  const vodCategoryMap = buildCategoryMap(vodCats.ok ? vodCats.value : []);
  const seriesCategoryMap = buildCategoryMap(seriesCats.ok ? seriesCats.value : []);

  const count = storeXtreamContent(
    sourceId,
    client,
    { streams: liveStreams.ok ? liveStreams.value : [], categories: liveCategoryMap },
    { streams: vodStreams.ok ? vodStreams.value : [], categories: vodCategoryMap },
    { series: seriesList.ok ? seriesList.value : [], categories: seriesCategoryMap },
  );

  return count;
}

function buildCategoryMap(
  categories: Array<{ categoryId: string; categoryName: string }>,
): Map<string, string> {
  const map = new Map<string, string>();
  for (const cat of categories) {
    map.set(cat.categoryId, cat.categoryName);
  }
  return map;
}

function fetchUrl(url: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const callback = (res: IncomingMessage) => {
      if (res.statusCode && (res.statusCode < 200 || res.statusCode >= 300)) {
        reject(new Error(`HTTP ${res.statusCode}: ${res.statusMessage}`));
        return;
      }

      const chunks: Buffer[] = [];
      res.on('data', (chunk: Buffer) => chunks.push(chunk));
      res.on('end', () => {
        const buffer = Buffer.concat(chunks);
        resolve(buffer.toString('utf-8'));
      });
      res.on('error', reject);
    };

    const request = url.startsWith('https')
      ? https.get(url, { timeout: 30_000 }, callback)
      : http.get(url, { timeout: 30_000 }, callback);

    request.on('error', reject);
    request.on('timeout', () => {
      request.destroy();
      reject(new Error('Request timed out'));
    });
  });
}
