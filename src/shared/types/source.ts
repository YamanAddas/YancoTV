export type SourceType = 'm3u_url' | 'm3u_file' | 'xtream';

export interface Source {
  id: string;
  name: string;
  type: SourceType;
  url?: string;
  filePath?: string;
  epgUrl?: string;
  lastSynced?: number;
  isActive: boolean;
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
  epgUrl?: string;
}
