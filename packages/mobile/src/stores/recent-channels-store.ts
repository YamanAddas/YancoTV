import {
  createRecentChannelsStore,
  type RecentChannelsAdapter,
} from '@yancotv/core';
import { asyncStorageKV } from '../storage/async-storage';
import { getJson, setJson } from '../storage/kv-store';

const KEY_RECENT_CHANNELS = 'app:recent-channels';

const adapter: RecentChannelsAdapter = {
  async read() {
    const ids = await getJson<string[]>(asyncStorageKV, KEY_RECENT_CHANNELS);
    return Array.isArray(ids) ? ids : [];
  },
  async write(ids) {
    await setJson(asyncStorageKV, KEY_RECENT_CHANNELS, ids);
  },
};

/**
 * Mobile recent-live-channels store — core factory + AsyncStorage adapter.
 *
 * A tiny ring buffer of ≤10 live channel IDs in most-recent-first order.
 * Hydrated once at boot from `HydrationGate`; `record(id)` is called by
 * `PlayerScreen` when a live stream starts. Backs quick-access features
 * (last-channel recall, auto-play on launch) that arrive in M5+.
 */
export const useRecentChannelsStore = createRecentChannelsStore(adapter);
