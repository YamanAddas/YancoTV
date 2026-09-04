import log from 'electron-log/main';
import {
  encodePinScryptSync,
  verifyPinAgainstHashSync,
} from '@yancotv/core';
import { getDb } from './db';

// ---------------------------------------------------------------------------
// Parental Controls Service
//
// PIN-based protection for content access. New PINs are hashed with scrypt
// and a per-PIN 16-byte random salt. Legacy unsalted SHA-256 hashes are still
// accepted on verify and transparently upgraded to the salted format on the
// next successful check. Verification is constant-time and rate-limited to
// deter local brute-force attempts.
// ---------------------------------------------------------------------------

// Rate limit: allow FAIL_THRESHOLD misses in a row for free, then lock for an
// exponentially-growing cooldown that doubles per further miss up to a cap.
const FAIL_THRESHOLD = 5;
const LOCKOUT_BASE_MS = 30_000;
const LOCKOUT_MAX_MS = 5 * 60_000;

let failedAttempts = 0;
let lockoutUntil = 0;

export interface ParentalSettings {
  /** Whether PIN protection is enabled */
  pinEnabled: boolean;
  /** Whether a PIN has been set */
  pinSet: boolean;
  /** Filter out adult-tagged content from all views */
  hideAdultContent: boolean;
  /** Require PIN to open Settings page */
  requirePinForSettings: boolean;
}

export interface ChannelOverride {
  contentId: string;
  customName?: string;
  customLogoUrl?: string;
  customNumber?: number;
  customGroup?: string;
}

// ---------------------------------------------------------------------------
// PIN Management
// ---------------------------------------------------------------------------

function writePinHash(encoded: string): void {
  const db = getDb();
  db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('parental_pin_hash', ?)").run(encoded);
}

/**
 * Set or update the parental PIN.
 */
export function setPin(pin: string): void {
  // A new or removed PIN invalidates anything unlocked under the old one.
  clearSessionUnlocks();
  const db = getDb();
  writePinHash(encodePinScryptSync(pin));
  db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('parental_pin_enabled', '1')").run();
  failedAttempts = 0;
  lockoutUntil = 0;
  log.info('Parental PIN set');
}

/**
 * ms remaining on the current brute-force cooldown, or 0 if not locked.
 */
export function getPinLockoutMs(): number {
  const remaining = lockoutUntil - Date.now();
  return remaining > 0 ? remaining : 0;
}

/**
 * Reset the in-memory failure counter and cooldown. Used by tests and after
 * legitimate administrative actions (e.g. PIN removal).
 */
export function resetPinAttempts(): void {
  failedAttempts = 0;
  lockoutUntil = 0;
}

function registerFailure(): void {
  failedAttempts += 1;
  if (failedAttempts >= FAIL_THRESHOLD) {
    const over = failedAttempts - FAIL_THRESHOLD;
    const cooldown = Math.min(LOCKOUT_BASE_MS * 2 ** over, LOCKOUT_MAX_MS);
    lockoutUntil = Date.now() + cooldown;
  }
}

/**
 * Verify a PIN against the stored hash. Returns false during a brute-force
 * cooldown regardless of correctness. Legacy unsalted SHA-256 hashes are
 * accepted once and upgraded to scrypt on the next successful check.
 */
export function verifyPin(pin: string): boolean {
  if (getPinLockoutMs() > 0) return false;

  const db = getDb();
  const row = db.prepare("SELECT value FROM settings WHERE key = 'parental_pin_hash'").get() as
    | { value: string }
    | undefined;
  if (!row) return false;

  const { ok, legacy } = verifyPinAgainstHashSync(pin, row.value);

  if (ok) {
    failedAttempts = 0;
    lockoutUntil = 0;
    if (legacy) {
      try {
        writePinHash(encodePinScryptSync(pin));
        log.info('Parental PIN upgraded to salted scrypt hash');
      } catch (err) {
        log.warn('Failed to upgrade parental PIN hash', err);
      }
    }
    return true;
  }

  registerFailure();
  return false;
}

