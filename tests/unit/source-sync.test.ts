import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { Source } from '../../src/shared/types/source';

// --- Mocks ---

vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}));

vi.mock('electron', () => ({
  BrowserWindow: {
    getAllWindows: vi.fn(() => []),
  },
}));

vi.mock('fs/promises', () => ({
  default: {
    stat: vi.fn(),
    readFile: vi.fn(),
  },
}));

vi.mock('http');
vi.mock('https');

const mockGetSourceById = vi.fn();
const mockGetAllSources = vi.fn();
const mockUpdateSourceSyncTime = vi.fn();
const mockGetSourceCredentials = vi.fn();
const mockGetSourceMacAddress = vi.fn();
const mockUpdateSourceEpgUrl = vi.fn();
const mockUpdateSourceHealth = vi.fn();

vi.mock('../../src/main/services/source-manager', () => ({
  getSourceById: (...args: unknown[]) => mockGetSourceById(...args),
  getAllSources: (...args: unknown[]) => mockGetAllSources(...args),
  updateSourceSyncTime: (...args: unknown[]) => mockUpdateSourceSyncTime(...args),
  getSourceCredentials: (...args: unknown[]) => mockGetSourceCredentials(...args),
  getSourceMacAddress: (...args: unknown[]) => mockGetSourceMacAddress(...args),
  updateSourceEpgUrl: (...args: unknown[]) => mockUpdateSourceEpgUrl(...args),
  updateSourceHealth: (...args: unknown[]) => mockUpdateSourceHealth(...args),
}));

const mockParseM3u = vi.fn();
vi.mock('../../src/main/services/m3u-parser', () => ({
  parseM3u: (...args: unknown[]) => mockParseM3u(...args),
}));

const mockStoreM3uEntries = vi.fn();
const mockStoreXtreamContent = vi.fn();
const mockStoreStalkerContent = vi.fn();
vi.mock('../../src/main/services/content-store', () => ({
  storeM3uEntries: (...args: unknown[]) => mockStoreM3uEntries(...args),
  storeXtreamContent: (...args: unknown[]) => mockStoreXtreamContent(...args),
  storeStalkerContent: (...args: unknown[]) => mockStoreStalkerContent(...args),
}));

const mockXtreamAuthenticate = vi.fn();
const mockXtreamBuildEpgUrl = vi.fn();
const mockXtreamGetLiveCategories = vi.fn();
const mockXtreamGetVodCategories = vi.fn();
const mockXtreamGetSeriesCategories = vi.fn();
const mockXtreamGetLiveStreams = vi.fn();
const mockXtreamGetVodStreams = vi.fn();
const mockXtreamGetSeriesList = vi.fn();

vi.mock('../../src/main/services/xtream-client', () => ({
  XtreamClient: vi.fn().mockImplementation(() => ({
    authenticate: mockXtreamAuthenticate,
    buildEpgUrl: mockXtreamBuildEpgUrl,
    getLiveCategories: mockXtreamGetLiveCategories,
    getVodCategories: mockXtreamGetVodCategories,
    getSeriesCategories: mockXtreamGetSeriesCategories,
    getLiveStreams: mockXtreamGetLiveStreams,
    getVodStreams: mockXtreamGetVodStreams,
    getSeriesList: mockXtreamGetSeriesList,
  })),
}));

const mockStalkerAuthenticate = vi.fn();
const mockStalkerGetLiveCategories = vi.fn();
const mockStalkerGetVodCategories = vi.fn();
const mockStalkerGetSeriesCategories = vi.fn();
const mockStalkerGetLiveChannels = vi.fn();
const mockStalkerGetVodItems = vi.fn();
const mockStalkerGetSeriesList = vi.fn();

vi.mock('../../src/main/services/stalker-client', () => ({
  StalkerClient: vi.fn().mockImplementation(() => ({
    authenticate: mockStalkerAuthenticate,
    getLiveCategories: mockStalkerGetLiveCategories,
    getVodCategories: mockStalkerGetVodCategories,
    getSeriesCategories: mockStalkerGetSeriesCategories,
    getLiveChannels: mockStalkerGetLiveChannels,
    getVodItems: mockStalkerGetVodItems,
    getSeriesList: mockStalkerGetSeriesList,
  })),
}));

vi.mock('../../src/main/services/settings-service', () => ({
  getSetting: vi.fn(() => '0'),
}));

import { syncSource, isSyncing } from '../../src/main/services/source-sync';
import fs from 'fs/promises';
import http from 'http';
import https from 'https';

// --- Helpers ---

