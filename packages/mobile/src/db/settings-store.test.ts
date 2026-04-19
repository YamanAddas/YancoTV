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
const settings = require('./settings-store') as typeof import('./settings-store');

beforeEach(() => {
  mockExecute.mockReset();
  mockTransaction.mockClear();
});

describe('getSetting', () => {
  it('returns the value when the row exists', async () => {
    mockExecute.mockResolvedValueOnce({ rows: [{ value: 'dark' }] });
    expect(await settings.getSetting('theme')).toBe('dark');
    expect(mockExecute).toHaveBeenCalledWith(
      'SELECT value FROM settings WHERE key = ?',
      ['theme'],
    );
  });

  it('returns null when the key is missing', async () => {
    mockExecute.mockResolvedValueOnce({ rows: [] });
    expect(await settings.getSetting('nope')).toBeNull();
  });

  it('returns null when rows is undefined', async () => {
    mockExecute.mockResolvedValueOnce({});
    expect(await settings.getSetting('nope')).toBeNull();
  });
});

describe('setSetting', () => {
  it('uses INSERT OR REPLACE for upsert semantics', async () => {
    mockExecute.mockResolvedValueOnce({});
    await settings.setSetting('theme', 'dark');
    expect(mockExecute).toHaveBeenCalledWith(
      'INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)',
      ['theme', 'dark'],
    );
  });
});

describe('deleteSetting', () => {
  it('deletes the row by key', async () => {
    mockExecute.mockResolvedValueOnce({});
    await settings.deleteSetting('theme');
    expect(mockExecute).toHaveBeenCalledWith(
      'DELETE FROM settings WHERE key = ?',
      ['theme'],
    );
  });
});

describe('getAllSettings', () => {
  it('flattens rows into a key-value map', async () => {
    mockExecute.mockResolvedValueOnce({
      rows: [
        { key: 'theme', value: 'dark' },
        { key: 'lastSource', value: 'src-1' },
      ],
    });
    expect(await settings.getAllSettings()).toEqual({
      theme: 'dark',
      lastSource: 'src-1',
    });
  });

  it('returns an empty object when nothing is stored', async () => {
    mockExecute.mockResolvedValueOnce({ rows: [] });
    expect(await settings.getAllSettings()).toEqual({});
  });
});

describe('setSettings', () => {
  it('writes every entry inside a single transaction', async () => {
    mockExecute.mockResolvedValue({});

    await settings.setSettings({
      theme: 'dark',
      lastSource: 'src-1',
      epgRefreshHours: '6',
    });

    expect(mockTransaction).toHaveBeenCalledTimes(1);
    expect(mockExecute).toHaveBeenCalledTimes(3);
    expect(mockExecute.mock.calls.every(([sql]) =>
      String(sql).startsWith('INSERT OR REPLACE INTO settings'),
    )).toBe(true);
  });

  it('is a no-op transaction when given an empty object', async () => {
    await settings.setSettings({});
    expect(mockTransaction).toHaveBeenCalledTimes(1);
    expect(mockExecute).not.toHaveBeenCalled();
  });
});
