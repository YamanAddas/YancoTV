import { describe, it, expect, vi, beforeEach } from 'vitest';
import http from 'http';
import https from 'https';
import { XtreamClient } from '../../src/main/services/xtream-client';

// Mock modules
vi.mock('http');
vi.mock('https');
vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), error: vi.fn(), warn: vi.fn() },
}));

/** Simulate a successful HTTP JSON response */
function mockHttpGet(responseData: unknown) {
  const mockGet = vi.fn((_url: string, _opts: unknown, callback: (res: unknown) => void) => {
    const res = {
      statusCode: 200,
      on: vi.fn((event: string, handler: (data?: Buffer) => void) => {
        if (event === 'data') handler(Buffer.from(JSON.stringify(responseData)));
        if (event === 'end') handler();
      }),
    };
    callback(res);
    return { on: vi.fn(), destroy: vi.fn() };
  });

  vi.mocked(http.get).mockImplementation(mockGet as unknown as typeof http.get);
  vi.mocked(https.get).mockImplementation(mockGet as unknown as typeof https.get);
}

/** Simulate a network error */
function mockHttpError(error: Error) {
  const mockGet = vi.fn(() => {
    return {
      on: vi.fn((event: string, handler: (err: Error) => void) => {
        if (event === 'error') handler(error);
      }),
      destroy: vi.fn(),
    };
  });

  vi.mocked(http.get).mockImplementation(mockGet as unknown as typeof http.get);
  vi.mocked(https.get).mockImplementation(mockGet as unknown as typeof https.get);
}

