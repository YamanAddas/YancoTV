import { create } from 'zustand';

export type ToastKind = 'info' | 'success' | 'error';

export interface Toast {
  id: string;
  kind: ToastKind;
  message: string;
  action?: { label: string; href: string };
  expiresAt: number;
}

interface ToastStore {
  toasts: Toast[];
  push: (
    input: Omit<Toast, 'id' | 'expiresAt'> & { durationMs?: number },
  ) => string;
  dismiss: (id: string) => void;
  clear: () => void;
}

const DEFAULT_DURATION = 3500;

const timers = new Map<string, ReturnType<typeof setTimeout>>();

export const useToastStore = create<ToastStore>((set, get) => ({
  toasts: [],
  push: ({ durationMs = DEFAULT_DURATION, ...rest }) => {
    const id = `t-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const toast: Toast = { id, expiresAt: Date.now() + durationMs, ...rest };
    set((s) => ({ toasts: [...s.toasts, toast] }));
    const handle = setTimeout(() => {
      timers.delete(id);
      get().dismiss(id);
    }, durationMs);
    timers.set(id, handle);
    return id;
  },
  dismiss: (id) => {
    const t = timers.get(id);
    if (t) {
      clearTimeout(t);
      timers.delete(id);
    }
    set((s) => ({ toasts: s.toasts.filter((x) => x.id !== id) }));
  },
  clear: () => {
    for (const handle of timers.values()) clearTimeout(handle);
    timers.clear();
    set({ toasts: [] });
  },
}));

// Test hook — reset the store + timers between tests.
export const __testing = {
  reset: (): void => {
    for (const handle of timers.values()) clearTimeout(handle);
    timers.clear();
    useToastStore.setState({ toasts: [] });
  },
  timerCount: (): number => timers.size,
};
