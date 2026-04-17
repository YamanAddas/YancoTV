-- Sprint 13: VOD Downloads — HTTP streaming download manager with pause/resume.
-- Stores queued, active, and completed downloads. Files live on disk in the
-- user-configured downloads directory.

CREATE TABLE IF NOT EXISTS downloads (
  id TEXT PRIMARY KEY,
  content_id TEXT,
  episode_id TEXT,
  title TEXT NOT NULL,
  stream_url TEXT NOT NULL,
  file_path TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('queued', 'downloading', 'paused', 'completed', 'failed', 'cancelled')),
  queued_at INTEGER NOT NULL,
  started_at INTEGER,
  completed_at INTEGER,
  bytes_downloaded INTEGER NOT NULL DEFAULT 0,
  bytes_total INTEGER,
  error TEXT,
  resumable INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_downloads_status ON downloads(status);
CREATE INDEX IF NOT EXISTS idx_downloads_queued_at ON downloads(queued_at DESC);
