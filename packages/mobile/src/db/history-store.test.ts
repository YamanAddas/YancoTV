import type { DB } from '@op-engineering/op-sqlite';

const mockExecute = jest.fn();

jest.mock('./db', () => ({
  getDb: () => ({ execute: mockExecute }) as unknown as DB,
}));

// eslint-disable-next-line @typescript-eslint/no-require-imports
const history = require('./history-store') as typeof import('./history-store');

beforeEach(() => {
  mockExecute.mockReset();
});

describe('recordWatch', () => {
  it('inserts a fresh row at position 0 and returns the new id', async () => {
    mockExecute.mockResolvedValueOnce({});
    const id = await history.recordWatch('content-1', 'episode-1');

    expect(typeof id).toBe('string');
    expect(id.length).toBeGreaterThan(0);

    const [sql, params] = mockExecute.mock.calls[0];
    expect(sql).toMatch(/^INSERT INTO watch_history/);
    expect(params[0]).toBe(id);
    expect(params[1]).toBe('content-1');
    expect(params[2]).toBe('episode-1');
    expect(typeof params[3]).toBe('number'); // watched_at
  });

  it('stores episode_id as NULL when omitted', async () => {
    mockExecute.mockResolvedValueOnce({});
    await history.recordWatch('content-1');
    const [, params] = mockExecute.mock.calls[0];
    expect(params[2]).toBeNull();
  });
});

describe('updatePosition', () => {
  it('writes position and duration when both are provided', async () => {
    mockExecute.mockResolvedValueOnce({});
    await history.updatePosition('hist-1', 123, 456);
    const [sql, params] = mockExecute.mock.calls[0];
    expect(sql).toMatch(/^UPDATE watch_history/);
    expect(params[0]).toBe(123);
    expect(params[1]).toBe(456);
    expect(params[3]).toBe('hist-1');
  });

  it('passes null for durationSeconds so COALESCE keeps the existing value', async () => {
    mockExecute.mockResolvedValueOnce({});
    await history.updatePosition('hist-1', 42);
    const [, params] = mockExecute.mock.calls[0];
    expect(params[1]).toBeNull();
  });
});

describe('getLastPosition', () => {
  it('looks up by (content, episode) when episodeId is provided', async () => {
    mockExecute.mockResolvedValueOnce({
      rows: [{ position_seconds: 90, duration_seconds: 1800 }],
    });

    const pos = await history.getLastPosition('content-1', 'ep-1');

    const [sql, params] = mockExecute.mock.calls[0];
    expect(sql).toContain('episode_id = ?');
    expect(params).toEqual(['content-1', 'ep-1']);
    expect(pos).toEqual({ positionSeconds: 90, durationSeconds: 1800 });
  });

  it('looks up rows with NULL episode when episodeId is omitted', async () => {
    mockExecute.mockResolvedValueOnce({
      rows: [{ position_seconds: 15, duration_seconds: null }],
    });

    const pos = await history.getLastPosition('content-1');

    const [sql, params] = mockExecute.mock.calls[0];
    expect(sql).toContain('episode_id IS NULL');
    expect(params).toEqual(['content-1']);
    expect(pos).toEqual({ positionSeconds: 15, durationSeconds: undefined });
  });

  it('returns null when no row is found', async () => {
    mockExecute.mockResolvedValueOnce({ rows: [] });
    expect(await history.getLastPosition('content-1')).toBeNull();
  });
});

describe('getPositionsBatch', () => {
  it('short-circuits without a DB call when the id list is empty', async () => {
    const result = await history.getPositionsBatch('content-1', []);
    expect(result).toEqual({});
    expect(mockExecute).not.toHaveBeenCalled();
  });

  it('builds the IN-clause placeholders from the id list', async () => {
    mockExecute.mockResolvedValueOnce({ rows: [] });
    await history.getPositionsBatch('content-1', ['a', 'b', 'c']);
    const [sql, params] = mockExecute.mock.calls[0];
    expect(sql).toContain('episode_id IN (?,?,?)');
    expect(params).toEqual(['content-1', 'a', 'b', 'c']);
  });

  it('maps rows by episode_id and drops zero-position rows', async () => {
    mockExecute.mockResolvedValueOnce({
      rows: [
        { episode_id: 'a', position_seconds: 60, duration_seconds: 1200 },
        { episode_id: 'b', position_seconds: 0, duration_seconds: 1200 },
        { episode_id: 'c', position_seconds: 30, duration_seconds: null },
      ],
    });

    const result = await history.getPositionsBatch('content-1', ['a', 'b', 'c']);

    expect(result).toEqual({
      a: { positionSeconds: 60, durationSeconds: 1200 },
      c: { positionSeconds: 30, durationSeconds: undefined },
    });
  });
});

describe('getRecentlyWatched', () => {
  it('joins content and maps rows into HistoryEntry shape', async () => {
    mockExecute.mockResolvedValueOnce({
      rows: [
        {
          id: 'hist-1',
          content_id: 'c1',
          episode_id: null,
          position_seconds: 30,
          duration_seconds: 1800,
          watched_at: 12345,
          source_id: 's1',
          type: 'movie',
          title: 'Title',
          clean_title: null,
          group_name: 'Group',
          stream_url: 'http://x',
          logo_url: null,
          tvg_id: null,
          metadata_json: null,
          sort_order: 0,
          created_at: 9999,
        },
      ],
    });

    const entries = await history.getRecentlyWatched(5);

    expect(entries).toHaveLength(1);
    expect(entries[0]).toMatchObject({
      id: 'hist-1',
      contentId: 'c1',
      positionSeconds: 30,
      content: {
        id: 'c1',
        type: 'movie',
        title: 'Title',
        groupName: 'Group',
        streamUrl: 'http://x',
      },
    });
    expect(entries[0].episodeId).toBeUndefined();

    const [sql, params] = mockExecute.mock.calls[0];
    expect(sql).toContain('JOIN content');
    expect(params).toEqual([5]);
  });
});

describe('removeHistoryEntry / clearHistory', () => {
  it('deletes by id', async () => {
    mockExecute.mockResolvedValueOnce({});
    await history.removeHistoryEntry('hist-1');
    expect(mockExecute).toHaveBeenCalledWith(
      'DELETE FROM watch_history WHERE id = ?',
      ['hist-1'],
    );
  });

  it('clears the entire table', async () => {
    mockExecute.mockResolvedValueOnce({});
    await history.clearHistory();
    expect(mockExecute).toHaveBeenCalledWith('DELETE FROM watch_history');
  });
});
