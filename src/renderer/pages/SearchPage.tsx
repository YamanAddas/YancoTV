import { useState, useEffect, useCallback, useRef } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { HorizontalContentRow, type ContentCardData } from '../components/ContentGrid';
import { usePlayerStore } from '../stores/player-store';
import { useFavoritesStore } from '../stores/favorites-store';
import type { ContentItem } from '../../shared/types';
import {
  getSearchHistory,
  recordSearch,
  removeFromHistory,
  clearSearchHistory,
} from '../utils/search-history';

function toCardData(item: ContentItem): ContentCardData {
  return {
    id: item.id,
    title: item.title,
    cleanTitle: item.cleanTitle,
    groupName: item.groupName,
    logoUrl: item.logoUrl,
    streamUrl: item.streamUrl,
    type: item.type,
  };
}

type TypeFilter = 'all' | 'live' | 'movie' | 'series';

const FILTER_OPTIONS: { value: TypeFilter; label: string; icon: string }[] = [
  { value: 'all', label: 'All', icon: '✦' },
  { value: 'live', label: 'Live', icon: '📡' },
  { value: 'movie', label: 'Movies', icon: '🎬' },
  { value: 'series', label: 'Series', icon: '📺' },
];

function parseTypeFilter(value: string | null): TypeFilter {
  if (value === 'live' || value === 'movie' || value === 'series') return value;
  return 'all';
}

