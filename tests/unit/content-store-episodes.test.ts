import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { storeXtreamEpisodes } from '../../src/main/services/content-store';
import { XtreamClient } from '../../src/main/services/xtream-client';

// Mock database
vi.mock('../../src/main/services/db', () => {
  const prepared = {
    run: vi.fn(),
  };
  const db = {
    prepare: vi.fn(() => prepared),
    transaction: vi.fn((fn: () => void) => fn),
  };
  return { getDb: () => db };
});

vi.mock('electron-log/main', () => ({
  default: { info: vi.fn(), error: vi.fn(), warn: vi.fn() },
}));

vi.mock('crypto', () => ({
  randomUUID: () => 'test-uuid-' + Math.random().toString(36).slice(2, 8),
}));

describe('storeXtreamEpisodes', () => {
  let client: XtreamClient;

  beforeEach(() => {
    vi.clearAllMocks();
    client = new XtreamClient('http://provider.com', 'user', 'pass');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('builds correct episode URLs', () => {
    const spy = vi.spyOn(client, 'buildStreamUrl');

    storeXtreamEpisodes('content-1', client, {
      '1': [
        {
          id: '1001',
          episodeNum: 1,
          title: 'Pilot',
          containerExtension: 'mp4',
          info: { duration: '00:58:00', season: 1 },
        },
      ],
    });

    expect(spy).toHaveBeenCalledWith(1001, 'series', 'mp4');
  });

  it('skips episodes with non-numeric IDs', () => {
    const spy = vi.spyOn(client, 'buildStreamUrl');

    const count = storeXtreamEpisodes('content-1', client, {
      '1': [
        {
          id: 'abc',
          episodeNum: 1,
          title: 'Bad ID',
          containerExtension: 'mp4',
          info: {},
        },
        {
          id: '1002',
          episodeNum: 2,
          title: 'Good ID',
          containerExtension: 'mp4',
          info: {},
        },
      ],
    });

    // Only the valid episode should be built
    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy).toHaveBeenCalledWith(1002, 'series', 'mp4');
    expect(count).toBe(1);
  });

  it('skips episodes with empty string IDs', () => {
    const spy = vi.spyOn(client, 'buildStreamUrl');

    const count = storeXtreamEpisodes('content-1', client, {
      '1': [
        {
          id: '',
          episodeNum: 1,
          title: 'Empty ID',
          containerExtension: 'mp4',
          info: {},
        },
      ],
    });

    expect(spy).not.toHaveBeenCalled();
    expect(count).toBe(0);
  });

  it('handles empty extension by falling back to mp4', () => {
    const spy = vi.spyOn(client, 'buildStreamUrl');

    storeXtreamEpisodes('content-1', client, {
      '1': [
        {
          id: '2001',
          episodeNum: 1,
          title: 'No Extension',
          containerExtension: '',
          info: {},
        },
      ],
    });

    expect(spy).toHaveBeenCalledWith(2001, 'series', '');
    // The buildStreamUrl will use default 'mp4' for empty extension
    const url = client.buildStreamUrl(2001, 'series', '');
    expect(url).toBe('http://provider.com/series/user/pass/2001.mp4');
  });

  it('handles multiple seasons with valid episodes', () => {
    const count = storeXtreamEpisodes('content-1', client, {
      '1': [
        { id: '3001', episodeNum: 1, title: 'S1E1', containerExtension: 'mp4', info: { season: 1 } },
        { id: '3002', episodeNum: 2, title: 'S1E2', containerExtension: 'mp4', info: { season: 1 } },
      ],
      '2': [
        { id: '3003', episodeNum: 1, title: 'S2E1', containerExtension: 'mkv', info: { season: 2 } },
      ],
    });

    expect(count).toBe(3);
  });
});
