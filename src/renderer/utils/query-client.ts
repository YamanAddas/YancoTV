import { QueryClient } from '@tanstack/react-query';

/**
 * The app's single QueryClient.
 *
 * It used to be a module-local const inside `App.tsx`, which meant nothing
 * outside the React tree could invalidate a query. That mattered once parental
 * filtering moved into the main process (MB-404): hiding a channel changes
 * what `content:getLive` *returns*, so the cached result is stale the moment
 * the setting changes — but the code that changes it is a zustand store, not a
 * component, and had no way to say so.
 *
 * Living here rather than in `App.tsx` keeps it a leaf: stores can import it
 * without pulling in the component tree, so there is no import cycle.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
    },
  },
});

/**
 * Drop every cached content query.
 *
 * Call after anything that changes what the main process will hand back —
 * hiding or unhiding a channel, or toggling "hide adult content". Broad on
 * purpose: these are rare, user-initiated actions, and the alternative is
 * enumerating query keys here and having a page invent a new one that this
 * function then silently misses.
 */
export function invalidateContentQueries(): void {
  void queryClient.invalidateQueries({ queryKey: ['content'] });
  void queryClient.invalidateQueries({ queryKey: ['categories'] });
  void queryClient.invalidateQueries({ queryKey: ['search'] });
}
