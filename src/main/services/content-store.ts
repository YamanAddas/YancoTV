import { v4 as uuid } from 'uuid';
import log from 'electron-log/main';
import { getDb } from './db';
import { classifyEntry, normalizeCategory } from './content-classifier';
import { cleanTitle, extractSeasonEpisode, extractShowName } from './title-cleaner';
import type { ContentItem, ContentType, Episode } from '../../shared/types';
import type { M3uEntry } from './m3u-parser';
import type {
  XtreamLiveStream,
  XtreamVodStream,
  XtreamSeriesInfo,
  XtreamClient,
  XtreamSeriesEpisode,
} from './xtream-client';

interface ContentRow {
  id: string;
  source_id: string;
  type: string;
  title: string;
  clean_title: string | null;
  group_name: string | null;
  stream_url: string;
  logo_url: string | null;
  tvg_id: string | null;
  metadata_json: string | null;
  created_at: number;
}

interface EpisodeRow {
  id: string;
  content_id: string;
  season_number: number | null;
  episode_number: number | null;
  title: string | null;
  stream_url: string;
  duration: number | null;
}

function rowToContent(row: ContentRow): ContentItem {
  return {
    id: row.id,
    sourceId: row.source_id,
    type: row.type as ContentType,
    title: row.title,
    cleanTitle: row.clean_title ?? undefined,
    groupName: row.group_name ?? undefined,
    streamUrl: row.stream_url,
    logoUrl: row.logo_url ?? undefined,
    tvgId: row.tvg_id ?? undefined,
    metadataJson: row.metadata_json ?? undefined,
    createdAt: row.created_at,
  };
}

function rowToEpisode(row: EpisodeRow): Episode {
  return {
    id: row.id,
    contentId: row.content_id,
    seasonNumber: row.season_number ?? undefined,
    episodeNumber: row.episode_number ?? undefined,
    title: row.title ?? undefined,
    streamUrl: row.stream_url,
    duration: row.duration ?? undefined,
  };
}

// --- M3U storage ---

export function storeM3uEntries(sourceId: string, entries: M3uEntry[]): number {
  const db = getDb();
  const now = Date.now();

  // Clear existing content for this source before re-importing
  db.prepare('DELETE FROM content WHERE source_id = ?').run(sourceId);

  const insertContent = db.prepare(
    `INSERT INTO content (id, source_id, type, title, clean_title, group_name, stream_url, logo_url, tvg_id, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  );

  const insertEpisode = db.prepare(
    `INSERT INTO episodes (id, content_id, season_number, episode_number, title, stream_url, duration)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  );

  // Group series entries by show name for series grouping
  const seriesMap = new Map<string, { contentId: string; entries: M3uEntry[] }>();

  const insertMany = db.transaction((items: M3uEntry[]) => {
    for (const entry of items) {
      const contentType = classifyEntry(entry);
      const cleaned = cleanTitle(entry.title);
      const group = normalizeCategory(entry.groupTitle);

      if (contentType === 'series') {
        // Group by show name
        const showName = extractShowName(entry.title);
        const key = `${group}::${showName}`.toLowerCase();

        if (!seriesMap.has(key)) {
          const contentId = uuid();
          seriesMap.set(key, { contentId, entries: [] });

          // Insert the series parent entry
          insertContent.run(
            contentId,
            sourceId,
            contentType,
            showName,
            cleanTitle(showName),
            group || null,
            entry.streamUrl, // First episode URL as fallback
            entry.tvgLogo || null,
            entry.tvgId || null,
            now,
          );
        }

        seriesMap.get(key)!.entries.push(entry);
      } else {
        // Live or movie — insert directly
        insertContent.run(
          uuid(),
          sourceId,
          contentType,
          entry.title,
          cleaned,
          group || null,
          entry.streamUrl,
          entry.tvgLogo || null,
          entry.tvgId || null,
          now,
        );
      }
    }

    // Now insert episodes for grouped series
    for (const [, seriesData] of seriesMap) {
      for (const entry of seriesData.entries) {
        const se = extractSeasonEpisode(entry.title);
        insertEpisode.run(
          uuid(),
          seriesData.contentId,
          se?.season ?? null,
          se?.episode ?? null,
          entry.title,
          entry.streamUrl,
          entry.duration > 0 ? entry.duration : null,
        );
      }
    }
  });

  insertMany(entries);

  const seriesCount = seriesMap.size;
  const episodeCount = Array.from(seriesMap.values()).reduce(
    (sum, s) => sum + s.entries.length,
    0,
  );

  log.info(
    `Stored ${entries.length} M3U entries for source ${sourceId} (${seriesCount} series with ${episodeCount} episodes)`,
  );
  return entries.length;
}

// --- Xtream storage ---

