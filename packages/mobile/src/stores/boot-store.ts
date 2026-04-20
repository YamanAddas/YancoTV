import { create } from 'zustand';

// Boot readiness signal. `dbReady` flips true after `initDatabase()` resolves
// so SQLite consumers in render-time components (ContentPanel,
// CategoryFilterPanel, use-content-detail) can gate their queries without
// blocking first paint.
//
// Per M4R rule 6 (cached-first boot): the shell mounts immediately and
// paints an empty state; background init flips this flag and the already-
// mounted effects re-run.

interface BootState {
  dbReady: boolean;
  dbError: string | null;
  setDbReady: (v: boolean) => void;
  setDbError: (e: string | null) => void;
}

export const useBootStore = create<BootState>((set) => ({
  dbReady: false,
  dbError: null,
  setDbReady: (dbReady) => set({ dbReady }),
  setDbError: (dbError) => set({ dbError }),
}));
