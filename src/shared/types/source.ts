export type SourceType = 'm3u_url' | 'm3u_file' | 'xtream' | 'stalker';

export interface Source {
  id: string;
  name: string;
  type: SourceType;
  url?: string;
  filePath?: string;
  epgUrl?: string;
  /** Optional per-source HTTP User-Agent. Applied to mpv playback. */
  userAgent?: string;
  lastSynced?: number;
  isActive: boolean;
  priority: number;
  channelCount: number;
  lastSyncError?: string;
  autoSyncInterval: number;
  createdAt: number;
  updatedAt: number;
}

export interface AddSourceInput {
  name: string;
  type: SourceType;
  url?: string;
  filePath?: string;
  username?: string;
  password?: string;
  macAddress?: string;
  epgUrl?: string;
  userAgent?: string;
}

export interface UpdateSourceInput {
  id: string;
  name?: string;
  url?: string;
  username?: string;
  password?: string;
  macAddress?: string;
  epgUrl?: string;
  userAgent?: string;
  autoSyncInterval?: number;
}
