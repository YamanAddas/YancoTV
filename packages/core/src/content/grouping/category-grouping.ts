/**
 * Smart category grouping — organizes raw IPTV group names under
 * auto-detected language/country headers.
 *
 * Group names are shown in FULL (never stripped or shortened).
 * The only intelligence is detecting which language section each group
 * belongs to, using prefix codes and Unicode script detection.
 */

import { parseAllGroups } from './group-parser.js';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface SmartChild {
  /** Full original group_name — used as display label AND for content filtering */
  originalGroupName: string;
}

export interface SmartSection {
  /** Unique key for the section (e.g. "ar", "us") */
  key: string;
  /** Display label (e.g. "Arabic", "English") */
  label: string;
  /** Flag emoji */
  icon: string | null;
  /** Groups in this section — full original names */
  children: SmartChild[];
}

export interface SmartGroupedCategories {
  /** Language/country sections */
  sections: SmartSection[];
  /** Groups that couldn't be assigned to a language */
  ungrouped: SmartChild[];
}

// ---------------------------------------------------------------------------
// Main grouping function
// ---------------------------------------------------------------------------

export function groupCategoriesSmart(categories: string[]): SmartGroupedCategories {
  if (!categories || categories.length === 0) {
    return { sections: [], ungrouped: [] };
  }

  // Parse all group names for language detection
  const parsed = parseAllGroups(categories);

  // Build section map: sectionKey → { label, icon, children[] }
  const sectionMap = new Map<string, { label: string; icon: string | null; children: SmartChild[] }>();
  const ungrouped: SmartChild[] = [];

  for (const [originalName, pg] of parsed) {
    const sectionKey = pg.country?.toLowerCase() ?? pg.language?.toLowerCase() ?? null;

    if (!sectionKey) {
      ungrouped.push({ originalGroupName: originalName });
      continue;
    }

    let section = sectionMap.get(sectionKey);
    if (!section) {
      section = {
        label: pg.language || pg.country || sectionKey,
        icon: pg.country
          ? getFlagFromParsed(pg.country)
          : null,
        children: [],
      };
      sectionMap.set(sectionKey, section);
    }
    section.children.push({ originalGroupName: originalName });
  }

  // Build sections array — only sections with 2+ children get their own section
  // Single-child sections get merged into ungrouped
  const sections: SmartSection[] = [];

  for (const [key, data] of sectionMap) {
    if (data.children.length >= 2) {
      // Sort children alphabetically by original name
      data.children.sort((a, b) => a.originalGroupName.localeCompare(b.originalGroupName));
      sections.push({
        key,
        label: data.label,
        icon: data.icon,
        children: data.children,
      });
    } else {
      // Single item — don't create a section for it
      ungrouped.push(...data.children);
    }
  }

  // Sort sections alphabetically
  sections.sort((a, b) => a.label.localeCompare(b.label));
  // Sort ungrouped alphabetically
  ungrouped.sort((a, b) => a.originalGroupName.localeCompare(b.originalGroupName));

  return { sections, ungrouped };
}

// ---------------------------------------------------------------------------
// Helper
// ---------------------------------------------------------------------------

import { getCountryFlag } from './group-parser.js';

function getFlagFromParsed(countryCode: string): string | null {
  return getCountryFlag(countryCode);
}

// ---------------------------------------------------------------------------
// Legacy types (kept so old imports don't break during transition)
// ---------------------------------------------------------------------------

export interface CategoryGroup {
  prefix: string;
  children: string[];
  childLabels: string[];
}
