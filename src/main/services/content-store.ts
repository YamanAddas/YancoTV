import { randomUUID } from 'crypto';
import log from 'electron-log/main';
import { getDb, dropFtsTriggers, restoreFtsTriggers, rebuildFtsIndex } from './db';
import { classifyEntry, normalizeCategory } from './content-classifier';
import { cleanTitle, extractSeasonEpisode, extractShowName } from './title-cleaner';
import type { ContentItem, ContentType, Episode, SortOption } from '../../shared/types';
import type { M3uEntry } from './m3u-parser';
import type {
  XtreamLiveStream,
  XtreamVodStream,
  XtreamSeriesInfo,
  XtreamClient,
  XtreamSeriesEpisode,
} from './xtream-client';

// --- Constants ---

/** Rows per transaction batch — balances memory vs. transaction overhead */
const BATCH_SIZE = 2000;

// --- Row mapping ---

interface ContentRow {
  id: string;
  source_id: string;
  type: string;
  title: string;
  clean_title: string | null;
  group_name: string | null;
  stream_url: string;
  logo_url: string | null;
  tvg_id: string | null;
  metadata_json: string | null;
  sort_order: number;
  created_at: number;
}

interface EpisodeRow {
  id: string;
  content_id: string;
  season_number: number | null;
  episode_number: number | null;
  title: string | null;
  stream_url: string;
  duration: number | null;
}

function rowToContent(row: ContentRow): ContentItem {
  return {
    id: row.id,
    sourceId: row.source_id,
    type: row.type as ContentType,
    title: row.title,
    cleanTitle: row.clean_title ?? undefined,
    groupName: row.group_name ?? undefined,
    streamUrl: row.stream_url,
    logoUrl: row.logo_url ?? undefined,
    tvgId: row.tvg_id ?? undefined,
    metadataJson: row.metadata_json ?? undefined,
    sortOrder: row.sort_order,
    createdAt: row.created_at,
  };
}

function rowToEpisode(row: EpisodeRow): Episode {
  return {
    id: row.id,
    contentId: row.content_id,
    seasonNumber: row.season_number ?? undefined,
    episodeNumber: row.episode_number ?? undefined,
    title: row.title ?? undefined,
    streamUrl: row.stream_url,
    duration: row.duration ?? undefined,
  };
}

// --- Progress callback ---

export interface SyncProgress {
  phase: 'deleting' | 'inserting' | 'indexing' | 'done';
  current: number;
  total: number;
}

export type ProgressCallback = (progress: SyncProgress) => void;

/** Yield to the event loop so Electron can process UI events between batches */
function yieldToEventLoop(): Promise<void> {
  return new Promise((resolve) => setImmediate(resolve));
}

// --- M3U storage ---

