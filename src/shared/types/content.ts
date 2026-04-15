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
