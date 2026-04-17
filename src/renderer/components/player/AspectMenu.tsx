import { useEffect, useRef } from 'react';
import { usePlayerStore } from '../../stores/player-store';

/**
 * Compact aspect-ratio picker — appears above the aspect button in the
 * controls bar. Intentionally narrow and single-purpose so the full settings
 * panel stays reserved for actions that need more room.
 */
interface AspectMenuProps {
  onClose: () => void;
}

const ASPECT_OPTIONS: { value: string; label: string; hint?: string }[] = [
  { value: 'auto', label: 'Auto', hint: 'Original' },
  { value: '16:9', label: '16:9', hint: 'Widescreen' },
  { value: '4:3', label: '4:3', hint: 'Classic TV' },
  { value: '21:9', label: '21:9', hint: 'Ultrawide' },
  { value: '2.35:1', label: '2.35:1', hint: 'Cinemascope' },
  { value: '1:1', label: '1:1', hint: 'Square' },
  { value: 'fill', label: 'Fill', hint: 'Stretch' },
];

export function AspectMenu({ onClose }: AspectMenuProps) {
  const aspectRatio = usePlayerStore((s) => s.aspectRatio);
  const setAspectRatio = usePlayerStore((s) => s.setAspectRatio);
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClick = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) {
        onClose();
      }
    };
    // Use `click` (not `mousedown`) so the trigger button's onClick runs first.
    // Otherwise mousedown closes the menu, then the button's click toggles it
    // back open — making the button feel like it never closes.
    const timer = setTimeout(() => {
      document.addEventListener('click', handleClick);
    }, 100);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('click', handleClick);
    };
  }, [onClose]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
      }
    };
    window.addEventListener('keydown', handler, true);
    return () => window.removeEventListener('keydown', handler, true);
  }, [onClose]);

  return (
    <div
      ref={panelRef}
      className="absolute bottom-20 right-6 z-[50] w-56 overflow-hidden rounded-2xl border border-white/10 bg-surface-900/95 shadow-glass backdrop-blur-xl"
    >
      <div className="border-b border-white/10 px-4 py-2.5">
        <p className="text-xs font-medium uppercase tracking-wider text-surface-400">
          Aspect Ratio
        </p>
      </div>
      <ul className="max-h-72 overflow-y-auto py-1">
        {ASPECT_OPTIONS.map((opt) => {
          const selected = aspectRatio === opt.value;
          return (
            <li key={opt.value}>
              <button
                onClick={() => {
                  setAspectRatio(opt.value);
                  onClose();
                }}
                className={`flex w-full items-center justify-between px-4 py-2 text-sm transition-colors ${
                  selected
                    ? 'bg-accent/15 text-accent'
                    : 'text-surface-200 hover:bg-surface-800'
                }`}
              >
                <span className="flex items-center gap-2">
                  {selected ? (
                    <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
                      <path
                        fillRule="evenodd"
                        d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                        clipRule="evenodd"
                      />
                    </svg>
                  ) : (
                    <span className="inline-block h-4 w-4" />
                  )}
                  <span className="font-medium">{opt.label}</span>
                </span>
                {opt.hint && (
                  <span className="text-xs text-surface-500">{opt.hint}</span>
                )}
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
