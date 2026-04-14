import { ipcMain, app, dialog, BrowserWindow } from 'electron';
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
import { MpvPlayer } from '../player/mpv-player';
import type { PlayerState } from '../player/player.interface';

let player: MpvPlayer | null = null;

function getPlayer(): MpvPlayer {
  if (!player) {
    player = new MpvPlayer();

    // Forward player events to all renderer windows
    player.on('state-change', (state: PlayerState) => {
      for (const win of BrowserWindow.getAllWindows()) {
        win.webContents.send(IpcChannels.PLAYER_STATE_CHANGED, state);
      }
    });

    player.on('time-update', (position: number) => {
      for (const win of BrowserWindow.getAllWindows()) {
        win.webContents.send(IpcChannels.PLAYER_TIME_UPDATE, position);
      }
    });

    player.on('error', (err: Error) => {
      for (const win of BrowserWindow.getAllWindows()) {
        win.webContents.send(IpcChannels.PLAYER_ERROR, err.message);
      }
    });
  }
  return player;
}

export function destroyPlayer(): void {
  if (player) {
    player.destroy().catch((err) => log.error('Player destroy error:', err));
    player = null;
  }
}

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
  ipcMain.handle(IpcChannels.DIALOG_OPEN_M3U_FILE, async () => {
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

  // Player
  ipcMain.handle(IpcChannels.PLAYER_PLAY, async (_event, url: string, _title?: string) => {
    if (!url || typeof url !== 'string') {
      return { ok: false, error: 'Invalid URL' };
    }
    try {
      await getPlayer().play(url);
      return { ok: true };
    } catch (err) {
      log.error('Player play error:', err);
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_PAUSE, async () => {
    try {
      await getPlayer().pause();
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_RESUME, async () => {
    try {
      await getPlayer().resume();
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_STOP, async () => {
    try {
      await getPlayer().stop();
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_SEEK, async (_event, seconds: number) => {
    if (typeof seconds !== 'number') {
      return { ok: false, error: 'Invalid seek position' };
    }
    try {
      await getPlayer().seek(seconds);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_SET_VOLUME, async (_event, level: number) => {
    if (typeof level !== 'number') {
      return { ok: false, error: 'Invalid volume level' };
    }
    try {
      await getPlayer().setVolume(level);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_STATE, () => {
    return getPlayer().getState();
  });

  log.info('IPC handlers registered');
}
