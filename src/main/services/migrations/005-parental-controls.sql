-- Sprint 10: Parental Controls & Channel Management
-- PIN protection, channel locking/hiding, custom channel overrides

-- Ensure settings table exists (was created ad-hoc before, now formalized)
CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

-- Locked channels: require PIN to play
CREATE TABLE IF NOT EXISTS locked_channels (
  content_id TEXT PRIMARY KEY,
  locked_at INTEGER NOT NULL
);

-- Hidden channels: filtered from all views
CREATE TABLE IF NOT EXISTS hidden_channels (
  content_id TEXT PRIMARY KEY,
  hidden_at INTEGER NOT NULL
);

-- Channel overrides: custom name, logo, number, group
CREATE TABLE IF NOT EXISTS channel_overrides (
  content_id TEXT PRIMARY KEY,
  custom_name TEXT,
  custom_logo_url TEXT,
  custom_number INTEGER,
  custom_group TEXT,
  updated_at INTEGER NOT NULL
);

-- Indexes for efficient lookups
CREATE INDEX IF NOT EXISTS idx_channel_overrides_number ON channel_overrides(custom_number);