describe('XtreamClient', () => {
  let client: XtreamClient;

  beforeEach(() => {
    vi.clearAllMocks();
    client = new XtreamClient('http://provider.com', 'user1', 'pass1');
  });

  describe('constructor', () => {
    it('normalizes base URL', () => {
      const c1 = new XtreamClient('http://provider.com/', 'u', 'p');
      expect(c1.buildStreamUrl(1, 'live')).toBe('http://provider.com/live/u/p/1.ts');

      const c2 = new XtreamClient('http://provider.com/player_api.php', 'u', 'p');
      expect(c2.buildStreamUrl(1, 'live')).toBe('http://provider.com/live/u/p/1.ts');
    });
  });

  describe('buildStreamUrl', () => {
    it('builds live stream URL', () => {
      expect(client.buildStreamUrl(123, 'live')).toBe(
        'http://provider.com/live/user1/pass1/123.ts',
      );
    });

    it('builds movie stream URL', () => {
      expect(client.buildStreamUrl(456, 'movie')).toBe(
        'http://provider.com/movie/user1/pass1/456.mp4',
      );
    });

    it('builds series stream URL with custom extension', () => {
      expect(client.buildStreamUrl(789, 'series', 'mkv')).toBe(
        'http://provider.com/series/user1/pass1/789.mkv',
      );
    });

    it('defaults to ts for live when no extension provided', () => {
      expect(client.buildStreamUrl(100, 'live')).toContain('.ts');
    });

    it('defaults to mp4 for movie when no extension provided', () => {
      expect(client.buildStreamUrl(100, 'movie')).toContain('.mp4');
    });

    it('defaults to mp4 for series when no extension provided', () => {
      expect(client.buildStreamUrl(100, 'series')).toContain('.mp4');
    });

    it('falls back to default extension when empty string is provided', () => {
      expect(client.buildStreamUrl(100, 'movie', '')).toBe(
        'http://provider.com/movie/user1/pass1/100.mp4',
      );
    });

    it('falls back to default extension when whitespace-only string is provided', () => {
      expect(client.buildStreamUrl(100, 'movie', '  ')).toBe(
        'http://provider.com/movie/user1/pass1/100.mp4',
      );
    });

    it('falls back to default extension for live when empty string is provided', () => {
      expect(client.buildStreamUrl(100, 'live', '')).toBe(
        'http://provider.com/live/user1/pass1/100.ts',
      );
    });

    it('trims whitespace from extension', () => {
      expect(client.buildStreamUrl(100, 'movie', ' mkv ')).toBe(
        'http://provider.com/movie/user1/pass1/100.mkv',
      );
    });

    it('handles all valid Xtream container extensions', () => {
      const extensions = ['mp4', 'mkv', 'avi', 'ts', 'flv'];
      for (const ext of extensions) {
        const url = client.buildStreamUrl(100, 'movie', ext);
        expect(url).toMatch(new RegExp(`\\.${ext}$`));
      }
    });
  });

  describe('authenticate', () => {
    it('returns auth info on success', async () => {
      mockHttpGet({
        user_info: {
          username: 'user1',
          status: 'Active',
          exp_date: '1700000000',
          is_trial: '0',
          active_cons: 1,
          max_connections: 2,
        },
        server_info: {
          url: 'provider.com',
          port: '80',
          server_protocol: 'http',
          time_now: '2024-01-01 00:00:00',
          timezone: 'UTC',
        },
      });

      const result = await client.authenticate();
      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value.userInfo.username).toBe('user1');
        expect(result.value.userInfo.status).toBe('Active');
        expect(result.value.userInfo.isTrial).toBe(false);
      }
    });

    it('returns error for disabled account', async () => {
      mockHttpGet({
        user_info: { auth: 0, status: 'Disabled', username: 'user1' },
        server_info: {},
      });

      const result = await client.authenticate();
      expect(result.ok).toBe(false);
    });

    it('returns error for missing user_info', async () => {
      mockHttpGet({});

      const result = await client.authenticate();
      expect(result.ok).toBe(false);
    });
  });

  describe('getLiveStreams', () => {
    it('parses live streams response', async () => {
      mockHttpGet([
        {
          num: 1,
          name: 'CNN',
          stream_type: 'live',
          stream_id: 101,
          stream_icon: 'http://icon.com/cnn.png',
          epg_channel_id: 'cnn.us',
          category_id: '5',
        },
        {
          num: 2,
          name: 'BBC',
          stream_type: 'live',
          stream_id: 102,
          stream_icon: '',
          epg_channel_id: 'bbc.uk',
          category_id: '5',
        },
      ]);

      const result = await client.getLiveStreams();
      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value).toHaveLength(2);
        expect(result.value[0].name).toBe('CNN');
        expect(result.value[0].streamId).toBe(101);
        expect(result.value[0].epgChannelId).toBe('cnn.us');
      }
    });

    it('handles non-array response gracefully', async () => {
      mockHttpGet(null);

      const result = await client.getLiveStreams();
      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value).toHaveLength(0);
      }
    });
  });

  describe('getVodStreams', () => {
    it('parses VOD streams response', async () => {
      mockHttpGet([
        {
          num: 1,
          name: 'The Matrix',
          stream_type: 'movie',
          stream_id: 201,
          stream_icon: 'http://icon.com/matrix.jpg',
          rating: '8.7',
          category_id: '10',
          container_extension: 'mp4',
        },
      ]);

      const result = await client.getVodStreams();
      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value).toHaveLength(1);
        expect(result.value[0].name).toBe('The Matrix');
        expect(result.value[0].rating).toBe('8.7');
        expect(result.value[0].containerExtension).toBe('mp4');
      }
    });
  });

  describe('getSeriesList', () => {
    it('parses series list response', async () => {
      mockHttpGet([
        {
          num: 1,
          name: 'Breaking Bad',
          series_id: 301,
          cover: 'http://cover.com/bb.jpg',
          plot: 'A chemistry teacher turns to crime',
          genre: 'Drama',
          rating: '9.5',
          category_id: '15',
        },
      ]);

      const result = await client.getSeriesList();
      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value).toHaveLength(1);
        expect(result.value[0].name).toBe('Breaking Bad');
        expect(result.value[0].seriesId).toBe(301);
        expect(result.value[0].genre).toBe('Drama');
      }
    });
  });

  describe('getSeriesInfo', () => {
    it('parses series detail response', async () => {
      mockHttpGet({
        info: {
          name: 'Breaking Bad',
          cover: 'http://cover.com/bb.jpg',
          plot: 'Plot text',
          genre: 'Drama',
          rating: '9.5',
        },
        seasons: [
          { season_number: 1, name: 'Season 1' },
          { season_number: 2, name: 'Season 2' },
        ],
        episodes: {
          '1': [
            {
              id: '1001',
              episode_num: 1,
              title: 'Pilot',
              container_extension: 'mp4',
              info: { duration: '00:58:00', season: 1 },
            },
            {
              id: '1002',
              episode_num: 2,
              title: "Cat's in the Bag",
              container_extension: 'mp4',
              info: { duration: '00:48:00', season: 1 },
            },
          ],
        },
      });

      const result = await client.getSeriesInfo(301);
      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value.info.name).toBe('Breaking Bad');
        expect(result.value.seasons).toHaveLength(2);
        expect(result.value.episodes['1']).toHaveLength(2);
        expect(result.value.episodes['1'][0].title).toBe('Pilot');
      }
    });
  });

  describe('getLiveCategories', () => {
    it('parses categories response', async () => {
      mockHttpGet([
        { category_id: '1', category_name: 'News', parent_id: 0 },
        { category_id: '2', category_name: 'Sports', parent_id: 0 },
      ]);

      const result = await client.getLiveCategories();
      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value).toHaveLength(2);
        expect(result.value[0].categoryName).toBe('News');
        expect(result.value[1].categoryName).toBe('Sports');
      }
    });
  });

  describe('error handling', () => {
    it('returns error on network failure', async () => {
      mockHttpError(new Error('ECONNREFUSED'));

      const result = await client.authenticate();
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.error.message).toContain('ECONNREFUSED');
      }
    }, 20_000); // Retry logic adds delay before final failure
  });
});
