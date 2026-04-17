import { useEffect } from 'react';
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
  const qc = useQueryClient();
  useNotifications();

  // Load settings once on mount, then keep theme in sync. When
  // "Remember last channel" is on (19.6) and the user actually has a recent
  // live channel, auto-play it after settings finish loading so the app opens
  // into whatever they were last watching.
  useEffect(() => {
    load().then(async () => {
      const settings = useSettingsStore.getState();
      document.documentElement.setAttribute('data-theme', settings.get('ui_theme') || 'dark');

      if (settings.get('ui_remember_last_channel') !== '1') return;
      const lastId = useRecentChannelsStore.getState().mostRecent();
      if (!lastId || !window.api) return;
      try {
        const detail = await window.api.content.getDetail(lastId);
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
    });
  }, [load]);

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
