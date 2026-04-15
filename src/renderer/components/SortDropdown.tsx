import { useState, useRef, useEffect } from 'react';

export type SortOption = 'provider' | 'name-asc' | 'name-desc' | 'recent' | 'group';

interface SortChoice {
  value: SortOption;
  label: string;
  icon: string;
}

const SORT_OPTIONS: SortChoice[] = [
  { value: 'provider', label: 'Provider Order', icon: 'M3 4h18M3 8h18M3 12h18' },
  { value: 'name-asc', label: 'Name A\u2013Z', icon: 'M3 4h13M3 8h9M3 12h5M17 4v16m0 0l4-4m-4 4l-4-4' },
  { value: 'name-desc', label: 'Name Z\u2013A', icon: 'M3 4h5M3 8h9M3 12h13M17 20V4m0 0l4 4m-4-4l-4 4' },
  { value: 'group', label: 'Group', icon: 'M2.25 7.125C2.25 6.504 2.754 6 3.375 6h6c.621 0 1.125.504 1.125 1.125v3.75c0 .621-.504 1.125-1.125 1.125h-6A1.125 1.125 0 012.25 10.875v-3.75zM13.5 7.125c0-.621.504-1.125 1.125-1.125h6c.621 0 1.125.504 1.125 1.125v3.75c0 .621-.504 1.125-1.125 1.125h-6a1.125 1.125 0 01-1.125-1.125v-3.75zM2.25 16.875c0-.621.504-1.125 1.125-1.125h6c.621 0 1.125.504 1.125 1.125v3.75c0 .621-.504 1.125-1.125 1.125h-6A1.125 1.125 0 012.25 20.625v-3.75z' },
  { value: 'recent', label: 'Recently Added', icon: 'M12 6v6h4.5m4.5 0a9 9 0 11-18 0 9 9 0 0118 0z' },
];

interface SortDropdownProps {
  value: SortOption;
  onChange: (sort: SortOption) => void;
}

export function SortDropdown({ value, onChange }: SortDropdownProps) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

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

  // Close on Escape
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open]);

  const current = SORT_OPTIONS.find((o) => o.value === value) ?? SORT_OPTIONS[0];

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen(!open)}
        className="flex items-center gap-1.5 rounded-lg border border-surface-700 bg-surface-800 px-3 py-1.5 text-sm text-surface-300 transition-colors hover:border-surface-600 hover:text-surface-200"
        title="Sort by"
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
        <span>{current.label}</span>
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
        <div className="absolute right-0 z-50 mt-1 w-48 overflow-hidden rounded-lg border border-surface-700 bg-surface-800 shadow-xl shadow-black/30">
          {SORT_OPTIONS.map((option) => (
            <button
              key={option.value}
              onClick={() => {
                onChange(option.value);
                setOpen(false);
              }}
              className={`flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm transition-colors ${
                option.value === value
                  ? 'bg-accent/10 text-accent'
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
              <span>{option.label}</span>
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
