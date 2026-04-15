/**
 * Auto-groups a flat list of category names into a two-level hierarchy
 * by detecting common prefix patterns (e.g. "AR | Sports", "AR | News" → group "AR").
 *
 * Works with any IPTV list — no hardcoded prefixes. Detects the separator
 * pattern used in the category names and clusters accordingly.
 */

// Separators to try, in priority order (most specific first).
const SEPARATORS = [' | ', ' - ', ' / ', ' : ', '| ', '- '];

export interface CategoryGroup {
  /** The shared prefix (e.g. "AR", "US", "Arabic") */
  prefix: string;
  /** Original full category names belonging to this group */
  children: string[];
  /** Stripped display labels (suffix after separator) */
  childLabels: string[];
}

export interface GroupedCategories {
  groups: CategoryGroup[];
  ungrouped: string[];
}

interface ParsedCategory {
  original: string;
  prefix: string;
  suffix: string;
}

function tryParse(category: string): ParsedCategory | null {
  for (const sep of SEPARATORS) {
    const idx = category.indexOf(sep);
    if (idx > 0) {
      const prefix = category.slice(0, idx).trim();
      const suffix = category.slice(idx + sep.length).trim();
      if (prefix.length > 0 && suffix.length > 0) {
        return { original: category, prefix, suffix };
      }
    }
  }
  return null;
}

export function groupCategories(categories: string[]): GroupedCategories {
  // Parse each category to detect prefix/suffix
  const parsed: ParsedCategory[] = [];
  const noParse: string[] = [];

  for (const cat of categories) {
    if (!cat) continue;
    const result = tryParse(cat);
    if (result) {
      parsed.push(result);
    } else {
      noParse.push(cat);
    }
  }

  // Group by prefix — only form a super-group when 2+ categories share it
  const prefixMap = new Map<string, ParsedCategory[]>();
  for (const p of parsed) {
    const existing = prefixMap.get(p.prefix);
    if (existing) {
      existing.push(p);
    } else {
      prefixMap.set(p.prefix, [p]);
    }
  }

  const groups: CategoryGroup[] = [];
  const ungrouped: string[] = [...noParse];

  for (const [prefix, members] of prefixMap) {
    if (members.length >= 2) {
      // Sort children alphabetically by suffix
      members.sort((a, b) => a.suffix.localeCompare(b.suffix));
      groups.push({
        prefix,
        children: members.map((m) => m.original),
        childLabels: members.map((m) => m.suffix),
      });
    } else {
      // Single-member prefix — keep flat
      ungrouped.push(members[0].original);
    }
  }

  // Sort groups by prefix, ungrouped alphabetically
  groups.sort((a, b) => a.prefix.localeCompare(b.prefix));
  ungrouped.sort((a, b) => a.localeCompare(b));

  return { groups, ungrouped };
}
