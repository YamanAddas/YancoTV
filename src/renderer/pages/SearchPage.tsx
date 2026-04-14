import { useState, useEffect, useCallback, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ContentGrid, type ContentCardData } from '../components/ContentGrid';
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

  const live = results.filter((r) => r.type === 'live').map(toCardData);
  const movies = results.filter((r) => r.type === 'movie').map(toCardData);
  const series = results.filter((r) => r.type === 'series').map(toCardData);

  return (
    <div className="space-y-6">
      <div>
        <h2 className="mb-4 text-2xl font-bold text-surface-100">Search</h2>
        <div className="relative">
          <SearchIcon className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-surface-500" />
          <input
            type="search"
            placeholder="Search channels, movies, series..."
            value={query}
            onChange={handleQueryChange}
            autoFocus
            className="w-full rounded-xl border border-surface-700 bg-surface-800 py-3 pl-10 pr-4 text-surface-100 placeholder-surface-500 outline-none transition-colors focus:border-accent focus:ring-1 focus:ring-accent"
          />
        </div>
      </div>

      {!query.trim() && (
        <div className="flex flex-col items-center justify-center py-20 text-surface-500">
          <SearchIcon className="h-12 w-12 mb-4 opacity-30" />
          <p className="text-sm">Type to search your content library</p>
        </div>
      )}

      {query.trim() && !isLoading && results.length === 0 && (
        <div className="flex flex-col items-center justify-center py-20 text-surface-500">
          <p className="text-sm">No results for &ldquo;{query}&rdquo;</p>
        </div>
      )}

      {live.length > 0 && (
        <section>
          <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-400">
            Live TV &middot; {live.length}
          </h3>
          <ContentGrid
            items={live}
            onItemClick={handleItemClick}
            onFavoriteToggle={handleFavoriteToggle}
            favoriteIds={favoriteIds}
            isLoading={isLoading}
          />
        </section>
      )}

      {movies.length > 0 && (
        <section>
          <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-400">
            Movies &middot; {movies.length}
          </h3>
          <ContentGrid
            items={movies}
            onItemClick={handleItemClick}
            onFavoriteToggle={handleFavoriteToggle}
            favoriteIds={favoriteIds}
            isLoading={isLoading}
          />
        </section>
      )}

      {series.length > 0 && (
        <section>
          <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-400">
            Series &middot; {series.length}
          </h3>
          <ContentGrid
            items={series}
            onItemClick={handleItemClick}
            onFavoriteToggle={handleFavoriteToggle}
            favoriteIds={favoriteIds}
            isLoading={isLoading}
          />
        </section>
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
