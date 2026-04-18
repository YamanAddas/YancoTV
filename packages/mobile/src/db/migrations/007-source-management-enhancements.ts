export const name = '007-source-management-enhancements.sql';

export const sql = `
-- Sprint 11: Source Management Enhancements
-- Adds columns for Stalker Portal support, health tracking, priority ordering.
-- The pre-existing CHECK(type IN ('m3u_url','m3u_file','xtream')) cannot be
-- dropped via ALTER TABLE in SQLite and is bypassed for stalker inserts via
-- PRAGMA ignore_check_constraints at the app level.

ALTER TABLE sources ADD COLUMN mac_address_encrypted BLOB;
ALTER TABLE sources ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sources ADD COLUMN channel_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sources ADD COLUMN last_sync_error TEXT;
ALTER TABLE sources ADD COLUMN auto_sync_interval INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_sources_priority ON sources(priority ASC);
`;
