/**
 * Hook for smart category grouping with user preferences.
 *
 * Groups raw IPTV group names under auto-detected language headers,
 * applies user preferences (pinning, hiding, custom sort order).
 */

import { useState, useMemo, useCallback, useEffect } from 'react';
import {
  groupCategoriesSmart,
  type SmartSection,
  type SmartChild,
  type SmartGroupedCategories,
} from '../utils/category-grouping';
import { useGroupPreferencesStore } from '../stores/group-preferences-store';
import type { ContentType } from '../../shared/types';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface EnhancedSection extends SmartSection {
  isPinned: boolean;
  customName: string | null;
}

export interface EnhancedGroupedCategories {
  pinned: EnhancedSection[];
  sections: EnhancedSection[];
  ungrouped: SmartChild[];
}

export interface UseCategoryGroupsResult {
  grouped: EnhancedGroupedCategories;
  expandedGroups: Set<string>;
  toggleGroup: (sectionKey: string) => void;
  onTogglePin: (groupKey: string) => Promise<void>;
  onToggleHide: (groupKey: string) => Promise<void>;
  onRename: (groupKey: string, name: string | null) => Promise<void>;
  onReorder: (orderedKeys: string[]) => Promise<void>;
}

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

export function useCategoryGroups(
  categories: string[],
  contentType: ContentType,
): UseCategoryGroupsResult {
  const load = useGroupPreferencesStore((s) => s.load);
  const preferences = useGroupPreferencesStore((s) => s.preferences);
  const togglePinned = useGroupPreferencesStore((s) => s.togglePinned);
  const toggleHidden = useGroupPreferencesStore((s) => s.toggleHidden);
  const rename = useGroupPreferencesStore((s) => s.rename);
  const reorder = useGroupPreferencesStore((s) => s.reorder);

  useEffect(() => {
    load(contentType);
  }, [contentType, load]);

  // Smart grouping (pure, memoized)
  const rawGrouped: SmartGroupedCategories = useMemo(
    () => groupCategoriesSmart(categories),
    [categories],
  );

  // Apply preferences
  const grouped: EnhancedGroupedCategories = useMemo(() => {
    const pinned: EnhancedSection[] = [];
    const sections: EnhancedSection[] = [];

    // Sort child groups by user-set sort order, then alphabetically
    const childSortFn = (a: SmartChild, b: SmartChild) => {
      const orderA = preferences.get(a.originalGroupName)?.sortOrder ?? 999_999;
      const orderB = preferences.get(b.originalGroupName)?.sortOrder ?? 999_999;
      if (orderA !== orderB) return orderA - orderB;
      return a.originalGroupName.localeCompare(b.originalGroupName);
    };

    for (const section of rawGrouped.sections) {
      const pref = preferences.get(section.key);
      if (pref?.isHidden) continue;

      const sortedChildren = [...section.children].sort(childSortFn);

      const enhanced: EnhancedSection = {
        ...section,
        children: sortedChildren,
        isPinned: pref?.isPinned ?? false,
        customName: pref?.customName ?? null,
      };

      if (enhanced.isPinned) {
        pinned.push(enhanced);
      } else {
        sections.push(enhanced);
      }
    }

    // Sort sections by user sort order, then alphabetically
    const sectionSortFn = (a: EnhancedSection, b: EnhancedSection) => {
      const orderA = preferences.get(a.key)?.sortOrder ?? 999_999;
      const orderB = preferences.get(b.key)?.sortOrder ?? 999_999;
      if (orderA !== orderB) return orderA - orderB;
      return a.label.localeCompare(b.label);
    };

    pinned.sort(sectionSortFn);
    sections.sort(sectionSortFn);

    // Filter hidden ungrouped, then sort by user order
    const ungrouped = rawGrouped.ungrouped
      .filter((item) => !preferences.get(item.originalGroupName)?.isHidden)
      .sort(childSortFn);

    return { pinned, sections, ungrouped };
  }, [rawGrouped, preferences]);

  // Accordion state
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  useEffect(() => {
    setExpandedGroups(new Set());
  }, [categories]);

  const toggleGroup = useCallback((sectionKey: string) => {
    setExpandedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(sectionKey)) next.delete(sectionKey);
      else next.add(sectionKey);
      return next;
    });
  }, []);

  const onTogglePin = useCallback(
    (groupKey: string) => togglePinned(contentType, groupKey),
    [contentType, togglePinned],
  );
  const onToggleHide = useCallback(
    (groupKey: string) => toggleHidden(contentType, groupKey),
    [contentType, toggleHidden],
  );
  const onRename = useCallback(
    (groupKey: string, name: string | null) => rename(contentType, groupKey, name),
    [contentType, rename],
  );
  const onReorder = useCallback(
    (orderedKeys: string[]) => reorder(contentType, orderedKeys),
    [contentType, reorder],
  );

  return {
    grouped,
    expandedGroups,
    toggleGroup,
    onTogglePin,
    onToggleHide,
    onRename,
    onReorder,
  };
}
