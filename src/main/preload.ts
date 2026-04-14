import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';
import { IpcChannels } from '../shared/ipc-channels';

const api = {
  sources: {
    getAll: () => ipcRenderer.invoke(IpcChannels.SOURCES_GET_ALL),
    add: (input: unknown) => ipcRenderer.invoke(IpcChannels.SOURCES_ADD, input),
    remove: (id: string) => ipcRenderer.invoke(IpcChannels.SOURCES_REMOVE, id),
    sync: (id: string) => ipcRenderer.invoke(IpcChannels.SOURCES_SYNC, id),
  },

  content: {
    getLive: (sourceId?: string) => ipcRenderer.invoke(IpcChannels.CONTENT_GET_LIVE, sourceId),
    getMovies: (sourceId?: string) => ipcRenderer.invoke(IpcChannels.CONTENT_GET_MOVIES, sourceId),
    getSeries: (sourceId?: string) =>
      ipcRenderer.invoke(IpcChannels.CONTENT_GET_SERIES, sourceId),
    getCategories: (type: string) =>
      ipcRenderer.invoke(IpcChannels.CONTENT_GET_CATEGORIES, type),
    search: (query: string) => ipcRenderer.invoke(IpcChannels.CONTENT_SEARCH, query),
    getEpisodes: (contentId: string) =>
      ipcRenderer.invoke(IpcChannels.CONTENT_GET_EPISODES, contentId),
  },

  player: {
    play: (url: string, title?: string) =>
      ipcRenderer.invoke(IpcChannels.PLAYER_PLAY, url, title),
    pause: () => ipcRenderer.invoke(IpcChannels.PLAYER_PAUSE),
    resume: () => ipcRenderer.invoke(IpcChannels.PLAYER_RESUME),
    stop: () => ipcRenderer.invoke(IpcChannels.PLAYER_STOP),
    seek: (seconds: number) => ipcRenderer.invoke(IpcChannels.PLAYER_SEEK, seconds),
    setVolume: (level: number) => ipcRenderer.invoke(IpcChannels.PLAYER_SET_VOLUME, level),
    state: () => ipcRenderer.invoke(IpcChannels.PLAYER_STATE),

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
  },

  dialog: {
    openM3uFile: () => ipcRenderer.invoke(IpcChannels.DIALOG_OPEN_M3U_FILE),
  },

  db: {
    status: () => ipcRenderer.invoke(IpcChannels.DB_STATUS),
  },

  app: {
    getVersion: () => ipcRenderer.invoke(IpcChannels.APP_GET_VERSION),
  },
};

export type ApiType = typeof api;

contextBridge.exposeInMainWorld('api', api);
