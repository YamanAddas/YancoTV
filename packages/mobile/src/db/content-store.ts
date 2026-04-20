import type {
  ContentItem,
  ContentType,
  Episode,
  SortOption,
} from '@yancotv/core';
import { getDb } from './db';
import {
  buildFtsQueryAnd,
  buildFtsQueryOr,
  sortClause,
} from './content-queries';

/**
 * Mobile content-store.
 *
 * Mirrors the desktop service module (`src/main/services/content-store.ts`)
 * but runs against op-sqlite. All writes live behind `replaceSourceContent` —
 * sources-store drives sync and hands us a fresh `ContentItem[]`; we wipe
 * the source's content and re-insert. Matches the desktop "drop + bulk
 * insert" pattern, minus the FTS-trigger drop/restore dance (op-sqlite's
 * trigger overhead is tolerable for the scale we hit on-device).
 */

// executeBatch takes `[sql, [[p1,p2,...],[p1,p2,...]]]` — one prepared
// statement reused across N parameter rows inside a single JS↔native
// round-trip. Each `db.executeBatch(...)` is its own implicit BEGIN/COMMIT
// (one fsync per call with `synchronous=NORMAL` WAL).
//
// BATCH_ROWS is the JSI payload cap per call. One giant batch with all
// ~20k rows crashed the app (2026-04-19 — ANR watchdog on Fire TV during
// the op-sqlite JS-side `sanitizeArrayBuffersInArray` walk). 1000 is a
// comfortable headroom: ~12k scalars per call, ~20 calls for a 20k-row
// catalog, each yielding the JS thread at its `await`.
const BATCH_ROWS = 1000;

type Scalar = string | number | boolean | null;

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

// --- Writes ---

/** Wipe every content/episode row belonging to `sourceId`. */
export async function clearSourceContent(sourceId: string): Promise<void> {
  const db = getDb();
  await db.transaction(async (tx) => {
    // episodes first so the FK cascade isn't the thing doing the work
    await tx.execute(
      'DELETE FROM episodes WHERE content_id IN (SELECT id FROM content WHERE source_id = ?)',
      [sourceId],
    );
    await tx.execute('DELETE FROM content WHERE source_id = ?', [sourceId]);
  });
}

export interface ReplaceProgress {
  phase: 'wiping' | 'content' | 'episodes' | 'index';
  done: number;
  total: number;
}

// FTS triggers defined in migration 002. We drop them during bulk insert
// and recreate on the way out — otherwise every content insert fires an
// FTS insert and the segmented FTS5 index becomes the dominant cost
// (~50% of persist wall time on a 20k-row Xtream catalog). The recreate
// mirrors the migration body exactly; keep them in sync.
const CREATE_TRIGGER_AI = `CREATE TRIGGER IF NOT EXISTS content_ai AFTER INSERT ON content BEGIN
  INSERT INTO content_fts (content_id, title, clean_title, group_name)
  VALUES (new.id, new.title, new.clean_title, new.group_name);
END`;
const CREATE_TRIGGER_AD = `CREATE TRIGGER IF NOT EXISTS content_ad AFTER DELETE ON content BEGIN
  DELETE FROM content_fts WHERE content_id = old.id;
END`;
const CREATE_TRIGGER_AU = `CREATE TRIGGER IF NOT EXISTS content_au AFTER UPDATE ON content BEGIN
  DELETE FROM content_fts WHERE content_id = old.id;
  INSERT INTO content_fts (content_id, title, clean_title, group_name)
  VALUES (new.id, new.title, new.clean_title, new.group_name);
END`;

