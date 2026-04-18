/**
 * Split a multi-statement SQL script into individual statements.
 *
 * op-sqlite's `execute()` only processes the first statement per call, so
 * DDL scripts (migrations) must be split before being applied. The splitter
 * must respect:
 *
 *   - `--` line comments
 *   - `'...'` string literals (with `''` escape)
 *   - Trigger bodies wrapped in `BEGIN ... END;` — semicolons inside the
 *     body are internal separators, not statement terminators
 *
 * Returns trimmed statements with trailing semicolons stripped. Empty or
 * comment-only chunks are skipped.
 */
export function splitStatements(sql: string): string[] {
  const out: string[] = [];
  let buf = '';
  let i = 0;
  let inString = false;
  let triggerDepth = 0;

  const isWordChar = (c: string | undefined) =>
    c !== undefined && /[A-Za-z0-9_]/.test(c);

  const matchKeyword = (pos: number, word: string): boolean => {
    if (sql.slice(pos, pos + word.length).toUpperCase() !== word) return false;
    const before = pos > 0 ? sql[pos - 1] : undefined;
    const after = sql[pos + word.length];
    if (isWordChar(before) || isWordChar(after)) return false;
    return true;
  };

  while (i < sql.length) {
    const ch = sql[i];

    if (!inString && ch === '-' && sql[i + 1] === '-') {
      const nl = sql.indexOf('\n', i);
      i = nl === -1 ? sql.length : nl + 1;
      buf += '\n';
      continue;
    }

    if (ch === "'") {
      if (inString && sql[i + 1] === "'") {
        buf += "''";
        i += 2;
        continue;
      }
      inString = !inString;
      buf += ch;
      i++;
      continue;
    }

    if (!inString) {
      if (matchKeyword(i, 'BEGIN')) {
        triggerDepth++;
        buf += sql.slice(i, i + 5);
        i += 5;
        continue;
      }
      if (triggerDepth > 0 && matchKeyword(i, 'END')) {
        triggerDepth--;
        buf += sql.slice(i, i + 3);
        i += 3;
        continue;
      }
      if (ch === ';' && triggerDepth === 0) {
        const stmt = buf.trim();
        if (stmt.length > 0) out.push(stmt);
        buf = '';
        i++;
        continue;
      }
    }

    buf += ch;
    i++;
  }

  const tail = buf.trim();
  if (tail.length > 0) out.push(tail);
  return out;
}
