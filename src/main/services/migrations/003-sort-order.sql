-- Add sort_order column to preserve provider's original ordering
ALTER TABLE content ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;

-- Index for fast sorting by provider order within a source/type
CREATE INDEX idx_content_sort_order ON content(source_id, type, sort_order);

-- Drop FTS triggers BEFORE the bulk update to avoid per-row overhead
-- (the content_au trigger fires on every UPDATE, causing massive slowdown)
DROP TRIGGER IF EXISTS content_ai;
DROP TRIGGER IF EXISTS content_ad;
DROP TRIGGER IF EXISTS content_au;

-- Backfill existing content with rowid-based ordering
UPDATE content SET sort_order = rowid;

-- Restore FTS triggers
CREATE TRIGGER content_ai AFTER INSERT ON content BEGIN
  INSERT INTO content_fts (content_id, title, clean_title, group_name)
  VALUES (new.id, new.title, new.clean_title, new.group_name);
END;

CREATE TRIGGER content_ad AFTER DELETE ON content BEGIN
  DELETE FROM content_fts WHERE content_id = old.id;
END;

CREATE TRIGGER content_au AFTER UPDATE ON content BEGIN
  DELETE FROM content_fts WHERE content_id = old.id;
  INSERT INTO content_fts (content_id, title, clean_title, group_name)
  VALUES (new.id, new.title, new.clean_title, new.group_name);
END;