/**
 * Replace all content for a source. Fast path — drops FTS triggers so
 * per-row INSERTs don't fight an FTS5 segment merge, chunks content + FTS
 * into `executeBatch` calls of BATCH_ROWS, then restores triggers in a
 * `finally`. CREATE TRIGGER IF NOT EXISTS means a crash mid-persist leaves
 * the DB recoverable on next call; the trigger migration already ran, and
 * the next replace() call re-drops (no-op if missing) before its own work.
 *
 * Durability is per-chunk: each executeBatch is its own BEGIN/COMMIT with
 * `synchronous=NORMAL` on WAL. A mid-persist kill leaves the source with
 * the inserted prefix; the next sync wipes and redoes it.
 */
export async function replaceSourceContent(
  sourceId: string,
  items: ContentItem[],
  episodesByContentId: Record<string, Episode[]> = {},
  onProgress?: (p: ReplaceProgress) => void,
): Promise<void> {
  const db = getDb();

  onProgress?.({ phase: 'wiping', done: 0, total: 1 });
  // One batch: drop triggers + wipe FTS rows for this source + wipe episodes
  // + wipe content. FTS wipe must happen while the AFTER DELETE trigger is
  // already gone (otherwise the trigger's own DELETE races against ours).
  await db.executeBatch([
    ['DROP TRIGGER IF EXISTS content_ai'],
    ['DROP TRIGGER IF EXISTS content_ad'],
    ['DROP TRIGGER IF EXISTS content_au'],
    [
      'DELETE FROM content_fts WHERE content_id IN (SELECT id FROM content WHERE source_id = ?)',
      [sourceId],
    ],
    [
      'DELETE FROM episodes WHERE content_id IN (SELECT id FROM content WHERE source_id = ?)',
      [sourceId],
    ],
    ['DELETE FROM content WHERE source_id = ?', [sourceId]],
  ]);
  onProgress?.({ phase: 'wiping', done: 1, total: 1 });

  try {
    if (items.length > 0) {
      const contentSql = `INSERT INTO content
           (id, source_id, type, title, clean_title, group_name,
            stream_url, logo_url, tvg_id, metadata_json, sort_order, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`;
      for (let i = 0; i < items.length; i += BATCH_ROWS) {
        const chunk = items.slice(i, i + BATCH_ROWS);
        const rows: Scalar[][] = chunk.map((item) => [
          item.id,
          item.sourceId,
          item.type,
          item.title,
          item.cleanTitle ?? null,
          item.groupName ?? null,
          item.streamUrl,
          item.logoUrl ?? null,
          item.tvgId ?? null,
          item.metadataJson ?? null,
          item.sortOrder,
          item.createdAt,
        ]);
        await db.executeBatch([[contentSql, rows]]);
        onProgress?.({
          phase: 'content',
          done: Math.min(i + BATCH_ROWS, items.length),
          total: items.length,
        });
      }

      // Rebuild the FTS index for this source in bulk, trigger-free.
      const ftsSql = `INSERT INTO content_fts
           (content_id, title, clean_title, group_name)
         VALUES (?, ?, ?, ?)`;
      for (let i = 0; i < items.length; i += BATCH_ROWS) {
        const chunk = items.slice(i, i + BATCH_ROWS);
        const rows: Scalar[][] = chunk.map((item) => [
          item.id,
          item.title,
          item.cleanTitle ?? null,
          item.groupName ?? null,
        ]);
        await db.executeBatch([[ftsSql, rows]]);
        onProgress?.({
          phase: 'index',
          done: Math.min(i + BATCH_ROWS, items.length),
          total: items.length,
        });
      }
    }

    const episodeRows: Scalar[][] = [];
    for (const [contentId, episodes] of Object.entries(episodesByContentId)) {
      for (const ep of episodes) {
        episodeRows.push([
          ep.id,
          contentId,
          ep.seasonNumber ?? null,
          ep.episodeNumber ?? null,
          ep.title ?? null,
          ep.streamUrl,
          ep.duration ?? null,
        ]);
      }
    }
    if (episodeRows.length > 0) {
      const episodeSql = `INSERT INTO episodes
           (id, content_id, season_number, episode_number, title, stream_url, duration)
         VALUES (?, ?, ?, ?, ?, ?, ?)`;
      for (let i = 0; i < episodeRows.length; i += BATCH_ROWS) {
        const chunk = episodeRows.slice(i, i + BATCH_ROWS);
        await db.executeBatch([[episodeSql, chunk]]);
        onProgress?.({
          phase: 'episodes',
          done: Math.min(i + BATCH_ROWS, episodeRows.length),
          total: episodeRows.length,
        });
      }
    }
  } finally {
    // Always restore triggers — including on throw, so a partial persist
    // doesn't leave subsequent inserts silently out of the FTS index.
    await db.executeBatch([
      [CREATE_TRIGGER_AI],
      [CREATE_TRIGGER_AD],
      [CREATE_TRIGGER_AU],
    ]);
  }
}

