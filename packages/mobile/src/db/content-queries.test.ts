import {
  buildFtsQueryAnd,
  buildFtsQueryOr,
  sortClause,
} from './content-queries';

describe('sortClause', () => {
  it('orders by sort_order for provider sort', () => {
    expect(sortClause('provider')).toBe('ORDER BY sort_order ASC');
  });

  it('prefers clean_title over title for name sorts, case-insensitive', () => {
    expect(sortClause('name-asc')).toBe(
      'ORDER BY COALESCE(clean_title, title) COLLATE NOCASE ASC',
    );
    expect(sortClause('name-desc')).toBe(
      'ORDER BY COALESCE(clean_title, title) COLLATE NOCASE DESC',
    );
  });

  it('orders by created_at DESC then sort_order for recent', () => {
    expect(sortClause('recent')).toBe(
      'ORDER BY created_at DESC, sort_order ASC',
    );
  });

  it('orders by group then clean_title for group sort', () => {
    expect(sortClause('group')).toBe(
      'ORDER BY group_name COLLATE NOCASE ASC, COALESCE(clean_title, title) COLLATE NOCASE ASC',
    );
  });

  it('applies a table alias prefix when given', () => {
    expect(sortClause('provider', 'c')).toBe('ORDER BY c.sort_order ASC');
    expect(sortClause('name-asc', 'c')).toBe(
      'ORDER BY COALESCE(c.clean_title, c.title) COLLATE NOCASE ASC',
    );
  });
});

describe('buildFtsQueryAnd', () => {
  it('quotes each word and adds a prefix wildcard', () => {
    expect(buildFtsQueryAnd('game of thrones')).toBe(
      '"game"* "of"* "thrones"*',
    );
  });

  it('collapses runs of whitespace', () => {
    expect(buildFtsQueryAnd('  hello   world  ')).toBe('"hello"* "world"*');
  });

  it('escapes embedded double quotes', () => {
    expect(buildFtsQueryAnd('say "hi"')).toBe('"say"* """hi"""*');
  });

  it('returns empty string for empty input', () => {
    expect(buildFtsQueryAnd('')).toBe('');
    expect(buildFtsQueryAnd('   ')).toBe('');
  });
});

describe('buildFtsQueryOr', () => {
  it('returns empty string for single-word queries', () => {
    expect(buildFtsQueryOr('thrones')).toBe('');
    expect(buildFtsQueryOr('')).toBe('');
  });

  it('joins multiple words with OR', () => {
    expect(buildFtsQueryOr('game of thrones')).toBe(
      '"game"* OR "of"* OR "thrones"*',
    );
  });

  it('escapes embedded double quotes across both terms', () => {
    expect(buildFtsQueryOr('"a" "b"')).toBe('"""a"""* OR """b"""*');
  });
});
