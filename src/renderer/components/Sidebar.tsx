import { NavLink, useNavigate } from 'react-router-dom';
import { useState, useRef, useCallback, useEffect } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { useSettingsStore } from '../stores/settings-store';
import { usePlayerStore } from '../stores/player-store';
import { useT } from '../i18n';
import type { ContentItem } from '../../shared/types';
import { APP_VERSION as APP_VERSION_FALLBACK } from '../../shared/constants';

const SUGGEST_LIMIT = 6;
const SUGGEST_DEBOUNCE_MS = 200;
// Map content type → key in `iconMap`. Reuses the same SVG paths as the main
// nav items so the suggestion dropdown stays visually consistent. Emoji
// glyphs are deliberately avoided per project rule (text rendering is
// unreliable across Windows/Linux/macOS font stacks).
const TYPE_ICON_KEY: Record<'live' | 'movie' | 'series', string> = {
  live: 'tv',
  movie: 'film',
  series: 'layers',
};

// Nav items carry a translation KEY, not a label. The label is resolved at
// render time so switching language re-renders the sidebar rather than needing
// a reload — and so this module-level constant, which is evaluated once at
// import, cannot capture a stale language.
const navItems = [
  { path: '/home', labelKey: 'nav.home', icon: 'home' },
  { path: '/live', labelKey: 'nav.liveTv', icon: 'tv' },
  { path: '/guide', labelKey: 'nav.guide', icon: 'guide' },
  { path: '/movies', labelKey: 'nav.movies', icon: 'film' },
  { path: '/series', labelKey: 'nav.series', icon: 'layers' },
  { path: '/favorites', labelKey: 'nav.favorites', icon: 'heart' },
  { path: '/recordings', labelKey: 'nav.recordings', icon: 'record' },
  { path: '/downloads', labelKey: 'nav.downloads', icon: 'download' },
  { path: '/settings', labelKey: 'nav.settings', icon: 'settings' },
] as const;

const iconMap: Record<string, string> = {
  home: 'M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6',
  tv: 'M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
  guide:
    'M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z',
  film: 'M7 4v16M17 4v16M3 8h4m10 0h4M3 12h18M3 16h4m10 0h4M4 20h16a1 1 0 001-1V5a1 1 0 00-1-1H4a1 1 0 00-1 1v14a1 1 0 001 1z',
  layers:
    'M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10',
  heart:
    'M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12z',
  settings:
    'M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z',
  search:
    'M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 15.803a7.5 7.5 0 0010.607 10.607z',
  record:
    'M12 19a7 7 0 100-14 7 7 0 000 14zm0-3a4 4 0 110-8 4 4 0 010 8z',
  download:
    'M12 4v12m0 0l-4-4m4 4l4-4M4 20h16',
};

