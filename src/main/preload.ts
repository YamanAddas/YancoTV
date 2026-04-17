import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';
import { IpcChannels } from '../shared/ipc-channels';

const api = {
  sources: {
    getAll: () => ipcRenderer.invoke(IpcChannels.SOURCES_GET_ALL),
    add: (input: unknown) => ipcRenderer.invoke(IpcChannels.SOURCES_ADD, input),
    update: (input: unknown) => ipcRenderer.invoke(IpcChannels.SOURCES_UPDATE, input),
    remove: (id: string) => ipcRenderer.invoke(IpcChannels.SOURCES_REMOVE, id),
    sync: (id: string) => ipcRenderer.invoke(IpcChannels.SOURCES_SYNC, id),
    reorder: (orderedIds: string[]) => ipcRenderer.invoke(IpcChannels.SOURCES_REORDER, orderedIds),
    onSyncProgress: (callback: (sourceId: string, progress: { phase: string; current: number; total: number }) => void) => {
      const handler = (_event: IpcRendererEvent, sourceId: string, progress: { phase: string; current: number; total: number }) =>
        callback(sourceId, progress);
      ipcRenderer.on(IpcChannels.SOURCES_SYNC_PROGRESS, handler);
      return () => {
        ipcRenderer.removeListener(IpcChannels.SOURCES_SYNC_PROGRESS, handler);
      };
    },
  },

  content: {
    getLive: (sourceId?: string, sort?: string) => ipcRenderer.invoke(IpcChannels.CONTENT_GET_LIVE, sourceId, sort),
    getMovies: (sourceId?: string, sort?: string) => ipcRenderer.invoke(IpcChannels.CONTENT_GET_MOVIES, sourceId, sort),
    getSeries: (sourceId?: string, sort?: string) =>
      ipcRenderer.invoke(IpcChannels.CONTENT_GET_SERIES, sourceId, sort),
    getCategories: (type: string) =>
      ipcRenderer.invoke(IpcChannels.CONTENT_GET_CATEGORIES, type),
    search: (query: string) => ipcRenderer.invoke(IpcChannels.CONTENT_SEARCH, query),
    getEpisodes: (contentId: string) =>
      ipcRenderer.invoke(IpcChannels.CONTENT_GET_EPISODES, contentId),
    getDetail: (id: string) =>
      ipcRenderer.invoke(IpcChannels.CONTENT_GET_DETAIL, id),
    getRelated: (id: string) =>
      ipcRenderer.invoke(IpcChannels.CONTENT_GET_RELATED, id),
  },

  favorites: {
    getAll: () => ipcRenderer.invoke(IpcChannels.FAVORITES_GET_ALL),
    getIds: () => ipcRenderer.invoke(IpcChannels.FAVORITES_GET_IDS),
    add: (contentId: string) => ipcRenderer.invoke(IpcChannels.FAVORITES_ADD, contentId),
    remove: (contentId: string) => ipcRenderer.invoke(IpcChannels.FAVORITES_REMOVE, contentId),
  },

  history: {
    getRecent: (limit?: number) => ipcRenderer.invoke(IpcChannels.HISTORY_GET_RECENT, limit),
    getPosition: (contentId: string, episodeId?: string) =>
      ipcRenderer.invoke(IpcChannels.HISTORY_GET_POSITION, contentId, episodeId),
    getPositionsBatch: (contentId: string, episodeIds: string[]) =>
      ipcRenderer.invoke(IpcChannels.HISTORY_GET_POSITIONS_BATCH, contentId, episodeIds),
    record: (contentId: string, episodeId?: string) =>
      ipcRenderer.invoke(IpcChannels.HISTORY_RECORD, contentId, episodeId),
    updatePosition: (historyId: string, positionSeconds: number, durationSeconds?: number) =>
      ipcRenderer.invoke(IpcChannels.HISTORY_UPDATE_POSITION, historyId, positionSeconds, durationSeconds),
    remove: (id: string) => ipcRenderer.invoke(IpcChannels.HISTORY_REMOVE, id),
    clear: () => ipcRenderer.invoke(IpcChannels.HISTORY_CLEAR),
  },

  epg: {
    refresh: () => ipcRenderer.invoke(IpcChannels.EPG_REFRESH),
    getNowNext: (tvgId: string) => ipcRenderer.invoke(IpcChannels.EPG_GET_NOW_NEXT, tvgId),
    getNowNextBatch: (tvgIds: string[]) => ipcRenderer.invoke(IpcChannels.EPG_GET_NOW_NEXT_BATCH, tvgIds),
    getGuide: (startTime: number, endTime: number, sourceId?: string) =>
      ipcRenderer.invoke(IpcChannels.EPG_GET_GUIDE, startTime, endTime, sourceId),
    getForChannel: (tvgId: string, startTime: number, endTime: number) =>
      ipcRenderer.invoke(IpcChannels.EPG_GET_FOR_CHANNEL, tvgId, startTime, endTime),
    getStats: () => ipcRenderer.invoke(IpcChannels.EPG_GET_STATS),
    setGlobalUrl: (url: string) => ipcRenderer.invoke(IpcChannels.EPG_SET_GLOBAL_URL, url),
    getSettings: () => ipcRenderer.invoke(IpcChannels.EPG_GET_SETTINGS),
    onRefreshProgress: (
      callback: (progress: { phase: string; programmeCount?: number; channelCount?: number; error?: string }) => void,
    ) => {
      const handler = (
        _event: IpcRendererEvent,
        progress: { phase: string; programmeCount?: number; channelCount?: number; error?: string },
      ) => callback(progress);
      ipcRenderer.on(IpcChannels.EPG_REFRESH_PROGRESS, handler);
      return () => ipcRenderer.removeListener(IpcChannels.EPG_REFRESH_PROGRESS, handler);
    },
  },

  timeshift: {
    activate: () => ipcRenderer.invoke(IpcChannels.TIMESHIFT_ACTIVATE),
    deactivate: () => ipcRenderer.invoke(IpcChannels.TIMESHIFT_DEACTIVATE),
    getState: () => ipcRenderer.invoke(IpcChannels.TIMESHIFT_GET_STATE),
    onStateChange: (callback: (state: unknown) => void) => {
      const handler = (_event: IpcRendererEvent, state: unknown) => callback(state);
      ipcRenderer.on(IpcChannels.TIMESHIFT_STATE, handler);
      return () => {
        ipcRenderer.removeListener(IpcChannels.TIMESHIFT_STATE, handler);
      };
    },
  },

  catchup: {
    getUrl: (tvgId: string, programmeStart: number, programmeDuration: number) =>
      ipcRenderer.invoke(IpcChannels.CATCHUP_GET_URL, tvgId, programmeStart, programmeDuration),
    checkSupport: (tvgId: string) =>
      ipcRenderer.invoke(IpcChannels.CATCHUP_CHECK_SUPPORT, tvgId),
  },

  settings: {
    getAll: () => ipcRenderer.invoke(IpcChannels.SETTINGS_GET_ALL),
    set: (key: string, value: string) =>
      ipcRenderer.invoke(IpcChannels.SETTINGS_SET, key, value),
    setMany: (entries: Record<string, string>) =>
      ipcRenderer.invoke(IpcChannels.SETTINGS_SET_MANY, entries),
  },

  parental: {
    getSettings: () => ipcRenderer.invoke(IpcChannels.PARENTAL_GET_SETTINGS),
    setPin: (pin: string) => ipcRenderer.invoke(IpcChannels.PARENTAL_SET_PIN, pin),
    verifyPin: (pin: string) => ipcRenderer.invoke(IpcChannels.PARENTAL_VERIFY_PIN, pin),
    removePin: () => ipcRenderer.invoke(IpcChannels.PARENTAL_REMOVE_PIN),
    updateSetting: (key: string, value: boolean) =>
      ipcRenderer.invoke(IpcChannels.PARENTAL_UPDATE_SETTING, key, value),
    lockChannel: (contentId: string) =>
      ipcRenderer.invoke(IpcChannels.PARENTAL_LOCK_CHANNEL, contentId),
    unlockChannel: (contentId: string) =>
      ipcRenderer.invoke(IpcChannels.PARENTAL_UNLOCK_CHANNEL, contentId),
    getLockedIds: () => ipcRenderer.invoke(IpcChannels.PARENTAL_GET_LOCKED_IDS),
    isLocked: (contentId: string) =>
      ipcRenderer.invoke(IpcChannels.PARENTAL_IS_LOCKED, contentId),
    hideChannel: (contentId: string) =>
      ipcRenderer.invoke(IpcChannels.PARENTAL_HIDE_CHANNEL, contentId),
    unhideChannel: (contentId: string) =>
      ipcRenderer.invoke(IpcChannels.PARENTAL_UNHIDE_CHANNEL, contentId),
    getHiddenIds: () => ipcRenderer.invoke(IpcChannels.PARENTAL_GET_HIDDEN_IDS),
    setOverride: (override: { contentId: string; customName?: string; customLogoUrl?: string; customNumber?: number; customGroup?: string }) =>
      ipcRenderer.invoke(IpcChannels.PARENTAL_SET_OVERRIDE, override),
    removeOverride: (contentId: string) =>
      ipcRenderer.invoke(IpcChannels.PARENTAL_REMOVE_OVERRIDE, contentId),
    getOverrides: () => ipcRenderer.invoke(IpcChannels.PARENTAL_GET_OVERRIDES),
  },

  player: {
    checkMpv: () => ipcRenderer.invoke(IpcChannels.PLAYER_CHECK_MPV),
    play: (url: string, title?: string, startPosition?: number, contentId?: string) =>
      ipcRenderer.invoke(IpcChannels.PLAYER_PLAY, url, title, startPosition, contentId),
    pause: () => ipcRenderer.invoke(IpcChannels.PLAYER_PAUSE),
    resume: () => ipcRenderer.invoke(IpcChannels.PLAYER_RESUME),
    stop: () => ipcRenderer.invoke(IpcChannels.PLAYER_STOP),
    seek: (seconds: number) => ipcRenderer.invoke(IpcChannels.PLAYER_SEEK, seconds),
    setVolume: (level: number) => ipcRenderer.invoke(IpcChannels.PLAYER_SET_VOLUME, level),
    toggleMute: () => ipcRenderer.invoke(IpcChannels.PLAYER_TOGGLE_MUTE),
    setSpeed: (speed: number) => ipcRenderer.invoke(IpcChannels.PLAYER_SET_SPEED, speed),
    setAspectRatio: (ratio: string) => ipcRenderer.invoke(IpcChannels.PLAYER_SET_ASPECT_RATIO, ratio),
    toggleFullscreen: () => ipcRenderer.invoke(IpcChannels.PLAYER_TOGGLE_FULLSCREEN),
    setFullscreen: (fullscreen: boolean) => ipcRenderer.invoke(IpcChannels.PLAYER_SET_FULLSCREEN, fullscreen),
    getTracks: () => ipcRenderer.invoke(IpcChannels.PLAYER_GET_TRACKS),
    setSubtitleTrack: (id: number) => ipcRenderer.invoke(IpcChannels.PLAYER_SET_SUBTITLE_TRACK, id),
    toggleSubtitles: () => ipcRenderer.invoke(IpcChannels.PLAYER_TOGGLE_SUBTITLES),
    setAudioTrack: (id: number) => ipcRenderer.invoke(IpcChannels.PLAYER_SET_AUDIO_TRACK, id),
    getMediaInfo: () => ipcRenderer.invoke(IpcChannels.PLAYER_GET_MEDIA_INFO),
    loadSubtitleFile: () => ipcRenderer.invoke(IpcChannels.PLAYER_LOAD_SUBTITLE_FILE),
    addSubtitlePath: (path: string) =>
      ipcRenderer.invoke(IpcChannels.PLAYER_ADD_SUBTITLE_PATH, path),
    setSubtitleDelay: (seconds: number) =>
      ipcRenderer.invoke(IpcChannels.PLAYER_SET_SUBTITLE_DELAY, seconds),
    setAudioDelay: (seconds: number) =>
      ipcRenderer.invoke(IpcChannels.PLAYER_SET_AUDIO_DELAY, seconds),
    setVideoZoom: (factor: number) =>
      ipcRenderer.invoke(IpcChannels.PLAYER_SET_VIDEO_ZOOM, factor),
    takeScreenshot: () => ipcRenderer.invoke(IpcChannels.PLAYER_TAKE_SCREENSHOT),
    state: () => ipcRenderer.invoke(IpcChannels.PLAYER_STATE),

    // Theater overlay — show/hide the transparent controls window that sits
    // on top of the embedded mpv video in the main window.
    overlayShow: () => ipcRenderer.invoke(IpcChannels.PLAYER_OVERLAY_SHOW),
    overlayHide: () => ipcRenderer.invoke(IpcChannels.PLAYER_OVERLAY_HIDE),
    onOverlayShown: (
      callback: (media?: { url?: string; title?: string; contentId?: string }) => void,
    ) => {
      const handler = (
        _event: IpcRendererEvent,
        media?: { url?: string; title?: string; contentId?: string },
      ) => callback(media);
      ipcRenderer.on(IpcChannels.PLAYER_OVERLAY_SHOWN, handler);
      return () => ipcRenderer.removeListener(IpcChannels.PLAYER_OVERLAY_SHOWN, handler);
    },
    onOverlayHidden: (callback: () => void) => {
      const handler = () => callback();
      ipcRenderer.on(IpcChannels.PLAYER_OVERLAY_HIDDEN, handler);
      return () => ipcRenderer.removeListener(IpcChannels.PLAYER_OVERLAY_HIDDEN, handler);
    },

    onStateChange: (callback: (state: unknown) => void) => {
      const handler = (_event: IpcRendererEvent, state: unknown) => callback(state);
      ipcRenderer.on(IpcChannels.PLAYER_STATE_CHANGED, handler);
      return () => {
        ipcRenderer.removeListener(IpcChannels.PLAYER_STATE_CHANGED, handler);
      };
    },

    onTimeUpdate: (callback: (position: number) => void) => {
      const handler = (_event: IpcRendererEvent, position: number) => callback(position);
      ipcRenderer.on(IpcChannels.PLAYER_TIME_UPDATE, handler);
      return () => {
        ipcRenderer.removeListener(IpcChannels.PLAYER_TIME_UPDATE, handler);
      };
    },

    onError: (callback: (message: string) => void) => {
      const handler = (_event: IpcRendererEvent, message: string) => callback(message);
      ipcRenderer.on(IpcChannels.PLAYER_ERROR, handler);
      return () => {
        ipcRenderer.removeListener(IpcChannels.PLAYER_ERROR, handler);
      };
    },

    onSubtitleText: (callback: (text: string) => void) => {
      const handler = (_event: IpcRendererEvent, text: string) => callback(text);
      ipcRenderer.on(IpcChannels.PLAYER_SUBTITLE_TEXT, handler);
      return () => {
        ipcRenderer.removeListener(IpcChannels.PLAYER_SUBTITLE_TEXT, handler);
      };
    },

  },

  subtitles: {
    search: (params: {
      query?: string;
      imdb_id?: string;
      tmdb_id?: number;
      season?: number;
      episode?: number;
      languages?: string;
      moviehash?: string;
      type?: 'movie' | 'episode' | 'all';
    }) => ipcRenderer.invoke(IpcChannels.SUBTITLES_SEARCH, params),
    downloadAndLoad: (fileId: number) =>
      ipcRenderer.invoke(IpcChannels.SUBTITLES_DOWNLOAD_AND_LOAD, fileId),
  },

  groupPrefs: {
    get: (contentType: string) => ipcRenderer.invoke(IpcChannels.GROUP_PREFS_GET, contentType),
    set: (input: {
      contentType: string;
      groupKey: string;
      sortOrder?: number;
      isHidden?: boolean;
      isPinned?: boolean;
      customName?: string | null;
    }) => ipcRenderer.invoke(IpcChannels.GROUP_PREFS_SET, input),
    remove: (contentType: string, groupKey: string) =>
      ipcRenderer.invoke(IpcChannels.GROUP_PREFS_REMOVE, contentType, groupKey),
    reorder: (contentType: string, orderedKeys: string[]) =>
      ipcRenderer.invoke(IpcChannels.GROUP_PREFS_REORDER, contentType, orderedKeys),
  },

  dialog: {
    openM3uFile: () => ipcRenderer.invoke(IpcChannels.DIALOG_OPEN_M3U_FILE),
  },

  db: {
    status: () => ipcRenderer.invoke(IpcChannels.DB_STATUS),
  },

  download: {
    enqueue: (input: { contentId?: string; episodeId?: string; title: string; streamUrl: string }) =>
      ipcRenderer.invoke(IpcChannels.DOWNLOAD_ENQUEUE, input),
    pause: (id: string) => ipcRenderer.invoke(IpcChannels.DOWNLOAD_PAUSE, id),
    resume: (id: string) => ipcRenderer.invoke(IpcChannels.DOWNLOAD_RESUME, id),
    cancel: (id: string) => ipcRenderer.invoke(IpcChannels.DOWNLOAD_CANCEL, id),
    remove: (id: string, deleteFile: boolean) =>
      ipcRenderer.invoke(IpcChannels.DOWNLOAD_REMOVE, id, deleteFile),
    list: () => ipcRenderer.invoke(IpcChannels.DOWNLOAD_LIST),
    openFolder: () => ipcRenderer.invoke(IpcChannels.DOWNLOAD_OPEN_FOLDER),
    onProgress: (
      callback: (p: {
        id: string;
        bytesDownloaded: number;
        bytesTotal?: number;
        bytesPerSecond: number;
      }) => void,
    ) => {
      const handler = (
        _e: IpcRendererEvent,
        p: {
          id: string;
          bytesDownloaded: number;
          bytesTotal?: number;
          bytesPerSecond: number;
        },
      ) => callback(p);
      ipcRenderer.on(IpcChannels.DOWNLOAD_PROGRESS, handler);
      return () => ipcRenderer.removeListener(IpcChannels.DOWNLOAD_PROGRESS, handler);
    },
    onStatus: (
      callback: (p: { id: string; status: string; error?: string }) => void,
    ) => {
      const handler = (
        _e: IpcRendererEvent,
        p: { id: string; status: string; error?: string },
      ) => callback(p);
      ipcRenderer.on(IpcChannels.DOWNLOAD_STATUS, handler);
      return () => ipcRenderer.removeListener(IpcChannels.DOWNLOAD_STATUS, handler);
    },
  },

  tmdb: {
    getStatus: (): Promise<{ enabled: boolean; hasApiKey: boolean; language: string }> =>
      ipcRenderer.invoke(IpcChannels.TMDB_GET_STATUS),
    setApiKey: (apiKey: string): Promise<{ ok: boolean; error?: string }> =>
      ipcRenderer.invoke(IpcChannels.TMDB_SET_API_KEY, apiKey),
    clearApiKey: (): Promise<{ ok: boolean }> =>
      ipcRenderer.invoke(IpcChannels.TMDB_CLEAR_API_KEY),
    testApiKey: (apiKey: string): Promise<{ ok: boolean; error?: string }> =>
      ipcRenderer.invoke(IpcChannels.TMDB_TEST_API_KEY, apiKey),
    clearCache: (): Promise<{ ok: boolean }> =>
      ipcRenderer.invoke(IpcChannels.TMDB_CLEAR_CACHE),
  },

  recording: {
    start: (input: { contentId?: string; title: string; streamUrl: string }) =>
      ipcRenderer.invoke(IpcChannels.RECORDING_START, input),
    stop: (id: string) => ipcRenderer.invoke(IpcChannels.RECORDING_STOP, id),
    list: () => ipcRenderer.invoke(IpcChannels.RECORDING_LIST),
    remove: (id: string, deleteFile: boolean) =>
      ipcRenderer.invoke(IpcChannels.RECORDING_DELETE, id, deleteFile),
    openFolder: () => ipcRenderer.invoke(IpcChannels.RECORDING_OPEN_FOLDER),
    checkFfmpeg: () => ipcRenderer.invoke(IpcChannels.RECORDING_CHECK_FFMPEG),
    onProgress: (
      callback: (p: { id: string; durationSeconds: number; fileSizeBytes: number }) => void,
    ) => {
      const handler = (
        _e: IpcRendererEvent,
        p: { id: string; durationSeconds: number; fileSizeBytes: number },
      ) => callback(p);
      ipcRenderer.on(IpcChannels.RECORDING_PROGRESS, handler);
      return () => ipcRenderer.removeListener(IpcChannels.RECORDING_PROGRESS, handler);
    },
    onStatus: (callback: (p: { id: string; status: string }) => void) => {
      const handler = (_e: IpcRendererEvent, p: { id: string; status: string }) => callback(p);
      ipcRenderer.on(IpcChannels.RECORDING_STATUS, handler);
      return () => ipcRenderer.removeListener(IpcChannels.RECORDING_STATUS, handler);
    },
  },

  app: {
    getVersion: () => ipcRenderer.invoke(IpcChannels.APP_GET_VERSION),
  },

  window: {
    minimize: () => ipcRenderer.invoke(IpcChannels.WINDOW_MINIMIZE),
    maximize: () => ipcRenderer.invoke(IpcChannels.WINDOW_MAXIMIZE),
    close: () => ipcRenderer.invoke(IpcChannels.WINDOW_CLOSE),
    isMaximized: () => ipcRenderer.invoke(IpcChannels.WINDOW_IS_MAXIMIZED),
  },
};

export type ApiType = typeof api;

contextBridge.exposeInMainWorld('api', api);