export async function storeM3uEntries(
  sourceId: string,
  entries: M3uEntry[],
  onProgress?: ProgressCallback,
): Promise<number> {
  const db = getDb();
  const now = Date.now();

  // 1. Drop FTS triggers to avoid per-row overhead and known transaction bugs
  dropFtsTriggers();

  try {
    // 2. Clear existing content for this source
    onProgress?.({ phase: 'deleting', current: 0, total: entries.length });
    db.exec('PRAGMA foreign_keys = OFF');
    db.prepare('DELETE FROM episodes WHERE content_id IN (SELECT id FROM content WHERE source_id = ?)').run(sourceId);
    db.prepare('DELETE FROM content_fts WHERE content_id IN (SELECT id FROM content WHERE source_id = ?)').run(sourceId);
    db.prepare('DELETE FROM content WHERE source_id = ?').run(sourceId);
    db.exec('PRAGMA foreign_keys = ON');

    // 3. Pre-classify and group entries
    const directEntries: Array<{
      entry: M3uEntry;
      contentType: ContentType;
      cleaned: string;
      group: string;
    }> = [];

    const seriesMap = new Map<string, { contentId: string; showName: string; group: string; logo: string; tvgId: string; entries: M3uEntry[] }>();

    for (const entry of entries) {
      const contentType = classifyEntry(entry);
      const cleaned = cleanTitle(entry.title);
      const group = normalizeCategory(entry.groupTitle);

      if (contentType === 'series') {
        const showName = extractShowName(entry.title);
        const key = `${group}::${showName}`.toLowerCase();

        if (!seriesMap.has(key)) {
          seriesMap.set(key, {
            contentId: randomUUID(),
            showName,
            group,
            logo: entry.tvgLogo,
            tvgId: entry.tvgId,
            entries: [],
          });
        }
        seriesMap.get(key)!.entries.push(entry);
      } else {
        directEntries.push({ entry, contentType, cleaned, group });
      }
    }

    // 4. Prepare statements once
    const insertContent = db.prepare(
      `INSERT INTO content (id, source_id, type, title, clean_title, group_name, stream_url, logo_url, tvg_id, sort_order, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    );

    const insertEpisode = db.prepare(
      `INSERT INTO episodes (id, content_id, season_number, episode_number, title, stream_url, duration)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    );

    // 5. Insert direct entries (live + movies) in batches
    let inserted = 0;
    let sortOrder = 0;
    const totalToInsert = directEntries.length + seriesMap.size;

    for (let i = 0; i < directEntries.length; i += BATCH_SIZE) {
      const chunk = directEntries.slice(i, i + BATCH_SIZE);

      const insertBatch = db.transaction(() => {
        for (const { entry, contentType, cleaned, group } of chunk) {
          insertContent.run(
            randomUUID(),
            sourceId,
            contentType,
            entry.title,
            cleaned,
            group || null,
            entry.streamUrl,
            entry.tvgLogo || null,
            entry.tvgId || null,
            sortOrder++,
            now,
          );
        }
      });

      insertBatch();
      inserted += chunk.length;
      onProgress?.({ phase: 'inserting', current: inserted, total: totalToInsert });

      // Yield to event loop every batch so UI stays responsive
      await yieldToEventLoop();
    }

    // 6. Insert series parent entries + episodes in batches
    const seriesEntries = Array.from(seriesMap.values());

    for (let i = 0; i < seriesEntries.length; i += BATCH_SIZE) {
      const chunk = seriesEntries.slice(i, i + BATCH_SIZE);

      const insertSeriesBatch = db.transaction(() => {
        for (const series of chunk) {
          // Insert series parent
          insertContent.run(
            series.contentId,
            sourceId,
            'series',
            series.showName,
            cleanTitle(series.showName),
            series.group || null,
            series.entries[0].streamUrl, // First episode URL as fallback
            series.logo || null,
            series.tvgId || null,
            sortOrder++,
            now,
          );

          // Insert episodes for this series
          for (const ep of series.entries) {
            const se = extractSeasonEpisode(ep.title);
            insertEpisode.run(
              randomUUID(),
              series.contentId,
              se?.season ?? null,
              se?.episode ?? null,
              ep.title,
              ep.streamUrl,
              ep.duration > 0 ? ep.duration : null,
            );
          }
        }
      });

      insertSeriesBatch();
      inserted += chunk.length;
      onProgress?.({ phase: 'inserting', current: inserted, total: totalToInsert });
      await yieldToEventLoop();
    }

    // 7. Rebuild FTS index in one shot (much faster than per-row triggers)
    onProgress?.({ phase: 'indexing', current: 0, total: 1 });
    rebuildFtsIndex(sourceId);
    onProgress?.({ phase: 'done', current: entries.length, total: entries.length });

    const episodeCount = seriesEntries.reduce((sum, s) => sum + s.entries.length, 0);
    log.info(
      `Stored ${entries.length} M3U entries for source ${sourceId} (${seriesMap.size} series with ${episodeCount} episodes)`,
    );
    return entries.length;
  } finally {
    // Always restore triggers even if something fails
    restoreFtsTriggers();
  }
}

// --- Xtream storage ---

