import { getDb } from './db';

/**
 * op-sqlite-backed CRUD for the `group_preferences` table (migration 008).
 *
 * Mirrors desktop's `src/main/services/group-preferences-service.ts` contract,
 * but async against op-sqlite instead of synchronous better-sqlite3. The
 * Zustand store in `stores/group-preferences-store.ts` is the only caller —
 * it owns the in-memory map and calls through here on every mutation.
 */

export type GroupContentType = 'live' | 'movie' | 'series';

export interface GroupPreferenceRow {
  id: string;
  contentType: GroupContentType;
  groupKey: string;
  sortOrder: number;
  isHidden: boolean;
  isPinned: boolean;
  customName: string | null;
  createdAt: number;
}

interface RawRow {
  id: string;
  content_type: GroupContentType;
  group_key: string;
  sort_order: number;
  is_hidden: number;
  is_pinned: number;
  custom_name: string | null;
  created_at: number;
}

function hydrate(r: RawRow): GroupPreferenceRow {
  return {
    id: r.id,
    contentType: r.content_type,
    groupKey: r.group_key,
    sortOrder: r.sort_order,
    isHidden: r.is_hidden === 1,
    isPinned: r.is_pinned === 1,
    customName: r.custom_name,
    createdAt: r.created_at,
  };
}

function makePrefId(): string {
  return `gp_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
}

export async function listByType(
  contentType: GroupContentType,
): Promise<GroupPreferenceRow[]> {
  const db = getDb();
  const res = await db.execute(
    'SELECT * FROM group_preferences WHERE content_type = ?',
    [contentType],
  );
  const rows = (res.rows ?? []) as unknown as RawRow[];
  return rows.map(hydrate);
}

export interface UpsertInput {
  contentType: GroupContentType;
  groupKey: string;
  sortOrder?: number;
  isHidden?: boolean;
  isPinned?: boolean;
  customName?: string | null;
}

export async function upsert(input: UpsertInput): Promise<GroupPreferenceRow> {
  const db = getDb();
  const existing = (
    await db.execute(
      'SELECT * FROM group_preferences WHERE content_type = ? AND group_key = ?',
      [input.contentType, input.groupKey],
    )
  ).rows as unknown as RawRow[] | undefined;

  if (existing && existing.length > 0) {
    const current = hydrate(existing[0]);
    const next: GroupPreferenceRow = {
      ...current,
      sortOrder: input.sortOrder ?? current.sortOrder,
      isHidden: input.isHidden ?? current.isHidden,
      isPinned: input.isPinned ?? current.isPinned,
      customName:
        input.customName === undefined ? current.customName : input.customName,
    };
    await db.execute(
      `UPDATE group_preferences
         SET sort_order = ?, is_hidden = ?, is_pinned = ?, custom_name = ?
       WHERE id = ?`,
      [
        next.sortOrder,
        next.isHidden ? 1 : 0,
        next.isPinned ? 1 : 0,
        next.customName,
        next.id,
      ],
    );
    return next;
  }

  const row: GroupPreferenceRow = {
    id: makePrefId(),
    contentType: input.contentType,
    groupKey: input.groupKey,
    sortOrder: input.sortOrder ?? 0,
    isHidden: input.isHidden ?? false,
    isPinned: input.isPinned ?? false,
    customName: input.customName ?? null,
    createdAt: Date.now(),
  };
  await db.execute(
    `INSERT INTO group_preferences
       (id, content_type, group_key, sort_order, is_hidden, is_pinned, custom_name, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      row.id,
      row.contentType,
      row.groupKey,
      row.sortOrder,
      row.isHidden ? 1 : 0,
      row.isPinned ? 1 : 0,
      row.customName,
      row.createdAt,
    ],
  );
  return row;
}

export async function reorder(
  contentType: GroupContentType,
  orderedKeys: readonly string[],
): Promise<void> {
  for (let i = 0; i < orderedKeys.length; i++) {
    await upsert({
      contentType,
      groupKey: orderedKeys[i],
      sortOrder: i,
    });
  }
}

export async function remove(
  contentType: GroupContentType,
  groupKey: string,
): Promise<void> {
  const db = getDb();
  await db.execute(
    'DELETE FROM group_preferences WHERE content_type = ? AND group_key = ?',
    [contentType, groupKey],
  );
}
