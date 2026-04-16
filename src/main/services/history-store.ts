import { v4 as uuid } from 'uuid';
import { getDb } from './db';
import type { ContentType, HistoryEntry } from '../../shared/types';
export type { HistoryEntry } from '../../shared/types';

interface HistoryRow {
  id: string;
  content_id: string;
  episode_id: string | null;
  position_seconds: number;
  duration_seconds: number | null;
  watched_at: number;
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

function rowToEntry(row: HistoryRow): HistoryEntry {
  return {
    id: row.id,
    contentId: row.content_id,
    episodeId: row.episode_id ?? undefined,
    positionSeconds: row.position_seconds,
    durationSeconds: row.duration_seconds ?? undefined,
    watchedAt: row.watched_at,
    content: {
      id: row.content_id,
      sourceId: row.source_id,
      type: row.type as ContentType,
      title: row.title,
      cleanTitle: row.clean_title ?? undefined,
      groupName: row.group_name ?? undefined,
      streamUrl: row.stream_url,
      logoUrl: row.logo_url ?? undefined,
      tvgId: row.tvg_id ?? undefined,
      metadataJson: row.metadata_json ?? undefined,
      sortOrder: row.sort_order ?? 0,
      createdAt: row.created_at,
    },
  };
}

export function getRecentlyWatched(limit = 20): HistoryEntry[] {
  const db = getDb();
  // One entry per content+episode combination, most recent first
  const rows = db
    .prepare(
      `SELECT wh.id, wh.content_id, wh.episode_id, wh.position_seconds, wh.duration_seconds, wh.watched_at,
              c.source_id, c.type, c.title, c.clean_title, c.group_name,
              c.stream_url, c.logo_url, c.tvg_id, c.metadata_json, c.created_at
       FROM watch_history wh
       JOIN content c ON c.id = wh.content_id
       WHERE wh.watched_at = (
         SELECT MAX(wh2.watched_at) FROM watch_history wh2
         WHERE wh2.content_id = wh.content_id
           AND (wh2.episode_id = wh.episode_id OR (wh2.episode_id IS NULL AND wh.episode_id IS NULL))
       )
       GROUP BY wh.content_id, wh.episode_id
       ORDER BY wh.watched_at DESC
       LIMIT ?`,
    )
    .all(limit) as HistoryRow[];
  return rows.map(rowToEntry);
}

export function getLastPosition(
  contentId: string,
  episodeId?: string,
): { positionSeconds: number; durationSeconds?: number } | null {
  const db = getDb();
  let row: { position_seconds: number; duration_seconds: number | null } | undefined;

  if (episodeId) {
    row = db
      .prepare(
        `SELECT position_seconds, duration_seconds FROM watch_history
         WHERE content_id = ? AND episode_id = ?
         ORDER BY watched_at DESC LIMIT 1`,
      )
      .get(contentId, episodeId) as
      | { position_seconds: number; duration_seconds: number | null }
      | undefined;
  } else {
    row = db
      .prepare(
        `SELECT position_seconds, duration_seconds FROM watch_history
         WHERE content_id = ? AND episode_id IS NULL
         ORDER BY watched_at DESC LIMIT 1`,
      )
      .get(contentId) as
      | { position_seconds: number; duration_seconds: number | null }
      | undefined;
  }

  if (!row) return null;
  return {
    positionSeconds: row.position_seconds,
    durationSeconds: row.duration_seconds ?? undefined,
  };
}

/** Fetch watch positions for all episodes of a given content in one query. */
export function getPositionsBatch(
  contentId: string,
  episodeIds: string[],
): Record<string, { positionSeconds: number; durationSeconds?: number }> {
  if (episodeIds.length === 0) return {};
  const db = getDb();
  // Use a subquery to get the latest position per episode
  const placeholders = episodeIds.map(() => '?').join(',');
  const rows = db
    .prepare(
      `SELECT episode_id, position_seconds, duration_seconds
       FROM watch_history
       WHERE content_id = ? AND episode_id IN (${placeholders})
         AND watched_at = (
           SELECT MAX(wh2.watched_at) FROM watch_history wh2
           WHERE wh2.content_id = watch_history.content_id
             AND wh2.episode_id = watch_history.episode_id
         )`,
    )
    .all(contentId, ...episodeIds) as Array<{
    episode_id: string;
    position_seconds: number;
    duration_seconds: number | null;
  }>;

  const result: Record<string, { positionSeconds: number; durationSeconds?: number }> = {};
  for (const row of rows) {
    if (row.position_seconds > 0) {
      result[row.episode_id] = {
        positionSeconds: row.position_seconds,
        durationSeconds: row.duration_seconds ?? undefined,
      };
    }
  }
  return result;
}

export function recordWatch(contentId: string, episodeId?: string): string {
  const db = getDb();
  const id = uuid();
  db.prepare(
    `INSERT INTO watch_history (id, content_id, episode_id, position_seconds, watched_at)
     VALUES (?, ?, ?, 0, ?)`,
  ).run(id, contentId, episodeId ?? null, Date.now());
  return id;
}

export function updatePosition(
  historyId: string,
  positionSeconds: number,
  durationSeconds?: number,
): void {
  const db = getDb();
  db.prepare(
    `UPDATE watch_history
     SET position_seconds = ?, duration_seconds = COALESCE(?, duration_seconds), watched_at = ?
     WHERE id = ?`,
  ).run(positionSeconds, durationSeconds ?? null, Date.now(), historyId);
}

export function removeHistoryEntry(id: string): void {
  const db = getDb();
  db.prepare('DELETE FROM watch_history WHERE id = ?').run(id);
}

export function clearHistory(): void {
  const db = getDb();
  db.prepare('DELETE FROM watch_history').run();
}
