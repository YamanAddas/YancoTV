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

  // Enable WAL mode for better concurrent read performance
  db.pragma('journal_mode = WAL');
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

  // In development, migrations are in src; in production, in dist
  const dirs = [
    migrationsDir,
    path.join(__dirname, '..', '..', 'src', 'main', 'services', 'migrations'),
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

export function closeDatabase(): void {
  if (db) {
    db.close();
    db = null;
    log.info('Database closed');
  }
}
