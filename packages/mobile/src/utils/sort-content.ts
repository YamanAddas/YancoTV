import type { ContentItem, SortOption } from '@yancotv/core';

/**
 * Sort ContentItems per the desktop `SortOption` vocabulary. Provider order
 * reads `sortOrder` (the original M3U / Xtream index); group/name sorts fall
 * back to sortOrder as a stable tiebreaker so repeated sorts are deterministic.
 * Shared across Live / Movies / Series browse screens.
 */
export function sortContent(items: ContentItem[], sort: SortOption): ContentItem[] {
  const copy = items.slice();
  switch (sort) {
    case 'provider':
      copy.sort((a, b) => a.sortOrder - b.sortOrder);
      return copy;
    case 'name-asc':
      copy.sort((a, b) =>
        (a.cleanTitle || a.title).localeCompare(b.cleanTitle || b.title),
      );
      return copy;
    case 'name-desc':
      copy.sort((a, b) =>
        (b.cleanTitle || b.title).localeCompare(a.cleanTitle || a.title),
      );
      return copy;
    case 'recent':
      copy.sort((a, b) => b.createdAt - a.createdAt);
      return copy;
    case 'group':
      copy.sort((a, b) => {
        const g = (a.groupName ?? '').localeCompare(b.groupName ?? '');
        return g !== 0 ? g : a.sortOrder - b.sortOrder;
      });
      return copy;
    default:
      return copy;
  }
}
