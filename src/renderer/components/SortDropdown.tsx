import { useState, useRef, useEffect, useCallback } from 'react';
import { useT } from '../i18n';
import type { StringKey } from '../i18n/locales/en';

export type SortOption = 'provider' | 'name-asc' | 'name-desc' | 'recent' | 'group';

interface SortChoice {
  value: SortOption;
  /** Translation key — resolved at render, see the note on SORT_OPTIONS. */
  labelKey: StringKey;
  icon: string;
}

// Keys, not resolved labels: a module-level constant is evaluated once at
// import and would freeze the language active at load.
const SORT_OPTIONS: SortChoice[] = [
  { value: 'provider', labelKey: 'sort.provider', icon: 'M3 4h18M3 8h18M3 12h18' },
  { value: 'name-asc', labelKey: 'sort.nameAsc', icon: 'M3 4h13M3 8h9M3 12h5M17 4v16m0 0l4-4m-4 4l-4-4' },
  { value: 'name-desc', labelKey: 'sort.nameDesc', icon: 'M3 4h5M3 8h9M3 12h13M17 20V4m0 0l4 4m-4-4l-4 4' },
  { value: 'group', labelKey: 'sort.group', icon: 'M2.25 7.125C2.25 6.504 2.754 6 3.375 6h6c.621 0 1.125.504 1.125 1.125v3.75c0 .621-.504 1.125-1.125 1.125h-6A1.125 1.125 0 012.25 10.875v-3.75zM13.5 7.125c0-.621.504-1.125 1.125-1.125h6c.621 0 1.125.504 1.125 1.125v3.75c0 .621-.504 1.125-1.125 1.125h-6a1.125 1.125 0 01-1.125-1.125v-3.75zM2.25 16.875c0-.621.504-1.125 1.125-1.125h6c.621 0 1.125.504 1.125 1.125v3.75c0 .621-.504 1.125-1.125 1.125h-6A1.125 1.125 0 012.25 20.625v-3.75z' },
  { value: 'recent', labelKey: 'sort.recent', icon: 'M12 6v6h4.5m4.5 0a9 9 0 11-18 0 9 9 0 0118 0z' },
];

interface SortDropdownProps {
  value: SortOption;
  onChange: (sort: SortOption) => void;
}

export function SortDropdown({ value, onChange }: SortDropdownProps) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [focusIndex, setFocusIndex] = useState(-1);
  const ref = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([]);

  // When opening, focus the current value (or first item)
  useEffect(() => {
    if (open) {
      const idx = SORT_OPTIONS.findIndex((o) => o.value === value);
      setFocusIndex(idx >= 0 ? idx : 0);
    } else {
      setFocusIndex(-1);
    }
  }, [open, value]);

  // Focus the active item when focusIndex changes
  useEffect(() => {
    if (open && focusIndex >= 0) {
      itemRefs.current[focusIndex]?.focus();
    }
  }, [open, focusIndex]);

  // Close on click outside
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  const select = useCallback(
    (option: SortOption) => {
      onChange(option);
      setOpen(false);
      triggerRef.current?.focus();
    },
    [onChange],
  );

  // Keyboard handling on the trigger button
  const handleTriggerKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        if (!open) setOpen(true);
      } else if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        setOpen((v) => !v);
      }
    },
    [open],
  );

  // Keyboard handling within the dropdown menu
  const handleMenuKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      switch (e.key) {
        case 'ArrowDown':
          e.preventDefault();
          setFocusIndex((i) => Math.min(i + 1, SORT_OPTIONS.length - 1));
          break;
        case 'ArrowUp':
          e.preventDefault();
          setFocusIndex((i) => Math.max(i - 1, 0));
          break;
        case 'Home':
          e.preventDefault();
          setFocusIndex(0);
          break;
        case 'End':
          e.preventDefault();
          setFocusIndex(SORT_OPTIONS.length - 1);
          break;
        case 'Enter':
        case ' ':
          e.preventDefault();
          if (focusIndex >= 0) select(SORT_OPTIONS[focusIndex].value);
          break;
        case 'Escape':
          e.preventDefault();
          setOpen(false);
          triggerRef.current?.focus();
          break;
      }
    },
    [focusIndex, select],
  );

  const current = SORT_OPTIONS.find((o) => o.value === value) ?? SORT_OPTIONS[0];

  return (
    <div ref={ref} className="relative">
      <button
        ref={triggerRef}
        onClick={() => setOpen(!open)}
        onKeyDown={handleTriggerKeyDown}
        className="flex items-center gap-1.5 rounded-lg border border-accent/5 bg-surface-800 px-3 py-1.5 text-sm text-surface-300 transition-colors hover:border-accent/20 hover:text-surface-200 focus:outline-none focus:ring-1 focus:ring-accent/50"
        title={t('sort.by')}
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <svg
          className="h-4 w-4 text-surface-400"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          strokeWidth={1.5}
        >
          <path strokeLinecap="round" strokeLinejoin="round" d={current.icon} />
        </svg>
        <span>{t(current.labelKey)}</span>
        <svg
          className={`h-3.5 w-3.5 text-surface-500 transition-transform ${open ? 'rotate-180' : ''}`}
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          strokeWidth={2}
        >
          <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {open && (
        <div
          className="absolute right-0 z-50 mt-1 w-48 overflow-hidden rounded-xl border border-accent/5 bg-surface-800 shadow-xl shadow-black/30"
          role="listbox"
          onKeyDown={handleMenuKeyDown}
        >
          {SORT_OPTIONS.map((option, i) => (
            <button
              key={option.value}
              ref={(el) => { itemRefs.current[i] = el; }}
              onClick={() => select(option.value)}
              role="option"
              aria-selected={option.value === value}
              tabIndex={i === focusIndex ? 0 : -1}
              className={`flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm transition-colors focus:outline-none ${
                option.value === value
                  ? 'bg-accent/10 text-accent shadow-glow-sm'
                  : i === focusIndex
                    ? 'bg-surface-700 text-surface-200'
                    : 'text-surface-300 hover:bg-surface-700 hover:text-surface-200'
              }`}
            >
              <svg
                className="h-4 w-4 flex-shrink-0"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
                strokeWidth={1.5}
              >
                <path strokeLinecap="round" strokeLinejoin="round" d={option.icon} />
              </svg>
              <span>{t(option.labelKey)}</span>
              {option.value === value && (
                <svg
                  className="ml-auto h-4 w-4 flex-shrink-0"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={2}
                >
                  <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                </svg>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
