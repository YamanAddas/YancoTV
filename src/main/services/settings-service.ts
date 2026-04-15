import { getDb } from './db';

// ---------------------------------------------------------------------------
// App Settings Service
//
// Simple key-value store backed by the `settings` SQLite table.
// Values are always stored as strings. Callers handle type conversion.
// ---------------------------------------------------------------------------

/**
 * Get a single setting value by key. Returns null if not set.
 */
export function getSetting(key: string): string | null {
  const db = getDb();
  const row = db
    .prepare('SELECT value FROM settings WHERE key = ?')
    .get(key) as { value: string } | undefined;
  return row?.value ?? null;
}

/**
 * Set a single setting value.
 */
export function setSetting(key: string, value: string): void {
  const db = getDb();
  db.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)').run(key, value);
}

/**
 * Delete a setting (restore to default).
 */
export function deleteSetting(key: string): void {
  const db = getDb();
  db.prepare('DELETE FROM settings WHERE key = ?').run(key);
}

/**
 * Get all settings as a flat key→value map.
 * Used at startup to hydrate the renderer store in one round-trip.
 */
export function getAllSettings(): Record<string, string> {
  const db = getDb();
  const rows = db.prepare('SELECT key, value FROM settings').all() as {
    key: string;
    value: string;
  }[];
  const map: Record<string, string> = {};
  for (const row of rows) {
    map[row.key] = row.value;
  }
  return map;
}

/**
 * Bulk-set multiple settings in a single transaction.
 */
export function setSettings(entries: Record<string, string>): void {
  const db = getDb();
  const stmt = db.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)');
  const tx = db.transaction((e: Record<string, string>) => {
    for (const [key, value] of Object.entries(e)) {
      stmt.run(key, value);
    }
  });
  tx(entries);
}