/**
 * Remove the PIN and disable parental controls.
 */
export function removePin(): void {
  // A new or removed PIN invalidates anything unlocked under the old one.
  clearSessionUnlocks();
  const db = getDb();
  db.prepare("DELETE FROM settings WHERE key = 'parental_pin_hash'").run();
  db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('parental_pin_enabled', '0')").run();
  failedAttempts = 0;
  lockoutUntil = 0;
  log.info('Parental PIN removed');
}

/**
 * Get parental settings.
 */
export function getParentalSettings(): ParentalSettings {
  const db = getDb();

  const pinHash = db.prepare("SELECT value FROM settings WHERE key = 'parental_pin_hash'").get() as
    | { value: string }
    | undefined;
  const pinEnabled = db.prepare("SELECT value FROM settings WHERE key = 'parental_pin_enabled'").get() as
    | { value: string }
    | undefined;
  const hideAdult = db.prepare("SELECT value FROM settings WHERE key = 'parental_hide_adult'").get() as
    | { value: string }
    | undefined;
  const requirePin = db.prepare("SELECT value FROM settings WHERE key = 'parental_require_pin_settings'").get() as
    | { value: string }
    | undefined;

  return {
    pinEnabled: pinEnabled?.value === '1',
    pinSet: !!pinHash,
    hideAdultContent: hideAdult?.value === '1',
    requirePinForSettings: requirePin?.value === '1',
  };
}

/**
 * Update a parental setting.
 */
export function updateParentalSetting(key: string, value: boolean): void {
  const db = getDb();
  const dbKey = `parental_${key}`;
  db.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)').run(
    dbKey,
    value ? '1' : '0',
  );
}

// ---------------------------------------------------------------------------
// Playback gating (MB-405)
//
// Locking a channel meant "ask for the PIN before playing", and that check
// lived in `LiveTvPage` alongside the visibility filter — so it only ran when
// the user started playback from the Live TV grid. Opening the same title from
// its detail page, a favourite, a reminder, the channel-zap keys or
// autoplay-on-launch played it with no prompt at all.
//
// There are eight renderer call sites that start playback. Gating them one by
// one is what produced this bug the first time, so the authority lives here and
// is enforced in the PLAYER_PLAY handler — the single point everything funnels
// through. The renderer still prompts for the PIN, but that is now UX: if it
// forgets to, playback is refused rather than silently allowed.
//
// Unlocks are per-content and last only for the process. There is no "unlock
// everything" — entering the PIN for one channel must not open the rest — and
// nothing is persisted, so a restart re-locks.
// ---------------------------------------------------------------------------

const sessionUnlocked = new Set<string>();

/** Grant playback of one locked item for the rest of this process's life. */
export function unlockForSession(contentId: string): void {
  sessionUnlocked.add(contentId);
}

/** Drop every session unlock. Called when the PIN itself changes. */
export function clearSessionUnlocks(): void {
  sessionUnlocked.clear();
}

export function isUnlockedForSession(contentId: string): boolean {
  return sessionUnlocked.has(contentId);
}

/**
 * Whether playback of this item must be refused until the PIN is entered.
 *
 * Fails OPEN for an unknown id on purpose: `undefined` here means playback of
 * something with no catalogue row (a direct URL, a recording), which was never
 * lockable. Failing closed there would block ordinary playback rather than
 * protect anything. What must not happen is a LOCKED id slipping through, and
 * the caller resolves the id from the stream URL before asking.
 */
export function requiresPinToPlay(contentId: string | undefined): boolean {
  if (!contentId) return false;
  if (!getParentalSettings().pinEnabled) return false;
  if (isUnlockedForSession(contentId)) return false;
  return isChannelLocked(contentId);
}

