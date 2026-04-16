-- Sprint 11B: Group Preferences for Smart Groups Menu
-- Stores user customizations for category sidebar groups: sort order, visibility, pinning, renaming.

CREATE TABLE IF NOT EXISTS group_preferences (
  id TEXT PRIMARY KEY,
  content_type TEXT NOT NULL CHECK(content_type IN ('live', 'movie', 'series')),
  group_key TEXT NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  is_hidden INTEGER NOT NULL DEFAULT 0,
  is_pinned INTEGER NOT NULL DEFAULT 0,
  custom_name TEXT,
  created_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_group_prefs_type_key
  ON group_preferences(content_type, group_key);
