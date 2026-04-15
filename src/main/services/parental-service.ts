import { createHash } from 'crypto';
import log from 'electron-log/main';
import { getDb } from './db';

// ---------------------------------------------------------------------------
// Parental Controls Service
//
// PIN-based protection for content access. Uses SHA-256 hashing for PIN
// storage (not bcrypt — PINs are short numeric codes, not passwords, and
// we need synchronous verification for UX).
// ---------------------------------------------------------------------------

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

function hashPin(pin: string): string {
  return createHash('sha256').update(pin).digest('hex');
}

/**
 * Set or update the parental PIN.
 */
export function setPin(pin: string): void {
  const db = getDb();
  const hash = hashPin(pin);
  db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('parental_pin_hash', ?)").run(hash);
  db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('parental_pin_enabled', '1')").run();
  log.info('Parental PIN set');
}

/**
 * Verify a PIN against the stored hash.
 */
export function verifyPin(pin: string): boolean {
  const db = getDb();
  const row = db.prepare("SELECT value FROM settings WHERE key = 'parental_pin_hash'").get() as
    | { value: string }
    | undefined;
  if (!row) return false;
  return row.value === hashPin(pin);
}

/**
 * Remove the PIN and disable parental controls.
 */
export function removePin(): void {
  const db = getDb();
  db.prepare("DELETE FROM settings WHERE key = 'parental_pin_hash'").run();
  db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('parental_pin_enabled', '0')").run();
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
// Channel Locking
// ---------------------------------------------------------------------------

/**
 * Lock a channel (requires PIN to play).
 */
export function lockChannel(contentId: string): void {
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
