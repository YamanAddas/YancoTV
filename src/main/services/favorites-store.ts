import { v4 as uuid } from 'uuid';
import { getDb } from './db';
import type { ContentItem, ContentType } from '../../shared/types';

interface FavoriteWithContent {
  id: string;
  content_id: string;
  added_at: number;
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

export interface FavoriteEntry {
  favoriteId: string;
  addedAt: number;
  content: ContentItem;
}

function rowToFavorite(row: FavoriteWithContent): FavoriteEntry {
  return {
    favoriteId: row.id,
    addedAt: row.added_at,
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

export function getFavorites(): FavoriteEntry[] {
  const db = getDb();
  const rows = db
    .prepare(
      `SELECT f.id, f.content_id, f.added_at,
              c.source_id, c.type, c.title, c.clean_title, c.group_name,
              c.stream_url, c.logo_url, c.tvg_id, c.metadata_json, c.created_at
       FROM favorites f
       JOIN content c ON c.id = f.content_id
       ORDER BY f.added_at DESC`,
    )
    .all() as FavoriteWithContent[];
  return rows.map(rowToFavorite);
}

export function getFavoriteIds(): string[] {
  const db = getDb();
  const rows = db.prepare('SELECT content_id FROM favorites').all() as { content_id: string }[];
  return rows.map((r) => r.content_id);
}

export function isFavorite(contentId: string): boolean {
  const db = getDb();
  const row = db
    .prepare('SELECT id FROM favorites WHERE content_id = ?')
    .get(contentId) as { id: string } | undefined;
  return row !== undefined;
}

export function addFavorite(contentId: string): { ok: true; id: string } | { ok: false; error: string } {
  const db = getDb();
  if (isFavorite(contentId)) {
    return { ok: false, error: 'Already a favorite' };
  }
  const id = uuid();
  db.prepare('INSERT INTO favorites (id, content_id, added_at) VALUES (?, ?, ?)').run(
    id,
    contentId,
    Date.now(),
  );
  return { ok: true, id };
}

export function removeFavorite(contentId: string): { ok: boolean } {
  const db = getDb();
  db.prepare('DELETE FROM favorites WHERE content_id = ?').run(contentId);
  return { ok: true };
}