export function storeXtreamContent(
  sourceId: string,
  client: XtreamClient,
  liveStreams: { streams: XtreamLiveStream[]; categories: Map<string, string> },
  vodStreams: { streams: XtreamVodStream[]; categories: Map<string, string> },
  seriesList: { series: XtreamSeriesInfo[]; categories: Map<string, string> },
): number {
  const db = getDb();
  const now = Date.now();

  // Clear existing content for this source before re-importing
  db.prepare('DELETE FROM content WHERE source_id = ?').run(sourceId);

  const insertContent = db.prepare(
    `INSERT INTO content (id, source_id, type, title, clean_title, group_name, stream_url, logo_url, tvg_id, metadata_json, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  );

  let count = 0;

  const insertAll = db.transaction(() => {
    // Live streams
    for (const stream of liveStreams.streams) {
      const category = liveStreams.categories.get(stream.categoryId) ?? '';
      const group = normalizeCategory(category);
      const url = client.buildStreamUrl(stream.streamId, 'live');

      insertContent.run(
        uuid(),
        sourceId,
        'live',
        stream.name,
        cleanTitle(stream.name),
        group || null,
        url,
        stream.streamIcon || null,
        stream.epgChannelId || null,
        null,
        now,
      );
      count++;
    }

    // VOD streams
    for (const stream of vodStreams.streams) {
      const category = vodStreams.categories.get(stream.categoryId) ?? '';
      const group = normalizeCategory(category);
      const url = client.buildStreamUrl(
        stream.streamId,
        'movie',
        stream.containerExtension,
      );

      insertContent.run(
        uuid(),
        sourceId,
        'movie',
        stream.name,
        cleanTitle(stream.name),
        group || null,
        url,
        stream.streamIcon || null,
        null,
        stream.rating ? JSON.stringify({ rating: stream.rating }) : null,
        now,
      );
      count++;
    }

    // Series — insert as series type, episodes will be fetched on demand
    for (const series of seriesList.series) {
      const category = seriesList.categories.get(series.categoryId) ?? '';
      const group = normalizeCategory(category);

      const metadata = JSON.stringify({
        seriesId: series.seriesId,
        plot: series.plot || undefined,
        cast: series.cast || undefined,
        director: series.director || undefined,
        genre: series.genre || undefined,
        releaseDate: series.releaseDate || undefined,
        rating: series.rating || undefined,
      });

      insertContent.run(
        uuid(),
        sourceId,
        'series',
        series.name,
        cleanTitle(series.name),
        group || null,
        '', // No direct stream URL for series parent
        series.cover || null,
        null,
        metadata,
        now,
      );
      count++;
    }
  });

  insertAll();
  log.info(
    `Stored ${count} Xtream entries for source ${sourceId} (${liveStreams.streams.length} live, ${vodStreams.streams.length} VOD, ${seriesList.series.length} series)`,
  );
  return count;
}

/** Store episodes for a specific series (fetched on demand from Xtream API) */
export function storeXtreamEpisodes(
  contentId: string,
  client: XtreamClient,
  episodes: Record<string, XtreamSeriesEpisode[]>,
): number {
  const db = getDb();

  // Clear existing episodes for this content
  db.prepare('DELETE FROM episodes WHERE content_id = ?').run(contentId);

  const insert = db.prepare(
    `INSERT INTO episodes (id, content_id, season_number, episode_number, title, stream_url, duration)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  );

  let count = 0;

  const insertAll = db.transaction(() => {
    for (const [seasonNum, eps] of Object.entries(episodes)) {
      for (const ep of eps) {
        const season = ep.info.season ?? (parseInt(seasonNum, 10) || null);
        const streamUrl = client.buildStreamUrl(
          parseInt(ep.id, 10),
          'series',
          ep.containerExtension,
        );

        const durationSecs = ep.info.duration
          ? parseDuration(ep.info.duration)
          : null;

        insert.run(uuid(), contentId, season, ep.episodeNum, ep.title, streamUrl, durationSecs);
        count++;
      }
    }
  });

  insertAll();
  return count;
}

function parseDuration(duration: string): number | null {
  // Handle HH:MM:SS format
  const parts = duration.split(':');
  if (parts.length === 3) {
    const h = parseInt(parts[0], 10);
    const m = parseInt(parts[1], 10);
    const s = parseInt(parts[2], 10);
    if (!isNaN(h) && !isNaN(m) && !isNaN(s)) {
      return h * 3600 + m * 60 + s;
    }
  }
  // Try plain seconds
  const secs = parseInt(duration, 10);
  return isNaN(secs) ? null : secs;
}

// --- Query functions ---

export function getContentByType(type: ContentType, sourceId?: string): ContentItem[] {
  const db = getDb();
  if (sourceId) {
    const rows = db
      .prepare('SELECT * FROM content WHERE type = ? AND source_id = ? ORDER BY group_name, title')
      .all(type, sourceId) as ContentRow[];
    return rows.map(rowToContent);
  }
  const rows = db
    .prepare('SELECT * FROM content WHERE type = ? ORDER BY group_name, title')
    .all(type) as ContentRow[];
  return rows.map(rowToContent);
}

export function getCategories(type: ContentType): string[] {
  const db = getDb();
  const rows = db
    .prepare(
      'SELECT DISTINCT group_name FROM content WHERE type = ? AND group_name IS NOT NULL ORDER BY group_name',
    )
    .all(type) as { group_name: string }[];
  return rows.map((r) => r.group_name);
}

export function searchContent(query: string): ContentItem[] {
  const db = getDb();
  const pattern = `%${query}%`;
  const rows = db
    .prepare(
      `SELECT * FROM content WHERE title LIKE ? OR clean_title LIKE ? OR group_name LIKE ? ORDER BY title LIMIT 100`,
    )
    .all(pattern, pattern, pattern) as ContentRow[];
  return rows.map(rowToContent);
}

export function getContentCountByType(): Record<ContentType, number> {
  const db = getDb();
  const rows = db
    .prepare('SELECT type, COUNT(*) as count FROM content GROUP BY type')
    .all() as { type: string; count: number }[];

  const counts: Record<ContentType, number> = { live: 0, movie: 0, series: 0 };
  for (const row of rows) {
    counts[row.type as ContentType] = row.count;
  }
  return counts;
}

export function getEpisodes(contentId: string): Episode[] {
  const db = getDb();
  const rows = db
    .prepare(
      'SELECT * FROM episodes WHERE content_id = ? ORDER BY season_number, episode_number',
    )
    .all(contentId) as EpisodeRow[];
  return rows.map(rowToEpisode);
}
