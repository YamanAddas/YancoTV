-- Composite index covering the exact pattern used by getNowNextBatch:
--   WHERE channel_tvg_id IN (...) AND end_time > ?
--   ORDER BY channel_tvg_id, start_time ASC
-- The (channel_tvg_id, end_time, start_time) ordering lets SQLite seek directly
-- to the active programmes for each channel without a full scan.
CREATE INDEX IF NOT EXISTS idx_epg_channel_end_start
  ON epg_programmes(channel_tvg_id, end_time, start_time);

-- Index for time-range queries used by getGuideData:
--   WHERE end_time > ? AND start_time < ?
CREATE INDEX IF NOT EXISTS idx_epg_time_window
  ON epg_programmes(end_time, start_time);
