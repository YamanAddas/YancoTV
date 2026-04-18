import type { SortOption } from '@yancotv/core';

/**
 * Pure query-building helpers for content-store.
 *
 * Extracted so they can be exercised by Jest without pulling in the native
 * op-sqlite module. Keep this file side-effect-free.
 */

/** Build the `ORDER BY` clause for a content list query. */
export function sortClause(sort: SortOption, tableAlias?: string): string {
  const p = tableAlias ? `${tableAlias}.` : '';
  switch (sort) {
    case 'provider':
      return `ORDER BY ${p}sort_order ASC`;
    case 'name-asc':
      return `ORDER BY COALESCE(${p}clean_title, ${p}title) COLLATE NOCASE ASC`;
    case 'name-desc':
      return `ORDER BY COALESCE(${p}clean_title, ${p}title) COLLATE NOCASE DESC`;
    case 'recent':
      return `ORDER BY ${p}created_at DESC, ${p}sort_order ASC`;
    case 'group':
      return `ORDER BY ${p}group_name COLLATE NOCASE ASC, COALESCE(${p}clean_title, ${p}title) COLLATE NOCASE ASC`;
    default:
      return `ORDER BY ${p}sort_order ASC`;
  }
}

/** Quote an FTS5 term and append a prefix wildcard. Escapes embedded quotes. */
function quoteTerm(word: string): string {
  return `"${word.replace(/"/g, '""')}"*`;
}

/** FTS5 query: every word must match (AND, prefix on each). */
export function buildFtsQueryAnd(query: string): string {
  return query
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .map(quoteTerm)
    .join(' ');
}

/**
 * FTS5 query: any word can match (OR, prefix on each).
 * Returns empty string when the query has one or zero words because OR and
 * AND collapse to the same thing — callers can short-circuit on `''`.
 */
export function buildFtsQueryOr(query: string): string {
  const words = query.trim().split(/\s+/).filter(Boolean);
  if (words.length <= 1) return '';
  return words.map(quoteTerm).join(' OR ');
}
