import { ipcMain, app, dialog } from 'electron';
import log from 'electron-log/main';
import { IpcChannels } from '../../shared/ipc-channels';
import { addSourceInputSchema, updateSourceInputSchema } from '../../shared/schemas/source';
import { getDb } from '../services/db';
import { getAllSources, addSource, updateSource, removeSource, reorderSources, getSourceById, getSourceCredentials } from '../services/source-manager';
import { syncSource } from '../services/source-sync';
import { getMainWindow, getOverlayWindow, getVideoWindowHandle } from '../index';
import { findMpvPath } from '../player/mpv-path';
import { showOverlay, hideOverlay } from '../player/overlay-window';
import { showVideoWindow, hideVideoWindow } from '../player/video-window';
import {
  getContentByType,
  getCategories,
  searchContent,
  getContentCountByType,
  getEpisodes,
  getContentById,
  getRelatedContent,
  storeXtreamEpisodes,
} from '../services/content-store';
import { XtreamClient } from '../services/xtream-client';
import {
  getFavorites,
  getFavoriteIds,
  addFavorite,
  removeFavorite,
} from '../services/favorites-store';
import {
  getGroupPreferences as getGroupPreferencesAll,
  setGroupPreference as setGroupPref,
  removeGroupPreference as removeGroupPref,
  reorderGroups as reorderGroupPrefs,
  type SetGroupPreferenceInput as SetGroupPrefInput,
} from '../services/group-preferences-store';
import {
  getRecentlyWatched,
  getLastPosition,
  getPositionsBatch,
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
import { searchSubtitles, downloadSubtitle } from '../services/opensubtitles-client';

let player: MpvPlayer | null = null;

/**
 * Send a player event to every renderer that might be displaying player UI:
 * the main window and the transparent controls overlay. Both subscribe to the
 * same channels and keep their own Zustand stores in sync.
 */
function sendToPlayerRenderers(channel: string, ...args: unknown[]): void {
  const main = getMainWindow();
  const overlay = getOverlayWindow();
  if (main && !main.isDestroyed()) {
    main.webContents.send(channel, ...args);
  }
  if (overlay && !overlay.isDestroyed()) {
    overlay.webContents.send(channel, ...args);
  }
}

function getPlayer(): MpvPlayer {
  if (!player) {
    player = new MpvPlayer();

    // Forward player events to the main renderer window
    player.on('state-change', (state: PlayerState) => {
      sendToPlayerRenderers(IpcChannels.PLAYER_STATE_CHANGED, state);
    });

    player.on('time-update', (position: number) => {
      sendToPlayerRenderers(IpcChannels.PLAYER_TIME_UPDATE, position);
    });

    player.on('subtitle-text', (text: string) => {
      sendToPlayerRenderers(IpcChannels.PLAYER_SUBTITLE_TEXT, text);
    });

    player.on('error', (err: Error) => {
      sendToPlayerRenderers(IpcChannels.PLAYER_ERROR, err.message);
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

  // Group Preferences
  ipcMain.handle(IpcChannels.GROUP_PREFS_GET, (_event, contentType: string) => {
    return getGroupPreferencesAll(contentType);
  });

  ipcMain.handle(IpcChannels.GROUP_PREFS_SET, (_event, input: unknown) => {
    return setGroupPref(input as SetGroupPrefInput);
  });

  ipcMain.handle(IpcChannels.GROUP_PREFS_REMOVE, (_event, contentType: string, groupKey: string) => {
    removeGroupPref(contentType, groupKey);
  });

  ipcMain.handle(IpcChannels.GROUP_PREFS_REORDER, (_event, contentType: string, orderedKeys: string[]) => {
    reorderGroupPrefs(contentType, orderedKeys);
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

  ipcMain.handle(IpcChannels.CONTENT_GET_DETAIL, async (_event, id: string) => {
    if (!id || typeof id !== 'string') return null;
    const item = getContentById(id);
    if (!item) return null;

    // Parse metadata_json
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    let metadata: Record<string, any> = {};
    if (item.metadataJson) {
      try {
        metadata = JSON.parse(item.metadataJson);
      } catch {
        // Ignore invalid JSON
      }
    }

    // Get episodes for series
    let episodes = item.type === 'series' ? getEpisodes(id) : [];

    // On-demand fetch: Xtream series with seriesId but no episodes in DB
    if (item.type === 'series' && episodes.length === 0 && metadata.seriesId) {
      try {
        const source = getSourceById(item.sourceId);
        if (source?.type === 'xtream' && source.url) {
          const creds = getSourceCredentials(item.sourceId);
          if (creds) {
            const client = new XtreamClient(source.url, creds.username, creds.password);
            const result = await client.getSeriesInfo(metadata.seriesId);
            if (result.ok) {
              storeXtreamEpisodes(id, client, result.value.episodes);
              episodes = getEpisodes(id);

              // Enrich metadata from series info if sparse
              const info = result.value.info;
              if (!metadata.plot && info.plot) metadata.plot = info.plot;
              if (!metadata.cast && info.cast) metadata.cast = info.cast;
              if (!metadata.director && info.director) metadata.director = info.director;
              if (!metadata.genre && info.genre) metadata.genre = info.genre;
              if (!metadata.rating && info.rating) metadata.rating = info.rating;
              if (!metadata.releaseDate && info.releaseDate) metadata.releaseDate = info.releaseDate;
            }
          }
        }
      } catch (err) {
        log.error('Failed to fetch Xtream episodes on demand:', err);
      }
    }

    // On-demand fetch: Xtream movie with streamId but sparse metadata
    if (item.type === 'movie' && !metadata.plot) {
      // Get streamId from metadata or parse from URL (/movie/user/pass/{id}.ext)
      let vodId = metadata.streamId as number | undefined;
      if (!vodId && item.streamUrl) {
        const match = item.streamUrl.match(/\/movie\/[^/]+\/[^/]+\/(\d+)\./);
        if (match) vodId = parseInt(match[1], 10);
      }

      if (vodId) {
        try {
          const source = getSourceById(item.sourceId);
          if (source?.type === 'xtream' && source.url) {
            const creds = getSourceCredentials(item.sourceId);
            if (creds) {
              const client = new XtreamClient(source.url, creds.username, creds.password);
              const result = await client.getVodInfo(vodId);
              if (result.ok) {
                const info = result.value;
                if (info.plot) metadata.plot = info.plot;
                if (info.cast) metadata.cast = info.cast;
                if (info.director) metadata.director = info.director;
                if (info.genre) metadata.genre = info.genre;
                if (info.releaseDate) metadata.releaseDate = info.releaseDate;
                if (info.rating && info.rating !== '0') metadata.rating = info.rating;
                if (info.duration) metadata.duration = info.duration;
              }
            }
          }
        } catch (err) {
          log.error('Failed to fetch Xtream VOD info on demand:', err);
        }
      }
    }

    // Get watch position
    const watchPosition = getLastPosition(id);

    return { item, metadata, episodes, watchPosition: watchPosition ?? undefined };
  });

  ipcMain.handle(IpcChannels.CONTENT_GET_RELATED, (_event, id: string) => {
    if (!id || typeof id !== 'string') return { sameGroup: [], sameSource: [] };
    const item = getContentById(id);
    if (!item) return { sameGroup: [], sameSource: [] };
    return getRelatedContent(id, item.groupName, item.sourceId, item.type);
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

  ipcMain.handle(IpcChannels.HISTORY_GET_POSITIONS_BATCH, (_event, contentId: string, episodeIds: string[]) => {
    if (!contentId || typeof contentId !== 'string') return {};
    if (!Array.isArray(episodeIds)) return {};
    return getPositionsBatch(contentId, episodeIds);
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
      // Embed mpv into the dedicated video-stage BrowserWindow (not main).
      // The main window's Chromium compositor would cover mpv's child surface;
      // a separate transparent child window sidesteps that z-order trap. The
      // video window must be visible (shown) before we pass its HWND to mpv
      // so mpv's surface attaches to a window that's actually on screen.
      showVideoWindow();
      const wid = getVideoWindowHandle() ?? undefined;
      const opts: { startPosition?: number; wid?: string } = {};
      if (typeof startPosition === 'number') opts.startPosition = startPosition;
      if (wid) opts.wid = wid;
      await getPlayer().play(url, Object.keys(opts).length > 0 ? opts : undefined);
      if (opts.wid) {
        showOverlay();
        sendToPlayerRenderers(IpcChannels.PLAYER_OVERLAY_SHOWN);
      } else {
        // No handle — fall back to mpv's own standalone window. Hide the
        // video stage we optimistically showed.
        hideVideoWindow();
      }
      return { ok: true };
    } catch (err) {
      hideVideoWindow();
      log.error('Player play error:', err);
      return { ok: false, error: String((err as Error).message) };
    }
  });

  // Theater overlay — shown when mpv is embedded in the main window
  ipcMain.handle(IpcChannels.PLAYER_OVERLAY_SHOW, () => {
    showOverlay();
    sendToPlayerRenderers(IpcChannels.PLAYER_OVERLAY_SHOWN);
    return { ok: true };
  });

  ipcMain.handle(IpcChannels.PLAYER_OVERLAY_HIDE, () => {
    hideOverlay();
    sendToPlayerRenderers(IpcChannels.PLAYER_OVERLAY_HIDDEN);
    return { ok: true };
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
      hideOverlay();
      hideVideoWindow();
      sendToPlayerRenderers(IpcChannels.PLAYER_OVERLAY_HIDDEN);
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

  ipcMain.handle(IpcChannels.PLAYER_TOGGLE_SUBTITLES, async () => {
    try {
      await getPlayer().toggleSubtitles();
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_GET_MEDIA_INFO, () => {
    return getPlayer().getMediaInfo();
  });

  ipcMain.handle(IpcChannels.PLAYER_LOAD_SUBTITLE_FILE, async () => {
    const main = getMainWindow();
    if (!main) return { ok: false, error: 'No main window' };

    const result = await dialog.showOpenDialog(main, {
      title: 'Load Subtitle File',
      filters: [
        { name: 'Subtitle Files', extensions: ['srt', 'ass', 'ssa', 'vtt', 'sub', 'idx'] },
        { name: 'All Files', extensions: ['*'] },
      ],
      properties: ['openFile'],
    });

    if (result.canceled || result.filePaths.length === 0) {
      return { ok: false, error: 'cancelled' };
    }

    const chosen = result.filePaths[0];
    // Load into mpv immediately so the user doesn't have to take a second step.
    try {
      await getPlayer().addSubtitleFile(chosen);
    } catch (err) {
      log.warn('sub-add failed for picker result:', err);
      // Still return path — html5 backend uses it via <track>.
    }
    return { ok: true, path: chosen };
  });

  // Load an arbitrary subtitle path into mpv (used by OpenSubtitles flow).
  ipcMain.handle(IpcChannels.PLAYER_ADD_SUBTITLE_PATH, async (_event, subtitlePath: string) => {
    if (!subtitlePath || typeof subtitlePath !== 'string') {
      return { ok: false, error: 'Invalid subtitle path' };
    }
    try {
      await getPlayer().addSubtitleFile(subtitlePath);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_SET_SUBTITLE_DELAY, async (_event, seconds: number) => {
    if (typeof seconds !== 'number' || !isFinite(seconds)) {
      return { ok: false, error: 'Invalid delay' };
    }
    try {
      await getPlayer().setSubtitleDelay(seconds);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_SET_AUDIO_DELAY, async (_event, seconds: number) => {
    if (typeof seconds !== 'number' || !isFinite(seconds)) {
      return { ok: false, error: 'Invalid delay' };
    }
    try {
      await getPlayer().setAudioDelay(seconds);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_SET_VIDEO_ZOOM, async (_event, factor: number) => {
    if (typeof factor !== 'number' || !isFinite(factor) || factor <= 0) {
      return { ok: false, error: 'Invalid zoom' };
    }
    try {
      await getPlayer().setVideoZoom(factor);
      return { ok: true };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  ipcMain.handle(IpcChannels.PLAYER_TAKE_SCREENSHOT, async () => {
    try {
      const outPath = await getPlayer().takeScreenshot();
      return { ok: true, path: outPath };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  // OpenSubtitles search + download + load
  ipcMain.handle(
    IpcChannels.SUBTITLES_SEARCH,
    async (_event, params: Record<string, unknown>) => {
      try {
        const results = await searchSubtitles({
          query: typeof params.query === 'string' ? params.query : undefined,
          imdb_id: typeof params.imdb_id === 'string' ? params.imdb_id : undefined,
          tmdb_id: typeof params.tmdb_id === 'number' ? params.tmdb_id : undefined,
          season: typeof params.season === 'number' ? params.season : undefined,
          episode: typeof params.episode === 'number' ? params.episode : undefined,
          languages: typeof params.languages === 'string' ? params.languages : undefined,
          moviehash: typeof params.moviehash === 'string' ? params.moviehash : undefined,
          type: params.type as 'movie' | 'episode' | 'all' | undefined,
        });
        return { ok: true, results };
      } catch (err) {
        return { ok: false, error: String((err as Error).message) };
      }
    },
  );

  ipcMain.handle(IpcChannels.SUBTITLES_DOWNLOAD_AND_LOAD, async (_event, fileId: number) => {
    if (typeof fileId !== 'number') {
      return { ok: false, error: 'Invalid file id' };
    }
    try {
      const { path: subPath, remaining } = await downloadSubtitle(fileId);
      try {
        await getPlayer().addSubtitleFile(subPath);
      } catch (err) {
        log.warn('sub-add after OpenSubtitles download failed:', err);
      }
      return { ok: true, path: subPath, remaining };
    } catch (err) {
      return { ok: false, error: String((err as Error).message) };
    }
  });

  // Fullscreen — managed by Electron main window
  ipcMain.handle(IpcChannels.PLAYER_SET_FULLSCREEN, (_event, fullscreen: boolean) => {
    const main = getMainWindow();
    if (!main) return { ok: false };
    main.setFullScreen(fullscreen);
    return { ok: true, fullscreen: main.isFullScreen() };
  });

  // Window controls (custom titlebar)
  ipcMain.handle(IpcChannels.WINDOW_MINIMIZE, () => {
    getMainWindow()?.minimize();
  });

  ipcMain.handle(IpcChannels.WINDOW_MAXIMIZE, () => {
    const main = getMainWindow();
    if (!main) return;
    if (main.isMaximized()) {
      main.unmaximize();
    } else {
      main.maximize();
    }
  });

  ipcMain.handle(IpcChannels.WINDOW_CLOSE, () => {
    getMainWindow()?.close();
  });

  ipcMain.handle(IpcChannels.WINDOW_IS_MAXIMIZED, () => {
    return getMainWindow()?.isMaximized() ?? false;
  });

  // mpv availability check
  ipcMain.handle(IpcChannels.PLAYER_CHECK_MPV, () => {
    return { available: findMpvPath() !== null };
  });

  log.info('IPC handlers registered');
}
