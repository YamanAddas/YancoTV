import type { ContentItem, ContentType, SortOption } from '@yancotv/core';
import { getDb } from './db';
import { buildFtsQueryAnd, buildFtsQueryOr, sortClause } from './content-queries';

// Paged SQL helpers for the post-M4R shell. ContentPanel and SearchOverlay
// call these instead of loading everything into Zustand. Keeps memory flat
// no matter how many channels a source has.

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

export interface ListOptions {
  type: ContentType;
  limit: number;
  offset: number;
  groupName?: string;
  sourceId?: string;
  sort?: SortOption;
}

export async function listByType(opts: ListOptions): Promise<ContentItem[]> {
  const db = getDb();
  const order = sortClause(opts.sort ?? 'provider');
  const params: (string | number)[] = [opts.type];
  let where = 'type = ?';
  if (opts.sourceId) {
    where += ' AND source_id = ?';
    params.push(opts.sourceId);
  }
  if (opts.groupName) {
    where += ' AND group_name = ?';
    params.push(opts.groupName);
  }
  params.push(opts.limit, opts.offset);
  const res = await db.execute(
    `SELECT * FROM content WHERE ${where} ${order} LIMIT ? OFFSET ?`,
    params,
  );
  return ((res.rows ?? []) as unknown as ContentRow[]).map(rowToContent);
}

export interface GroupCount {
  name: string;
  count: number;
}

// Group counts for the CategoryFilterPanel middle column (M4R.D.2).
// Returns all non-empty `group_name` values for the given content type with
// their row count, ordered alphabetically. Rows with `group_name IS NULL`
// are folded into the caller's "All" bucket via `countByType`.
export async function groupsForType(
  type: ContentType,
  sourceId?: string,
): Promise<GroupCount[]> {
  const db = getDb();
  const params: (string | number)[] = [type];
  let where = "type = ? AND group_name IS NOT NULL AND group_name <> ''";
  if (sourceId) {
    where += ' AND source_id = ?';
    params.push(sourceId);
  }
  const res = await db.execute(
    `SELECT group_name AS name, COUNT(*) AS count FROM content
       WHERE ${where}
     GROUP BY group_name
     ORDER BY group_name COLLATE NOCASE`,
    params,
  );
  return ((res.rows ?? []) as unknown as GroupCount[]).map((r) => ({
    name: r.name,
    count: Number(r.count),
  }));
}

export interface CountOptions {
  type: ContentType;
  groupName?: string;
  sourceId?: string;
}

export async function countByType(opts: CountOptions): Promise<number> {
  const db = getDb();
  const params: (string | number)[] = [opts.type];
  let where = 'type = ?';
  if (opts.sourceId) {
    where += ' AND source_id = ?';
    params.push(opts.sourceId);
  }
  if (opts.groupName) {
    where += ' AND group_name = ?';
    params.push(opts.groupName);
  }
  const res = await db.execute(
    `SELECT COUNT(*) AS n FROM content WHERE ${where}`,
    params,
  );
  const rows = (res.rows ?? []) as unknown as { n: number }[];
  return rows[0]?.n ?? 0;
}

export async function searchFts(
  query: string,
  limit = 60,
): Promise<ContentItem[]> {
  const trimmed = query.trim();
  if (!trimmed) return [];
  const db = getDb();
  const andQuery = buildFtsQueryAnd(trimmed);
  const orQuery = buildFtsQueryOr(trimmed);

  try {
    const and = await db.execute(
      `SELECT c.* FROM content c
         WHERE c.id IN (
           SELECT content_id FROM content_fts WHERE content_fts MATCH ?
           ORDER BY rank LIMIT ?
         )
       ORDER BY COALESCE(c.clean_title, c.title) COLLATE NOCASE`,
      [andQuery, limit],
    );
    let rows = (and.rows ?? []) as unknown as ContentRow[];
    if (rows.length === 0 && orQuery) {
      const or = await db.execute(
        `SELECT c.* FROM content c
           WHERE c.id IN (
             SELECT content_id FROM content_fts WHERE content_fts MATCH ?
             ORDER BY rank LIMIT ?
           )
         ORDER BY COALESCE(c.clean_title, c.title) COLLATE NOCASE`,
        [orQuery, limit],
      );
      rows = (or.rows ?? []) as unknown as ContentRow[];
    }
    return rows.map(rowToContent);
  } catch {
    const pattern = `%${trimmed}%`;
    const res = await db.execute(
      `SELECT * FROM content
         WHERE title LIKE ? OR clean_title LIKE ? OR group_name LIKE ?
       ORDER BY COALESCE(clean_title, title) COLLATE NOCASE LIMIT ?`,
      [pattern, pattern, pattern, limit],
    );
    return ((res.rows ?? []) as unknown as ContentRow[]).map(rowToContent);
  }
}
