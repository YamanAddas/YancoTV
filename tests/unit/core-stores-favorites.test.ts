import { describe, it, expect, vi } from 'vitest';
import {
  createFavoritesStore,
  type FavoritesAdapter,
} from '@yancotv/core/stores';

function makeMemoryAdapter(initial: string[] = []): FavoritesAdapter & {
  ids: Set<string>;
} {
  const ids = new Set(initial);
  return {
    ids,
    getIds: async () => [...ids],
    add: async (id) => {
      ids.add(id);
    },
    remove: async (id) => {
      ids.delete(id);
    },
  };
}

describe('createFavoritesStore', () => {
  it('starts empty and unloaded', () => {
    const store = createFavoritesStore(makeMemoryAdapter());
    const state = store.getState();
    expect(state.favoriteIds.size).toBe(0);
    expect(state.isLoaded).toBe(false);
    expect(state.lastError).toBeNull();
  });

  it('loads IDs from the adapter', async () => {
    const store = createFavoritesStore(makeMemoryAdapter(['a', 'b']));
    await store.getState().load();
    const state = store.getState();
    expect([...state.favoriteIds].sort()).toEqual(['a', 'b']);
    expect(state.isLoaded).toBe(true);
    expect(state.lastError).toBeNull();
  });

  it('toggle adds a missing favorite', async () => {
    const adapter = makeMemoryAdapter();
    const store = createFavoritesStore(adapter);
    await store.getState().load();
    await store.getState().toggle('x');
    expect(store.getState().favoriteIds.has('x')).toBe(true);
    expect(adapter.ids.has('x')).toBe(true);
  });

  it('toggle removes an existing favorite', async () => {
    const adapter = makeMemoryAdapter(['x']);
    const store = createFavoritesStore(adapter);
    await store.getState().load();
    await store.getState().toggle('x');
    expect(store.getState().favoriteIds.has('x')).toBe(false);
    expect(adapter.ids.has('x')).toBe(false);
  });

  it('toggle does not mutate local state if the adapter throws', async () => {
    const boom = new Error('db locked');
    const adapter: FavoritesAdapter = {
      getIds: async () => [],
      add: vi.fn().mockRejectedValue(boom),
      remove: vi.fn(),
    };
    const store = createFavoritesStore(adapter);
    await store.getState().load();
    await store.getState().toggle('x');
    expect(store.getState().favoriteIds.has('x')).toBe(false);
    expect(store.getState().lastError).toBe(boom);
  });

  it('toggle clears a previous error after a successful add', async () => {
    const adapter: FavoritesAdapter = {
      getIds: async () => [],
      add: vi.fn()
        .mockRejectedValueOnce(new Error('transient'))
        .mockResolvedValueOnce(undefined),
      remove: vi.fn(),
    };
    const store = createFavoritesStore(adapter);
    await store.getState().load();
    await store.getState().toggle('x');
    expect(store.getState().lastError).toBeInstanceOf(Error);
    await store.getState().toggle('x');
    expect(store.getState().lastError).toBeNull();
    expect(store.getState().favoriteIds.has('x')).toBe(true);
  });

  it('isFavorite reflects current state', async () => {
    const store = createFavoritesStore(makeMemoryAdapter(['a']));
    await store.getState().load();
    expect(store.getState().isFavorite('a')).toBe(true);
    expect(store.getState().isFavorite('b')).toBe(false);
  });

  it('surfaces load errors without blocking isLoaded', async () => {
    const adapter: FavoritesAdapter = {
      getIds: vi.fn().mockRejectedValue(new Error('io')),
      add: vi.fn(),
      remove: vi.fn(),
    };
    const store = createFavoritesStore(adapter);
    await store.getState().load();
    expect(store.getState().isLoaded).toBe(true);
    expect(store.getState().lastError?.message).toBe('io');
  });
});
