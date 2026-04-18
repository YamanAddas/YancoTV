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

const INSERT_BATCH_SIZE = 500;

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

/**
 * Atomically replace all content for a source. Episodes are inserted in the
 * same transaction so the source either has the new catalog or the old one,
 * never a half-mutated state.
 */
export async function replaceSourceContent(
  sourceId: string,
  items: ContentItem[],
  episodesByContentId: Record<string, Episode[]> = {},
): Promise<void> {
  const db = getDb();

  await db.transaction(async (tx) => {
    await tx.execute(
      'DELETE FROM episodes WHERE content_id IN (SELECT id FROM content WHERE source_id = ?)',
      [sourceId],
    );
    await tx.execute('DELETE FROM content WHERE source_id = ?', [sourceId]);

    for (let i = 0; i < items.length; i += INSERT_BATCH_SIZE) {
      const chunk = items.slice(i, i + INSERT_BATCH_SIZE);
      for (const item of chunk) {
        await tx.execute(
          `INSERT INTO content
             (id, source_id, type, title, clean_title, group_name,
              stream_url, logo_url, tvg_id, metadata_json, sort_order, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
          [
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
          ],
        );
      }
    }

    for (const [contentId, episodes] of Object.entries(episodesByContentId)) {
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
    }
  });
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
