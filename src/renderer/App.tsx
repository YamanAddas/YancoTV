import { useEffect, useRef } from 'react';
import { HashRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClientProvider, useQueryClient } from '@tanstack/react-query';
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
import { queryClient } from './utils/query-client';
import { I18nProvider, isLocaleCode, LOCALES, type LocaleCode } from './i18n';
import { usePlayerStore } from './stores/player-store';



// Map setting values to route paths.
//
// Every value here MUST name a route declared below. Two did not, and both
// produced an infinite redirect: '/' -> <Navigate to={startRoute}> -> the same
// place, forever.
//   - `history: '/history'` — there is no History page and never was, yet
//     "History" was offered in the start-page dropdown, so any user who picked
//     it hit the loop on every launch.
//   - `home: '/'` — self-referential. Not offered in the dropdown, so it was
//     unreachable in practice, but a stored setting would have done it.
// `history` is gone from the map and from the dropdown; `home` now points at
// the real `/home` route.
const START_PAGE_ROUTES: Record<string, string> = {
  live: '/live',
  movies: '/movies',
  series: '/series',
  guide: '/guide',
  favorites: '/favorites',
  home: '/home',
};

/** Paths that actually have a <Route> below. Guards the start-page lookup. */
const ROUTED_START_PATHS = new Set(Object.values(START_PAGE_ROUTES));

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

  // Language. `lang` and `dir` go on <html>, not on a React wrapper: `dir`
  // drives the browser's own bidi algorithm, logical CSS properties, scrollbar
  // side and caret behaviour, and it has to be on the document root for those
  // to apply to portals and overlays too — the player controls and the PIN
  // modal both render outside the main tree.
  const languageSetting = get('ui_language');
  const locale: LocaleCode = isLocaleCode(languageSetting) ? languageSetting : 'en';

  useEffect(() => {
    document.documentElement.setAttribute('lang', locale);
    document.documentElement.setAttribute('dir', LOCALES[locale].dir);
  }, [locale]);

  // Resolve the configured start page to a route
  const startPageSetting = get('ui_start_page');
  // The `?? '/live'` fallback only covers a value that is absent from the map.
  // It could not save a user who had already SAVED `startPage: 'history'`
  // before the entry was removed — for them the lookup succeeded and returned a
  // path with no route. Resolving through the known-good set means a stale or
  // hand-edited setting lands on Live TV instead of a redirect loop.
  const mapped = START_PAGE_ROUTES[startPageSetting];
  const startRoute =
    mapped && ROUTED_START_PATHS.has(mapped) ? mapped : '/live';

  return (
    <I18nProvider locale={locale}>
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
    </I18nProvider>
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
