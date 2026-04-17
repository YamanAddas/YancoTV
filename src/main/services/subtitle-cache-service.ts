/**
 * Subtitle cache service.
 *
 * Tracks which subtitle files have already been downloaded for a given piece
 * of content so that replaying the same item doesn't burn OpenSubtitles
 * download quota.
 *
 * Storage: subtitle_cache table (migration 012).
 * Files live in userData/subtitles-cache/ and are managed by
 * opensubtitles-client (which writes them); we just track the mapping here.
 */

import fs from 'fs';
import { getDb } from './db';

interface CacheRow {
  id: number;
  content_id: string;
  episode_id: string | null;
  language: string;
  file_path: string;
  file_id: number | null;
  created_at: number;
}

export interface CachedSubtitle {
  filePath: string;
  fileId: number | null;
  language: string;
}

/**
 * Look up a cached subtitle for the given content + language.
 * Returns null if not cached or if the file has been deleted from disk.
 */
export function getCachedSubtitle(
  contentId: string,
  language: string,
  episodeId?: string,
): CachedSubtitle | null {
  const db = getDb();
  const row = db
    .prepare(
      `SELECT * FROM subtitle_cache
       WHERE content_id = ? AND COALESCE(episode_id, '') = ? AND language = ?
       LIMIT 1`,
    )
    .get(contentId, episodeId ?? '', language) as CacheRow | undefined;

  if (!row) return null;

  // Validate the file still exists — cache entry is stale otherwise
  try {
    fs.accessSync(row.file_path, fs.constants.R_OK);
  } catch {
    // File gone — purge the stale row
    db.prepare('DELETE FROM subtitle_cache WHERE id = ?').run(row.id);
    return null;
  }

  return { filePath: row.file_path, fileId: row.file_id, language: row.language };
}

/**
 * Store a subtitle file mapping after a successful download.
 * Uses INSERT OR REPLACE so replaying the same content updates the path.
 */
export function cacheSubtitle(opts: {
  contentId: string;
  language: string;
  filePath: string;
  episodeId?: string;
  fileId?: number;
}): void {
  const db = getDb();
  db.prepare(
    `INSERT INTO subtitle_cache (content_id, episode_id, language, file_path, file_id)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(content_id, COALESCE(episode_id, ''), language)
     DO UPDATE SET file_path = excluded.file_path,
                   file_id   = excluded.file_id,
                   created_at = strftime('%s', 'now')`,
  ).run(
    opts.contentId,
    opts.episodeId ?? null,
    opts.language,
    opts.filePath,
    opts.fileId ?? null,
  );
}

/**
 * Remove all cached entries for a content item (e.g. when user clears cache).
 */
export function evictSubtitleCache(contentId: string, episodeId?: string): void {
  const db = getDb();
  if (episodeId !== undefined) {
    db.prepare(
      `DELETE FROM subtitle_cache WHERE content_id = ? AND episode_id = ?`,
    ).run(contentId, episodeId);
  } else {
    db.prepare(`DELETE FROM subtitle_cache WHERE content_id = ?`).run(contentId);
  }
}

/** Total number of cached entries — shown in settings. */
export function getSubtitleCacheStats(): { count: number } {
  const db = getDb();
  const row = db.prepare('SELECT COUNT(*) as count FROM subtitle_cache').get() as {
    count: number;
  };
  return { count: row.count };
}

/** Purge all rows — user-triggered clear. Does not delete files from disk. */
export function clearSubtitleCache(): void {
  getDb().prepare('DELETE FROM subtitle_cache').run();
}
