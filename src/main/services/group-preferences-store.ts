/**
 * Group preferences store — persists user customizations for the smart groups
 * menu (sort order, visibility, pinning, custom names).
 *
 * SQLite-backed, accessed from the main process only.
 */

import { v4 as uuid } from 'uuid';
import { getDb } from './db';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface GroupPreference {
  id: string;
  contentType: string;
  groupKey: string;
  sortOrder: number;
  isHidden: boolean;
  isPinned: boolean;
  customName: string | null;
  createdAt: number;
}

export interface SetGroupPreferenceInput {
  contentType: string;
  groupKey: string;
  sortOrder?: number;
  isHidden?: boolean;
  isPinned?: boolean;
  customName?: string | null;
}

// ---------------------------------------------------------------------------
// Row mapping
// ---------------------------------------------------------------------------

interface GroupPrefRow {
  id: string;
  content_type: string;
  group_key: string;
  sort_order: number;
  is_hidden: number;
  is_pinned: number;
  custom_name: string | null;
  created_at: number;
}

function rowToPref(row: GroupPrefRow): GroupPreference {
  return {
    id: row.id,
    contentType: row.content_type,
    groupKey: row.group_key,
    sortOrder: row.sort_order,
    isHidden: row.is_hidden === 1,
    isPinned: row.is_pinned === 1,
    customName: row.custom_name,
    createdAt: row.created_at,
  };
}

// ---------------------------------------------------------------------------
// CRUD
// ---------------------------------------------------------------------------

/** Get all group preferences for a content type */
export function getGroupPreferences(contentType: string): GroupPreference[] {
  const db = getDb();
  const rows = db
    .prepare('SELECT * FROM group_preferences WHERE content_type = ? ORDER BY sort_order ASC')
    .all(contentType) as GroupPrefRow[];
  return rows.map(rowToPref);
}

/** Upsert a group preference (insert or update on conflict) */
export function setGroupPreference(input: SetGroupPreferenceInput): GroupPreference {
  const db = getDb();
  const now = Date.now();

  // Check if it already exists
  const existing = db
    .prepare('SELECT * FROM group_preferences WHERE content_type = ? AND group_key = ?')
    .get(input.contentType, input.groupKey) as GroupPrefRow | undefined;

  if (existing) {
    // Update existing
    const updates: string[] = [];
    const params: unknown[] = [];

    if (input.sortOrder !== undefined) {
      updates.push('sort_order = ?');
      params.push(input.sortOrder);
    }
    if (input.isHidden !== undefined) {
      updates.push('is_hidden = ?');
      params.push(input.isHidden ? 1 : 0);
    }
    if (input.isPinned !== undefined) {
      updates.push('is_pinned = ?');
      params.push(input.isPinned ? 1 : 0);
    }
    if (input.customName !== undefined) {
      updates.push('custom_name = ?');
      params.push(input.customName);
    }

    if (updates.length > 0) {
      params.push(existing.id);
      db.prepare(`UPDATE group_preferences SET ${updates.join(', ')} WHERE id = ?`).run(...params);
    }

    const updated = db.prepare('SELECT * FROM group_preferences WHERE id = ?').get(existing.id) as GroupPrefRow;
    return rowToPref(updated);
  }

  // Insert new
  const id = uuid();
  db.prepare(
    `INSERT INTO group_preferences (id, content_type, group_key, sort_order, is_hidden, is_pinned, custom_name, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(
    id,
    input.contentType,
    input.groupKey,
    input.sortOrder ?? 0,
    input.isHidden ? 1 : 0,
    input.isPinned ? 1 : 0,
    input.customName ?? null,
    now,
  );

  const inserted = db.prepare('SELECT * FROM group_preferences WHERE id = ?').get(id) as GroupPrefRow;
  return rowToPref(inserted);
}

/** Reorder groups by updating sort_order for each key */
export function reorderGroups(contentType: string, orderedKeys: string[]): void {
  const db = getDb();
  const now = Date.now();

  const upsert = db.prepare(
    `INSERT INTO group_preferences (id, content_type, group_key, sort_order, is_hidden, is_pinned, custom_name, created_at)
     VALUES (?, ?, ?, ?, 0, 0, NULL, ?)
     ON CONFLICT(content_type, group_key) DO UPDATE SET sort_order = excluded.sort_order`,
  );

  const transaction = db.transaction(() => {
    for (let i = 0; i < orderedKeys.length; i++) {
      upsert.run(uuid(), contentType, orderedKeys[i], i, now);
    }
  });

  transaction();
}

/** Remove a group preference */
export function removeGroupPreference(contentType: string, groupKey: string): void {
  const db = getDb();
  db.prepare('DELETE FROM group_preferences WHERE content_type = ? AND group_key = ?').run(
    contentType,
    groupKey,
  );
}
