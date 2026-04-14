export type ContentType = 'live' | 'movie' | 'series';

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
