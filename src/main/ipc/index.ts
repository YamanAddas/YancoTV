import { ipcMain, app, dialog } from 'electron';
import log from 'electron-log/main';
import { IpcChannels } from '../../shared/ipc-channels';
import { addSourceInputSchema } from '../../shared/schemas/source';
import { getDb } from '../services/db';
import { getAllSources, addSource, removeSource } from '../services/source-manager';
import { syncSource } from '../services/source-sync';
import {
  getContentByType,
  getCategories,
  searchContent,
  getContentCountByType,
  getEpisodes,
} from '../services/content-store';

export function registerIpcHandlers(): void {
  log.info('Registering IPC handlers...');

  // App
  ipcMain.handle(IpcChannels.APP_GET_VERSION, () => {
    return app.getVersion();
  });

  // Database status
  ipcMain.handle(IpcChannels.DB_STATUS, () => {
    try {
      const db = getDb();
      const tables = db
        .prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
        .all() as { name: string }[];
      const counts = getContentCountByType();
      return { ok: true, tables: tables.map((t) => t.name), counts };
    } catch (error) {
      log.error('DB status check failed:', error);
      return { ok: false, error: String(error) };
    }
  });

  // Source management
  ipcMain.handle(IpcChannels.SOURCES_GET_ALL, () => {
    return getAllSources();
  });

  ipcMain.handle(IpcChannels.SOURCES_ADD, async (_event, input: unknown) => {
    const parsed = addSourceInputSchema.safeParse(input);
    if (!parsed.success) {
      return { ok: false, error: parsed.error.issues.map((i) => i.message).join(', ') };
    }

    const result = addSource(parsed.data);
    if (!result.ok) {
      return { ok: false, error: result.error.message };
    }

    // Auto-sync after adding
    const syncResult = await syncSource(result.value.id);
    return {
      ok: true,
      source: result.value,
      syncedCount: syncResult.ok ? syncResult.value : 0,
      syncError: syncResult.ok ? undefined : syncResult.error.message,
    };
  });

  ipcMain.handle(IpcChannels.SOURCES_REMOVE, (_event, id: string) => {
    if (!id || typeof id !== 'string') {
      return { ok: false, error: 'Invalid source ID' };
    }
    const result = removeSource(id);
    return result.ok ? { ok: true } : { ok: false, error: result.error.message };
  });

  ipcMain.handle(IpcChannels.SOURCES_SYNC, async (_event, id: string) => {
    if (!id || typeof id !== 'string') {
      return { ok: false, error: 'Invalid source ID' };
    }
    const result = await syncSource(id);
    return result.ok
      ? { ok: true, count: result.value }
      : { ok: false, error: result.error.message };
  });

  // File picker for M3U files
  ipcMain.handle('dialog:openM3uFile', async () => {
    const result = await dialog.showOpenDialog({
      title: 'Select M3U Playlist',
      filters: [
        { name: 'M3U Playlists', extensions: ['m3u', 'm3u8'] },
        { name: 'All Files', extensions: ['*'] },
      ],
      properties: ['openFile'],
    });
    return result.canceled ? null : result.filePaths[0];
  });

  // Content browsing
  ipcMain.handle(IpcChannels.CONTENT_GET_LIVE, (_event, sourceId?: string) => {
    return getContentByType('live', sourceId);
  });

  ipcMain.handle(IpcChannels.CONTENT_GET_MOVIES, (_event, sourceId?: string) => {
    return getContentByType('movie', sourceId);
  });

  ipcMain.handle(IpcChannels.CONTENT_GET_SERIES, (_event, sourceId?: string) => {
    return getContentByType('series', sourceId);
  });

  ipcMain.handle(IpcChannels.CONTENT_GET_CATEGORIES, (_event, type: string) => {
    if (!['live', 'movie', 'series'].includes(type)) return [];
    return getCategories(type as 'live' | 'movie' | 'series');
  });

  ipcMain.handle(IpcChannels.CONTENT_SEARCH, (_event, query: string) => {
    if (!query || typeof query !== 'string') return [];
    return searchContent(query);
  });

  ipcMain.handle(IpcChannels.CONTENT_GET_EPISODES, (_event, contentId: string) => {
    if (!contentId || typeof contentId !== 'string') return [];
    return getEpisodes(contentId);
  });

  // Player — stubs for Sprint 5
  ipcMain.handle(IpcChannels.PLAYER_PLAY, () => ({ ok: false, error: 'Not implemented yet' }));
  ipcMain.handle(IpcChannels.PLAYER_PAUSE, () => ({ ok: false, error: 'Not implemented yet' }));
  ipcMain.handle(IpcChannels.PLAYER_STOP, () => ({ ok: false, error: 'Not implemented yet' }));
  ipcMain.handle(IpcChannels.PLAYER_SEEK, () => ({ ok: false, error: 'Not implemented yet' }));
  ipcMain.handle(IpcChannels.PLAYER_SET_VOLUME, () => ({
    ok: false,
    error: 'Not implemented yet',
  }));
  ipcMain.handle(IpcChannels.PLAYER_STATE, () => null);

  log.info('IPC handlers registered');
}
