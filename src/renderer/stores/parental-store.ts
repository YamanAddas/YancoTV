import { create } from 'zustand';
import { invalidateContentQueries } from '../utils/query-client';

// ---------------------------------------------------------------------------
// Parental Controls Store
//
// Caches parental settings, locked IDs, and hidden IDs on the renderer side
// so components don't need individual IPC calls for every render.
// ---------------------------------------------------------------------------

interface ParentalSettings {
  pinEnabled: boolean;
  pinSet: boolean;
  hideAdultContent: boolean;
  requirePinForSettings: boolean;
}

interface ParentalState {
  settings: ParentalSettings;
  lockedIds: Set<string>;
  hiddenIds: Set<string>;
  loaded: boolean;

  // Actions
  load: () => Promise<void>;
  setPin: (pin: string) => Promise<{ ok: boolean; error?: string }>;
  verifyPin: (pin: string) => Promise<boolean>;
  removePin: () => Promise<void>;
  updateSetting: (key: string, value: boolean) => Promise<void>;
  lockChannel: (contentId: string) => Promise<void>;
  unlockChannel: (contentId: string) => Promise<void>;
  hideChannel: (contentId: string) => Promise<void>;
  unhideChannel: (contentId: string) => Promise<void>;
}

export const useParentalStore = create<ParentalState>((set, get) => ({
  settings: {
    pinEnabled: false,
    pinSet: false,
    hideAdultContent: false,
    requirePinForSettings: false,
  },
  lockedIds: new Set(),
  hiddenIds: new Set(),
  loaded: false,

  load: async () => {
    if (!window.api?.parental) return;

    const [settings, lockedIds, hiddenIds] = await Promise.all([
      window.api.parental.getSettings(),
      window.api.parental.getLockedIds(),
      window.api.parental.getHiddenIds(),
    ]);

    set({
      settings,
      lockedIds: new Set(lockedIds),
      hiddenIds: new Set(hiddenIds),
      loaded: true,
    });
  },

  setPin: async (pin: string) => {
    if (!window.api?.parental) return { ok: false, error: 'API not available' };
    const result = await window.api.parental.setPin(pin);
    if (result.ok) {
      await get().load(); // Refresh settings
    }
    return result;
  },

  verifyPin: async (pin: string) => {
    if (!window.api?.parental) return false;
    const result = await window.api.parental.verifyPin(pin);
    return result.verified;
  },

  removePin: async () => {
    if (!window.api?.parental) return;
    await window.api.parental.removePin();
    await get().load();
  },

  updateSetting: async (key: string, value: boolean) => {
    if (!window.api?.parental) return;
    await window.api.parental.updateSetting(key, value);
    // Optimistic update
    set((state) => ({
      settings: {
        ...state.settings,
        ...(key === 'hide_adult' ? { hideAdultContent: value } : {}),
        ...(key === 'require_pin_settings' ? { requirePinForSettings: value } : {}),
      },
    }));
    // `hide_adult` changes what every content query returns; `require_pin_settings`
    // does not, but invalidating on both keeps this from depending on which key
    // happens to be visibility-bearing today.
    invalidateContentQueries();
  },

  lockChannel: async (contentId: string) => {
    if (!window.api?.parental) return;
    await window.api.parental.lockChannel(contentId);
    set((state) => {
      const next = new Set(state.lockedIds);
      next.add(contentId);
      return { lockedIds: next };
    });
  },

  unlockChannel: async (contentId: string) => {
    if (!window.api?.parental) return;
    await window.api.parental.unlockChannel(contentId);
    set((state) => {
      const next = new Set(state.lockedIds);
      next.delete(contentId);
      return { lockedIds: next };
    });
  },

  hideChannel: async (contentId: string) => {
    if (!window.api?.parental) return;
    await window.api.parental.hideChannel(contentId);
    set((state) => {
      const next = new Set(state.hiddenIds);
      next.add(contentId);
      return { hiddenIds: next };
    });
    // MB-404 — the main process now does the filtering, so the cached content
    // queries are stale the instant this returns. Without this the channel
    // stays on screen until something else happens to refetch, which reads as
    // "Hide did nothing".
    invalidateContentQueries();
  },

  unhideChannel: async (contentId: string) => {
    if (!window.api?.parental) return;
    await window.api.parental.unhideChannel(contentId);
    set((state) => {
      const next = new Set(state.hiddenIds);
      next.delete(contentId);
      return { hiddenIds: next };
    });
    invalidateContentQueries();
  },
}));
