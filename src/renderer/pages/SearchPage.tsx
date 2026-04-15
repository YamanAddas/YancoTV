import { useState, useEffect, useCallback, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import { HorizontalContentRow, type ContentCardData } from '../components/ContentGrid';
import { usePlayerStore } from '../stores/player-store';
import { useFavoritesStore } from '../stores/favorites-store';
import type { ContentItem } from '../../shared/types';

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

export function SearchPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [query, setQuery] = useState(searchParams.get('q') ?? '');
  const [results, setResults] = useState<ContentItem[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

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
  }, []);

  useEffect(() => {
    const q = searchParams.get('q') ?? '';
    setQuery(q);
    doSearch(q);
  }, [searchParams, doSearch]);

  const handleQueryChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const q = e.target.value;
    setQuery(q);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      setSearchParams(q.trim() ? { q } : {}, { replace: true });
    }, 300);
  };

  const handleItemClick = useCallback(
    (item: ContentCardData) => {
      play(item.streamUrl, item.cleanTitle || item.title, item.id);
    },
    [play],
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

      {!query.trim() && !isLoading && (
        <div className="flex flex-col items-center justify-center py-20 text-surface-500">
          <SearchIcon className="mb-4 h-12 w-12 opacity-30" />
          <p className="text-sm">Type to search your content library</p>
        </div>
      )}

      {query.trim() && !isLoading && results.length === 0 && (
        <div className="flex flex-col items-center justify-center py-20 text-surface-500">
          <p className="text-sm">No results for &ldquo;{query}&rdquo;</p>
        </div>
      )}

      {!isLoading && live.length > 0 && (
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

      {!isLoading && movies.length > 0 && (
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

      {!isLoading && series.length > 0 && (
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
