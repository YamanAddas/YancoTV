import type { DB } from '@op-engineering/op-sqlite';

// The adapter imports `getDb()` from './db', which opens the native module.
// Replace the db module wholesale so the adapter talks to a fake op-sqlite.
// Jest hoists jest.mock() above imports, so the mocked fn must be prefixed
// with `mock` to clear Jest's out-of-scope variable guard.
const mockExecute = jest.fn();

jest.mock('./db', () => ({
  getDb: () => ({ execute: mockExecute }) as unknown as DB,
}));

// Must require() (not import) so module resolution happens after jest.mock
// has been hoisted and registered above.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { sqliteFavoritesAdapter } = require('./favorites-adapter') as typeof import('./favorites-adapter');

beforeEach(() => {
  mockExecute.mockReset();
});

describe('sqliteFavoritesAdapter', () => {
  describe('getIds', () => {
    it('maps content_id rows to an ID array', async () => {
      mockExecute.mockResolvedValueOnce({
        rows: [{ content_id: 'a' }, { content_id: 'b' }],
      });
      const ids = await sqliteFavoritesAdapter.getIds();
      expect(ids).toEqual(['a', 'b']);
      expect(mockExecute).toHaveBeenCalledWith(
        'SELECT content_id FROM favorites',
      );
    });

    it('returns an empty array when rows is undefined', async () => {
      mockExecute.mockResolvedValueOnce({});
      expect(await sqliteFavoritesAdapter.getIds()).toEqual([]);
    });
  });

  describe('add', () => {
    it('inserts when content_id is not already a favorite', async () => {
      mockExecute
        .mockResolvedValueOnce({ rows: [] }) // existence check
        .mockResolvedValueOnce({}); // insert

      await sqliteFavoritesAdapter.add('content-1');

      expect(mockExecute).toHaveBeenNthCalledWith(
        1,
        'SELECT id FROM favorites WHERE content_id = ?',
        ['content-1'],
      );
      const [insertSql, insertParams] = mockExecute.mock.calls[1];
      expect(insertSql).toMatch(/^INSERT INTO favorites/);
      expect(insertParams).toHaveLength(3);
      expect(insertParams[1]).toBe('content-1');
      expect(typeof insertParams[0]).toBe('string');
      expect(typeof insertParams[2]).toBe('number');
    });

    it('is a no-op when content is already a favorite', async () => {
      mockExecute.mockResolvedValueOnce({ rows: [{ id: 'existing' }] });

      await sqliteFavoritesAdapter.add('content-1');

      expect(mockExecute).toHaveBeenCalledTimes(1);
    });
  });

  describe('remove', () => {
    it('deletes by content_id', async () => {
      mockExecute.mockResolvedValueOnce({});
      await sqliteFavoritesAdapter.remove('content-1');
      expect(mockExecute).toHaveBeenCalledWith(
        'DELETE FROM favorites WHERE content_id = ?',
        ['content-1'],
      );
    });
  });
});
