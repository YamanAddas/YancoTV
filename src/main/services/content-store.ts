import { randomUUID } from 'crypto';
import log from 'electron-log/main';
import { getDb, dropFtsTriggers, restoreFtsTriggers, rebuildFtsIndex } from './db';
import { classifyEntry } from './content-classifier';
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
import type {
  StalkerClient,
  StalkerChannel,
  StalkerVodItem,
  StalkerSeriesItem,
} from './stalker-client';

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
      const group = entry.groupTitle.trim();

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
      `INSERT INTO content (id, source_id, type, title, clean_title, group_name, stream_url, logo_url, tvg_id, metadata_json, sort_order, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
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
          // Build metadata for catch-up support (M3U sources)
          let m3uMetadata: string | null = null;
          if (entry.catchupType || entry.catchupSource || entry.catchupDays) {
            m3uMetadata = JSON.stringify({
              ...(entry.catchupType && { catchupType: entry.catchupType }),
              ...(entry.catchupSource && { catchupSource: entry.catchupSource }),
              ...(entry.catchupDays && { tvArchiveDuration: entry.catchupDays * 24 }),
            });
          }

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
            m3uMetadata,
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
            null, // metadata_json
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
          const group = category.trim();
          const url = client.buildStreamUrl(stream.streamId, 'live');

          // Store tvArchive info so catch-up service can check availability
          const liveMetadata =
            stream.tvArchive > 0
              ? JSON.stringify({
                  streamId: stream.streamId,
                  tvArchive: stream.tvArchive,
                  tvArchiveDuration: stream.tvArchiveDuration,
                })
              : stream.streamId
                ? JSON.stringify({ streamId: stream.streamId })
                : null;

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
            liveMetadata,
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
          const group = category.trim();
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
          const group = category.trim();

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

  // Multi-source: check if there are multiple sources
  const sourceCount = db.prepare('SELECT COUNT(*) as cnt FROM sources').get() as { cnt: number };
  if (sourceCount.cnt > 1) {
    return getContentByTypeMerged(type, sort);
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

/** Build an FTS5 query where ALL words must match (AND logic, prefix on each word) */
function buildFtsQueryAnd(query: string): string {
  return query
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .map((word) => `"${word.replace(/"/g, '""')}"*`)
    .join(' ');
}

/** Build an FTS5 query where ANY word can match (OR logic, prefix on each word).
 *  Returns empty string when the query has only one word (AND and OR are identical). */
function buildFtsQueryOr(query: string): string {
  const words = query.trim().split(/\s+/).filter(Boolean);
  if (words.length <= 1) return '';
  return words.map((word) => `"${word.replace(/"/g, '""')}"*`).join(' OR ');
}

/** Max results returned per content type from a single search. */
const SEARCH_LIMIT_PER_TYPE = 60;

/**
 * Smart search: runs separate per-type queries so Live TV results can't crowd
 * out Movies/Series. Within each type it tries:
 *   1. FTS5 AND (all words must match) — precise, ranked by relevance
 *   2. FTS5 OR  (any word matches)    — broader, still ranked
 *   3. LIKE fallback                  — handles corrupted/empty FTS index
 */
