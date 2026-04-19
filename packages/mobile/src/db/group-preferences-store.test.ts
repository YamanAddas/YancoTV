import type { DB } from '@op-engineering/op-sqlite';

const mockExecute = jest.fn();

jest.mock('./db', () => ({
  getDb: () => ({ execute: mockExecute }) as unknown as DB,
}));

// eslint-disable-next-line @typescript-eslint/no-require-imports
const mod = require('./group-preferences-store') as typeof import('./group-preferences-store');

beforeEach(() => {
  mockExecute.mockReset();
});

describe('group-preferences-store', () => {
  describe('listByType', () => {
    it('returns hydrated rows for a content type', async () => {
      mockExecute.mockResolvedValueOnce({
        rows: [
          {
            id: 'gp_1',
            content_type: 'live',
            group_key: 'sports',
            sort_order: 0,
            is_hidden: 0,
            is_pinned: 1,
            custom_name: null,
            created_at: 1,
          },
          {
            id: 'gp_2',
            content_type: 'live',
            group_key: 'movies-ar',
            sort_order: 1,
            is_hidden: 1,
            is_pinned: 0,
            custom_name: 'Arabic VOD',
            created_at: 2,
          },
        ],
      });

      const prefs = await mod.listByType('live');

      expect(prefs).toHaveLength(2);
      expect(prefs[0]).toMatchObject({
        groupKey: 'sports',
        isPinned: true,
        isHidden: false,
      });
      expect(prefs[1]).toMatchObject({
        groupKey: 'movies-ar',
        isPinned: false,
        isHidden: true,
        customName: 'Arabic VOD',
      });
      expect(mockExecute).toHaveBeenCalledWith(
        'SELECT * FROM group_preferences WHERE content_type = ?',
        ['live'],
      );
    });

    it('returns an empty array when rows is missing', async () => {
      mockExecute.mockResolvedValueOnce({});
      expect(await mod.listByType('movie')).toEqual([]);
    });
  });

  describe('upsert', () => {
    it('inserts a new preference row when none exists', async () => {
      mockExecute
        .mockResolvedValueOnce({ rows: [] }) // lookup
        .mockResolvedValueOnce({}); // insert

      const row = await mod.upsert({
        contentType: 'live',
        groupKey: 'sports',
        isPinned: true,
      });

      expect(row.contentType).toBe('live');
      expect(row.groupKey).toBe('sports');
      expect(row.isPinned).toBe(true);
      expect(row.isHidden).toBe(false);
      expect(row.customName).toBeNull();
      const [insertSql] = mockExecute.mock.calls[1];
      expect(insertSql).toMatch(/^INSERT INTO group_preferences/);
    });

    it('updates in place when the row exists and preserves untouched fields', async () => {
      mockExecute
        .mockResolvedValueOnce({
          rows: [
            {
              id: 'gp_1',
              content_type: 'live',
              group_key: 'sports',
              sort_order: 3,
              is_hidden: 0,
              is_pinned: 0,
              custom_name: 'Sports',
              created_at: 1,
            },
          ],
        })
        .mockResolvedValueOnce({});

      const row = await mod.upsert({
        contentType: 'live',
        groupKey: 'sports',
        isPinned: true,
      });

      expect(row.isPinned).toBe(true);
      expect(row.sortOrder).toBe(3);
      expect(row.customName).toBe('Sports');
      const [updateSql, params] = mockExecute.mock.calls[1];
      expect(updateSql).toMatch(/^UPDATE group_preferences/);
      expect(params[params.length - 1]).toBe('gp_1');
    });

    it('clears customName when explicitly null', async () => {
      mockExecute
        .mockResolvedValueOnce({
          rows: [
            {
              id: 'gp_1',
              content_type: 'live',
              group_key: 'sports',
              sort_order: 0,
              is_hidden: 0,
              is_pinned: 0,
              custom_name: 'Old Name',
              created_at: 1,
            },
          ],
        })
        .mockResolvedValueOnce({});

      const row = await mod.upsert({
        contentType: 'live',
        groupKey: 'sports',
        customName: null,
      });

      expect(row.customName).toBeNull();
    });
  });

  describe('reorder', () => {
    it('upserts sort_order for each key in order', async () => {
      // Each upsert() does a lookup (no rows) + insert = 2 mockExecute calls.
      // Seed four responses for two keys.
      mockExecute
        .mockResolvedValueOnce({ rows: [] })
        .mockResolvedValueOnce({})
        .mockResolvedValueOnce({ rows: [] })
        .mockResolvedValueOnce({});

      await mod.reorder('live', ['a', 'b']);

      expect(mockExecute).toHaveBeenCalledTimes(4);
    });
  });

  describe('remove', () => {
    it('deletes by content_type + group_key', async () => {
      mockExecute.mockResolvedValueOnce({});
      await mod.remove('live', 'sports');
      expect(mockExecute).toHaveBeenCalledWith(
        'DELETE FROM group_preferences WHERE content_type = ? AND group_key = ?',
        ['live', 'sports'],
      );
    });
  });
});
