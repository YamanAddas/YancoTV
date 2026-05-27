import { useEffect, useRef } from 'react';
import { HashRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query';
import { Layout } from './components/Layout';
import { HomePage } from './pages/HomePage';
import { LiveTvPage } from './pages/LiveTvPage';
import { GuidePage } from './pages/GuidePage';
import { MoviesPage } from './pages/MoviesPage';
import { SeriesPage } from './pages/SeriesPage';
import { SearchPage } from './pages/SearchPage';
import { FavoritesPage } from './pages/FavoritesPage';
import { SettingsPage } from './pages/SettingsPage';
import { ContentDetailPage } from './pages/ContentDetailPage';
import { RecordingsPage } from './pages/RecordingsPage';
import { DownloadsPage } from './pages/DownloadsPage';
import { useSettingsStore } from './stores/settings-store';
import { useNotifications } from './hooks/use-notifications';
import { useRecentChannelsStore } from './stores/recent-channels-store';
import { usePlayerStore } from './stores/player-store';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
    },
  },
});

// Map setting values to route paths
const START_PAGE_ROUTES: Record<string, string> = {
  live: '/live',
  movies: '/movies',
  series: '/series',
  guide: '/guide',
  favorites: '/favorites',
  history: '/history',
  home: '/',
};

function AppInner() {
  const { load, get } = useSettingsStore();
  const settingsLoaded = useSettingsStore((s) => s.loaded);
  const backend = usePlayerStore((s) => s.backend);
  const qc = useQueryClient();
  const autoplayFiredRef = useRef(false);
  useNotifications();

  // Settings load + theme sync — runs once on mount, independent of the
  // backend-readiness gate below.
  useEffect(() => {
    load().then(() => {
      const settings = useSettingsStore.getState();
      document.documentElement.setAttribute('data-theme', settings.get('ui_theme') || 'dark');
    });
  }, [load]);

  // Auto-play the most recently watched live channel — but only after BOTH
  // settings have loaded AND the player backend has been resolved by
  // main.tsx's init(). Without the backend gate we'd race: settings often
  // resolve before checkMpv() does, so play() would read backend='none' and
  // take the html5 branch on machines that actually have mpv installed.
  //
  // The play() call defaults to mini mode (see player-store.ts) so the user
  // lands on a fully usable menu with the previous channel docked bottom-
  // right rather than swallowed by a full-screen theater.
  useEffect(() => {
    if (!settingsLoaded || backend === 'none') return;
    if (get('ui_remember_last_channel') !== '1') return;
    const lastId = useRecentChannelsStore.getState().mostRecent();
    if (!lastId || !window.api) return;

    // Only fire once per app launch — subsequent renders (e.g. when backend
    // flips during retries) must not re-trigger playback. The ref lives in
    // closure across renders.
    if (autoplayFiredRef.current) return;
    autoplayFiredRef.current = true;

    (async () => {
      try {
        const detail = await window.api.content.getDetail(lastId);
        // If the user already kicked off playback (or stopped one) while
        // getDetail was in flight, bail — the autoplay shouldn't barge in on
        // an explicit user action.
        if (usePlayerStore.getState().mode !== 'idle') return;
        const item = detail?.item;
        if (item?.streamUrl) {
          usePlayerStore.getState().play(
            item.streamUrl,
            item.cleanTitle || item.title,
            item.id,
            undefined,
            'live',
          );
        }
      } catch {
        // Channel deleted or source removed — silently skip.
      }
    })();
  }, [settingsLoaded, backend, get]);

  // Invalidate cached content when any source finishes syncing, so the
  // next visit to Live / Movies / Series sees fresh data.
  useEffect(() => {
    if (!window.api?.sources?.onSyncProgress) return;
    return window.api.sources.onSyncProgress((_sourceId, prog) => {
      if (prog?.phase === 'done') {
        qc.invalidateQueries({ queryKey: ['content'] });
        qc.invalidateQueries({ queryKey: ['categories'] });
      }
    });
  }, [qc]);

  // Subscribe to theme changes so the setting takes effect immediately
  useEffect(() => {
    return useSettingsStore.subscribe((state) => {
      const theme = state.data.ui_theme || 'dark';
      document.documentElement.setAttribute('data-theme', theme);
    });
  }, []);

  // Resolve the configured start page to a route
  const startPageSetting = get('ui_start_page');
  const startRoute = START_PAGE_ROUTES[startPageSetting] ?? '/live';

  return (
    <Routes>
      <Route element={<Layout />}>
        {/* '/' redirects to the configured start page */}
        <Route path="/" element={<Navigate to={startRoute} replace />} />
        <Route path="/home" element={<HomePage />} />
        <Route path="/live" element={<LiveTvPage />} />
        <Route path="/guide" element={<GuidePage />} />
        <Route path="/movies" element={<MoviesPage />} />
        <Route path="/movies/:id" element={<ContentDetailPage />} />
        <Route path="/series" element={<SeriesPage />} />
        <Route path="/series/:id" element={<ContentDetailPage />} />
        <Route path="/search" element={<SearchPage />} />
        <Route path="/favorites" element={<FavoritesPage />} />
        <Route path="/recordings" element={<RecordingsPage />} />
        <Route path="/downloads" element={<DownloadsPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="*" element={<Navigate to={startRoute} replace />} />
      </Route>
    </Routes>
  );
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <HashRouter>
        <AppInner />
      </HashRouter>
    </QueryClientProvider>
  );
}
