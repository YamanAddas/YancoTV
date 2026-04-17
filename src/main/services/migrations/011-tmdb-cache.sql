-- Migration 011: TMDb metadata cache
--
-- Caches TMDb lookups keyed by (content_id) so we do not hit the API on
-- every detail-page visit. Values are stored alongside a timestamp so the
-- service can age entries out based on TTL.

CREATE TABLE IF NOT EXISTS tmdb_cache (
  content_id    TEXT PRIMARY KEY,
  tmdb_id       INTEGER,
  tmdb_type     TEXT CHECK(tmdb_type IN ('movie', 'tv')),
  payload_json  TEXT,
  miss          INTEGER NOT NULL DEFAULT 0,
  fetched_at    INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tmdb_cache_fetched_at ON tmdb_cache(fetched_at);
