import { useState, useMemo, useCallback, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { useCategoryGroups } from '../hooks/use-category-groups';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface CategorySidebarProps {
  categories: string[];
  selected: string | string[] | null;
  onSelect: (category: string | string[] | null) => void;
  isLoading?: boolean;
  categoryCounts?: Record<string, number>;
  totalCount?: number;
  defaultCollapsed?: boolean;
}

type FocusableItem =
  | { type: 'all' }
  | { type: 'group'; prefix: string; children: string[] }
  | { type: 'child'; original: string; groupPrefix: string; label: string }
  | { type: 'ungrouped'; original: string };

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const SPRING = { type: 'spring' as const, stiffness: 300, damping: 30 };

function isGroupActive(selected: string | string[] | null, children: string[]): boolean {
  if (!Array.isArray(selected)) return false;
  if (selected.length !== children.length) return false;
  const set = new Set(selected);
  return children.every((c) => set.has(c));
}

function isChildActive(selected: string | string[] | null, child: string): boolean {
  return selected === child;
}

function sumCounts(children: string[], counts?: Record<string, number>): number | undefined {
  if (!counts) return undefined;
  let s = 0;
  for (const c of children) s += counts[c] ?? 0;
  return s;
}

/** Unique key for each focusable item, used for data-focus-idx lookup. */
function itemKey(item: FocusableItem): string {
  switch (item.type) {
    case 'all': return '__all__';
    case 'group': return `__g__${item.prefix}`;
    case 'child': return item.original;
    case 'ungrouped': return item.original;
  }
}

// ---------------------------------------------------------------------------
// CountBadge
// ---------------------------------------------------------------------------

function CountBadge({ count, active }: { count?: number; active: boolean }) {
  if (count == null) return null;
  return (
    <span
      className={`ml-2 flex-shrink-0 rounded px-1.5 py-px text-[10px] font-medium tabular-nums ${
        active ? 'bg-accent/15 text-accent' : 'bg-surface-700/40 text-surface-500'
      }`}
    >
      {count.toLocaleString()}
    </span>
  );
}

// ---------------------------------------------------------------------------
// CategorySidebar
// ---------------------------------------------------------------------------

export function CategorySidebar({
  categories,
  selected,
  onSelect,
  isLoading,
  categoryCounts,
  totalCount,
  defaultCollapsed,
}: CategorySidebarProps) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed ?? false);
  const { grouped, expandedGroups, toggleGroup } = useCategoryGroups(categories);
  const [focusIndex, setFocusIndex] = useState(-1);
  const containerRef = useRef<HTMLDivElement>(null);

  // -- Build flat list of focusable items --
  const focusableItems = useMemo(() => {
    const items: FocusableItem[] = [{ type: 'all' }];
    for (const group of grouped.groups) {
      items.push({ type: 'group', prefix: group.prefix, children: group.children });
      if (expandedGroups.has(group.prefix)) {
        for (let i = 0; i < group.children.length; i++) {
          items.push({
            type: 'child',
            original: group.children[i],
            groupPrefix: group.prefix,
            label: group.childLabels[i],
          });
        }
      }
    }
    for (const cat of grouped.ungrouped) {
      items.push({ type: 'ungrouped', original: cat });
    }
    return items;
  }, [grouped, expandedGroups]);

  // Build key→index map for rendering
  const indexMap = useMemo(() => {
    const map = new Map<string, number>();
    focusableItems.forEach((item, i) => map.set(itemKey(item), i));
    return map;
  }, [focusableItems]);

  // Clamp focusIndex when list shrinks
  useEffect(() => {
    setFocusIndex((prev) =>
      prev < 0 ? prev : Math.min(prev, focusableItems.length - 1),
    );
  }, [focusableItems.length]);

  // Focus the DOM element when focusIndex changes
  useEffect(() => {
    if (focusIndex < 0 || !containerRef.current) return;
    const el = containerRef.current.querySelector(
      `[data-fi="${focusIndex}"]`,
    ) as HTMLElement | null;
    if (el) {
      el.focus();
      el.scrollIntoView({ block: 'nearest' });
    }
  }, [focusIndex]);

  // -- Activate the focused item --
  const activateItem = useCallback(
    (item: FocusableItem) => {
      switch (item.type) {
        case 'all':
          onSelect(null);
          break;
        case 'group':
          onSelect(item.children);
          break;
        case 'child':
        case 'ungrouped':
          onSelect(item.original);
          break;
      }
    },
    [onSelect],
  );

  // -- Keyboard handler --
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (collapsed) return;
      const len = focusableItems.length;
      if (len === 0) return;

      switch (e.key) {
        case 'ArrowDown':
          e.preventDefault();
          setFocusIndex((i) => (i < 0 ? 0 : Math.min(i + 1, len - 1)));
          break;
        case 'ArrowUp':
          e.preventDefault();
          setFocusIndex((i) => (i <= 0 ? 0 : i - 1));
          break;
        case 'Home':
          e.preventDefault();
          setFocusIndex(0);
          break;
        case 'End':
          e.preventDefault();
          setFocusIndex(len - 1);
          break;
        case 'Enter':
        case ' ':
          e.preventDefault();
          if (focusIndex >= 0 && focusIndex < len) {
            activateItem(focusableItems[focusIndex]);
          }
          break;
        case 'ArrowRight': {
          if (focusIndex < 0) break;
          const item = focusableItems[focusIndex];
          if (item.type === 'group' && !expandedGroups.has(item.prefix)) {
            e.preventDefault();
            toggleGroup(item.prefix);
          }
          break;
        }
        case 'ArrowLeft': {
          if (focusIndex < 0) break;
          const item = focusableItems[focusIndex];
          if (item.type === 'group' && expandedGroups.has(item.prefix)) {
            e.preventDefault();
            toggleGroup(item.prefix);
          } else if (item.type === 'child') {
            // Jump focus to parent group header
            e.preventDefault();
            const parentIdx = focusableItems.findIndex(
              (fi) => fi.type === 'group' && fi.prefix === item.groupPrefix,
            );
            if (parentIdx >= 0) setFocusIndex(parentIdx);
          }
          break;
        }
      }
    },
    [collapsed, focusIndex, focusableItems, expandedGroups, activateItem, toggleGroup],
  );

  // -- Loading / empty --
  if (isLoading) {
    return (
      <div className="glass w-52 flex-shrink-0 space-y-1 rounded-xl p-2">
        {Array.from({ length: 10 }).map((_, i) => (
          <div key={i} className="h-8 animate-pulse rounded-md bg-surface-800/50" />
        ))}
      </div>
    );
  }

  if (categories.length === 0) return null;

  const hasGroups = grouped.groups.length > 0;

  // -- Render helpers --
  const fi = (key: string) => indexMap.get(key) ?? -1;
  const focusProps = (idx: number) => ({
    'data-fi': idx,
    tabIndex: idx === focusIndex ? 0 : -1,
  });

  return (
    <motion.div
      className="glass flex-shrink-0 overflow-hidden rounded-xl"
      animate={{ width: collapsed ? 40 : 280 }}
      transition={SPRING}
    >
      {/* Toggle button */}
      <div className="flex h-9 items-center px-2">
        <button
          onClick={() => setCollapsed((v) => !v)}
          className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md text-surface-400 transition-colors hover:bg-surface-700/40 hover:text-accent focus:outline-none focus:ring-1 focus:ring-accent/50"
          title={collapsed ? 'Expand categories' : 'Collapse categories'}
        >
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 6.75h16.5M3.75 12h16.5m-16.5 5.25h16.5" />
          </svg>
        </button>
      </div>

      {/* Content */}
      <AnimatePresence>
        {!collapsed && (
          <motion.div
            ref={containerRef}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.15 }}
            className="overflow-y-auto px-2 pb-2"
            style={{ maxHeight: 'calc(100% - 36px)' }}
            role="listbox"
            onKeyDown={handleKeyDown}
          >
            {/* ── All ─────────────────────────────────────────────── */}
            {(() => {
              const idx = fi('__all__');
              const active = selected === null;
              return (
                <button
                  {...focusProps(idx)}
                  onClick={() => onSelect(null)}
                  className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-sm font-medium transition-all duration-200 focus:outline-none focus:ring-1 focus:ring-accent/30 ${
                    active
                      ? 'bg-accent/10 text-accent shadow-glow-sm'
                      : 'text-surface-400 hover:bg-surface-700/30 hover:text-surface-200'
                  }`}
                >
                  <span>All</span>
                  <CountBadge count={totalCount} active={active} />
                </button>
              );
            })()}

            <div className="mx-2 my-1.5 border-t border-accent/8" />

            {/* ── Super-groups ────────────────────────────────────── */}
            {grouped.groups.map((group) => {
              const gIdx = fi(`__g__${group.prefix}`);
              const active = isGroupActive(selected, group.children);
              const hasActiveChild =
                !active && typeof selected === 'string' && group.children.includes(selected);
              const count = sumCounts(group.children, categoryCounts);
              const expanded = expandedGroups.has(group.prefix);

              return (
                <div key={group.prefix}>
                  {/* Group header */}
                  <div
                    className={`flex w-full items-center rounded-lg transition-all duration-200 ${
                      active
                        ? 'bg-accent/10 shadow-glow-sm'
                        : hasActiveChild
                          ? 'bg-accent/5'
                          : 'hover:bg-surface-700/30'
                    }`}
                  >
                    {/* Chevron (mouse-only, not in keyboard tab order) */}
                    <button
                      tabIndex={-1}
                      onClick={() => toggleGroup(group.prefix)}
                      className="flex h-8 w-7 flex-shrink-0 items-center justify-center text-surface-500 hover:text-surface-300"
                    >
                      <svg
                        className={`h-3 w-3 transition-transform duration-200 ${expanded ? 'rotate-90' : ''}`}
                        fill="currentColor"
                        viewBox="0 0 20 20"
                      >
                        <path d="M6.293 4.293a1 1 0 011.414 0L13.414 10l-5.707 5.707a1 1 0 01-1.414-1.414L10.586 10 6.293 5.707a1 1 0 010-1.414z" />
                      </svg>
                    </button>

                    {/* Group label — keyboard focusable */}
                    <button
                      {...focusProps(gIdx)}
                      onClick={() => onSelect(group.children)}
                      className={`flex min-w-0 flex-1 items-center justify-between py-2 pr-3 text-left text-sm font-semibold focus:outline-none focus:ring-1 focus:ring-accent/30 rounded ${
                        active
                          ? 'text-accent'
                          : hasActiveChild
                            ? 'text-accent/70'
                            : 'text-surface-300 hover:text-surface-100'
                      }`}
                    >
                      <span className="min-w-0 flex-1 truncate">{group.prefix}</span>
                      <CountBadge count={count} active={active} />
                    </button>
                  </div>

                  {/* Accordion children */}
                  <AnimatePresence initial={false}>
                    {expanded && (
                      <motion.div
                        initial={{ height: 0, opacity: 0 }}
                        animate={{ height: 'auto', opacity: 1 }}
                        exit={{ height: 0, opacity: 0 }}
                        transition={{ duration: 0.2 }}
                        className="overflow-hidden"
                      >
                        <div className="pl-3">
                          {group.children.map((child, i) => {
                            const cIdx = fi(child);
                            const childCount = categoryCounts?.[child];
                            const childActive = isChildActive(selected, child);
                            return (
                              <button
                                key={child}
                                {...focusProps(cIdx)}
                                onClick={() => onSelect(child)}
                                className={`flex w-full items-center justify-between rounded-lg px-3 py-1.5 text-left text-sm transition-all duration-200 focus:outline-none focus:ring-1 focus:ring-accent/30 ${
                                  childActive
                                    ? 'bg-accent/10 text-accent shadow-glow-sm'
                                    : 'text-surface-400 hover:bg-surface-700/30 hover:text-surface-200'
                                }`}
                              >
                                <span className="min-w-0 flex-1 truncate">{group.childLabels[i]}</span>
                                <CountBadge count={childCount} active={childActive} />
                              </button>
                            );
                          })}
                        </div>
                      </motion.div>
                    )}
                  </AnimatePresence>
                </div>
              );
            })}

            {/* Separator */}
            {hasGroups && grouped.ungrouped.length > 0 && (
              <div className="mx-2 my-1.5 border-t border-accent/8" />
            )}

            {/* ── Ungrouped ───────────────────────────────────────── */}
            {grouped.ungrouped.map((cat) => {
              const idx = fi(cat);
              const count = categoryCounts?.[cat];
              const active = isChildActive(selected, cat);
              return (
                <button
                  key={cat}
                  {...focusProps(idx)}
                  onClick={() => onSelect(cat)}
                  className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-sm transition-all duration-200 focus:outline-none focus:ring-1 focus:ring-accent/30 ${
                    active
                      ? 'bg-accent/10 text-accent shadow-glow-sm'
                      : 'text-surface-400 hover:bg-surface-700/30 hover:text-surface-200'
                  }`}
                >
                  <span className="min-w-0 flex-1 truncate">{cat}</span>
                  <CountBadge count={count} active={active} />
                </button>
              );
            })}
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
