export type ContentType = 'live' | 'movie' | 'series';

export type SortOption = 'provider' | 'name-asc' | 'name-desc' | 'recent' | 'group';

export interface ContentItem {
  id: string;
  sourceId: string;
  type: ContentType;
  title: string;
  cleanTitle?: string;
  groupName?: string;
  streamUrl: string;
  logoUrl?: string;
  tvgId?: string;
  metadataJson?: string;
  sortOrder: number;
  createdAt: number;
}

export interface Episode {
  id: string;
  contentId: string;
  seasonNumber?: number;
  episodeNumber?: number;
  title?: string;
  streamUrl: string;
  duration?: number;
}

/** Parsed metadata from the metadata_json column */
export interface ContentMetadata {
  // Xtream series / Stalker series
  plot?: string;
  cast?: string;
  director?: string;
  genre?: string;
  releaseDate?: string;
  rating?: string;
  // Xtream VOD
  description?: string;
  // Xtream IDs (for catch-up / episode fetch)
  seriesId?: number;
  streamId?: number;
  // Stalker IDs
  stalkerId?: string;
  // VOD duration (from Xtream get_vod_info)
  duration?: string;
  // Catch-up / archive
  tvArchive?: number;
  tvArchiveDuration?: number;
  catchupType?: string;
  catchupSource?: string;
  // TMDb enrichment
  tmdbId?: number;
  tmdbType?: 'movie' | 'tv';
  tmdbPosterUrl?: string;
  tmdbBackdropUrl?: string;
  tmdbTagline?: string;
  tmdbEnrichedAt?: number;
}

/** Enriched content detail returned by content:getDetail */
export interface ContentDetail {
  item: ContentItem;
  metadata: ContentMetadata;
  episodes: Episode[];
  watchPosition?: { positionSeconds: number; durationSeconds?: number };
}

/** Watch history entry with joined content data */
export interface HistoryEntry {
  id: string;
  contentId: string;
  episodeId?: string;
  positionSeconds: number;
  durationSeconds?: number;
  watchedAt: number;
  content: ContentItem;
}

/** Favorite entry with joined content data */
export interface FavoriteEntry {
  favoriteId: string;
  addedAt: number;
  content: ContentItem;
}
