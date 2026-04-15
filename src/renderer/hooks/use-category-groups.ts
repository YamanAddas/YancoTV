import { useState, useMemo, useCallback, useEffect } from 'react';
import { groupCategories, type GroupedCategories } from '../utils/category-grouping';

export function useCategoryGroups(categories: string[]) {
  const grouped: GroupedCategories = useMemo(
    () => groupCategories(categories),
    [categories],
  );

  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  // Reset accordion state when the category list changes (e.g. new source)
  useEffect(() => {
    setExpandedGroups(new Set());
  }, [categories]);

  const toggleGroup = useCallback((prefix: string) => {
    setExpandedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(prefix)) {
        next.delete(prefix);
      } else {
        next.add(prefix);
      }
      return next;
    });
  }, []);

  return { grouped, expandedGroups, toggleGroup };
}
