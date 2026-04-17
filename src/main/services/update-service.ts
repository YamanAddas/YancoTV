import { app } from 'electron';
import log from 'electron-log/main';
import { UPDATE_MANIFEST_URL } from '../../shared/constants';

const UPDATE_FETCH_TIMEOUT_MS = 10_000;

// ---------------------------------------------------------------------------
// Update check — manually triggered from Settings → About.
//
// Fetches a small JSON manifest from UPDATE_MANIFEST_URL and compares its
// `version` field to app.getVersion(). No binary download or install — that's
// 18.3's scope. Returning a structured result keeps the renderer dumb: it just
// renders whatever state we hand back.
//
// When UPDATE_MANIFEST_URL is empty (the current pre-release default) we
// short-circuit with a "not configured" result so the button still works
// gracefully against a repo with no release infrastructure yet.
// ---------------------------------------------------------------------------

export interface UpdateManifest {
  version: string;
  url?: string;
  notes?: string;
}

export type UpdateCheckResult =
  | { ok: true; status: 'up-to-date'; currentVersion: string }
  | { ok: true; status: 'update-available'; currentVersion: string; latestVersion: string; url?: string; notes?: string }
  | { ok: false; status: 'not-configured' }
  | { ok: false; status: 'error'; error: string };

/**
 * Compare two dotted version strings (e.g. "0.2.0" vs "0.1.0").
 * Returns >0 if a > b, <0 if a < b, 0 if equal.
 *
 * Any non-numeric suffix (e.g. "-beta.1") is ignored — we only compare the
 * leading numeric components. Good enough for this button; full semver
 * handling isn't worth the dependency.
 */
function compareVersions(a: string, b: string): number {
  const parse = (v: string): number[] =>
    v
      .replace(/[^0-9.].*$/, '')
      .split('.')
      .map((n) => parseInt(n, 10))
      .map((n) => (Number.isFinite(n) ? n : 0));
  const pa = parse(a);
  const pb = parse(b);
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const diff = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (diff !== 0) return diff;
  }
  return 0;
}

export async function checkForUpdates(): Promise<UpdateCheckResult> {
  const currentVersion = app.getVersion();

  if (!UPDATE_MANIFEST_URL) {
    return { ok: false, status: 'not-configured' };
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), UPDATE_FETCH_TIMEOUT_MS);
  try {
    const res = await fetch(UPDATE_MANIFEST_URL, {
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    });
    if (!res.ok) {
      return { ok: false, status: 'error', error: `Update server returned ${res.status}` };
    }
    const manifest = (await res.json()) as UpdateManifest;
    if (!manifest || typeof manifest.version !== 'string') {
      return { ok: false, status: 'error', error: 'Update manifest is malformed' };
    }

    const cmp = compareVersions(manifest.version, currentVersion);
    if (cmp > 0) {
      return {
        ok: true,
        status: 'update-available',
        currentVersion,
        latestVersion: manifest.version,
        url: manifest.url,
        notes: manifest.notes,
      };
    }
    return { ok: true, status: 'up-to-date', currentVersion };
  } catch (err) {
    log.warn('[update-check] failed:', err);
    const isAbort = (err as { name?: string })?.name === 'AbortError';
    return {
      ok: false,
      status: 'error',
      error: isAbort
        ? `Update check timed out after ${UPDATE_FETCH_TIMEOUT_MS}ms`
        : err instanceof Error
        ? err.message
        : String(err),
    };
  } finally {
    clearTimeout(timer);
  }
}

// Exported for unit tests.
export const __testing = { compareVersions };
