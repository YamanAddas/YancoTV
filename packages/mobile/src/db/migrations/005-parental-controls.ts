export const name = '005-parental-controls.sql';

export const sql = `
-- Sprint 10: Parental Controls & Channel Management

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

CREATE INDEX IF NOT EXISTS idx_channel_overrides_number ON channel_overrides(custom_number);
`;
