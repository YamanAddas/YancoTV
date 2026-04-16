-- Sprint 11: Source Management Enhancements
-- Adds columns for Stalker Portal support, source health tracking, priority ordering.
-- NOTE: Cannot remove the CHECK constraint on sources.type via ALTER TABLE in SQLite.
-- The CHECK constraint ('m3u_url','m3u_file','xtream') is bypassed at the app level
-- using PRAGMA ignore_check_constraints for stalker-type inserts.

ALTER TABLE sources ADD COLUMN mac_address_encrypted BLOB;
ALTER TABLE sources ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sources ADD COLUMN channel_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sources ADD COLUMN last_sync_error TEXT;
ALTER TABLE sources ADD COLUMN auto_sync_interval INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_sources_priority ON sources(priority ASC);
