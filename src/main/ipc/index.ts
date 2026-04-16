import { ipcMain, app, dialog, BrowserWindow } from 'electron';
import log from 'electron-log/main';
import { IpcChannels } from '../../shared/ipc-channels';
import { addSourceInputSchema, updateSourceInputSchema } from '../../shared/schemas/source';
import { getDb } from '../services/db';
import { getAllSources, addSource, updateSource, removeSource, reorderSources } from '../services/source-manager';
import { syncSource } from '../services/source-sync';
import {
  getContentByType,
  getCategories,
  searchContent,
  getContentCountByType,
  getEpisodes,
  getContentById,
  getRelatedContent,
} from '../services/content-store';
import {
  getFavorites,
  getFavoriteIds,
  addFavorite,
  removeFavorite,
} from '../services/favorites-store';
import {
  getRecentlyWatched,
  getLastPosition,
  recordWatch,
  updatePosition,
  removeHistoryEntry,
  clearHistory,
} from '../services/history-store';
import {
  refreshEpg,
  getNowNext,
  getNowNextBatch,
  getGuideData,
  getProgrammesForChannel,
  getEpgStats,
} from '../services/epg-service';
import { getCatchupUrl, checkCatchupSupport } from '../services/catchup-service';
import {
  activateTimeshift,
  deactivateTimeshift,
  getTimeshiftState,
} from '../services/timeshift-service';
import {
  getAllSettings,
  setSetting,
  setSettings,
} from '../services/settings-service';
import {
  getParentalSettings,
  setPin,
  verifyPin,
  removePin,
  updateParentalSetting,
  lockChannel,
  unlockChannel,
  getLockedChannelIds,
  isChannelLocked,
  hideChannel,
  unhideChannel,
  getHiddenChannelIds,
  setChannelOverride,
  removeChannelOverride,
  getAllChannelOverrides,
} from '../services/parental-service';
import type { ChannelOverride } from '../services/parental-service';
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

  ipcMain.handle(IpcChannels.SOURCES_UPDATE, (_event, input: unknown) => {
    const parsed = updateSourceInputSchema.safeParse(input);
    if (!parsed.success) {
      return { ok: false, error: parsed.error.issues.map((i) => i.message).join(', ') };
    }
    const result = updateSource(parsed.data);
    return result.ok
      ? { ok: true, source: result.value }
      : { ok: false, error: result.error.message };
  });

  ipcMain.handle(IpcChannels.SOURCES_REORDER, (_event, orderedIds: unknown) => {
    if (!Array.isArray(orderedIds) || !orderedIds.every((id) => typeof id === 'string')) {
      return { ok: false, error: 'Invalid ordered IDs array' };
    }
    const result = reorderSources(orderedIds as string[]);
    return result.ok ? { ok: true } : { ok: false, error: result.error.message };
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
  ipcMain.handle(IpcChannels.CONTENT_GET_LIVE, (_event, sourceId?: string, sort?: string) => {
    return getContentByType('live', sourceId, (sort as 'provider' | 'name-asc' | 'name-desc' | 'recent' | 'group') || 'provider');
  });

  ipcMain.handle(IpcChannels.CONTENT_GET_MOVIES, (_event, sourceId?: string, sort?: string) => {
    return getContentByType('movie', sourceId, (sort as 'provider' | 'name-asc' | 'name-desc' | 'recent' | 'group') || 'provider');
  });

  ipcMain.handle(IpcChannels.CONTENT_GET_SERIES, (_event, sourceId?: string, sort?: string) => {
    return getContentByType('series', sourceId, (sort as 'provider' | 'name-asc' | 'name-desc' | 'recent' | 'group') || 'provider');
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

  ipcMain.handle(IpcChannels.CONTENT_GET_DETAIL, (_event, id: string) => {
    if (!id || typeof id !== 'string') return null;
    const item = getContentById(id);
    if (!item) return null;

    // Parse metadata_json
    let metadata = {};
    if (item.metadataJson) {
      try {
        metadata = JSON.parse(item.metadataJson);
      } catch {
        // Ignore invalid JSON
      }
    }

    // Get episodes for series
    const episodes = item.type === 'series' ? getEpisodes(id) : [];

    // Get watch position
    const watchPosition = getLastPosition(id);

    return { item, metadata, episodes, watchPosition: watchPosition ?? undefined };
  });

  ipcMain.handle(IpcChannels.CONTENT_GET_RELATED, (_event, id: string) => {
    if (!id || typeof id !== 'string') return { sameGroup: [], sameSource: [] };
    const item = getContentById(id);
    if (!item) return { sameGroup: [], sameSource: [] };
    return getRelatedContent(id, item.groupName, item.sourceId);
  });

  // Favorites
  ipcMain.handle(IpcChannels.FAVORITES_GET_ALL, () => {
    return getFavorites();
  });

  ipcMain.handle(IpcChannels.FAVORITES_GET_IDS, () => {
    return getFavoriteIds();
  });

  ipcMain.handle(IpcChannels.FAVORITES_ADD, (_event, contentId: string) => {
    if (!contentId || typeof contentId !== 'string') {
      return { ok: false, error: 'Invalid content ID' };
    }
    return addFavorite(contentId);
  });

  ipcMain.handle(IpcChannels.FAVORITES_REMOVE, (_event, contentId: string) => {
    if (!contentId || typeof contentId !== 'string') {
      return { ok: false, error: 'Invalid content ID' };
    }
    return removeFavorite(contentId);
  });

  // Watch history
  ipcMain.handle(IpcChannels.HISTORY_GET_RECENT, (_event, limit?: number) => {
    return getRecentlyWatched(typeof limit === 'number' ? limit : 20);
  });

  ipcMain.handle(IpcChannels.HISTORY_GET_POSITION, (_event, contentId: string, episodeId?: string) => {
    if (!contentId || typeof contentId !== 'string') return null;
    return getLastPosition(contentId, typeof episodeId === 'string' ? episodeId : undefined);
  });

  ipcMain.handle(IpcChannels.HISTORY_RECORD, (_event, contentId: string, episodeId?: string) => {
    if (!contentId || typeof contentId !== 'string') {
      return { ok: false, error: 'Invalid content ID' };
    }
    try {
      const historyId = recordWatch(contentId, typeof episodeId === 'string' ? episodeId : undefined);
      return { ok: true, historyId };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.HISTORY_UPDATE_POSITION, (_event, historyId: string, positionSeconds: number, durationSeconds?: number) => {
    if (!historyId || typeof historyId !== 'string') return;
    if (typeof positionSeconds !== 'number') return;
    updatePosition(historyId, Math.floor(positionSeconds), typeof durationSeconds === 'number' ? Math.floor(durationSeconds) : undefined);
  });

  ipcMain.handle(IpcChannels.HISTORY_REMOVE, (_event, id: string) => {
    if (!id || typeof id !== 'string') return;
    removeHistoryEntry(id);
  });

  ipcMain.handle(IpcChannels.HISTORY_CLEAR, () => {
    clearHistory();
  });

  // EPG
  ipcMain.handle(IpcChannels.EPG_REFRESH, async () => {
    try {
      const result = await refreshEpg();
      return result;
    } catch (err) {
      log.error('EPG refresh error:', err);
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.EPG_GET_NOW_NEXT, (_event, tvgId: string) => {
    if (!tvgId || typeof tvgId !== 'string') return { channelTvgId: '' };
    return getNowNext(tvgId);
  });

  ipcMain.handle(IpcChannels.EPG_GET_NOW_NEXT_BATCH, (_event, tvgIds: string[]) => {
    if (!Array.isArray(tvgIds)) return {};
    return getNowNextBatch(tvgIds);
  });

  ipcMain.handle(
    IpcChannels.EPG_GET_GUIDE,
    (_event, startTime: number, endTime: number, sourceId?: string) => {
      if (typeof startTime !== 'number' || typeof endTime !== 'number') return [];
      return getGuideData(startTime, endTime, typeof sourceId === 'string' ? sourceId : undefined);
    },
  );

  ipcMain.handle(
    IpcChannels.EPG_GET_FOR_CHANNEL,
    (_event, tvgId: string, startTime: number, endTime: number) => {
      if (!tvgId || typeof tvgId !== 'string') return [];
      if (typeof startTime !== 'number' || typeof endTime !== 'number') return [];
      return getProgrammesForChannel(tvgId, startTime, endTime);
    },
  );

  ipcMain.handle(IpcChannels.EPG_GET_STATS, () => {
    return getEpgStats();
  });

  ipcMain.handle(IpcChannels.EPG_SET_GLOBAL_URL, (_event, url: string) => {
    try {
      const db = getDb();
      db.exec(`CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)`);
      if (url && typeof url === 'string' && url.trim()) {
        db.prepare(`INSERT OR REPLACE INTO settings (key, value) VALUES ('epg_global_url', ?)`).run(url.trim());
      } else {
        db.prepare(`DELETE FROM settings WHERE key = 'epg_global_url'`).run();
      }
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.EPG_GET_SETTINGS, () => {
    try {
      const db = getDb();
      db.exec(`CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)`);
      const globalUrl = db.prepare(`SELECT value FROM settings WHERE key = 'epg_global_url'`).get() as { value: string } | undefined;
      const lastRefreshed = db.prepare(`SELECT value FROM settings WHERE key = 'epg_last_refreshed'`).get() as { value: string } | undefined;
      const refreshInterval = db.prepare(`SELECT value FROM settings WHERE key = 'epg_refresh_interval'`).get() as { value: string } | undefined;

      return {
        globalEpgUrl: globalUrl?.value || '',
        refreshIntervalHours: refreshInterval ? parseInt(refreshInterval.value, 10) : 12,
        lastRefreshedAt: lastRefreshed ? parseInt(lastRefreshed.value, 10) : null,
      };
    } catch (err) {
      log.error('Failed to get EPG settings:', err);
      return { globalEpgUrl: '', refreshIntervalHours: 12, lastRefreshedAt: null };
    }
  });

  // Catch-up
  ipcMain.handle(
    IpcChannels.CATCHUP_GET_URL,
    (_event, tvgId: string, programmeStart: number, programmeDuration: number) => {
      if (!tvgId || typeof tvgId !== 'string') return { ok: false, error: 'Invalid tvgId' };
      if (typeof programmeStart !== 'number' || typeof programmeDuration !== 'number') {
        return { ok: false, error: 'Invalid time parameters' };
      }
      const result = getCatchupUrl(tvgId, programmeStart, programmeDuration);
      if (!result.ok) return { ok: false, error: result.error.message };
      return { ok: true, ...result.value };
    },
  );

  ipcMain.handle(IpcChannels.CATCHUP_CHECK_SUPPORT, (_event, tvgId: string) => {
    if (!tvgId || typeof tvgId !== 'string') return { available: false, archiveHours: 0 };
    return checkCatchupSupport(tvgId);
  });

  // Timeshift
  ipcMain.handle(IpcChannels.TIMESHIFT_ACTIVATE, () => {
    activateTimeshift();
    return { ok: true };
  });

  ipcMain.handle(IpcChannels.TIMESHIFT_DEACTIVATE, () => {
    deactivateTimeshift();
    return { ok: true };
  });

  ipcMain.handle(IpcChannels.TIMESHIFT_GET_STATE, () => {
    return getTimeshiftState();
  });

  // App settings
  ipcMain.handle(IpcChannels.SETTINGS_GET_ALL, () => {
    return getAllSettings();
  });

  ipcMain.handle(IpcChannels.SETTINGS_SET, (_event, key: string, value: string) => {
    if (!key || typeof key !== 'string') return { ok: false, error: 'Invalid key' };
    if (typeof value !== 'string') return { ok: false, error: 'Value must be a string' };
    // Block internal keys that have their own dedicated handlers
    const blockedPrefixes = ['parental_', 'epg_last_refreshed'];
    if (blockedPrefixes.some((p) => key.startsWith(p))) {
      return { ok: false, error: 'Use the dedicated API for this setting' };
    }
    try {
      setSetting(key, value);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.SETTINGS_SET_MANY, (_event, entries: Record<string, string>) => {
    if (!entries || typeof entries !== 'object') return { ok: false, error: 'Invalid entries' };
    try {
      setSettings(entries);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  // Parental Controls
  ipcMain.handle(IpcChannels.PARENTAL_GET_SETTINGS, () => {
    return getParentalSettings();
  });

  ipcMain.handle(IpcChannels.PARENTAL_SET_PIN, (_event, pin: string) => {
    if (!pin || typeof pin !== 'string' || pin.length < 4 || !/^\d+$/.test(pin)) {
      return { ok: false, error: 'PIN must be at least 4 digits' };
    }
    try {
      setPin(pin);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PARENTAL_VERIFY_PIN, (_event, pin: string) => {
    if (!pin || typeof pin !== 'string') return { verified: false };
    return { verified: verifyPin(pin) };
  });

  ipcMain.handle(IpcChannels.PARENTAL_REMOVE_PIN, () => {
    try {
      removePin();
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PARENTAL_UPDATE_SETTING, (_event, key: string, value: boolean) => {
    if (!key || typeof key !== 'string') return { ok: false, error: 'Invalid key' };
    if (typeof value !== 'boolean') return { ok: false, error: 'Value must be boolean' };
    const allowedKeys = ['hide_adult', 'require_pin_settings'];
    if (!allowedKeys.includes(key)) return { ok: false, error: 'Unknown setting key' };
    try {
      updateParentalSetting(key, value);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PARENTAL_LOCK_CHANNEL, (_event, contentId: string) => {
    if (!contentId || typeof contentId !== 'string') return { ok: false, error: 'Invalid content ID' };
    try {
      lockChannel(contentId);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PARENTAL_UNLOCK_CHANNEL, (_event, contentId: string) => {
    if (!contentId || typeof contentId !== 'string') return { ok: false, error: 'Invalid content ID' };
    try {
      unlockChannel(contentId);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PARENTAL_GET_LOCKED_IDS, () => {
    return getLockedChannelIds();
  });

  ipcMain.handle(IpcChannels.PARENTAL_IS_LOCKED, (_event, contentId: string) => {
    if (!contentId || typeof contentId !== 'string') return false;
    return isChannelLocked(contentId);
  });

  ipcMain.handle(IpcChannels.PARENTAL_HIDE_CHANNEL, (_event, contentId: string) => {
    if (!contentId || typeof contentId !== 'string') return { ok: false, error: 'Invalid content ID' };
    try {
      hideChannel(contentId);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PARENTAL_UNHIDE_CHANNEL, (_event, contentId: string) => {
    if (!contentId || typeof contentId !== 'string') return { ok: false, error: 'Invalid content ID' };
    try {
      unhideChannel(contentId);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PARENTAL_GET_HIDDEN_IDS, () => {
    return getHiddenChannelIds();
  });

  ipcMain.handle(IpcChannels.PARENTAL_SET_OVERRIDE, (_event, override: ChannelOverride) => {
    if (!override || typeof override !== 'object' || !override.contentId) {
      return { ok: false, error: 'Invalid override data' };
    }
    try {
      setChannelOverride(override);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PARENTAL_REMOVE_OVERRIDE, (_event, contentId: string) => {
    if (!contentId || typeof contentId !== 'string') return { ok: false, error: 'Invalid content ID' };
    try {
      removeChannelOverride(contentId);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PARENTAL_GET_OVERRIDES, () => {
    return getAllChannelOverrides();
  });

  // Player
  ipcMain.handle(IpcChannels.PLAYER_PLAY, async (_event, url: string, _title?: string, startPosition?: number) => {
    if (!url || typeof url !== 'string') {
      return { ok: false, error: 'Invalid URL' };
    }
    try {
      await getPlayer().play(url, typeof startPosition === 'number' ? { startPosition } : undefined);
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

  ipcMain.handle(IpcChannels.PLAYER_TOGGLE_MUTE, async () => {
    try {
      await getPlayer().toggleMute();
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_SET_SPEED, async (_event, speed: number) => {
    if (typeof speed !== 'number') {
      return { ok: false, error: 'Invalid speed value' };
    }
    try {
      await getPlayer().setSpeed(speed);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_SET_ASPECT_RATIO, async (_event, ratio: string) => {
    if (!ratio || typeof ratio !== 'string') {
      return { ok: false, error: 'Invalid aspect ratio' };
    }
    try {
      await getPlayer().setAspectRatio(ratio as 'auto' | '16:9' | '4:3' | '21:9' | 'fill');
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_TOGGLE_FULLSCREEN, async () => {
    try {
      await getPlayer().toggleFullscreen();
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_GET_TRACKS, () => {
    const p = getPlayer();
    return {
      subtitles: p.getSubtitleTracks(),
      audio: p.getAudioTracks(),
    };
  });

  ipcMain.handle(IpcChannels.PLAYER_SET_SUBTITLE_TRACK, async (_event, id: number) => {
    if (typeof id !== 'number') {
      return { ok: false, error: 'Invalid track ID' };
    }
    try {
      await getPlayer().setSubtitleTrack(id);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_SET_AUDIO_TRACK, async (_event, id: number) => {
    if (typeof id !== 'number') {
      return { ok: false, error: 'Invalid track ID' };
    }
    try {
      await getPlayer().setAudioTrack(id);
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