export function SearchPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [query, setQuery] = useState(searchParams.get('q') ?? '');
  const [typeFilter, setTypeFilter] = useState<TypeFilter>(parseTypeFilter(searchParams.get('type')));
  const [results, setResults] = useState<ContentItem[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [history, setHistory] = useState<string[]>(() => getSearchHistory());
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const navigate = useNavigate();
  const play = usePlayerStore((s) => s.play);
  const toggle = useFavoritesStore((s) => s.toggle);
  const favoriteIds = useFavoritesStore((s) => s.favoriteIds);

  const doSearch = useCallback(async (q: string) => {
    if (!q.trim() || !window.api) {
      setResults([]);
      return;
    }
    setIsLoading(true);
    const data: ContentItem[] = await window.api.content.search(q.trim());
    setResults(data);
    setIsLoading(false);
    // Only record once we have actual results — avoids history entries for
    // typos that never matched anything.
    if (data.length > 0) {
      recordSearch(q);
      setHistory(getSearchHistory());
    }
  }, []);

  const handleHistoryPick = (q: string) => {
    setQuery(q);
    const params: Record<string, string> = { q };
    if (typeFilter !== 'all') params.type = typeFilter;
    setSearchParams(params, { replace: true });
  };

  const handleHistoryRemove = (q: string) => {
    removeFromHistory(q);
    setHistory(getSearchHistory());
  };

  const handleClearHistory = () => {
    clearSearchHistory();
    setHistory([]);
  };

  useEffect(() => {
    const q = searchParams.get('q') ?? '';
    setQuery(q);
    setTypeFilter(parseTypeFilter(searchParams.get('type')));
    doSearch(q);
  }, [searchParams, doSearch]);

  const handleQueryChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const q = e.target.value;
    setQuery(q);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      const params: Record<string, string> = {};
      if (q.trim()) params.q = q;
      if (typeFilter !== 'all') params.type = typeFilter;
      setSearchParams(params, { replace: true });
    }, 300);
  };

  const handleFilterChange = (next: TypeFilter) => {
    setTypeFilter(next);
    const params: Record<string, string> = {};
    if (query.trim()) params.q = query;
    if (next !== 'all') params.type = next;
    setSearchParams(params, { replace: true });
  };

  const handleItemClick = useCallback(
    (item: ContentCardData) => {
      // Series parents have no stream URL — navigate to detail page
      if (item.type === 'series') {
        navigate(`/series/${item.id}`);
        return;
      }
      // Movies also benefit from the detail page for metadata
      if (item.type === 'movie') {
        navigate(`/movies/${item.id}`);
        return;
      }
      play(
        item.streamUrl,
        item.cleanTitle || item.title,
        item.id,
        undefined,
        item.type as 'live' | 'movie' | 'series',
      );
    },
    [play, navigate],
  );

  const handleFavoriteToggle = useCallback(
    (item: ContentCardData) => {
      toggle(item.id);
    },
    [toggle],
  );

  // Max cards shown per zone before the "and N more" overflow hint
  const ZONE_DISPLAY_CAP = 24;

  const allLive   = results.filter((r) => r.type === 'live').map(toCardData);
  const allMovies = results.filter((r) => r.type === 'movie').map(toCardData);
  const allSeries = results.filter((r) => r.type === 'series').map(toCardData);

  const live   = allLive.slice(0, ZONE_DISPLAY_CAP);
  const movies = allMovies.slice(0, ZONE_DISPLAY_CAP);
  const series = allSeries.slice(0, ZONE_DISPLAY_CAP);

  const visibleCount =
    (typeFilter === 'all' || typeFilter === 'live' ? allLive.length : 0) +
    (typeFilter === 'all' || typeFilter === 'movie' ? allMovies.length : 0) +
    (typeFilter === 'all' || typeFilter === 'series' ? allSeries.length : 0);

  return (
    <div className="space-y-6">
      <div>
        <h2 className="mb-4 text-2xl font-bold text-surface-100 text-glow-sm">Search</h2>
        <div className="relative">
          <SearchIcon className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-surface-500" />
          <input
            type="search"
            placeholder="Search channels, movies, series..."
            value={query}
            onChange={handleQueryChange}
            autoFocus
            className="w-full rounded-xl border border-accent/5 bg-surface-800 py-3 pl-10 pr-4 text-surface-100 placeholder-surface-500 outline-none transition-colors focus:border-accent focus:ring-1 focus:ring-accent"
          />
        </div>
        <div className="mt-3 flex flex-wrap gap-2" role="tablist" aria-label="Filter by content type">
          {FILTER_OPTIONS.map((opt) => {
            const selected = typeFilter === opt.value;
            return (
              <button
                key={opt.value}
                role="tab"
                aria-selected={selected}
                onClick={() => handleFilterChange(opt.value)}
                className={`flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-medium transition-colors ${
                  selected
                    ? 'border-accent bg-accent/15 text-accent'
                    : 'border-surface-700 bg-surface-800 text-surface-400 hover:border-surface-600 hover:text-surface-200'
                }`}
              >
                <span aria-hidden>{opt.icon}</span>
                {opt.label}
              </button>
            );
          })}
        </div>
      </div>

      {isLoading && (
        <div className="flex items-center justify-center py-16 text-surface-500">
          <svg className="mr-2 h-5 w-5 animate-spin" fill="none" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
          </svg>
          <span className="text-sm">Searching…</span>
        </div>
      )}

      {!query.trim() && !isLoading && history.length === 0 && (
        <div className="flex flex-col items-center justify-center py-20 text-surface-500">
          <SearchIcon className="mb-4 h-12 w-12 opacity-30" />
          <p className="text-sm">Type to search your content library</p>
        </div>
      )}

      {!query.trim() && !isLoading && history.length > 0 && (
        <section>
          <div className="mb-3 flex items-center justify-between">
            <h3 className="text-sm font-semibold uppercase tracking-wider text-surface-400">
              Recent searches
            </h3>
            <button
              onClick={handleClearHistory}
              className="text-xs text-surface-500 transition-colors hover:text-surface-300"
            >
              Clear all
            </button>
          </div>
          <div className="flex flex-wrap gap-2">
            {history.map((h) => (
              <span
                key={h}
                className="group flex items-center gap-1 rounded-full border border-surface-700 bg-surface-800 pl-3 pr-1 text-sm text-surface-200"
              >
                <button
                  onClick={() => handleHistoryPick(h)}
                  className="py-1.5 transition-colors hover:text-accent"
                >
                  {h}
                </button>
                <button
                  onClick={() => handleHistoryRemove(h)}
                  aria-label={`Remove ${h} from history`}
                  className="rounded-full p-1 text-surface-500 opacity-60 transition-opacity hover:bg-surface-700 hover:text-surface-200 hover:opacity-100"
                >
                  <svg width="10" height="10" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <path d="M3 3 L13 13 M13 3 L3 13" strokeLinecap="round" />
                  </svg>
                </button>
              </span>
            ))}
          </div>
        </section>
      )}

      {query.trim() && !isLoading && visibleCount === 0 && (
        <div className="flex flex-col items-center justify-center py-20 text-surface-500">
          <p className="text-sm">
            No {typeFilter === 'all' ? '' : `${typeFilter === 'movie' ? 'movie' : typeFilter} `}
            results for &ldquo;{query}&rdquo;
          </p>
        </div>
      )}

      {!isLoading && (typeFilter === 'all' || typeFilter === 'live') && live.length > 0 && (
        <section>
          <ZoneHeader icon="📡" label="Live TV" total={allLive.length} cap={ZONE_DISPLAY_CAP} />
          <HorizontalContentRow
            items={live}
            onItemClick={handleItemClick}
            onFavoriteToggle={handleFavoriteToggle}
            favoriteIds={favoriteIds}
          />
        </section>
      )}

      {!isLoading && (typeFilter === 'all' || typeFilter === 'movie') && movies.length > 0 && (
        <section>
          <ZoneHeader icon="🎬" label="Movies" total={allMovies.length} cap={ZONE_DISPLAY_CAP} />
          <HorizontalContentRow
            items={movies}
            onItemClick={handleItemClick}
            onFavoriteToggle={handleFavoriteToggle}
            favoriteIds={favoriteIds}
          />
        </section>
      )}

      {!isLoading && (typeFilter === 'all' || typeFilter === 'series') && series.length > 0 && (
        <section>
          <ZoneHeader icon="📺" label="Series" total={allSeries.length} cap={ZONE_DISPLAY_CAP} />
          <HorizontalContentRow
            items={series}
            onItemClick={handleItemClick}
            onFavoriteToggle={handleFavoriteToggle}
            favoriteIds={favoriteIds}
          />
        </section>
      )}
    </div>
  );
}

function ZoneHeader({
  icon,
  label,
  total,
  cap,
}: {
  icon: string;
  label: string;
  total: number;
  cap: number;
}) {
  const overflow = total - cap;
  return (
    <div className="mb-3 flex items-center gap-2">
      <span className="text-base leading-none">{icon}</span>
      <h3 className="text-sm font-semibold uppercase tracking-wider text-surface-400">
        {label}
      </h3>
      <span className="rounded-full bg-surface-800 px-2 py-0.5 text-xs font-medium text-surface-400">
        {total}
      </span>
      {overflow > 0 && (
        <span className="text-xs text-surface-600">
          (showing first {cap} — refine your search to narrow results)
        </span>
      )}
    </div>
  );
}

function SearchIcon({ className }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 15.803a7.5 7.5 0 0010.607 10.607z"
      />
    </svg>
  );
}
