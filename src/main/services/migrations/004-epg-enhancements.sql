-- Add EPG URL field to sources (per-source EPG configuration)
ALTER TABLE sources ADD COLUMN epg_url TEXT;

-- Add icon URL to EPG programmes
ALTER TABLE epg_programmes ADD COLUMN icon_url TEXT;

-- Add source_id to epg_programmes so we can track which source provided the data
ALTER TABLE epg_programmes ADD COLUMN source_id TEXT REFERENCES sources(id) ON DELETE CASCADE;

-- Index for looking up programmes by source
CREATE INDEX idx_epg_source ON epg_programmes(source_id);

-- Index for looking up programmes by end_time (needed for "now" queries)
CREATE INDEX idx_epg_end_time ON epg_programmes(end_time);
