import * as m001 from './001-initial-schema';
import * as m002 from './002-fts5-search';
import * as m003 from './003-sort-order';
import * as m004 from './004-epg-enhancements';
import * as m005 from './005-parental-controls';
import * as m006 from './006-epg-indexes';
import * as m007 from './007-source-management-enhancements';
import * as m008 from './008-group-preferences';

export interface Migration {
  readonly name: string;
  readonly sql: string;
}

/**
 * Migrations in application order. Names match the filenames on the desktop
 * side (`src/main/services/migrations/`) verbatim so the `migrations.name`
 * row values are identical across platforms — makes schema-level diffs
 * meaningful and prevents re-application of desktop-applied migrations if
 * we ever share a DB dump.
 */
export const MIGRATIONS: readonly Migration[] = [
  m001,
  m002,
  m003,
  m004,
  m005,
  m006,
  m007,
  m008,
];
