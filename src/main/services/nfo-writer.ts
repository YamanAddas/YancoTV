import type { ContentMetadata, Episode } from '../../shared/types';

/**
 * Generate Kodi/Jellyfin-compatible .nfo XML sidecar files.
 *
 * Spec references:
 *   - https://kodi.wiki/view/NFO_files/Movies
 *   - https://kodi.wiki/view/NFO_files/TV_shows
 *
 * We deliberately emit only fields we actually have — Kodi is happy with a
 * partial .nfo, and empty/unknown fields clutter its UI.
 *
 * Exported helpers are pure string builders so they can be unit-tested without
 * touching the filesystem.
 */

function escape(raw: string): string {
  return raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function tag(name: string, value: string | number | undefined | null): string | null {
  if (value === undefined || value === null || value === '') return null;
  return `  <${name}>${escape(String(value))}</${name}>`;
}

function parseYear(releaseDate: string | undefined): number | undefined {
  if (!releaseDate) return undefined;
  const m = releaseDate.match(/\b(19|20)\d{2}\b/);
  return m ? Number(m[0]) : undefined;
}

/**
 * Split a comma/slash-separated list into trimmed non-empty items.
 * Provider fields ("cast": "Alice, Bob / Carol") vary wildly in delimiter.
 */
export function splitList(raw: string | undefined): string[] {
  if (!raw) return [];
  return raw
    .split(/[,/;]/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

export interface MovieNfoInput {
  title: string;
  originalTitle?: string;
  metadata?: ContentMetadata;
}

export function buildMovieNfo(input: MovieNfoInput): string {
  const md = input.metadata ?? {};
  const lines: (string | null)[] = [
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
    '<movie>',
    tag('title', input.title),
    tag('originaltitle', input.originalTitle),
    tag('plot', md.plot ?? md.description),
    tag('outline', md.plot ?? md.description),
    tag('tagline', md.tmdbTagline),
    tag('year', parseYear(md.releaseDate)),
    tag('premiered', md.releaseDate),
    tag('director', md.director),
    tag('mpaa', md.rating),
  ];
  for (const g of splitList(md.genre)) {
    lines.push(tag('genre', g));
  }
  for (const actor of splitList(md.cast)) {
    lines.push('  <actor>', `    <name>${escape(actor)}</name>`, '  </actor>');
  }
  if (md.tmdbId !== undefined) {
    lines.push(`  <uniqueid type="tmdb">${escape(String(md.tmdbId))}</uniqueid>`);
  }
  lines.push('</movie>');
  return lines.filter((l) => l !== null).join('\n') + '\n';
}

export interface EpisodeNfoInput {
  showTitle: string;
  episode: Episode;
  metadata?: ContentMetadata;
}

export function buildEpisodeNfo(input: EpisodeNfoInput): string {
  const { episode, metadata = {} } = input;
  const lines: (string | null)[] = [
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
    '<episodedetails>',
    tag('title', episode.title || `Episode ${episode.episodeNumber ?? ''}`.trim()),
    tag('showtitle', input.showTitle),
    tag('season', episode.seasonNumber),
    tag('episode', episode.episodeNumber),
    tag('plot', metadata.plot ?? metadata.description),
    tag('aired', metadata.releaseDate),
    tag('runtime', episode.duration ? Math.round(episode.duration / 60) : undefined),
  ];
  lines.push('</episodedetails>');
  return lines.filter((l) => l !== null).join('\n') + '\n';
}

export interface TvShowNfoInput {
  title: string;
  metadata?: ContentMetadata;
}

/**
 * tvshow.nfo lives alongside the show folder (or any file in it — Kodi scans).
 * Useful when a series' episodes are downloaded into one folder.
 */
export function buildTvShowNfo(input: TvShowNfoInput): string {
  const md = input.metadata ?? {};
  const lines: (string | null)[] = [
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
    '<tvshow>',
    tag('title', input.title),
    tag('plot', md.plot ?? md.description),
    tag('premiered', md.releaseDate),
    tag('year', parseYear(md.releaseDate)),
    tag('mpaa', md.rating),
  ];
  for (const g of splitList(md.genre)) {
    lines.push(tag('genre', g));
  }
  for (const actor of splitList(md.cast)) {
    lines.push('  <actor>', `    <name>${escape(actor)}</name>`, '  </actor>');
  }
  if (md.tmdbId !== undefined) {
    lines.push(`  <uniqueid type="tmdb">${escape(String(md.tmdbId))}</uniqueid>`);
  }
  lines.push('</tvshow>');
  return lines.filter((l) => l !== null).join('\n') + '\n';
}

// Also exported for tests that want to confirm escape rules.
export const __testing = { escape, parseYear };
