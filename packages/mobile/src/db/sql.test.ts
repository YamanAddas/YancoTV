import { splitStatements } from './sql';

describe('splitStatements', () => {
  it('splits simple semicolon-terminated statements', () => {
    const sql = 'SELECT 1; SELECT 2; SELECT 3;';
    expect(splitStatements(sql)).toEqual(['SELECT 1', 'SELECT 2', 'SELECT 3']);
  });

  it('ignores -- line comments', () => {
    const sql = '-- one\nSELECT 1;\n-- two\nSELECT 2;';
    expect(splitStatements(sql)).toEqual(['SELECT 1', 'SELECT 2']);
  });

  it('keeps semicolons inside single-quoted string literals intact', () => {
    const sql = `INSERT INTO t VALUES ('a;b;c'); SELECT 1;`;
    expect(splitStatements(sql)).toEqual([
      `INSERT INTO t VALUES ('a;b;c')`,
      'SELECT 1',
    ]);
  });

  it('handles escaped quotes inside string literals', () => {
    const sql = `INSERT INTO t VALUES ('it''s fine'); SELECT 1;`;
    expect(splitStatements(sql)).toEqual([
      `INSERT INTO t VALUES ('it''s fine')`,
      'SELECT 1',
    ]);
  });

  it('treats the body of a BEGIN..END trigger as a single statement', () => {
    const sql = `
      CREATE TRIGGER t AFTER INSERT ON x BEGIN
        DELETE FROM y WHERE id = new.id;
        INSERT INTO y VALUES (new.id, new.name);
      END;
      SELECT 1;
    `;
    const statements = splitStatements(sql);
    expect(statements).toHaveLength(2);
    expect(statements[0]).toMatch(/^CREATE TRIGGER t[\s\S]*END$/);
    expect(statements[0]).toContain('DELETE FROM y');
    expect(statements[0]).toContain('INSERT INTO y');
    expect(statements[1]).toBe('SELECT 1');
  });

  it('does not match BEGIN/END inside identifiers', () => {
    const sql = 'SELECT BEGINNING FROM t; SELECT ENDING FROM t;';
    expect(splitStatements(sql)).toEqual([
      'SELECT BEGINNING FROM t',
      'SELECT ENDING FROM t',
    ]);
  });

  it('is case-insensitive for BEGIN/END keywords', () => {
    const sql = `
      CREATE TRIGGER t AFTER INSERT ON x begin
        DELETE FROM y;
      end;
      SELECT 1;
    `;
    const statements = splitStatements(sql);
    expect(statements).toHaveLength(2);
    expect(statements[1]).toBe('SELECT 1');
  });

  it('returns an empty array for comment-only or whitespace input', () => {
    expect(splitStatements('')).toEqual([]);
    expect(splitStatements('   \n  \n')).toEqual([]);
    expect(splitStatements('-- just a comment\n-- another\n')).toEqual([]);
  });

  it('parses the real FTS5 trigger migration correctly', () => {
    const sql = `
      CREATE VIRTUAL TABLE content_fts USING fts5(
        content_id UNINDEXED, title, clean_title, group_name
      );

      INSERT INTO content_fts (content_id, title, clean_title, group_name)
      SELECT id, title, clean_title, group_name FROM content;

      CREATE TRIGGER content_ai AFTER INSERT ON content BEGIN
        INSERT INTO content_fts (content_id, title, clean_title, group_name)
        VALUES (new.id, new.title, new.clean_title, new.group_name);
      END;

      CREATE TRIGGER content_ad AFTER DELETE ON content BEGIN
        DELETE FROM content_fts WHERE content_id = old.id;
      END;

      CREATE TRIGGER content_au AFTER UPDATE ON content BEGIN
        DELETE FROM content_fts WHERE content_id = old.id;
        INSERT INTO content_fts (content_id, title, clean_title, group_name)
        VALUES (new.id, new.title, new.clean_title, new.group_name);
      END;
    `;
    const statements = splitStatements(sql);
    expect(statements).toHaveLength(5);
    expect(statements[0]).toMatch(/^CREATE VIRTUAL TABLE content_fts/);
    expect(statements[1]).toMatch(/^INSERT INTO content_fts/);
    expect(statements[2]).toMatch(/^CREATE TRIGGER content_ai[\s\S]*END$/);
    expect(statements[3]).toMatch(/^CREATE TRIGGER content_ad[\s\S]*END$/);
    expect(statements[4]).toMatch(/^CREATE TRIGGER content_au[\s\S]*END$/);
    expect(statements[4]).toContain('DELETE FROM content_fts');
    expect(statements[4]).toContain('INSERT INTO content_fts');
  });

  it('trailing statement without semicolon is still returned', () => {
    expect(splitStatements('SELECT 1')).toEqual(['SELECT 1']);
  });
});
