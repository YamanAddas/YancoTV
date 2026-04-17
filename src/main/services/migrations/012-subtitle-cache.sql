-- Subtitle cache: maps content/episode + language to a local file so
-- replaying the same item doesn't consume OpenSubtitles download quota.
CREATE TABLE IF NOT EXISTS subtitle_cache (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  content_id  TEXT    NOT NULL,
  episode_id  TEXT,                        -- NULL for movies
  language    TEXT    NOT NULL,
  file_path   TEXT    NOT NULL,
  file_id     INTEGER,                     -- OpenSubtitles file_id (for dedup)
  created_at  INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
);

-- One cached subtitle per content+episode+language combination
CREATE UNIQUE INDEX IF NOT EXISTS idx_subtitle_cache_unique
  ON subtitle_cache(content_id, COALESCE(episode_id, ''), language);

CREATE INDEX IF NOT EXISTS idx_subtitle_cache_lookup
  ON subtitle_cache(content_id, language);
