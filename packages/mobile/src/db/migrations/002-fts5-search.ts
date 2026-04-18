export const name = '002-fts5-search.sql';

export const sql = `
-- FTS5 full-text search index for content

CREATE VIRTUAL TABLE content_fts USING fts5(
  content_id UNINDEXED,
  title,
  clean_title,
  group_name
);

INSERT INTO content_fts (content_id, title, clean_title, group_name)
SELECT id, title, clean_title, group_name FROM content;

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
`;
