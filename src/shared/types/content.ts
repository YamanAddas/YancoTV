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
  // Catch-up / archive
  tvArchive?: number;
  tvArchiveDuration?: number;
  catchupType?: string;
  catchupSource?: string;
}

/** Enriched content detail returned by content:getDetail */
export interface ContentDetail {
  item: ContentItem;
  metadata: ContentMetadata;
  episodes: Episode[];
  watchPosition?: { positionSeconds: number; durationSeconds?: number };
}