/** Merge a partial metadata JSON patch into an existing content row. */
export async function patchContentMetadata(
  contentId: string,
  patch: Record<string, unknown>,
): Promise<void> {
  const db = getDb();
  const current = await getContentById(contentId);
  if (!current) return;
  let existing: Record<string, unknown> = {};
  if (current.metadataJson) {
    try {
      existing = JSON.parse(current.metadataJson) as Record<string, unknown>;
    } catch {
      existing = {};
    }
  }
  const merged = JSON.stringify({ ...existing, ...patch });
  await db.execute('UPDATE content SET metadata_json = ? WHERE id = ?', [
    merged,
    contentId,
  ]);
}

/** Replace all episodes belonging to a single content row. */
export async function replaceEpisodes(
  contentId: string,
  episodes: Episode[],
): Promise<void> {
  const db = getDb();
  await db.transaction(async (tx) => {
    await tx.execute('DELETE FROM episodes WHERE content_id = ?', [contentId]);
    for (const ep of episodes) {
      await tx.execute(
        `INSERT INTO episodes
           (id, content_id, season_number, episode_number, title, stream_url, duration)
         VALUES (?, ?, ?, ?, ?, ?, ?)`,
        [
          ep.id,
          contentId,
          ep.seasonNumber ?? null,
          ep.episodeNumber ?? null,
          ep.title ?? null,
          ep.streamUrl,
          ep.duration ?? null,
        ],
      );
    }
  });
}

// --- Queries ---

function asContentRows(rows: unknown): ContentRow[] {
  return (rows ?? []) as ContentRow[];
}

function asEpisodeRows(rows: unknown): EpisodeRow[] {
  return (rows ?? []) as EpisodeRow[];
}

export async function getContentByType(
  type: ContentType,
  sourceId?: string,
  sort: SortOption = 'provider',
): Promise<ContentItem[]> {
  const db = getDb();
  const order = sortClause(sort);
  if (sourceId) {
    const res = await db.execute(
      `SELECT * FROM content WHERE type = ? AND source_id = ? ${order}`,
      [type, sourceId],
    );
    return asContentRows(res.rows).map(rowToContent);
  }
  const res = await db.execute(
    `SELECT * FROM content WHERE type = ? ${order}`,
    [type],
  );
  return asContentRows(res.rows).map(rowToContent);
}

export async function getContentBySource(
  sourceId: string,
): Promise<ContentItem[]> {
  const db = getDb();
  const res = await db.execute(
    'SELECT * FROM content WHERE source_id = ? ORDER BY sort_order ASC',
    [sourceId],
  );
  return asContentRows(res.rows).map(rowToContent);
}

export async function getContentById(
  id: string,
): Promise<ContentItem | null> {
  const db = getDb();
  const res = await db.execute('SELECT * FROM content WHERE id = ?', [id]);
  const row = asContentRows(res.rows)[0];
  return row ? rowToContent(row) : null;
}

export async function getContentByTvgId(
  tvgId: string,
): Promise<ContentItem | null> {
  const db = getDb();
  const res = await db.execute(
    "SELECT * FROM content WHERE tvg_id = ? AND type = 'live' LIMIT 1",
    [tvgId],
  );
  const row = asContentRows(res.rows)[0];
  return row ? rowToContent(row) : null;
}

