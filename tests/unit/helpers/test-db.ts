/**
 * Test helper: creates an in-memory SQLite database with the YancoTV schema
 * and wires it into the `getDb` mock so service modules use it transparently.
 */
import Database from 'better-sqlite3';

/** SQL matching the production migrations (001 + 003 + 005), minus FTS5 */
const SCHEMA_SQL = `
-- 001-initial-schema
CREATE TABLE sources (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL CHECK(type IN ('m3u_url', 'm3u_file', 'xtream')),
  url TEXT,
  file_path TEXT,
  username_encrypted BLOB,
  password_encrypted BLOB,
  last_synced INTEGER,
  is_active INTEGER DEFAULT 1,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE content (
  id TEXT PRIMARY KEY,
  source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  type TEXT NOT NULL CHECK(type IN ('live', 'movie', 'series')),
  title TEXT NOT NULL,
  clean_title TEXT,
  group_name TEXT,
  stream_url TEXT NOT NULL,
  logo_url TEXT,
  tvg_id TEXT,
  metadata_json TEXT,
  created_at INTEGER NOT NULL
);

CREATE TABLE episodes (
  id TEXT PRIMARY KEY,
  content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
  season_number INTEGER,
  episode_number INTEGER,
  title TEXT,
  stream_url TEXT NOT NULL,
  duration INTEGER
);

CREATE TABLE favorites (
  id TEXT PRIMARY KEY,
  content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
  added_at INTEGER NOT NULL
);

CREATE TABLE watch_history (
  id TEXT PRIMARY KEY,
  content_id TEXT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
  episode_id TEXT REFERENCES episodes(id),
  position_seconds INTEGER DEFAULT 0,
  duration_seconds INTEGER,
  watched_at INTEGER NOT NULL
);

CREATE TABLE epg_programmes (
  id TEXT PRIMARY KEY,
  channel_tvg_id TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  start_time INTEGER NOT NULL,
  end_time INTEGER NOT NULL,
  category TEXT
);

CREATE INDEX idx_content_source ON content(source_id);
CREATE INDEX idx_content_type ON content(type);
CREATE INDEX idx_content_group ON content(group_name);
CREATE INDEX idx_watch_history_content ON watch_history(content_id);
CREATE INDEX idx_favorites_content ON favorites(content_id);

-- 003-sort-order
ALTER TABLE content ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;

-- 004-epg-enhancements
ALTER TABLE sources ADD COLUMN epg_url TEXT;
ALTER TABLE epg_programmes ADD COLUMN icon_url TEXT;
ALTER TABLE epg_programmes ADD COLUMN source_id TEXT REFERENCES sources(id) ON DELETE CASCADE;

-- 005-parental-controls
CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS locked_channels (
  content_id TEXT PRIMARY KEY,
  locked_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS hidden_channels (
  content_id TEXT PRIMARY KEY,
  hidden_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS channel_overrides (
  content_id TEXT PRIMARY KEY,
  custom_name TEXT,
  custom_logo_url TEXT,
  custom_number INTEGER,
  custom_group TEXT,
  updated_at INTEGER NOT NULL
);
`;

export function createTestDb(): Database.Database {
  const db = new Database(':memory:');
  db.pragma('journal_mode = WAL');
  db.pragma('foreign_keys = ON');
  db.exec(SCHEMA_SQL);
  return db;
}

/** Insert a minimal source row and return its id */
export function insertTestSource(
  db: Database.Database,
  overrides: Partial<{
    id: string;
    name: string;
    type: string;
    url: string;
  }> = {},
): string {
  const id = overrides.id ?? 'src-1';
  const now = Date.now();
  db.prepare(
    `INSERT INTO sources (id, name, type, url, is_active, created_at, updated_at)
     VALUES (?, ?, ?, ?, 1, ?, ?)`,
  ).run(
    id,
    overrides.name ?? 'Test Source',
    overrides.type ?? 'm3u_url',
    overrides.url ?? 'http://example.com/playlist.m3u',
    now,
    now,
  );
  return id;
}

/** Insert a minimal content row and return its id */
export function insertTestContent(
  db: Database.Database,
  overrides: Partial<{
    id: string;
    sourceId: string;
    type: string;
    title: string;
    streamUrl: string;
    groupName: string;
    tvgId: string;
  }> = {},
): string {
  const id = overrides.id ?? 'content-1';
  db.prepare(
    `INSERT INTO content (id, source_id, type, title, stream_url, group_name, tvg_id, created_at, sort_order)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)`,
  ).run(
    id,
    overrides.sourceId ?? 'src-1',
    overrides.type ?? 'live',
    overrides.title ?? 'Test Channel',
    overrides.streamUrl ?? 'http://stream.com/ch1',
    overrides.groupName ?? null,
    overrides.tvgId ?? null,
    Date.now(),
  );
  return id;
}
