/** EPG programme as stored in the database */
export interface EpgProgramme {
  id: string;
  channelTvgId: string;
  title: string;
  description?: string;
  startTime: number; // Unix seconds
  endTime: number; // Unix seconds
  category?: string;
  iconUrl?: string;
}

/** Now + Next pair for a single channel */
export interface NowNext {
  channelTvgId: string;
  now?: EpgProgramme;
  next?: EpgProgramme;
}

/** Map of tvgId -> NowNext for bulk queries */
export type NowNextMap = Record<string, NowNext>;

/** EPG guide slice — programmes for a time range, grouped by channel */
export interface EpgGuideData {
  channels: EpgGuideChannel[];
  startTime: number;
  endTime: number;
}

export interface EpgGuideChannel {
  tvgId: string;
  /** Channel display name (joined from content table) */
  name: string;
  logoUrl?: string;
  /** Stream URL for direct playback — avoids a second getLive() call from the Guide page */
  streamUrl?: string;
  programmes: EpgProgramme[];
}

/** Status returned after an EPG refresh */
export interface EpgRefreshResult {
  ok: boolean;
  programmeCount?: number;
  channelCount?: number;
  error?: string;
}

/** EPG settings stored in the settings table */
export interface EpgSettings {
  globalEpgUrl?: string;
  refreshIntervalHours: number; // default 12
  lastRefreshedAt?: number; // Unix ms
}
