import path from 'path';

/**
 * Resolve `filename` under `baseDir` and reject anything that escapes.
 *
 * `filename` is treated as a candidate path component (or short
 * relative path) — `confinePath` resolves it against `baseDir`, then
 * verifies the resolved location is still inside the base. Any
 * traversal attempt (`..` segments, absolute overrides like
 * `/etc/passwd` or `C:\Windows\System32\...`) raises an error rather
 * than silently writing outside the intended directory.
 *
 * Use this any time the filename component is derived (even
 * indirectly) from external input: playlist titles, OpenSubtitles
 * file IDs, EPG channel names, Stalker portal metadata.
 *
 * The `// nosemgrep:` markers below are intentional: the
 * path-traversal rule fires on every `path.resolve` / `path.relative`
 * use and this function is the defence itself.
 *
 * @throws if `filename` resolves outside `baseDir`.
 */
export function confinePath(baseDir: string, filename: string): string {
  // nosemgrep: javascript.lang.security.audit.path-traversal.path-join-resolve-traversal.path-join-resolve-traversal
  const safeBase = path.resolve(baseDir);
  // nosemgrep: javascript.lang.security.audit.path-traversal.path-join-resolve-traversal.path-join-resolve-traversal
  const resolved = path.resolve(safeBase, filename);
  // nosemgrep: javascript.lang.security.audit.path-traversal.path-join-resolve-traversal.path-join-resolve-traversal
  const rel = path.relative(safeBase, resolved);
  if (rel.startsWith('..') || path.isAbsolute(rel)) {
    throw new Error(
      `confinePath: '${filename}' resolves outside of '${baseDir}'`,
    );
  }
  return resolved;
}

/**
 * Like `confinePath`, but returns `null` on traversal instead of
 * throwing. Useful in loops that probe candidate filenames and want
 * to skip-and-continue rather than abort.
 */
export function tryConfinePath(baseDir: string, filename: string): string | null {
  try {
    return confinePath(baseDir, filename);
  } catch {
    return null;
  }
}