export function searchContent(query: string): ContentItem[] {
  const db = getDb();
  const types: ContentType[] = ['live', 'movie', 'series'];
  const allResults: ContentItem[] = [];

  const andQuery = buildFtsQueryAnd(query);
  const orQuery = buildFtsQueryOr(query);

  const ftsStmt = db.prepare(
    `SELECT c.* FROM content c
     WHERE c.type = ? AND c.id IN (
       SELECT content_id FROM content_fts WHERE content_fts MATCH ?
       ORDER BY rank LIMIT ?
     )
     ORDER BY COALESCE(c.clean_title, c.title) COLLATE NOCASE`,
  );

  const likeStmt = db.prepare(
    `SELECT * FROM content
     WHERE type = ? AND (title LIKE ? OR clean_title LIKE ? OR group_name LIKE ?)
     ORDER BY COALESCE(clean_title, title) COLLATE NOCASE
     LIMIT ?`,
  );

  for (const type of types) {
    try {
      // 1. Try AND: every word must appear
      let rows = ftsStmt.all(type, andQuery, SEARCH_LIMIT_PER_TYPE) as ContentRow[];

      // 2. OR fallback: at least one word must appear
      if (rows.length === 0 && orQuery) {
        rows = ftsStmt.all(type, orQuery, SEARCH_LIMIT_PER_TYPE) as ContentRow[];
      }

      allResults.push(...rows.map(rowToContent));
    } catch {
      // 3. FTS unavailable / corrupt query — fall back to LIKE
      const pattern = `%${query.trim()}%`;
      const rows = likeStmt.all(
        type, pattern, pattern, pattern, SEARCH_LIMIT_PER_TYPE,
      ) as ContentRow[];
      allResults.push(...rows.map(rowToContent));
    }
  }

  return allResults;
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

/** Find content by ID */
export function getContentById(id: string): ContentItem | null {
  const db = getDb();
  const row = db
    .prepare('SELECT * FROM content WHERE id = ?')
    .get(id) as ContentRow | undefined;
  return row ? rowToContent(row) : null;
}

/** Get related content: same group + same source (different group), capped at 20 each */
export function getRelatedContent(
  id: string,
  groupName?: string,
  sourceId?: string,
): { sameGroup: ContentItem[]; sameSource: ContentItem[] } {
  const db = getDb();
  const sameGroup: ContentItem[] = [];
  const sameSource: ContentItem[] = [];

  if (groupName) {
    const rows = db
      .prepare(
        `SELECT * FROM content
         WHERE group_name = ? AND id != ? AND type IN ('movie', 'series')
         ORDER BY COALESCE(clean_title, title) COLLATE NOCASE ASC
         LIMIT 20`,
      )
      .all(groupName, id) as ContentRow[];
    sameGroup.push(...rows.map(rowToContent));
  }

  if (sourceId) {
    const rows = db
      .prepare(
        `SELECT * FROM content
         WHERE source_id = ? AND id != ? AND type IN ('movie', 'series')
           AND (group_name IS NULL OR group_name != ?)
         ORDER BY COALESCE(clean_title, title) COLLATE NOCASE ASC
         LIMIT 20`,
      )
      .all(sourceId, id, groupName ?? '') as ContentRow[];
    sameSource.push(...rows.map(rowToContent));
  }

  return { sameGroup, sameSource };
}

/** Find a live channel by its tvgId (for catch-up URL building) */
export function getContentByTvgId(tvgId: string): ContentItem | null {
  const db = getDb();
  const row = db
    .prepare('SELECT * FROM content WHERE tvg_id = ? AND type = ? LIMIT 1')
    .get(tvgId, 'live') as ContentRow | undefined;
  return row ? rowToContent(row) : null;
}

// --- Stalker storage ---

export async function storeStalkerContent(
  sourceId: string,
  client: StalkerClient,
  liveChannels: { channels: StalkerChannel[]; categories: Map<string, string> },
  vodItems: { items: StalkerVodItem[]; categories: Map<string, string> },
  seriesList: { series: StalkerSeriesItem[]; categories: Map<string, string> },
  onProgress?: ProgressCallback,
): Promise<number> {
  const db = getDb();
  const now = Date.now();

  const totalItems =
    liveChannels.channels.length +
    vodItems.items.length +
    seriesList.series.length;

  dropFtsTriggers();

  try {
    // Clear existing content for this source
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

    // Insert live channels
    for (let i = 0; i < liveChannels.channels.length; i += BATCH_SIZE) {
      const chunk = liveChannels.channels.slice(i, i + BATCH_SIZE);

      const insertBatch = db.transaction(() => {
        for (const ch of chunk) {
          const category = liveChannels.categories.get(ch.tvGenreId) ?? '';
          const group = category.trim();
          const streamUrl = client.buildStreamUrl(ch.cmd);

          const metadata =
            ch.tvArchive > 0
              ? JSON.stringify({
                  stalkerId: ch.id,
                  tvArchive: ch.tvArchive,
                  tvArchiveDuration: ch.tvArchiveDuration,
                })
              : ch.id
                ? JSON.stringify({ stalkerId: ch.id })
                : null;

          insertContent.run(
            randomUUID(),
            sourceId,
            'live',
            ch.name,
            cleanTitle(ch.name),
            group || null,
            streamUrl,
            ch.logo || null,
            ch.epgId || null,
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

    // Insert VOD items
    for (let i = 0; i < vodItems.items.length; i += BATCH_SIZE) {
      const chunk = vodItems.items.slice(i, i + BATCH_SIZE);

      const insertBatch = db.transaction(() => {
        for (const vod of chunk) {
          const category = vodItems.categories.get(vod.categoryId) ?? '';
          const group = category.trim();
          const streamUrl = client.buildStreamUrl(vod.cmd);

          insertContent.run(
            randomUUID(),
            sourceId,
            'movie',
            vod.name,
            cleanTitle(vod.name),
            group || null,
            streamUrl,
            vod.logo || null,
            null,
            vod.description ? JSON.stringify({ description: vod.description }) : null,
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

    // Insert series
    for (let i = 0; i < seriesList.series.length; i += BATCH_SIZE) {
      const chunk = seriesList.series.slice(i, i + BATCH_SIZE);

      const insertBatch = db.transaction(() => {
        for (const series of chunk) {
          const category = seriesList.categories.get(series.categoryId) ?? '';
          const group = category.trim();

          const metadata = JSON.stringify({
            stalkerId: series.id,
            plot: series.plot || undefined,
            genre: series.genre || undefined,
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

    // Rebuild FTS index
    onProgress?.({ phase: 'indexing', current: 0, total: 1 });
    rebuildFtsIndex(sourceId);
    onProgress?.({ phase: 'done', current: totalItems, total: totalItems });

    log.info(
      `Stored ${totalItems} Stalker entries for source ${sourceId} (${liveChannels.channels.length} live, ${vodItems.items.length} VOD, ${seriesList.series.length} series)`,
    );
    return totalItems;
  } finally {
    restoreFtsTriggers();
  }
}

// --- Multi-source merge with dedup ---

/**
 * Get content across all sources with deduplication by stream_url.
 * When multiple sources have the same stream URL, the one from the
 * highest-priority source (lowest priority number) wins.
 */
export function getContentByTypeMerged(
  type: ContentType,
  sort: SortOption = 'provider',
): ContentItem[] {
  const db = getDb();
  const order = sortClause(sort);

  // Fetch all content for this type, ordered by source priority first
  const rows = db
    .prepare(
      `SELECT c.* FROM content c
       JOIN sources s ON c.source_id = s.id
       WHERE c.type = ?
       ORDER BY s.priority ASC, c.${order.replace('ORDER BY ', '')}`,
    )
    .all(type) as ContentRow[];

  // Dedup: first occurrence wins (from lowest-priority-number source)
  const seen = new Set<string>();
  const deduped: ContentItem[] = [];
  for (const row of rows) {
    const key = row.stream_url;
    // Skip dedup for empty stream URLs (series parents)
    if (key && seen.has(key)) continue;
    if (key) seen.add(key);
    deduped.push(rowToContent(row));
  }
  return deduped;
}
