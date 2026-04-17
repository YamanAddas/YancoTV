-- Sprint 12: Recordings — ffmpeg-based live stream recording.
-- Stores metadata for active and completed recordings. Files live on disk.

CREATE TABLE IF NOT EXISTS recordings (
  id TEXT PRIMARY KEY,
  content_id TEXT,
  title TEXT NOT NULL,
  stream_url TEXT NOT NULL,
  file_path TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('recording', 'completed', 'failed', 'cancelled')),
  started_at INTEGER NOT NULL,
  ended_at INTEGER,
  duration_seconds INTEGER,
  file_size_bytes INTEGER,
  error TEXT
);

CREATE INDEX IF NOT EXISTS idx_recordings_status ON recordings(status);
CREATE INDEX IF NOT EXISTS idx_recordings_started_at ON recordings(started_at DESC);