function makeSource(overrides: Partial<Source>): Source {
  return {
    id: 'src-1',
    name: 'Test Source',
    type: 'm3u_url',
    url: 'http://example.com/playlist.m3u',
    isActive: true,
    priority: 0,
    channelCount: 0,
    autoSyncInterval: 0,
    createdAt: Date.now(),
    updatedAt: Date.now(),
    ...overrides,
  };
}

/** Simulate a successful HTTP response returning the given body string */
function mockHttpSuccess(body: string) {
  const mockGet = vi.fn((_url: string, _opts: unknown, callback: (res: unknown) => void) => {
    const res = {
      statusCode: 200,
      on: vi.fn((event: string, handler: (data?: Buffer) => void) => {
        if (event === 'data') handler(Buffer.from(body));
        if (event === 'end') handler();
      }),
    };
    callback(res);
    return { on: vi.fn(), destroy: vi.fn() };
  });
  vi.mocked(http.get).mockImplementation(mockGet as unknown as typeof http.get);
  vi.mocked(https.get).mockImplementation(mockGet as unknown as typeof https.get);
}

/** Simulate an HTTP network error */
function mockHttpError(error: Error) {
  const mockGet = vi.fn(() => ({
    on: vi.fn((event: string, handler: (err: Error) => void) => {
      if (event === 'error') handler(error);
    }),
    destroy: vi.fn(),
  }));
  vi.mocked(http.get).mockImplementation(mockGet as unknown as typeof http.get);
  vi.mocked(https.get).mockImplementation(mockGet as unknown as typeof https.get);
}

// --- Tests ---

