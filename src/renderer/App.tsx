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
import { useSettingsStore } from './stores/settings-store';

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

  // Load settings once on mount, then keep theme in sync
  useEffect(() => {
    load().then(() => {
      const theme = useSettingsStore.getState().get('ui_theme');
      document.documentElement.setAttribute('data-theme', theme || 'dark');
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
