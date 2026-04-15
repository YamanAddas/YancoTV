import Database from 'better-sqlite3';
import path from 'path';
import { app } from 'electron';
import log from 'electron-log/main';
import fs from 'fs';
import { DB_FILE_NAME } from '../../shared/constants';

let db: Database.Database | null = null;

export function getDb(): Database.Database {
  if (!db) {
    throw new Error('Database not initialized. Call initDatabase() first.');
  }
  return db;
}

export function initDatabase(): void {
  const userDataPath = app.getPath('userData');
  const dbPath = path.join(userDataPath, DB_FILE_NAME);

  log.info(`Initializing database at: ${dbPath}`);

  db = new Database(dbPath);

  // Performance pragmas — safe with WAL mode on desktop
  db.pragma('journal_mode = WAL');
  db.pragma('synchronous = NORMAL'); // Safe with WAL, 2x faster than FULL
  db.pragma('cache_size = -64000'); // 64MB page cache (negative = KB)
  db.pragma('temp_store = MEMORY'); // Temp tables in RAM
  db.pragma('foreign_keys = ON');

  runMigrations(db);

  log.info('Database initialized successfully');
}

function runMigrations(database: Database.Database): void {
  // Create migrations tracking table
  database.exec(`
    CREATE TABLE IF NOT EXISTS migrations (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL UNIQUE,
      applied_at INTEGER NOT NULL
    );
  `);

  const migrationsDir = path.join(__dirname, 'migrations');

  // In development, compiled output is in dist/main/main/services/ so use cwd() fallback.
  // In production, migrations are bundled alongside the compiled output.
  const dirs = [
    migrationsDir,
    path.join(process.cwd(), 'src', 'main', 'services', 'migrations'),
  ];

  let migrationFiles: string[] = [];
  for (const dir of dirs) {
    if (fs.existsSync(dir)) {
      migrationFiles = fs
        .readdirSync(dir)
        .filter((f) => f.endsWith('.sql'))
        .sort();
      break;
    }
  }

  if (migrationFiles.length === 0) {
    log.warn('No migration files found');
    return;
  }

  const applied = new Set(
    database
      .prepare('SELECT name FROM migrations')
      .all()
      .map((row) => (row as { name: string }).name),
  );

  const applyMigration = database.transaction((name: string, sql: string) => {
    database.exec(sql);
    database.prepare('INSERT INTO migrations (name, applied_at) VALUES (?, ?)').run(name, Date.now());
  });

  for (const file of migrationFiles) {
    if (applied.has(file)) continue;

    log.info(`Applying migration: ${file}`);
    const dir = dirs.find((d) => fs.existsSync(d))!;
    const sql = fs.readFileSync(path.join(dir, file), 'utf-8');
    applyMigration(file, sql);
    log.info(`Migration applied: ${file}`);
  }
}

/** Temporarily disable FTS5 triggers for bulk operations */
export function dropFtsTriggers(): void {
  const database = getDb();
  database.exec('DROP TRIGGER IF EXISTS content_ai');
  database.exec('DROP TRIGGER IF EXISTS content_ad');
  database.exec('DROP TRIGGER IF EXISTS content_au');
}

/** Re-create FTS5 triggers after bulk operations */
export function restoreFtsTriggers(): void {
  const database = getDb();
  database.exec(`
    CREATE TRIGGER IF NOT EXISTS content_ai AFTER INSERT ON content BEGIN
      INSERT INTO content_fts (content_id, title, clean_title, group_name)
      VALUES (new.id, new.title, new.clean_title, new.group_name);
    END;
  `);
  database.exec(`
    CREATE TRIGGER IF NOT EXISTS content_ad AFTER DELETE ON content BEGIN
      DELETE FROM content_fts WHERE content_id = old.id;
    END;
  `);
  database.exec(`
    CREATE TRIGGER IF NOT EXISTS content_au AFTER UPDATE ON content BEGIN
      DELETE FROM content_fts WHERE content_id = old.id;
      INSERT INTO content_fts (content_id, title, clean_title, group_name)
      VALUES (new.id, new.title, new.clean_title, new.group_name);
    END;
  `);
}

/** Rebuild FTS index from scratch for a specific source (or all content) */
export function rebuildFtsIndex(sourceId?: string): void {
  const database = getDb();
  if (sourceId) {
    // Only add entries for the given source
    database.prepare(`
      INSERT INTO content_fts (content_id, title, clean_title, group_name)
      SELECT id, title, clean_title, group_name FROM content WHERE source_id = ?
    `).run(sourceId);
  } else {
    // Full rebuild
    database.exec("DELETE FROM content_fts");
    database.exec(`
      INSERT INTO content_fts (content_id, title, clean_title, group_name)
      SELECT id, title, clean_title, group_name FROM content
    `);
  }
  // Optimize the FTS index segments
  database.exec("INSERT INTO content_fts(content_fts) VALUES('optimize')");
}

export function closeDatabase(): void {
  if (db) {
    db.close();
    db = null;
    log.info('Database closed');
  }
}
