export type DownloadStatus =
  | 'queued'
  | 'downloading'
  | 'paused'
  | 'completed'
  | 'failed'
  | 'cancelled';

export interface Download {
  id: string;
  contentId?: string;
  episodeId?: string;
  title: string;
  streamUrl: string;
  filePath: string;
  status: DownloadStatus;
  queuedAt: number;
  startedAt?: number;
  completedAt?: number;
  bytesDownloaded: number;
  bytesTotal?: number;
  error?: string;
  resumable: boolean;
}

export interface EnqueueDownloadInput {
  contentId?: string;
  episodeId?: string;
  title: string;
  streamUrl: string;
}

export interface DownloadProgress {
  id: string;
  bytesDownloaded: number;
  bytesTotal?: number;
  bytesPerSecond: number;
}

export interface DownloadStatusChange {
  id: string;
  status: DownloadStatus;
  error?: string;
}
