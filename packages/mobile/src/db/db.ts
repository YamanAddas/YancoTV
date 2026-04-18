import { open, type DB } from '@op-engineering/op-sqlite';
import { MIGRATIONS, type Migration } from './migrations';
import { splitStatements } from './sql';

const DB_NAME = 'yancotv.db';

let db: DB | null = null;

export function getDb(): DB {
  if (!db) {
    throw new Error('Database not initialized. Call initDatabase() first.');
  }
  return db;
}

export interface InitDbResult {
  path: string;
  version: string;
  applied: string[];
}

/**
 * Open the mobile database, apply pragmas, run any pending migrations, and
 * return a summary. Safe to call once per app boot; re-entry is a no-op
 * after the first successful call.
 */
export async function initDatabase(): Promise<InitDbResult> {
  if (db) {
    const info = await currentInfo(db);
    return info;
  }

  const handle = open({ name: DB_NAME });

  await handle.execute('PRAGMA journal_mode = WAL');
  await handle.execute('PRAGMA synchronous = NORMAL');
  await handle.execute('PRAGMA foreign_keys = ON');
  await handle.execute('PRAGMA temp_store = MEMORY');

  const applied = await runMigrations(handle, MIGRATIONS);

  db = handle;
  const info = await currentInfo(handle);
  return { ...info, applied };
}

async function currentInfo(handle: DB): Promise<InitDbResult> {
  const verRes = await handle.execute('SELECT sqlite_version() AS v');
  const version = (verRes.rows?.[0]?.v as string | undefined) ?? 'unknown';
  const path = handle.getDbPath();
  return { path, version, applied: [] };
}

async function runMigrations(
  handle: DB,
  migrations: readonly Migration[],
): Promise<string[]> {
  await handle.execute(
    `CREATE TABLE IF NOT EXISTS migrations (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       name TEXT NOT NULL UNIQUE,
       applied_at INTEGER NOT NULL
     )`,
  );

  const existing = await handle.execute('SELECT name FROM migrations');
  const already = new Set(
    (existing.rows ?? []).map((r) => r.name as string),
  );

  const applied: string[] = [];

  for (const migration of migrations) {
    if (already.has(migration.name)) continue;

    const statements = splitStatements(migration.sql);
    if (statements.length === 0) {
      throw new Error(
        `Migration ${migration.name} produced zero statements after splitting`,
      );
    }

    await handle.transaction(async (tx) => {
      for (const stmt of statements) {
        try {
          await tx.execute(stmt);
        } catch (err) {
          const msg = err instanceof Error ? err.message : String(err);
          throw new Error(
            `Migration ${migration.name} failed on statement:\n${stmt}\n\n${msg}`,
          );
        }
      }
      await tx.execute(
        'INSERT INTO migrations (name, applied_at) VALUES (?, ?)',
        [migration.name, Date.now()],
      );
    });

    applied.push(migration.name);
  }

  return applied;
}

export function closeDatabase(): void {
  if (db) {
    db.close();
    db = null;
  }
}