// ---------------------------------------------------------------------------
// Visibility enforcement
//
// MB-404 — hiding and adult-filtering used to happen in the renderer, in
// `LiveTvPage`, after `content:getLive` had already handed it the entire
// catalogue. Three things were wrong with that:
//
//   1. Only Live TV applied it. Movies, Series, Search, Home, Favorites and
//      the Guide each fetched their own content and filtered nothing, so a
//      hidden channel or an adult title was one click away on any other page
//      while the toggle read "Filter out channels and VOD tagged as adult".
//   2. The main process shipped hidden rows to the renderer regardless. The
//      filter was cosmetic; the data had already crossed the boundary.
//   3. `parental-service` was never consulted by `content-store` at all, so
//      there was no single place that could be made right.
//
// The rule now: **nothing hidden leaves the main process.** These helpers are
// applied at the IPC boundary, which is deliberately narrower than the store
// itself — backup/export and the recorder must still see the whole catalogue,
// or hiding a channel would silently drop it from the user's backup.
//
// Locking is NOT a visibility rule and is not applied here. A locked channel
// must stay on screen wearing its padlock; it is gated at playback instead.
// ---------------------------------------------------------------------------

/** Minimum shape these helpers need. Keeps them usable for rows and items alike. */
interface VisibilityCandidate {
  id: string;
  title?: string | null;
  groupName?: string | null;
}

// Substrings that mark a group or title as adult. Matched case-insensitively
// against the group name, and — apart from `adult`, which appears in ordinary
// titles like "Adulthood" — against the title too.
const ADULT_GROUP_MARKERS = ['adult', 'xxx', '18+', 'porn'] as const;
const ADULT_TITLE_MARKERS = ['xxx', '18+', 'porn'] as const;

/**
 * Whether an item is adult-tagged.
 *
 * Deliberately conservative on titles. `adult` is checked in the GROUP only:
 * providers name groups "ADULT 18+", but a film called "Adulthood" or "Young
 * Adult" is a false positive that would vanish from a user's library with no
 * explanation. A group is the provider's own categorisation and is the more
 * reliable signal, so it carries the looser marker set.
 */
export function isAdultContent(item: VisibilityCandidate): boolean {
  const group = (item.groupName ?? '').toLowerCase();
  const title = (item.title ?? '').toLowerCase();
  return (
    ADULT_GROUP_MARKERS.some((m) => group.includes(m)) ||
    ADULT_TITLE_MARKERS.some((m) => title.includes(m))
  );
}

/** Whether a group NAME is adult-tagged — used to drop empty categories. */
export function isAdultGroupName(groupName: string): boolean {
  const g = groupName.toLowerCase();
  return ADULT_GROUP_MARKERS.some((m) => g.includes(m));
}

/**
 * Remove everything the user has chosen not to see.
 *
 * Returns the input array unchanged when nothing is hidden and adult filtering
 * is off — the common case, and worth the check: this runs on browse queries
 * that can return six figures of rows.
 */
export function applyParentalVisibility<T extends VisibilityCandidate>(items: T[]): T[] {
  const hideAdult = getParentalSettings().hideAdultContent;
  const hidden = new Set(getHiddenChannelIds());
  if (!hideAdult && hidden.size === 0) return items;
  return items.filter(
    (item) => !hidden.has(item.id) && !(hideAdult && isAdultContent(item)),
  );
}

/** Single-item form. Returns null for anything the user has hidden. */
export function filterHiddenItem<T extends VisibilityCandidate>(item: T | null): T | null {
  if (!item) return null;
  return applyParentalVisibility([item])[0] ?? null;
}

/** Drop adult category names so an emptied group does not linger in the sidebar. */
export function applyParentalCategoryVisibility(names: string[]): string[] {
  if (!getParentalSettings().hideAdultContent) return names;
  return names.filter((n) => !isAdultGroupName(n));
}

// ---------------------------------------------------------------------------
// Channel Locking
// ---------------------------------------------------------------------------

/**
 * Lock a channel (requires PIN to play).
 */
export function lockChannel(contentId: string): void {
  // Re-locking must take effect immediately, even if the user unlocked this
  // same item earlier in the session.
  sessionUnlocked.delete(contentId);
  const db = getDb();
  db.prepare('INSERT OR IGNORE INTO locked_channels (content_id, locked_at) VALUES (?, ?)').run(
    contentId,
    Date.now(),
  );
}

