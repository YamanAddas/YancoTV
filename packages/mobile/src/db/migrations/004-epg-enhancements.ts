export const name = '004-epg-enhancements.sql';

export const sql = `
ALTER TABLE sources ADD COLUMN epg_url TEXT;

ALTER TABLE epg_programmes ADD COLUMN icon_url TEXT;

ALTER TABLE epg_programmes ADD COLUMN source_id TEXT REFERENCES sources(id) ON DELETE CASCADE;

CREATE INDEX idx_epg_source ON epg_programmes(source_id);

CREATE INDEX idx_epg_end_time ON epg_programmes(end_time);
`;
