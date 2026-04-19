import type { DB } from '@op-engineering/op-sqlite';

const mockExecute = jest.fn();
const mockTransaction = jest.fn(
  async (work: (tx: { execute: typeof mockExecute }) => Promise<void>) => {
    await work({ execute: mockExecute });
  },
);

jest.mock('./db', () => ({
  getDb: () =>
    ({ execute: mockExecute, transaction: mockTransaction }) as unknown as DB,
}));

// eslint-disable-next-line @typescript-eslint/no-require-imports
const sources = require('./sources-store') as typeof import('./sources-store');

beforeEach(() => {
  mockExecute.mockReset();
  mockTransaction.mockClear();
});

describe('getAllSources', () => {
  it('orders by priority then created_at desc and maps columns to camelCase', async () => {
    mockExecute.mockResolvedValueOnce({
      rows: [
        {
          id: 's1',
          name: 'Alpha',
          type: 'xtream',
          url: 'http://a',
          epg_url: null,
          username_encrypted: 'user',
          password_encrypted: 'pw',
          mac_address_encrypted: null,
          priority: 0,
          channel_count: 10,
          last_sync_error: null,
          last_synced: 123,
          is_active: 1,
          created_at: 1,
          updated_at: 2,
        },
      ],
    });

    const result = await sources.getAllSources();

    const [sql] = mockExecute.mock.calls[0];
    expect(sql).toContain('ORDER BY priority ASC, created_at DESC');
    expect(result).toEqual([
      {
        id: 's1',
        name: 'Alpha',
        type: 'xtream',
        url: 'http://a',
        epgUrl: undefined,
        lastSynced: 123,
        lastSyncError: undefined,
        channelCount: 10,
        priority: 0,
        createdAt: 1,
        updatedAt: 2,
      },
    ]);
  });

  it('returns an empty array when rows is missing', async () => {
    mockExecute.mockResolvedValueOnce({});
    expect(await sources.getAllSources()).toEqual([]);
  });
});

describe('getSourceById', () => {
  it('returns null when no row matches', async () => {
    mockExecute.mockResolvedValueOnce({ rows: [] });
    expect(await sources.getSourceById('missing')).toBeNull();
    expect(mockExecute).toHaveBeenCalledWith(
      'SELECT * FROM sources WHERE id = ?',
      ['missing'],
    );
  });
});

describe('getSourceCredentials', () => {
  it('decodes plaintext BLOB strings into { username, password, macAddress }', async () => {
    mockExecute.mockResolvedValueOnce({
      rows: [
        {
          username_encrypted: 'alice',
          password_encrypted: 'hunter2',
          mac_address_encrypted: null,
        },
      ],
    });

    const creds = await sources.getSourceCredentials('s1');
    expect(creds).toEqual({
      username: 'alice',
      password: 'hunter2',
      macAddress: undefined,
    });
  });

  it('decodes Uint8Array BLOB payloads via TextDecoder', async () => {
    const enc = new TextEncoder();
    mockExecute.mockResolvedValueOnce({
      rows: [
        {
          username_encrypted: enc.encode('bob'),
          password_encrypted: null,
          mac_address_encrypted: enc.encode('AA:BB:CC'),
        },
      ],
    });

    const creds = await sources.getSourceCredentials('s1');
    expect(creds).toEqual({
      username: 'bob',
      password: undefined,
      macAddress: 'AA:BB:CC',
    });
  });

  it('returns an empty object when the source has no row', async () => {
    mockExecute.mockResolvedValueOnce({ rows: [] });
    expect(await sources.getSourceCredentials('nope')).toEqual({});
  });
});