export async function getCategories(type: ContentType): Promise<string[]> {
  const db = getDb();
  const res = await db.execute(
    'SELECT DISTINCT group_name FROM content WHERE type = ? AND group_name IS NOT NULL ORDER BY group_name',
    [type],
  );
  const rows = (res.rows ?? []) as unknown as { group_name: string }[];
  return rows.map((r) => r.group_name);
}

export async function getContentCountByType(): Promise<Record<ContentType, number>> {
  const db = getDb();
  const res = await db.execute(
    'SELECT type, COUNT(*) AS count FROM content GROUP BY type',
  );
  const counts: Record<ContentType, number> = { live: 0, movie: 0, series: 0 };
  const rows = (res.rows ?? []) as unknown as { type: string; count: number }[];
  for (const row of rows) {
    counts[row.type as ContentType] = row.count;
  }
  return counts;
}

export async function getEpisodes(contentId: string): Promise<Episode[]> {
  const db = getDb();
  const res = await db.execute(
    'SELECT * FROM episodes WHERE content_id = ? ORDER BY season_number, episode_number',
    [contentId],
  );
  return asEpisodeRows(res.rows).map(rowToEpisode);
}

/** Max results returned per content type from a single search. */
const SEARCH_LIMIT_PER_TYPE = 60;

/**
 * Per-type search so Live TV results can't crowd out Movies/Series. Within
 * each type it tries FTS5 AND, then FTS5 OR, then a LIKE fallback — matching
 * desktop's behavior.
 */
export async function searchContent(query: string): Promise<ContentItem[]> {
  const db = getDb();
  const types: ContentType[] = ['live', 'movie', 'series'];
  const andQuery = buildFtsQueryAnd(query);
  const orQuery = buildFtsQueryOr(query);

  let ftsAvailable = true;
  try {
    await db.execute('SELECT 1 FROM content_fts LIMIT 0');
  } catch {
    ftsAvailable = false;
  }

  const results: ContentItem[] = [];

  for (const type of types) {
    let rows: ContentRow[] = [];
    let fellBack = !ftsAvailable;

    if (ftsAvailable) {
      try {
        const and = await db.execute(
          `SELECT c.* FROM content c
             WHERE c.type = ? AND c.id IN (
               SELECT content_id FROM content_fts WHERE content_fts MATCH ?
               ORDER BY rank LIMIT ?
             )
           ORDER BY COALESCE(c.clean_title, c.title) COLLATE NOCASE`,
          [type, andQuery, SEARCH_LIMIT_PER_TYPE],
        );
        rows = asContentRows(and.rows);

        if (rows.length === 0 && orQuery) {
          const or = await db.execute(
            `SELECT c.* FROM content c
               WHERE c.type = ? AND c.id IN (
                 SELECT content_id FROM content_fts WHERE content_fts MATCH ?
                 ORDER BY rank LIMIT ?
               )
             ORDER BY COALESCE(c.clean_title, c.title) COLLATE NOCASE`,
            [type, orQuery, SEARCH_LIMIT_PER_TYPE],
          );
          rows = asContentRows(or.rows);
        }
      } catch {
        fellBack = true;
      }
    }

    if (fellBack) {
      const pattern = `%${query.trim()}%`;
      const like = await db.execute(
        `SELECT * FROM content
           WHERE type = ? AND (title LIKE ? OR clean_title LIKE ? OR group_name LIKE ?)
         ORDER BY COALESCE(clean_title, title) COLLATE NOCASE
         LIMIT ?`,
        [type, pattern, pattern, pattern, SEARCH_LIMIT_PER_TYPE],
      );
      rows = asContentRows(like.rows);
    }

    for (const row of rows) results.push(rowToContent(row));
  }

  return results;
}
