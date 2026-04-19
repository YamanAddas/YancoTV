import type { FavoritesAdapter } from '@yancotv/core';
import { getDb } from './db';

/**
 * op-sqlite-backed adapter for core's `createFavoritesStore`.
 *
 * The core factory owns the in-memory `Set<string>` of favorite IDs and
 * drives `toggle()`; this adapter just persists each change. Desktop uses
 * the same shape via IPC against a synchronous better-sqlite3 handle — here
 * we're async against op-sqlite but the contract is identical.
 */

function makeFavoriteId(): string {
  return `fav_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
}

export const sqliteFavoritesAdapter: FavoritesAdapter = {
  async getIds(): Promise<string[]> {
    const db = getDb();
    const res = await db.execute('SELECT content_id FROM favorites');
    const rows = (res.rows ?? []) as unknown as { content_id: string }[];
    return rows.map((r) => r.content_id);
  },

  async add(contentId: string): Promise<void> {
    const db = getDb();
    // Match desktop's "unique per content" constraint — skip if already
    // present rather than erroring, so toggle() stays idempotent.
    const existing = await db.execute(
      'SELECT id FROM favorites WHERE content_id = ?',
      [contentId],
    );
    if ((existing.rows ?? []).length > 0) return;
    await db.execute(
      'INSERT INTO favorites (id, content_id, added_at) VALUES (?, ?, ?)',
      [makeFavoriteId(), contentId, Date.now()],
    );
  },

  async remove(contentId: string): Promise<void> {
    const db = getDb();
    await db.execute('DELETE FROM favorites WHERE content_id = ?', [contentId]);
  },
};