describe('source-sync', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('syncSource', () => {
    it('returns error for non-existent source', async () => {
      mockGetSourceById.mockReturnValue(undefined);

      const result = await syncSource('non-existent-id');

      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.error.message).toBe('Source not found');
      }
    });

    it('returns error when sync is already in progress for the same source', async () => {
      // Create a source that will trigger a long-running sync
      const source = makeSource({ id: 'src-concurrent', type: 'm3u_url' });
      mockGetSourceById.mockReturnValue(source);

      // Make HTTP fetch hang indefinitely
      const mockGet = vi.fn((_url: string, _opts: unknown, _callback: unknown) => {
        // Never call callback — simulates a pending request
        return { on: vi.fn(), destroy: vi.fn() };
      });
      vi.mocked(http.get).mockImplementation(mockGet as unknown as typeof http.get);

      // Start first sync (will not resolve)
      const firstSync = syncSource('src-concurrent');

      // Second sync should fail immediately
      const result = await syncSource('src-concurrent');
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.error.message).toBe('Sync already in progress for this source');
      }

      // Clean up: the first sync is still pending, but the activeSyncs set
      // will be cleaned up by the finally block when the promise settles.
      // We don't await firstSync since it will never resolve in this test.
    });

    it('calls M3U parser for m3u_url source type', async () => {
      const source = makeSource({ type: 'm3u_url', url: 'http://example.com/list.m3u' });
      mockGetSourceById.mockReturnValue(source);

      const m3uContent = '#EXTM3U\n#EXTINF:-1,Channel 1\nhttp://stream.com/1';
      mockHttpSuccess(m3uContent);
      mockParseM3u.mockReturnValue({ entries: [{ title: 'Channel 1' }], epgUrl: null });
      mockStoreM3uEntries.mockResolvedValue(1);

      const result = await syncSource('src-1');

      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value).toBe(1);
      }
      expect(mockParseM3u).toHaveBeenCalledWith(m3uContent);
      expect(mockStoreM3uEntries).toHaveBeenCalledWith(
        'src-1',
        [{ title: 'Channel 1' }],
        expect.any(Function),
      );
      expect(mockUpdateSourceSyncTime).toHaveBeenCalledWith('src-1');
    });

    it('calls M3U parser for m3u_file source type', async () => {
      const source = makeSource({
        type: 'm3u_file',
        filePath: 'C:\\playlists\\local.m3u',
        url: undefined,
      });
      mockGetSourceById.mockReturnValue(source);

      const fileContent = '#EXTM3U\n#EXTINF:-1,Local Channel\nhttp://stream.com/local';
      vi.mocked(fs.stat).mockResolvedValue({ size: 1024 } as Awaited<ReturnType<typeof fs.stat>>);
      vi.mocked(fs.readFile).mockResolvedValue(fileContent);
      mockParseM3u.mockReturnValue({
        entries: [{ title: 'Local Channel' }],
        epgUrl: 'http://epg.example.com/xmltv.xml',
      });
      mockStoreM3uEntries.mockResolvedValue(1);

      const result = await syncSource('src-1');

      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value).toBe(1);
      }
      expect(vi.mocked(fs.readFile)).toHaveBeenCalledWith('C:\\playlists\\local.m3u', 'utf-8');
      expect(mockParseM3u).toHaveBeenCalledWith(fileContent);
      expect(mockStoreM3uEntries).toHaveBeenCalledWith(
        'src-1',
        [{ title: 'Local Channel' }],
        expect.any(Function),
      );
      // EPG URL detected from M3U should be saved
      expect(mockUpdateSourceEpgUrl).toHaveBeenCalledWith(
        'src-1',
        'http://epg.example.com/xmltv.xml',
      );
    });

    it('calls XtreamClient for xtream source type', async () => {
      const source = makeSource({ type: 'xtream', url: 'http://xtream.example.com' });
      mockGetSourceById.mockReturnValue(source);
      mockGetSourceCredentials.mockReturnValue({ username: 'user1', password: 'pass1' });

      mockXtreamAuthenticate.mockResolvedValue({
        ok: true,
        value: { userInfo: { username: 'user1', status: 'Active' } },
      });
      mockXtreamBuildEpgUrl.mockReturnValue('http://xtream.example.com/xmltv.php');

      const emptyOk = { ok: true, value: [] };
      mockXtreamGetLiveCategories.mockResolvedValue(emptyOk);
      mockXtreamGetVodCategories.mockResolvedValue(emptyOk);
      mockXtreamGetSeriesCategories.mockResolvedValue(emptyOk);
      mockXtreamGetLiveStreams.mockResolvedValue(emptyOk);
      mockXtreamGetVodStreams.mockResolvedValue(emptyOk);
      mockXtreamGetSeriesList.mockResolvedValue(emptyOk);

      mockStoreXtreamContent.mockResolvedValue(42);

      const result = await syncSource('src-1');

      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value).toBe(42);
      }
      expect(mockXtreamAuthenticate).toHaveBeenCalled();
      expect(mockStoreXtreamContent).toHaveBeenCalledWith(
        'src-1',
        expect.any(Object), // the XtreamClient instance
        expect.objectContaining({ streams: [], categories: expect.any(Map) }),
        expect.objectContaining({ streams: [], categories: expect.any(Map) }),
        expect.objectContaining({ series: [], categories: expect.any(Map) }),
        expect.any(Function),
      );
      expect(mockUpdateSourceSyncTime).toHaveBeenCalledWith('src-1');
      expect(mockUpdateSourceEpgUrl).toHaveBeenCalledWith(
        'src-1',
        'http://xtream.example.com/xmltv.php',
      );
    });

    it('calls StalkerClient for stalker source type', async () => {
      const source = makeSource({ type: 'stalker', url: 'http://portal.example.com' });
      mockGetSourceById.mockReturnValue(source);
      mockGetSourceMacAddress.mockReturnValue('00:1A:79:AA:BB:CC');

      mockStalkerAuthenticate.mockResolvedValue({
        ok: true,
        value: { portalUrl: 'http://portal.example.com' },
      });

      const emptyOk = { ok: true, value: [] };
      mockStalkerGetLiveCategories.mockResolvedValue(emptyOk);
      mockStalkerGetVodCategories.mockResolvedValue(emptyOk);
      mockStalkerGetSeriesCategories.mockResolvedValue(emptyOk);
      mockStalkerGetLiveChannels.mockResolvedValue(emptyOk);
      mockStalkerGetVodItems.mockResolvedValue(emptyOk);
      mockStalkerGetSeriesList.mockResolvedValue(emptyOk);

      mockStoreStalkerContent.mockResolvedValue(15);

      const result = await syncSource('src-1');

      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value).toBe(15);
      }
      expect(mockStalkerAuthenticate).toHaveBeenCalled();
      expect(mockStoreStalkerContent).toHaveBeenCalledWith(
        'src-1',
        expect.any(Object), // the StalkerClient instance
        expect.objectContaining({ channels: [], categories: expect.any(Map) }),
        expect.objectContaining({ items: [], categories: expect.any(Map) }),
        expect.objectContaining({ series: [], categories: expect.any(Map) }),
        expect.any(Function),
      );
      expect(mockUpdateSourceSyncTime).toHaveBeenCalledWith('src-1');
    });

    it('returns count of synced items on success', async () => {
      const source = makeSource({ type: 'm3u_url', url: 'http://example.com/big.m3u' });
      mockGetSourceById.mockReturnValue(source);

      mockHttpSuccess('#EXTM3U\ncontent...');
      mockParseM3u.mockReturnValue({
        entries: Array.from({ length: 500 }, (_, i) => ({ title: `Ch ${i}` })),
        epgUrl: null,
      });
      mockStoreM3uEntries.mockResolvedValue(500);

      const result = await syncSource('src-1');

      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value).toBe(500);
      }
      expect(mockUpdateSourceHealth).toHaveBeenCalledWith('src-1', 500);
    });

    it('returns error on network failure for m3u_url', async () => {
      const source = makeSource({ type: 'm3u_url', url: 'http://example.com/broken.m3u' });
      mockGetSourceById.mockReturnValue(source);

      mockHttpError(new Error('ECONNREFUSED'));

      const result = await syncSource('src-1');

      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.error.message).toBe('ECONNREFUSED');
      }
      // Health should be updated with error
      expect(mockUpdateSourceHealth).toHaveBeenCalledWith('src-1', 0, 'ECONNREFUSED');
    });

    it('returns error when m3u_url source has no URL', async () => {
      const source = makeSource({ type: 'm3u_url', url: undefined });
      mockGetSourceById.mockReturnValue(source);

      const result = await syncSource('src-1');

      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.error.message).toBe('Source has no URL');
      }
    });

    it('returns error when m3u_file source has no file path', async () => {
      const source = makeSource({ type: 'm3u_file', filePath: undefined, url: undefined });
      mockGetSourceById.mockReturnValue(source);

      const result = await syncSource('src-1');

      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.error.message).toBe('Source has no file path');
      }
    });

    it('returns error when m3u_file exceeds max size', async () => {
      const source = makeSource({
        type: 'm3u_file',
        filePath: 'C:\\huge.m3u',
        url: undefined,
      });
      mockGetSourceById.mockReturnValue(source);

      // 300 MB — exceeds the 200 MB limit
      vi.mocked(fs.stat).mockResolvedValue({
        size: 300 * 1024 * 1024,
      } as Awaited<ReturnType<typeof fs.stat>>);

      const result = await syncSource('src-1');

      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.error.message).toContain('File too large');
        expect(result.error.message).toContain('200MB');
      }
    });

    it('returns error when xtream authentication fails', async () => {
      const source = makeSource({ type: 'xtream', url: 'http://xtream.example.com' });
      mockGetSourceById.mockReturnValue(source);
      mockGetSourceCredentials.mockReturnValue({ username: 'user1', password: 'pass1' });

      mockXtreamAuthenticate.mockResolvedValue({
        ok: false,
        error: new Error('Invalid credentials'),
      });

      const result = await syncSource('src-1');

      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.error.message).toBe('Invalid credentials');
      }
    });

    it('returns error when xtream source has no credentials', async () => {
      const source = makeSource({ type: 'xtream', url: 'http://xtream.example.com' });
      mockGetSourceById.mockReturnValue(source);
      mockGetSourceCredentials.mockReturnValue(null);

      const result = await syncSource('src-1');

      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.error.message).toBe('Xtream source has no credentials');
      }
    });

    it('returns error when stalker authentication fails', async () => {
      const source = makeSource({ type: 'stalker', url: 'http://portal.example.com' });
      mockGetSourceById.mockReturnValue(source);
      mockGetSourceMacAddress.mockReturnValue('00:1A:79:AA:BB:CC');

      mockStalkerAuthenticate.mockResolvedValue({
        ok: false,
        error: new Error('Portal rejected MAC'),
      });

      const result = await syncSource('src-1');

      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.error.message).toBe('Portal rejected MAC');
      }
    });

    it('clears syncing state even on failure', async () => {
      const source = makeSource({ type: 'm3u_url', url: 'http://example.com/fail.m3u' });
      mockGetSourceById.mockReturnValue(source);
      mockHttpError(new Error('Network down'));

      await syncSource('src-1');

      // After sync completes (even with error), isSyncing should be false
      expect(isSyncing('src-1')).toBe(false);
    });

    it('auto-detects EPG URL from M3U URL source', async () => {
      const source = makeSource({ type: 'm3u_url', url: 'http://example.com/list.m3u' });
      mockGetSourceById.mockReturnValue(source);

      mockHttpSuccess('#EXTM3U url-tvg="http://epg.example.com/guide.xml"\n');
      mockParseM3u.mockReturnValue({
        entries: [],
        epgUrl: 'http://epg.example.com/guide.xml',
      });
      mockStoreM3uEntries.mockResolvedValue(0);

      await syncSource('src-1');

      expect(mockUpdateSourceEpgUrl).toHaveBeenCalledWith(
        'src-1',
        'http://epg.example.com/guide.xml',
      );
    });
  });

  describe('isSyncing', () => {
    it('returns false when no sync is active', () => {
      expect(isSyncing('any-id')).toBe(false);
    });
  });
});
