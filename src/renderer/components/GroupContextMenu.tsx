/**
 * Right-click context menu for smart group sidebar items.
 * Supports: Pin/Unpin, Rename, Hide, Move to Top.
 */

import { useEffect, useRef, useState, useCallback } from 'react';
import { useT } from '../i18n';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface ContextMenuAction {
  type: 'pin' | 'unpin' | 'rename' | 'hide' | 'moveToTop';
  groupKey: string;
  newName?: string;
}

interface GroupContextMenuProps {
  x: number;
  y: number;
  groupKey: string;
  groupLabel: string;
  isPinned: boolean;
  isHidden: boolean;
  onAction: (action: ContextMenuAction) => void;
  onClose: () => void;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function GroupContextMenu({
  x,
  y,
  groupKey,
  groupLabel,
  isPinned,
  onAction,
  onClose,
}: GroupContextMenuProps) {
  const t = useT();
  const menuRef = useRef<HTMLDivElement>(null);
  const [renaming, setRenaming] = useState(false);
  const [renameValue, setRenameValue] = useState(groupLabel);
  const inputRef = useRef<HTMLInputElement>(null);

  // Adjust position to stay within viewport
  const [pos, setPos] = useState({ x, y });

  useEffect(() => {
    if (!menuRef.current) return;
    const rect = menuRef.current.getBoundingClientRect();
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const adjusted = { x, y };
    if (x + rect.width > vw - 8) adjusted.x = vw - rect.width - 8;
    if (y + rect.height > vh - 8) adjusted.y = vh - rect.height - 8;
    if (adjusted.x < 8) adjusted.x = 8;
    if (adjusted.y < 8) adjusted.y = 8;
    setPos(adjusted);
  }, [x, y]);

  // Close on outside click or Escape
  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        onClose();
      }
    }
    function handleKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    document.addEventListener('mousedown', handleClick);
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('mousedown', handleClick);
      document.removeEventListener('keydown', handleKey);
    };
  }, [onClose]);

  // Focus rename input when entering rename mode
  useEffect(() => {
    if (renaming && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [renaming]);

  const handleRenameSubmit = useCallback(() => {
    const trimmed = renameValue.trim();
    if (trimmed && trimmed !== groupLabel) {
      onAction({ type: 'rename', groupKey, newName: trimmed });
    }
    onClose();
  }, [renameValue, groupLabel, groupKey, onAction, onClose]);

  const menuItems = [
    {
      label: isPinned ? 'Unpin' : 'Pin to Top',
      icon: isPinned ? unpinIcon : pinIcon,
      action: () => {
        onAction({ type: isPinned ? 'unpin' : 'pin', groupKey });
        onClose();
      },
    },
    {
      label: t('action.rename'),
      icon: renameIcon,
      action: () => setRenaming(true),
    },
    {
      label: t('action.hide'),
      icon: hideIcon,
      action: () => {
        onAction({ type: 'hide', groupKey });
        onClose();
      },
    },
    { divider: true as const },
    {
      label: t('action.moveToTop'),
      icon: moveTopIcon,
      action: () => {
        onAction({ type: 'moveToTop', groupKey });
        onClose();
      },
    },
  ];

  return (
    <div
      ref={menuRef}
      className="fixed z-[9999] min-w-[180px] rounded-lg border border-surface-700/60 bg-surface-900/95 py-1 shadow-xl backdrop-blur-lg"
      style={{ left: pos.x, top: pos.y }}
    >
      {renaming ? (
        <div className="px-2 py-1.5">
          <input
            ref={inputRef}
            type="text"
            value={renameValue}
            onChange={(e) => setRenameValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleRenameSubmit();
              if (e.key === 'Escape') onClose();
            }}
            onBlur={handleRenameSubmit}
            className="w-full rounded-md border border-surface-600 bg-surface-800 px-2.5 py-1.5 text-[13px] text-surface-100 outline-none focus:border-accent/50 focus:ring-1 focus:ring-accent/30"
          />
        </div>
      ) : (
        menuItems.map((item, i) => {
          if ('divider' in item) {
            return <div key={i} className="mx-2 my-1 border-t border-surface-700/50" />;
          }
          return (
            <button
              key={item.label}
              onClick={item.action}
              className="flex w-full items-center gap-2.5 px-3 py-1.5 text-left text-[13px] text-surface-300 transition-colors hover:bg-surface-700/40 hover:text-surface-100"
            >
              <span className="flex h-4 w-4 items-center justify-center text-surface-500">
                {item.icon}
              </span>
              {item.label}
            </button>
          );
        })
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Icons (inline SVGs to avoid dependency)
// ---------------------------------------------------------------------------

const pinIcon = (
  <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M16.5 3.75V16.5L12 14.25 7.5 16.5V3.75m9 0H7.5m9 0h1.125c.621 0 1.125.504 1.125 1.125v1.5c0 .621-.504 1.125-1.125 1.125H5.625A1.125 1.125 0 014.5 6.375v-1.5c0-.621.504-1.125 1.125-1.125H7.5" />
  </svg>
);

const unpinIcon = (
  <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M3 3l18 18M16.5 3.75V12m0 0l-4.5-2.25L7.5 12V3.75m9 0H7.5m9 0h1.125c.621 0 1.125.504 1.125 1.125v1.5" />
  </svg>
);

const renameIcon = (
  <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M16.862 4.487l1.687-1.688a1.875 1.875 0 112.652 2.652L6.832 19.82a4.5 4.5 0 01-1.897 1.13l-2.685.8.8-2.685a4.5 4.5 0 011.13-1.897L16.863 4.487z" />
  </svg>
);

const hideIcon = (
  <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M3.98 8.223A10.477 10.477 0 001.934 12C3.226 16.338 7.244 19.5 12 19.5c.993 0 1.953-.138 2.863-.395M6.228 6.228A10.45 10.45 0 0112 4.5c4.756 0 8.773 3.162 10.065 7.498a10.523 10.523 0 01-4.293 5.774M6.228 6.228L3 3m3.228 3.228l3.65 3.65m7.894 7.894L21 21m-3.228-3.228l-3.65-3.65m0 0a3 3 0 10-4.243-4.243m4.242 4.242L9.88 9.88" />
  </svg>
);

const moveTopIcon = (
  <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 12.75l7.5-7.5 7.5 7.5m-15 6l7.5-7.5 7.5 7.5" />
  </svg>
);