describe('insertSource', () => {
  it('computes next priority as MAX(priority) + 1 and inserts plain m3u row', async () => {
    mockExecute
      .mockResolvedValueOnce({ rows: [{ max_p: 2 }] }) // MAX(priority)
      .mockResolvedValueOnce({}); // INSERT

    await sources.insertSource({
      id: 'src-1',
      name: 'Playlist',
      type: 'm3u_url',
      url: 'http://list.m3u',
    });

    const [insertSql, insertParams] = mockExecute.mock.calls[1];
    expect(insertSql).toMatch(/^INSERT INTO sources/);
    expect(insertParams[0]).toBe('src-1');
    expect(insertParams[1]).toBe('Playlist');
    expect(insertParams[2]).toBe('m3u_url');
    expect(insertParams[3]).toBe('http://list.m3u');
    expect(insertParams[4]).toBeNull(); // epg_url
    expect(insertParams[5]).toBeNull(); // username
    expect(insertParams[6]).toBeNull(); // password
    expect(insertParams[7]).toBeNull(); // mac
    expect(insertParams[8]).toBe(3); // priority
  });

  it('starts priority at 0 when the sources table is empty', async () => {
    mockExecute
      .mockResolvedValueOnce({ rows: [{ max_p: null }] })
      .mockResolvedValueOnce({});

    await sources.insertSource({
      id: 'src-first',
      name: 'First',
      type: 'm3u_url',
      url: 'http://first',
    });

    const [, insertParams] = mockExecute.mock.calls[1];
    expect(insertParams[8]).toBe(0);
  });

  it('passes xtream credentials through to the BLOB columns', async () => {
    mockExecute
      .mockResolvedValueOnce({ rows: [{ max_p: 0 }] })
      .mockResolvedValueOnce({});

    await sources.insertSource({
      id: 'src-x',
      name: 'Xtream',
      type: 'xtream',
      url: 'http://x',
      username: 'u',
      password: 'p',
    });

    const [, params] = mockExecute.mock.calls[1];
    expect(params[5]).toBe('u');
    expect(params[6]).toBe('p');
    expect(params[7]).toBeNull();
  });

  it('toggles ignore_check_constraints around a stalker insert', async () => {
    mockExecute
      .mockResolvedValueOnce({ rows: [{ max_p: 0 }] }) // MAX
      .mockResolvedValueOnce({}) // PRAGMA ON
      .mockResolvedValueOnce({}) // INSERT
      .mockResolvedValueOnce({}); // PRAGMA OFF

    await sources.insertSource({
      id: 'src-s',
      name: 'Portal',
      type: 'stalker',
      url: 'http://portal',
      macAddress: 'AA:BB:CC:DD:EE:FF',
    });

    const pragmaOn = mockExecute.mock.calls[1][0] as string;
    const insertSql = mockExecute.mock.calls[2][0] as string;
    const pragmaOff = mockExecute.mock.calls[3][0] as string;

    expect(pragmaOn).toBe('PRAGMA ignore_check_constraints = ON');
    expect(insertSql).toMatch(/^INSERT INTO sources/);
    expect(pragmaOff).toBe('PRAGMA ignore_check_constraints = OFF');

    const insertParams = mockExecute.mock.calls[2][1];
    expect(insertParams[7]).toBe('AA:BB:CC:DD:EE:FF');
  });

  it('turns the constraint-bypass pragma off even if the insert throws', async () => {
    mockExecute
      .mockResolvedValueOnce({ rows: [{ max_p: 0 }] })
      .mockResolvedValueOnce({}) // PRAGMA ON
      .mockRejectedValueOnce(new Error('boom')) // INSERT fails
      .mockResolvedValueOnce({}); // PRAGMA OFF (finally)

    await expect(
      sources.insertSource({
        id: 'bad',
        name: 'Bad',
        type: 'stalker',
        url: 'http://x',
        macAddress: 'AA:BB',
      }),
    ).rejects.toThrow('boom');

    const pragmaOff = mockExecute.mock.calls[3][0] as string;
    expect(pragmaOff).toBe('PRAGMA ignore_check_constraints = OFF');
  });
});

describe('updateSourceSync', () => {
  it('only writes columns that are explicitly set (and always bumps updated_at)', async () => {
    mockExecute.mockResolvedValueOnce({});

    await sources.updateSourceSync('src-1', {
      lastSynced: 1000,
      channelCount: 42,
    });

    const [sql, params] = mockExecute.mock.calls[0];
    expect(sql).toMatch(/^UPDATE sources SET /);
    expect(sql).toContain('last_synced = ?');
    expect(sql).toContain('channel_count = ?');
    expect(sql).not.toContain('last_sync_error');
    expect(sql).toContain('updated_at = ?');
    expect(sql).toMatch(/WHERE id = \?$/);

    expect(params[0]).toBe(1000);
    expect(params[1]).toBe(42);
    expect(typeof params[2]).toBe('number'); // updated_at = Date.now()
    expect(params[3]).toBe('src-1');
  });

  it('explicit null for lastSyncError clears the column', async () => {
    mockExecute.mockResolvedValueOnce({});
    await sources.updateSourceSync('src-1', { lastSyncError: null });

    const [sql, params] = mockExecute.mock.calls[0];
    expect(sql).toContain('last_sync_error = ?');
    expect(params[0]).toBeNull();
  });

  it('an undefined lastSyncError leaves the column untouched', async () => {
    mockExecute.mockResolvedValueOnce({});
    await sources.updateSourceSync('src-1', { channelCount: 7 });
    const [sql] = mockExecute.mock.calls[0];
    expect(sql).not.toContain('last_sync_error');
  });
});

describe('deleteSource', () => {
  it('clears FTS inside a transaction and then removes the source row', async () => {
    mockExecute.mockResolvedValue({});

    await sources.deleteSource('src-1');

    expect(mockTransaction).toHaveBeenCalledTimes(1);

    const [ftsSql, ftsParams] = mockExecute.mock.calls[0];
    expect(ftsSql).toContain('DELETE FROM content_fts');
    expect(ftsSql).toContain('source_id = ?');
    expect(ftsParams).toEqual(['src-1']);

    const [sourceSql, sourceParams] = mockExecute.mock.calls[1];
    expect(sourceSql).toBe('DELETE FROM sources WHERE id = ?');
    expect(sourceParams).toEqual(['src-1']);
  });
});
