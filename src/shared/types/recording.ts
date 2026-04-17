export type RecordingStatus = 'recording' | 'completed' | 'failed' | 'cancelled';

export interface Recording {
  id: string;
  contentId?: string;
  title: string;
  streamUrl: string;
  filePath: string;
  status: RecordingStatus;
  startedAt: number;
  endedAt?: number;
  durationSeconds?: number;
  fileSizeBytes?: number;
  error?: string;
}

export interface StartRecordingInput {
  contentId?: string;
  title: string;
  streamUrl: string;
}

export interface RecordingProgress {
  id: string;
  durationSeconds: number;
  fileSizeBytes: number;
}
