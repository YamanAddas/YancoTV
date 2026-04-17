/**
 * Smart Category Sidebar — groups shown under auto-detected language headers.
 * Full original group names are always displayed.
 * Supports drag-and-drop reordering, search, pinning, and context menu.
 */

import { useState, useMemo, useCallback, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
  useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useCategoryGroups, type EnhancedSection } from '../hooks/use-category-groups';
import { GroupContextMenu, type ContextMenuAction } from './GroupContextMenu';
import type { ContentType } from '../../shared/types';
import type { SmartChild } from '../utils/category-grouping';
import { prettifyGroupName } from '../utils/group-parser';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface CategorySidebarProps {
  categories: string[];
  selected: string | string[] | null;
  onSelect: (category: string | string[] | null) => void;
  contentType: ContentType;
  isLoading?: boolean;
  categoryCounts?: Record<string, number>;
  totalCount?: number;
  defaultCollapsed?: boolean;
}

interface ContextMenuState {
  x: number;
  y: number;
  groupKey: string;
  groupLabel: string;
  isPinned: boolean;
  isHidden: boolean;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const SPRING = { type: 'spring' as const, stiffness: 300, damping: 30 };

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function isChildActive(selected: string | string[] | null, groupName: string): boolean {
  if (selected === groupName) return true;
  if (Array.isArray(selected) && selected.includes(groupName)) return true;
  return false;
}

function isSectionActive(selected: string | string[] | null, children: SmartChild[]): boolean {
  if (!Array.isArray(selected)) return false;
  const names = children.map((c) => c.originalGroupName);
  if (selected.length !== names.length) return false;
  const set = new Set(selected);
  return names.every((n) => set.has(n));
}

function sectionHasActiveChild(selected: string | string[] | null, children: SmartChild[]): boolean {
  if (!selected || Array.isArray(selected)) return false;
  return children.some((c) => c.originalGroupName === selected);
}

// ---------------------------------------------------------------------------
// CountBadge
// ---------------------------------------------------------------------------

function CountBadge({ count, active }: { count?: number; active: boolean }) {
  if (count == null) return null;
  return (
    <span
      className={`ml-auto flex-shrink-0 rounded px-1.5 py-0.5 text-[11px] font-medium tabular-nums ${
        active ? 'bg-accent/15 text-accent' : 'bg-surface-700/40 text-surface-500'
      }`}
    >
      {count.toLocaleString()}
    </span>
  );
}

// ---------------------------------------------------------------------------
// SortableSection — a draggable language section with its children
// ---------------------------------------------------------------------------

function SortableSection({
  section,
  isExpanded,
  selected,
  categoryCounts,
  onToggleExpand,
  onSelectSection,
  onSelectChild,
  onContextMenu,
}: {
  section: EnhancedSection;
  isExpanded: boolean;
  selected: string | string[] | null;
  categoryCounts?: Record<string, number>;
  onToggleExpand: () => void;
  onSelectSection: () => void;
  onSelectChild: (groupName: string) => void;
  onContextMenu: (e: React.MouseEvent) => void;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: section.key });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
    zIndex: isDragging ? 50 : 'auto' as string | number,
  };

  const active = isSectionActive(selected, section.children);
  const hasActive = sectionHasActiveChild(selected, section.children);
  const displayLabel = section.customName || section.label;

  // Sum counts for all children in this section
  const sectionCount = useMemo(() => {
    if (!categoryCounts) return undefined;
    let total = 0;
    for (const child of section.children) {
      total += categoryCounts[child.originalGroupName] ?? 0;
    }
    return total;
  }, [section.children, categoryCounts]);

  return (
    <div ref={setNodeRef} style={style} className="group/section">
      {/* Section header */}
      <div
        onContextMenu={onContextMenu}
        className={`flex w-full items-center rounded-lg transition-all duration-200 ${
          active
            ? 'bg-accent/10 shadow-glow-sm'
            : hasActive
              ? 'bg-accent/5'
              : 'hover:bg-surface-700/30'
        }`}
      >
        {/* Drag handle */}
        <button
          {...attributes}
          {...listeners}
          tabIndex={-1}
          className="flex h-8 w-5 flex-shrink-0 cursor-grab items-center justify-center text-surface-600 opacity-0 transition-opacity group-hover/section:opacity-100 active:cursor-grabbing"
          title="Drag to reorder"
        >
          <svg className="h-3 w-3" viewBox="0 0 10 16" fill="currentColor">
            <circle cx="3" cy="2" r="1.2" />
            <circle cx="7" cy="2" r="1.2" />
            <circle cx="3" cy="6" r="1.2" />
            <circle cx="7" cy="6" r="1.2" />
            <circle cx="3" cy="10" r="1.2" />
            <circle cx="7" cy="10" r="1.2" />
            <circle cx="3" cy="14" r="1.2" />
            <circle cx="7" cy="14" r="1.2" />
          </svg>
        </button>

        {/* Chevron */}
        <button
          tabIndex={-1}
          onClick={onToggleExpand}
          className="flex h-8 w-5 flex-shrink-0 items-center justify-center text-surface-500 hover:text-surface-300"
        >
          <svg
            className={`h-3 w-3 transition-transform duration-200 ${isExpanded ? 'rotate-90' : ''}`}
            fill="currentColor"
            viewBox="0 0 20 20"
          >
            <path d="M6.293 4.293a1 1 0 011.414 0L13.414 10l-5.707 5.707a1 1 0 01-1.414-1.414L10.586 10 6.293 5.707a1 1 0 010-1.414z" />
          </svg>
        </button>

        {/* Section label */}
        <button
          onClick={onSelectSection}
          onContextMenu={onContextMenu}
          className={`flex min-w-0 flex-1 items-center gap-1.5 py-2 pr-3 text-left text-[13px] font-semibold transition-colors focus:outline-none focus:ring-1 focus:ring-accent/30 rounded ${
            active
              ? 'text-accent'
              : hasActive
                ? 'text-accent/70'
                : 'text-surface-200 hover:text-surface-100'
          }`}
        >
          {section.icon && <span className="text-sm leading-none">{section.icon}</span>}
          <span className="truncate">{displayLabel}</span>
          <CountBadge count={sectionCount} active={active} />
        </button>
      </div>

      {/* Children — full original group names */}
      <AnimatePresence initial={false}>
        {isExpanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden"
          >
            <div className="pl-6">
              {section.children.map((child) => {
                const childActive = isChildActive(selected, child.originalGroupName);
                const count = categoryCounts?.[child.originalGroupName];
                return (
                  <button
                    key={child.originalGroupName}
                    onClick={() => onSelectChild(child.originalGroupName)}
                    className={`flex w-full items-center gap-2 rounded-lg px-3 py-1.5 text-left text-[13px] transition-all duration-200 focus:outline-none focus:ring-1 focus:ring-accent/30 ${
                      childActive
                        ? 'bg-accent/10 text-accent shadow-glow-sm'
                        : 'text-surface-400 hover:bg-surface-700/30 hover:text-surface-200'
                    }`}
                  >
                    <span
                      className="min-w-0 flex-1 truncate"
                      title={child.originalGroupName}
                    >
                      {prettifyGroupName(child.originalGroupName)}
                    </span>
                    <CountBadge count={count} active={childActive} />
                  </button>
                );
              })}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

// ---------------------------------------------------------------------------
// CategorySidebar
// ---------------------------------------------------------------------------

export function CategorySidebar({
  categories,
  selected,
  onSelect,
  contentType,
  isLoading,
  categoryCounts,
  totalCount,
  defaultCollapsed,
}: CategorySidebarProps) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed ?? false);
  const [searchQuery, setSearchQuery] = useState('');
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const {
    grouped,
    expandedGroups,
    toggleGroup,
    onTogglePin,
    onToggleHide,
    onRename,
    onReorder,
  } = useCategoryGroups(categories, contentType);

  // DnD sensors
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  useEffect(() => {
    setSearchQuery('');
  }, [categories]);

  // Filter by search query
  const filteredGrouped = useMemo(() => {
    if (!searchQuery.trim()) return grouped;
    const q = searchQuery.toLowerCase();

    const filterSection = (s: EnhancedSection): EnhancedSection | null => {
      const labelMatch = (s.customName || s.label).toLowerCase().includes(q);
      const matchingChildren = s.children.filter((c) =>
        c.originalGroupName.toLowerCase().includes(q),
      );
      if (labelMatch) return s;
      if (matchingChildren.length > 0) return { ...s, children: matchingChildren };
      return null;
    };

    return {
      pinned: grouped.pinned.map(filterSection).filter(Boolean) as EnhancedSection[],
      sections: grouped.sections.map(filterSection).filter(Boolean) as EnhancedSection[],
      ungrouped: grouped.ungrouped.filter((u) =>
        u.originalGroupName.toLowerCase().includes(q),
      ),
    };
  }, [grouped, searchQuery]);

  const allSectionKeys = useMemo(
    () => [
      ...filteredGrouped.pinned.map((s) => s.key),
      ...filteredGrouped.sections.map((s) => s.key),
    ],
    [filteredGrouped],
  );

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      if (!over || active.id === over.id) return;

      const allKeys = [
        ...grouped.pinned.map((s) => s.key),
        ...grouped.sections.map((s) => s.key),
      ];
      const oldIndex = allKeys.indexOf(active.id as string);
      const newIndex = allKeys.indexOf(over.id as string);
      if (oldIndex === -1 || newIndex === -1) return;

      const newKeys = [...allKeys];
      newKeys.splice(oldIndex, 1);
      newKeys.splice(newIndex, 0, active.id as string);
      onReorder(newKeys);
    },
    [grouped, onReorder],
  );

  const handleContextMenu = useCallback(
    (e: React.MouseEvent, section: EnhancedSection) => {
      e.preventDefault();
      setContextMenu({
        x: e.clientX,
        y: e.clientY,
        groupKey: section.key,
        groupLabel: section.customName || section.label,
        isPinned: section.isPinned,
        isHidden: false,
      });
    },
    [],
  );

  const handleContextAction = useCallback(
    (action: ContextMenuAction) => {
      switch (action.type) {
        case 'pin':
        case 'unpin':
          onTogglePin(action.groupKey);
          break;
        case 'rename':
          if (action.newName) onRename(action.groupKey, action.newName);
          break;
        case 'hide':
          onToggleHide(action.groupKey);
          break;
        case 'moveToTop':
          onReorder([action.groupKey, ...allSectionKeys.filter((k) => k !== action.groupKey)]);
          break;
      }
    },
    [onTogglePin, onRename, onToggleHide, onReorder, allSectionKeys],
  );

  // Loading skeleton
  if (isLoading) {
    return (
      <div className="glass w-[280px] flex-shrink-0 space-y-1 rounded-xl p-2">
        {Array.from({ length: 12 }).map((_, i) => (
          <div key={i} className="h-8 animate-pulse rounded-md bg-surface-800/50" />
        ))}
      </div>
    );
  }

  if (categories.length === 0) return null;

  const hasSections = filteredGrouped.pinned.length > 0 || filteredGrouped.sections.length > 0;

  const renderSection = (section: EnhancedSection) => (
    <SortableSection
      key={section.key}
      section={section}
      isExpanded={expandedGroups.has(section.key)}
      selected={selected}
      categoryCounts={categoryCounts}
      onToggleExpand={() => toggleGroup(section.key)}
      onSelectSection={() =>
        onSelect(section.children.map((c) => c.originalGroupName))
      }
      onSelectChild={(groupName) => onSelect(groupName)}
      onContextMenu={(e) => handleContextMenu(e, section)}
    />
  );

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
          >
            {/* Search */}
            <div className="relative mb-2">
              <svg
                className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-surface-500"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
                strokeWidth={2}
              >
                <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
              </svg>
              <input
                type="text"
                placeholder="Filter groups..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-full rounded-md border border-surface-700/50 bg-surface-800/50 py-1.5 pl-8 pr-2.5 text-[13px] text-surface-200 placeholder-surface-600 outline-none transition-colors focus:border-accent/40 focus:ring-1 focus:ring-accent/20"
              />
              {searchQuery && (
                <button
                  onClick={() => setSearchQuery('')}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-surface-500 hover:text-surface-300"
                >
                  <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              )}
            </div>

            {/* All */}
            <button
              onClick={() => onSelect(null)}
              className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-[13px] font-semibold transition-all duration-200 focus:outline-none focus:ring-1 focus:ring-accent/30 ${
                selected === null
                  ? 'bg-accent/10 text-accent shadow-glow-sm'
                  : 'text-surface-300 hover:bg-surface-700/30 hover:text-surface-100'
              }`}
            >
              <span>All</span>
              <CountBadge count={totalCount} active={selected === null} />
            </button>

            <div className="mx-2 my-2 border-t border-surface-700/30" />

            <DndContext
              sensors={sensors}
              collisionDetection={closestCenter}
              onDragEnd={handleDragEnd}
            >
              <SortableContext
                items={allSectionKeys}
                strategy={verticalListSortingStrategy}
              >
                {/* Pinned */}
                {filteredGrouped.pinned.length > 0 && (
                  <>
                    <div className="mb-1 px-2 text-[11px] font-semibold uppercase tracking-wider text-surface-500">
                      Pinned
                    </div>
                    {filteredGrouped.pinned.map(renderSection)}
                    <div className="mx-2 my-2 border-t border-surface-700/30" />
                  </>
                )}

                {/* Sections */}
                {filteredGrouped.sections.map(renderSection)}
              </SortableContext>
            </DndContext>

            {/* Ungrouped */}
            {hasSections && filteredGrouped.ungrouped.length > 0 && (
              <div className="mx-2 my-2 border-t border-surface-700/30" />
            )}
            {filteredGrouped.ungrouped.length > 0 && (
              <>
                {hasSections && (
                  <div className="mb-1 px-2 text-[11px] font-semibold uppercase tracking-wider text-surface-500">
                    Other
                  </div>
                )}
                {filteredGrouped.ungrouped.map((item) => {
                  const count = categoryCounts?.[item.originalGroupName];
                  const active = isChildActive(selected, item.originalGroupName);
                  return (
                    <button
                      key={item.originalGroupName}
                      onClick={() => onSelect(item.originalGroupName)}
                      className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-[13px] transition-all duration-200 focus:outline-none focus:ring-1 focus:ring-accent/30 ${
                        active
                          ? 'bg-accent/10 text-accent shadow-glow-sm'
                          : 'text-surface-400 hover:bg-surface-700/30 hover:text-surface-200'
                      }`}
                    >
                      <span className="min-w-0 flex-1 truncate">{item.originalGroupName}</span>
                      <CountBadge count={count} active={active} />
                    </button>
                  );
                })}
              </>
            )}
          </motion.div>
        )}
      </AnimatePresence>

      {/* Context menu */}
      {contextMenu && (
        <GroupContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          groupKey={contextMenu.groupKey}
          groupLabel={contextMenu.groupLabel}
          isPinned={contextMenu.isPinned}
          isHidden={contextMenu.isHidden}
          onAction={handleContextAction}
          onClose={() => setContextMenu(null)}
        />
      )}
    </motion.div>
  );
}
