import { describe, it, expect, vi } from 'vitest';
import {
  createRecentChannelsStore,
  type RecentChannelsAdapter,
} from '@yancotv/core';

function makeMemoryAdapter(initial: string[] = []): RecentChannelsAdapter & {
  writes: string[][];
  stored: string[];
} {
  let stored = [...initial];
  const writes: string[][] = [];
  return {
    get stored() {
      return stored;
    },
    writes,
    read: async () => [...stored],
    write: async (ids) => {
      writes.push([...ids]);
      stored = [...ids];
    },
  };
}

describe('createRecentChannelsStore', () => {
  it('starts empty with hydrated=false', () => {
    const store = createRecentChannelsStore(makeMemoryAdapter(['a']));
    const state = store.getState();
    expect(state.ids).toEqual([]);
    expect(state.hydrated).toBe(false);
    expect(state.lastError).toBeNull();
  });

  it('hydrate loads persisted IDs', async () => {
    const store = createRecentChannelsStore(makeMemoryAdapter(['a', 'b', 'c']));
    await store.getState().hydrate();
    const state = store.getState();
    expect(state.ids).toEqual(['a', 'b', 'c']);
    expect(state.hydrated).toBe(true);
  });

  it('hydrate respects maxEntries', async () => {
    const store = createRecentChannelsStore(
      makeMemoryAdapter(['a', 'b', 'c', 'd', 'e']),
      { maxEntries: 3 },
    );
    await store.getState().hydrate();
    expect(store.getState().ids).toEqual(['a', 'b', 'c']);
  });

  it('hydrate surfaces adapter errors and still flips hydrated', async () => {
    const adapter: RecentChannelsAdapter = {
      read: vi.fn().mockRejectedValue(new Error('disk')),
      write: vi.fn(),
    };
    const store = createRecentChannelsStore(adapter);
    await store.getState().hydrate();
    const state = store.getState();
    expect(state.hydrated).toBe(true);
    expect(state.lastError?.message).toBe('disk');
  });

  it('record moves an existing id to the head', async () => {
    const adapter = makeMemoryAdapter(['b', 'a']);
    const store = createRecentChannelsStore(adapter);
    await store.getState().hydrate();
    await store.getState().record('a');
    expect(store.getState().ids).toEqual(['a', 'b']);
    expect(adapter.stored).toEqual(['a', 'b']);
  });

  it('record prepends new ids and caps at maxEntries', async () => {
    const adapter = makeMemoryAdapter();
    const store = createRecentChannelsStore(adapter, { maxEntries: 3 });
    await store.getState().hydrate();
    await store.getState().record('a');
    await store.getState().record('b');
    await store.getState().record('c');
    await store.getState().record('d');
    expect(store.getState().ids).toEqual(['d', 'c', 'b']);
    expect(adapter.stored).toEqual(['d', 'c', 'b']);
  });

  it('record ignores empty ids', async () => {
    const adapter = makeMemoryAdapter(['a']);
    const store = createRecentChannelsStore(adapter);
    await store.getState().hydrate();
    await store.getState().record('');
    expect(store.getState().ids).toEqual(['a']);
    expect(adapter.writes).toEqual([]);
  });

  it('remove filters and persists', async () => {
    const adapter = makeMemoryAdapter(['a', 'b']);
    const store = createRecentChannelsStore(adapter);
    await store.getState().hydrate();
    await store.getState().remove('a');
    expect(store.getState().ids).toEqual(['b']);
    expect(adapter.stored).toEqual(['b']);
  });

  it('clear empties local state and persists', async () => {
    const adapter = makeMemoryAdapter(['a', 'b']);
    const store = createRecentChannelsStore(adapter);
    await store.getState().hydrate();
    await store.getState().clear();
    expect(store.getState().ids).toEqual([]);
    expect(adapter.stored).toEqual([]);
  });

  it('write errors are captured in lastError without blocking the local mutation', async () => {
    const adapter: RecentChannelsAdapter = {
      read: async () => [],
      write: vi.fn().mockRejectedValue(new Error('quota')),
    };
    const store = createRecentChannelsStore(adapter);
    await store.getState().hydrate();
    await store.getState().record('a');
    expect(store.getState().ids).toEqual(['a']);
    expect(store.getState().lastError?.message).toBe('quota');
  });

  it('previous and mostRecent expose positional selectors', async () => {
    const adapter = makeMemoryAdapter(['a', 'b', 'c']);
    const store = createRecentChannelsStore(adapter);
    await store.getState().hydrate();
    expect(store.getState().mostRecent()).toBe('a');
    expect(store.getState().previous()).toBe('b');
  });
});