/**
 * Unlock a channel.
 */
export function unlockChannel(contentId: string): void {
  const db = getDb();
  db.prepare('DELETE FROM locked_channels WHERE content_id = ?').run(contentId);
}

/**
 * Check if a channel is locked.
 */
export function isChannelLocked(contentId: string): boolean {
  const db = getDb();
  const row = db.prepare('SELECT 1 FROM locked_channels WHERE content_id = ?').get(contentId);
  return !!row;
}

/**
 * Get all locked channel IDs.
 */
export function getLockedChannelIds(): string[] {
  const db = getDb();
  const rows = db.prepare('SELECT content_id FROM locked_channels').all() as { content_id: string }[];
  return rows.map((r) => r.content_id);
}

// ---------------------------------------------------------------------------
// Channel Hiding
// ---------------------------------------------------------------------------

/**
 * Hide a channel from all views.
 */
export function hideChannel(contentId: string): void {
  const db = getDb();
  db.prepare('INSERT OR IGNORE INTO hidden_channels (content_id, hidden_at) VALUES (?, ?)').run(
    contentId,
    Date.now(),
  );
}

/**
 * Unhide a channel.
 */
export function unhideChannel(contentId: string): void {
  const db = getDb();
  db.prepare('DELETE FROM hidden_channels WHERE content_id = ?').run(contentId);
}

/**
 * Get all hidden channel IDs.
 */
export function getHiddenChannelIds(): string[] {
  const db = getDb();
  const rows = db.prepare('SELECT content_id FROM hidden_channels').all() as { content_id: string }[];
  return rows.map((r) => r.content_id);
}

// ---------------------------------------------------------------------------
// Channel Overrides (custom name, logo, number, group)
// ---------------------------------------------------------------------------

/**
 * Set a channel override (partial update — only non-undefined fields are saved).
 */
export function setChannelOverride(override: ChannelOverride): void {
  const db = getDb();
  const existing = db
    .prepare('SELECT * FROM channel_overrides WHERE content_id = ?')
    .get(override.contentId) as Record<string, unknown> | undefined;

  if (existing) {
    db.prepare(
      `UPDATE channel_overrides SET
        custom_name = COALESCE(?, custom_name),
        custom_logo_url = COALESCE(?, custom_logo_url),
        custom_number = COALESCE(?, custom_number),
        custom_group = COALESCE(?, custom_group),
        updated_at = ?
       WHERE content_id = ?`,
    ).run(
      override.customName ?? null,
      override.customLogoUrl ?? null,
      override.customNumber ?? null,
      override.customGroup ?? null,
      Date.now(),
      override.contentId,
    );
  } else {
    db.prepare(
      `INSERT INTO channel_overrides (content_id, custom_name, custom_logo_url, custom_number, custom_group, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)`,
    ).run(
      override.contentId,
      override.customName ?? null,
      override.customLogoUrl ?? null,
      override.customNumber ?? null,
      override.customGroup ?? null,
      Date.now(),
    );
  }
}

/**
 * Remove all overrides for a channel.
 */
export function removeChannelOverride(contentId: string): void {
  const db = getDb();
  db.prepare('DELETE FROM channel_overrides WHERE content_id = ?').run(contentId);
}

/**
 * Get all channel overrides (map of contentId -> override).
 */
export function getAllChannelOverrides(): Record<string, ChannelOverride> {
  const db = getDb();
  const rows = db.prepare('SELECT * FROM channel_overrides').all() as Array<{
    content_id: string;
    custom_name: string | null;
    custom_logo_url: string | null;
    custom_number: number | null;
    custom_group: string | null;
  }>;

  const map: Record<string, ChannelOverride> = {};
  for (const row of rows) {
    map[row.content_id] = {
      contentId: row.content_id,
      customName: row.custom_name ?? undefined,
      customLogoUrl: row.custom_logo_url ?? undefined,
      customNumber: row.custom_number ?? undefined,
      customGroup: row.custom_group ?? undefined,
    };
  }
  return map;
}