function useClock() {
  const [time, setTime] = useState(() => new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
  useEffect(() => {
    const id = setInterval(() => {
      setTime(new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
    }, 10000);
    return () => clearInterval(id);
  }, []);
  return time;
}

export function Sidebar() {
  const t = useT();
  const [expanded, setExpanded] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [suggestions, setSuggestions] = useState<ContentItem[]>([]);
  const [selectedIdx, setSelectedIdx] = useState(-1);
  const [suggestOpen, setSuggestOpen] = useState(false);
  // Real package version via IPC (matches `app.getVersion()` from main).
  // Falls back to the shared constant during the brief window before the
  // IPC round-trip lands, and on any failure.
  const [appVersion, setAppVersion] = useState(APP_VERSION_FALLBACK);
  const inputRef = useRef<HTMLInputElement>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const searchSeqRef = useRef(0);
  const navigate = useNavigate();
  const play = usePlayerStore((s) => s.play);
  const showClock = useSettingsStore((s) => s.getBool('ui_show_clock'));
  const time = useClock();

  useEffect(() => {
    let cancelled = false;
    window.api?.app?.getVersion?.().then((v) => {
      if (!cancelled && typeof v === 'string' && v) setAppVersion(v);
    }).catch(() => {
      // Keep the fallback — non-fatal, the constant is good enough.
    });
    return () => { cancelled = true; };
  }, []);

  const resetSuggestions = useCallback(() => {
    setSuggestions([]);
    setSelectedIdx(-1);
    setSuggestOpen(false);
  }, []);

  const runFullSearch = useCallback(
    (q: string) => {
      const trimmed = q.trim();
      if (!trimmed) return;
      navigate(`/search?q=${encodeURIComponent(trimmed)}`);
      setSearchQuery('');
      resetSuggestions();
      inputRef.current?.blur();
    },
    [navigate, resetSuggestions],
  );

  const handleSuggestionPick = useCallback(
    (item: ContentItem) => {
      if (item.type === 'series') {
        navigate(`/series/${item.id}`);
      } else if (item.type === 'movie') {
        navigate(`/movies/${item.id}`);
      } else {
        play(item.streamUrl, item.cleanTitle || item.title, item.id, undefined, item.type);
      }
      setSearchQuery('');
      resetSuggestions();
      inputRef.current?.blur();
    },
    [navigate, play, resetSuggestions],
  );

  const handleSearchSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault();
      // If user arrowed to a suggestion, pick it instead of running a full search.
      if (selectedIdx >= 0 && suggestions[selectedIdx]) {
        handleSuggestionPick(suggestions[selectedIdx]);
        return;
      }
      runFullSearch(searchQuery);
    },
    [searchQuery, selectedIdx, suggestions, handleSuggestionPick, runFullSearch],
  );

  // Debounced autocomplete fetch. Sequence guard avoids stale async
  // responses clobbering a newer query result.
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    const q = searchQuery.trim();
    if (!q || !window.api) {
      resetSuggestions();
      return;
    }
    debounceRef.current = setTimeout(async () => {
      const seq = ++searchSeqRef.current;
      try {
        const data = (await window.api.content.search(q)) as ContentItem[];
        if (seq !== searchSeqRef.current) return;
        setSuggestions(data.slice(0, SUGGEST_LIMIT));
        setSelectedIdx(-1);
        setSuggestOpen(true);
      } catch {
        if (seq !== searchSeqRef.current) return;
        resetSuggestions();
      }
    }, SUGGEST_DEBOUNCE_MS);
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [searchQuery, resetSuggestions]);

  const handleSearchKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (!suggestOpen || suggestions.length === 0) {
      if (e.key === 'Escape' && searchQuery) {
        setSearchQuery('');
        resetSuggestions();
      }
      return;
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setSelectedIdx((idx) => (idx + 1) % suggestions.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setSelectedIdx((idx) => (idx <= 0 ? suggestions.length - 1 : idx - 1));
    } else if (e.key === 'Escape') {
      e.preventDefault();
      if (selectedIdx >= 0) {
        setSelectedIdx(-1);
      } else {
        setSuggestOpen(false);
        setSearchQuery('');
      }
    }
  };

  // Ctrl+F to focus search, Ctrl+B to toggle sidebar — single stable listener
  // so a sidebar collapse/expand doesn't re-bind window.keydown on every
  // toggle. The Ctrl+F branch reads `expanded` via setExpanded's functional
  // setter (no closure over the value), which keeps the effect dep array
  // empty without staleness.
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey)) return;
      if (e.key === 'f') {
        e.preventDefault();
        setExpanded((prev) => {
          if (!prev) {
            // Defer focus until the expand animation has placed the input
            // in the DOM (the search field is conditionally rendered).
            setTimeout(() => inputRef.current?.focus(), 100);
            return true;
          }
          // Already expanded — focus immediately.
          inputRef.current?.focus();
          return prev;
        });
      } else if (e.key === 'b') {
        e.preventDefault();
        setExpanded((prev) => !prev);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  return (
    <motion.nav
      className="glass-strong relative z-30 flex flex-col overflow-hidden"
      animate={{ width: expanded ? 260 : 56 }}
      transition={{ type: 'spring', stiffness: 300, damping: 30 }}
    >
      {/* Header — hamburger toggle */}
      <div className="flex h-10 flex-shrink-0 items-center px-3">
        <button
          onClick={() => setExpanded((v) => !v)}
          className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg text-surface-400 transition-colors hover:bg-surface-700/40 hover:text-accent"
          title={expanded ? 'Collapse sidebar (Ctrl+B)' : 'Expand sidebar (Ctrl+B)'}
        >
          <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 6.75h16.5M3.75 12h16.5m-16.5 5.25h16.5" />
          </svg>
        </button>
      </div>

      {/* Logo — full-width, dedicated section */}
      <AnimatePresence>
        {expanded && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.2 }}
            className="flex-shrink-0 overflow-hidden px-2 pb-3"
          >
            <img
              src={new URL('../assets/yancotv_logo.png', import.meta.url).href}
              alt="YancoTV"
              className="w-full h-auto object-contain object-left"
              draggable={false}
            />
          </motion.div>
        )}
      </AnimatePresence>

      {/* Search bar — only when expanded */}
      <AnimatePresence>
        {expanded && (
          <motion.form
            onSubmit={handleSearchSubmit}
            className="px-3 pb-2"
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.2 }}
          >
            <div className="relative">
              <svg
                className="absolute left-2.5 top-2.5 h-4 w-4 text-surface-500"
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
                onKeyDown={handleSearchKeyDown}
                onFocus={() => {
                  if (suggestions.length > 0) setSuggestOpen(true);
                }}
                onBlur={() => {
                  // Delay so mousedown on a suggestion registers before close.
                  setTimeout(() => setSuggestOpen(false), 120);
                }}
                aria-autocomplete="list"
                aria-expanded={suggestOpen && suggestions.length > 0}
                aria-controls="sidebar-search-suggestions"
                aria-activedescendant={
                  selectedIdx >= 0 ? `sidebar-search-suggestion-${selectedIdx}` : undefined
                }
                role="combobox"
                className="w-full rounded-lg border border-surface-700/50 bg-surface-800/40 py-2 pl-8 pr-3 text-sm text-surface-200 placeholder-surface-500 outline-none transition-colors focus:border-accent/50 focus:ring-1 focus:ring-accent/30"
              />
              <AnimatePresence>
                {suggestOpen && suggestions.length > 0 && (
                  <motion.ul
                    id="sidebar-search-suggestions"
                    role="listbox"
                    initial={{ opacity: 0, y: -4 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, y: -4 }}
                    transition={{ duration: 0.12 }}
                    className="absolute left-0 right-0 top-full z-50 mt-1 overflow-hidden rounded-lg border border-surface-700/60 bg-surface-900/95 shadow-xl backdrop-blur"
                  >
                    {suggestions.map((item, idx) => {
                      const selected = idx === selectedIdx;
                      const title = item.cleanTitle || item.title;
                      return (
                        <li
                          key={item.id}
                          id={`sidebar-search-suggestion-${idx}`}
                          role="option"
                          aria-selected={selected}
                          onMouseDown={(e) => {
                            // Prevent input blur so click registers.
                            e.preventDefault();
                            handleSuggestionPick(item);
                          }}
                          onMouseEnter={() => setSelectedIdx(idx)}
                          className={`flex cursor-pointer items-center gap-2 px-3 py-2 text-sm transition-colors ${
                            selected
                              ? 'bg-accent/15 text-accent'
                              : 'text-surface-200 hover:bg-surface-700/40'
                          }`}
                        >
                          <svg
                            aria-hidden
                            className="h-4 w-4 flex-shrink-0 text-surface-500"
                            fill="none"
                            viewBox="0 0 24 24"
                            stroke="currentColor"
                            strokeWidth={1.5}
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              d={iconMap[TYPE_ICON_KEY[item.type]]}
                            />
                          </svg>
                          <span className="min-w-0 flex-1 truncate">{title}</span>
                          {item.groupName && (
                            <span className="hidden truncate text-xs text-surface-500 sm:inline">
                              {item.groupName}
                            </span>
                          )}
                        </li>
                      );
                    })}
                    <li
                      onMouseDown={(e) => {
                        e.preventDefault();
                        runFullSearch(searchQuery);
                      }}
                      className="flex cursor-pointer items-center gap-2 border-t border-surface-700/50 bg-surface-800/40 px-3 py-2 text-xs text-surface-400 transition-colors hover:bg-surface-700/40 hover:text-accent"
                    >
                      <span aria-hidden>↵</span>
                      <span>See all results for &ldquo;{searchQuery.trim()}&rdquo;</span>
                    </li>
                  </motion.ul>
                )}
              </AnimatePresence>
            </div>
          </motion.form>
        )}
      </AnimatePresence>

      {/* Navigation items */}
      <div
        className="flex flex-1 flex-col gap-1 px-2"
        role="navigation"
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            const links = Array.from(
              e.currentTarget.querySelectorAll<HTMLElement>('[data-nav-item]'),
            );
            const current = links.findIndex((el) => el === document.activeElement);
            const next =
              e.key === 'ArrowDown'
                ? Math.min(current + 1, links.length - 1)
                : Math.max(current - 1, 0);
            links[next]?.focus();
          }
        }}
      >
        {/* `end` used to be `item.path === '/'`, which is always false: no
            nav item points at '/' (the start-page redirect owns that
            route). Typing the list `as const` surfaced it. NavLink
            defaults `end` to false, so dropping the prop is a no-op. */}
        {navItems.map((item) => (
          <NavLink
            key={item.path}
            to={item.path}
            title={expanded ? undefined : t(item.labelKey)}
            data-nav-item=""
            className={({ isActive }) =>
              `flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-all duration-200 focus:outline-none focus:ring-1 focus:ring-accent/50 ${
                isActive
                  ? 'bg-accent/10 text-accent shadow-glow-sm'
                  : 'text-surface-400 hover:bg-surface-700/30 hover:text-surface-200'
              } ${expanded ? '' : 'justify-center px-0'}`
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
            <AnimatePresence>
              {expanded && (
                <motion.span
                  initial={{ opacity: 0, width: 0 }}
                  animate={{ opacity: 1, width: 'auto' }}
                  exit={{ opacity: 0, width: 0 }}
                  transition={{ duration: 0.15 }}
                  className="truncate"
                >
                  {t(item.labelKey)}
                </motion.span>
              )}
            </AnimatePresence>
          </NavLink>
        ))}
      </div>

      {/* Footer */}
      <div className="border-t border-accent/10 p-3">
        <AnimatePresence mode="wait">
          {expanded ? (
            <motion.div
              key="expanded-footer"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.15 }}
            >
              {showClock && (
                <p className="mb-1 text-sm font-medium tabular-nums text-surface-300">{time}</p>
              )}
              <p className="text-xs text-surface-600">v{appVersion}</p>
            </motion.div>
          ) : (
            <motion.div
              key="collapsed-footer"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.15 }}
            >
              {showClock && (
                <p className="text-center text-[10px] tabular-nums text-surface-500">
                  {time.split(':')[0]}
                  <br />
                  {time.split(':')[1]}
                </p>
              )}
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </motion.nav>
  );
}
