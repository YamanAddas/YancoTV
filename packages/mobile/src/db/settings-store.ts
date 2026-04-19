import { getDb } from './db';

/**
 * Mobile settings key-value store.
 *
 * Mirrors desktop's `src/main/services/settings-service.ts`: a single string
 * -> string table, values are always strings, and callers convert. Used for
 * small scalar preferences (theme, last-opened source, EPG refresh interval)
 * that would otherwise bloat AsyncStorage or drift from SQLite state.
 *
 * The `settings` table itself is created by migration 005.
 */

export async function getSetting(key: string): Promise<string | null> {
  const db = getDb();
  const res = await db.execute('SELECT value FROM settings WHERE key = ?', [key]);
  const row = (res.rows ?? [])[0] as unknown as { value: string } | undefined;
  return row?.value ?? null;
}

export async function setSetting(key: string, value: string): Promise<void> {
  const db = getDb();
  await db.execute(
    'INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)',
    [key, value],
  );
}

export async function deleteSetting(key: string): Promise<void> {
  const db = getDb();
  await db.execute('DELETE FROM settings WHERE key = ?', [key]);
}

/**
 * Bulk read — the boot path pulls every setting in one round-trip so the
 * Zustand hydration step can be sync from there.
 */
export async function getAllSettings(): Promise<Record<string, string>> {
  const db = getDb();
  const res = await db.execute('SELECT key, value FROM settings');
  const rows = (res.rows ?? []) as unknown as { key: string; value: string }[];
  const map: Record<string, string> = {};
  for (const row of rows) {
    map[row.key] = row.value;
  }
  return map;
}

/**
 * Bulk write inside a single transaction. Saves a Settings screen from doing
 * N separate await-set round-trips when the user taps "Save".
 */
export async function setSettings(
  entries: Record<string, string>,
): Promise<void> {
  const db = getDb();
  await db.transaction(async (tx) => {
    for (const [key, value] of Object.entries(entries)) {
      await tx.execute(
        'INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)',
        [key, value],
      );
    }
  });
}