export async function storeXtreamContent(
  sourceId: string,
  client: XtreamClient,
  liveStreams: { streams: XtreamLiveStream[]; categories: Map<string, string> },
  vodStreams: { streams: XtreamVodStream[]; categories: Map<string, string> },
  seriesList: { series: XtreamSeriesInfo[]; categories: Map<string, string> },
  onProgress?: ProgressCallback,
): Promise<number> {
  const db = getDb();
  const now = Date.now();

  const totalItems =
    liveStreams.streams.length +
    vodStreams.streams.length +
    seriesList.series.length;

  // 1. Drop FTS triggers
  dropFtsTriggers();

  try {
    // 2. Clear existing content for this source
    onProgress?.({ phase: 'deleting', current: 0, total: totalItems });
    db.exec('PRAGMA foreign_keys = OFF');
    db.prepare('DELETE FROM episodes WHERE content_id IN (SELECT id FROM content WHERE source_id = ?)').run(sourceId);
    db.prepare('DELETE FROM content_fts WHERE content_id IN (SELECT id FROM content WHERE source_id = ?)').run(sourceId);
    db.prepare('DELETE FROM content WHERE source_id = ?').run(sourceId);
    db.exec('PRAGMA foreign_keys = ON');

    const insertContent = db.prepare(
      `INSERT INTO content (id, source_id, type, title, clean_title, group_name, stream_url, logo_url, tvg_id, metadata_json, sort_order, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    );

    let inserted = 0;
    let sortOrder = 0;

    // 3. Insert live streams in batches
    for (let i = 0; i < liveStreams.streams.length; i += BATCH_SIZE) {
      const chunk = liveStreams.streams.slice(i, i + BATCH_SIZE);

      const insertBatch = db.transaction(() => {
        for (const stream of chunk) {
          const category = liveStreams.categories.get(stream.categoryId) ?? '';
          const group = normalizeCategory(category);
          const url = client.buildStreamUrl(stream.streamId, 'live');

          insertContent.run(
            randomUUID(),
            sourceId,
            'live',
            stream.name,
            cleanTitle(stream.name),
            group || null,
            url,
            stream.streamIcon || null,
            stream.epgChannelId || null,
            null,
            sortOrder++,
            now,
          );
        }
      });

      insertBatch();
      inserted += chunk.length;
      onProgress?.({ phase: 'inserting', current: inserted, total: totalItems });
      await yieldToEventLoop();
    }

    // 4. Insert VOD streams in batches
    for (let i = 0; i < vodStreams.streams.length; i += BATCH_SIZE) {
      const chunk = vodStreams.streams.slice(i, i + BATCH_SIZE);

      const insertBatch = db.transaction(() => {
        for (const stream of chunk) {
          const category = vodStreams.categories.get(stream.categoryId) ?? '';
          const group = normalizeCategory(category);
          const url = client.buildStreamUrl(
            stream.streamId,
            'movie',
            stream.containerExtension,
          );

          insertContent.run(
            randomUUID(),
            sourceId,
            'movie',
            stream.name,
            cleanTitle(stream.name),
            group || null,
            url,
            stream.streamIcon || null,
            null,
            stream.rating ? JSON.stringify({ rating: stream.rating }) : null,
            sortOrder++,
            now,
          );
        }
      });

      insertBatch();
      inserted += chunk.length;
      onProgress?.({ phase: 'inserting', current: inserted, total: totalItems });
      await yieldToEventLoop();
    }

    // 5. Insert series in batches (episodes fetched on demand)
    for (let i = 0; i < seriesList.series.length; i += BATCH_SIZE) {
      const chunk = seriesList.series.slice(i, i + BATCH_SIZE);

      const insertBatch = db.transaction(() => {
        for (const series of chunk) {
          const category = seriesList.categories.get(series.categoryId) ?? '';
          const group = normalizeCategory(category);

          const metadata = JSON.stringify({
            seriesId: series.seriesId,
            plot: series.plot || undefined,
            cast: series.cast || undefined,
            director: series.director || undefined,
            genre: series.genre || undefined,
            releaseDate: series.releaseDate || undefined,
            rating: series.rating || undefined,
          });

          insertContent.run(
            randomUUID(),
            sourceId,
            'series',
            series.name,
            cleanTitle(series.name),
            group || null,
            '', // No direct stream URL for series parent
            series.cover || null,
            null,
            metadata,
            sortOrder++,
            now,
          );
        }
      });

      insertBatch();
      inserted += chunk.length;
      onProgress?.({ phase: 'inserting', current: inserted, total: totalItems });
      await yieldToEventLoop();
    }

    // 6. Rebuild FTS index
    onProgress?.({ phase: 'indexing', current: 0, total: 1 });
    rebuildFtsIndex(sourceId);
    onProgress?.({ phase: 'done', current: totalItems, total: totalItems });

    log.info(
      `Stored ${totalItems} Xtream entries for source ${sourceId} (${liveStreams.streams.length} live, ${vodStreams.streams.length} VOD, ${seriesList.series.length} series)`,
    );
    return totalItems;
  } finally {
    restoreFtsTriggers();
  }
}

/** Store episodes for a specific series (fetched on demand from Xtream API) */
export function storeXtreamEpisodes(
  contentId: string,
  client: XtreamClient,
  episodes: Record<string, XtreamSeriesEpisode[]>,
): number {
  const db = getDb();

  // Clear existing episodes for this content
  db.prepare('DELETE FROM episodes WHERE content_id = ?').run(contentId);

  const insert = db.prepare(
    `INSERT INTO episodes (id, content_id, season_number, episode_number, title, stream_url, duration)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  );

  let count = 0;

  const insertAll = db.transaction(() => {
    for (const [seasonNum, eps] of Object.entries(episodes)) {
      for (const ep of eps) {
        const season = ep.info.season ?? (parseInt(seasonNum, 10) || null);
        const streamUrl = client.buildStreamUrl(
          parseInt(ep.id, 10),
          'series',
          ep.containerExtension,
        );

        const durationSecs = ep.info.duration
          ? parseDuration(ep.info.duration)
          : null;

        insert.run(randomUUID(), contentId, season, ep.episodeNum, ep.title, streamUrl, durationSecs);
        count++;
      }
    }
  });

  insertAll();
  return count;
}

function parseDuration(duration: string): number | null {
  // Handle HH:MM:SS format
  const parts = duration.split(':');
  if (parts.length === 3) {
    const h = parseInt(parts[0], 10);
    const m = parseInt(parts[1], 10);
    const s = parseInt(parts[2], 10);
    if (!isNaN(h) && !isNaN(m) && !isNaN(s)) {
      return h * 3600 + m * 60 + s;
    }
  }
  // Try plain seconds
  const secs = parseInt(duration, 10);
  return isNaN(secs) ? null : secs;
}

// --- Sort helpers ---

function sortClause(sort: SortOption): string {
  switch (sort) {
    case 'provider':
      return 'ORDER BY sort_order ASC';
    case 'name-asc':
      return 'ORDER BY COALESCE(clean_title, title) COLLATE NOCASE ASC';
    case 'name-desc':
      return 'ORDER BY COALESCE(clean_title, title) COLLATE NOCASE DESC';
    case 'recent':
      return 'ORDER BY created_at DESC, sort_order ASC';
    case 'group':
      return 'ORDER BY group_name COLLATE NOCASE ASC, COALESCE(clean_title, title) COLLATE NOCASE ASC';
    default:
      return 'ORDER BY sort_order ASC';
  }
}

// --- Query functions ---

export function getContentByType(
  type: ContentType,
  sourceId?: string,
  sort: SortOption = 'provider',
): ContentItem[] {
  const db = getDb();
  const order = sortClause(sort);

  if (sourceId) {
    const rows = db
      .prepare(`SELECT * FROM content WHERE type = ? AND source_id = ? ${order}`)
      .all(type, sourceId) as ContentRow[];
    return rows.map(rowToContent);
  }
  const rows = db
    .prepare(`SELECT * FROM content WHERE type = ? ${order}`)
    .all(type) as ContentRow[];
  return rows.map(rowToContent);
}

export function getCategories(type: ContentType): string[] {
  const db = getDb();
  const rows = db
    .prepare(
      'SELECT DISTINCT group_name FROM content WHERE type = ? AND group_name IS NOT NULL ORDER BY group_name',
    )
    .all(type) as { group_name: string }[];
  return rows.map((r) => r.group_name);
}

function buildFtsQuery(query: string): string {
  return query
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .map((word) => `"${word.replace(/"/g, '""')}"*`)
    .join(' ');
}

export function searchContent(query: string): ContentItem[] {
  const db = getDb();
  try {
    const ftsQuery = buildFtsQuery(query);
    const rows = db
      .prepare(
        `SELECT c.* FROM content c
         WHERE c.id IN (
           SELECT content_id FROM content_fts WHERE content_fts MATCH ?
           ORDER BY rank LIMIT 100
         )
         ORDER BY c.type, c.clean_title, c.title`,
      )
      .all(ftsQuery) as ContentRow[];
    return rows.map(rowToContent);
  } catch {
    // Fallback to LIKE if FTS5 index not available or query is invalid
    const pattern = `%${query}%`;
    const rows = db
      .prepare(
        `SELECT * FROM content WHERE title LIKE ? OR clean_title LIKE ? ORDER BY title LIMIT 100`,
      )
      .all(pattern, pattern) as ContentRow[];
    return rows.map(rowToContent);
  }
}

export function getContentCountByType(): Record<ContentType, number> {
  const db = getDb();
  const rows = db
    .prepare('SELECT type, COUNT(*) as count FROM content GROUP BY type')
    .all() as { type: string; count: number }[];

  const counts: Record<ContentType, number> = { live: 0, movie: 0, series: 0 };
  for (const row of rows) {
    counts[row.type as ContentType] = row.count;
  }
  return counts;
}

export function getEpisodes(contentId: string): Episode[] {
  const db = getDb();
  const rows = db
    .prepare(
      'SELECT * FROM episodes WHERE content_id = ? ORDER BY season_number, episode_number',
    )
    .all(contentId) as EpisodeRow[];
  return rows.map(rowToEpisode);
}
