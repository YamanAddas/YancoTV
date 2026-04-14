import { NavLink, useNavigate } from 'react-router-dom';
import { useState, useRef, useCallback, useEffect } from 'react';

const navItems = [
  { path: '/', label: 'Home', icon: 'home' },
  { path: '/live', label: 'Live TV', icon: 'tv' },
  { path: '/movies', label: 'Movies', icon: 'film' },
  { path: '/series', label: 'Series', icon: 'layers' },
  { path: '/favorites', label: 'Favorites', icon: 'heart' },
  { path: '/settings', label: 'Settings', icon: 'settings' },
];

const iconMap: Record<string, string> = {
  home: 'M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6',
  tv: 'M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
  film: 'M7 4v16M17 4v16M3 8h4m10 0h4M3 12h18M3 16h4m10 0h4M4 20h16a1 1 0 001-1V5a1 1 0 00-1-1H4a1 1 0 00-1 1v14a1 1 0 001 1z',
  layers:
    'M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10',
  heart:
    'M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12z',
  settings:
    'M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z',
  search:
    'M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 15.803a7.5 7.5 0 0010.607 10.607z',
};

export function Sidebar() {
  const [searchQuery, setSearchQuery] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();

  const handleSearchSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault();
      if (searchQuery.trim()) {
        navigate(`/search?q=${encodeURIComponent(searchQuery.trim())}`);
        setSearchQuery('');
        inputRef.current?.blur();
      }
    },
    [searchQuery, navigate],
  );

  // Ctrl+F / Cmd+F to focus search
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
        e.preventDefault();
        inputRef.current?.focus();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  return (
    <nav className="flex w-56 flex-col border-r border-surface-800 bg-surface-900">
      <div className="flex h-14 items-center px-5">
        <h1 className="text-lg font-bold text-accent">YancoTV</h1>
      </div>

      {/* Search bar */}
      <form onSubmit={handleSearchSubmit} className="px-3 pb-2">
        <div className="relative">
          <svg
            className="absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-surface-500"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={1.5}
          >
            <path strokeLinecap="round" strokeLinejoin="round" d={iconMap.search} />
          </svg>
          <input
            ref={inputRef}
            type="search"
            placeholder="Search... (Ctrl+F)"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full rounded-lg border border-surface-700 bg-surface-800 py-2 pl-8 pr-3 text-sm text-surface-200 placeholder-surface-500 outline-none transition-colors focus:border-accent focus:ring-1 focus:ring-accent"
          />
        </div>
      </form>

      <div className="flex flex-1 flex-col gap-1 px-3">
        {navItems.map((item) => (
          <NavLink
            key={item.path}
            to={item.path}
            end={item.path === '/'}
            className={({ isActive }) =>
              `flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors ${
                isActive
                  ? 'bg-accent/10 text-accent'
                  : 'text-surface-400 hover:bg-surface-800 hover:text-surface-200'
              }`
            }
          >
            <svg
              className="h-5 w-5 flex-shrink-0"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={1.5}
            >
              <path strokeLinecap="round" strokeLinejoin="round" d={iconMap[item.icon]} />
            </svg>
            {item.label}
          </NavLink>
        ))}
      </div>

      <div className="border-t border-surface-800 p-4">
        <p className="text-xs text-surface-500">v0.1.0</p>
      </div>
    </nav>
  );
}
