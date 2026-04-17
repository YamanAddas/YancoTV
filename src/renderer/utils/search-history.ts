// Tiny ring-buffer of recent search queries, persisted to localStorage. Used
// by SearchPage to surface recent searches as suggestions when the input is
// empty, and as autocomplete hints when the user is typing.

const STORAGE_KEY = 'yancotv.search-history';
const MAX_ENTRIES = 20;

function read(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((s): s is string => typeof s === 'string');
  } catch {
    return [];
  }
}

function write(entries: string[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
  } catch {
    // Storage quota / privacy mode — silent fallback.
  }
}

export function getSearchHistory(): string[] {
  return read();
}

export function recordSearch(query: string): void {
  const trimmed = query.trim();
  // Require at least 2 chars — avoids polluting history with single keystrokes
  // captured by the debounce before the user finishes typing.
  if (trimmed.length < 2) return;
  const existing = read();
  const normalized = trimmed.toLowerCase();
  const filtered = existing.filter((s) => s.toLowerCase() !== normalized);
  filtered.unshift(trimmed);
  write(filtered.slice(0, MAX_ENTRIES));
}

export function removeFromHistory(query: string): void {
  const normalized = query.trim().toLowerCase();
  write(read().filter((s) => s.toLowerCase() !== normalized));
}

export function clearSearchHistory(): void {
  write([]);
}
